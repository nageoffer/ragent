/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Agent 侧 /agent/v1/chat 的 SSE 客户端
 * 与 RAG 的 /rag/v3/chat 协议不同：没有 reject 事件，限流在建流之前拒绝；工具更新从 block(kind=tool) 读取
 */
final class AgentChatClient {

    private static final String CHAT_PATH = "/agent/v1/chat";

    private final RagentHttpClient http;
    private final HttpClient client;

    AgentChatClient(RagentHttpClient http, InitializerConfig config) {
        this.http = http;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getInt("server.connect-timeout-seconds", 5)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 提问一次并把 SSE 读到服务端关闭
     * conversationId 为空即新开会话，服务端在 meta 事件里回传本轮真正使用的会话 ID
     * 非空 ID 仅用于续聊；会话不存在或已删除时服务端拒绝请求，不会重建
     */
    AgentTurnResult ask(String question, String conversationId, Duration timeout)
            throws IOException, InterruptedException {
        String path = CHAT_PATH + "?question=" + RagentHttpClient.encodeQuery(question)
                + (conversationId == null || conversationId.isBlank()
                ? "" : "&conversationId=" + RagentHttpClient.encodeQuery(conversationId));
        HttpRequest request = HttpRequest.newBuilder(URI.create(http.baseUrl() + path))
                .timeout(timeout)
                .header("Accept", "text/event-stream")
                .header("Authorization", http.authorization())
                .GET()
                .build();

        HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP " + response.statusCode() + " GET " + CHAT_PATH + "，问题: " + question);
            }
            Accumulator accumulator = new Accumulator();
            // 请求超时只覆盖到响应头，流开始之后靠这里的截止时间兜住服务端一直不结束的情况
            Instant deadline = Instant.now().plus(timeout);
            lines.forEach(line -> {
                if (Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException("SSE 流超过单轮超时仍未结束: " + timeout + "，问题: " + question);
                }
                accumulator.accept(line);
            });
            accumulator.flush();
            return accumulator.toResult();
        }
    }

    record AgentTurnResult(String conversationId, String taskId, String messageId,
                           String answer, List<String> tools, int thinkChars) {
    }

    /**
     * 逐行重组 SSE 帧，只保留回归判定用得上的字段
     */
    private static final class Accumulator {

        private final StringBuilder data = new StringBuilder();
        private final StringBuilder answer = new StringBuilder();
        private final Set<String> tools = new LinkedHashSet<>();
        private String event;
        private String conversationId;
        private String taskId;
        private String messageId;
        private String messageStatus;
        private int thinkChars;
        private boolean done;
        private String failure;

        void accept(String line) {
            if (line.isEmpty()) {
                flush();
                return;
            }
            if (line.startsWith(":")) {
                return;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : stripLeadingSpace(line.substring(colon + 1));
            switch (field) {
                case "event" -> event = value;
                case "data" -> {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(value);
                }
                default -> {
                    // id 与 retry 字段对回归没有意义
                }
            }
        }

        void flush() {
            if (event == null && data.isEmpty()) {
                return;
            }
            handle(event, data.toString());
            event = null;
            data.setLength(0);
        }

        private void handle(String name, String payload) {
            if (name == null) {
                return;
            }
            switch (name) {
                case "meta" -> {
                    Map<String, Object> meta = asObject(payload);
                    conversationId = SimpleJson.string(meta, "conversationId");
                    taskId = SimpleJson.string(meta, "taskId");
                }
                case "message" -> {
                    Map<String, Object> delta = asObject(payload);
                    String text = SimpleJson.string(delta, "delta");
                    if (text == null) {
                        return;
                    }
                    // 思考内容单独计数：判定关键词只看正式回答，否则模型「想过」也算记得
                    if ("reasoning".equals(SimpleJson.string(delta, "type"))) {
                        thinkChars += text.length();
                    } else {
                        answer.append(text);
                    }
                }
                case "block" -> {
                    Map<String, Object> block = asObject(payload);
                    if (!"tool".equals(SimpleJson.string(block, "kind"))) {
                        return;
                    }
                    String toolName = SimpleJson.string(block, "name");
                    if (toolName != null && !toolName.isBlank()) {
                        tools.add(toolName);
                    }
                }
                case "finish" -> {
                    Map<String, Object> completion = asObject(payload);
                    messageId = SimpleJson.string(completion, "messageId");
                    messageStatus = SimpleJson.string(completion, "messageStatus");
                }
                case "done" -> done = true;
                case "cancel" -> failure = "任务被取消: " + abbreviate(payload);
                default -> {
                    // hint 等事件对回归没有意义
                }
            }
        }

        AgentTurnResult toResult() throws IOException {
            if (failure != null) {
                throw new IOException(failure + describeConversation());
            }
            if (!done) {
                throw new IOException("SSE 流未收到 done 事件即结束" + describeConversation());
            }
            if (answer.isEmpty()) {
                throw new IOException("模型没有产出任何回答内容" + describeConversation());
            }
            if (messageId == null || messageId.isBlank()) {
                throw new IOException("答案没有落库，finish 事件缺少 messageId" + describeConversation());
            }
            if (messageStatus != null && !"NORMAL".equals(messageStatus)) {
                throw new IOException("答案落库状态异常: " + messageStatus + describeConversation());
            }
            return new AgentTurnResult(conversationId, taskId, messageId, answer.toString(),
                    List.copyOf(new ArrayList<>(tools)), thinkChars);
        }

        private String describeConversation() {
            return conversationId == null ? "" : "，conversationId=" + conversationId;
        }

        private static Map<String, Object> asObject(String payload) {
            try {
                return SimpleJson.object(SimpleJson.parse(payload));
            } catch (RuntimeException ex) {
                return Map.of();
            }
        }

        private static String stripLeadingSpace(String value) {
            return value.startsWith(" ") ? value.substring(1) : value;
        }

        private static String abbreviate(String value) {
            return value.length() <= 500 ? value : value.substring(0, 500) + "...";
        }
    }
}
