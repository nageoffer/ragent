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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * /agent/v1/chat 与确认接口的 SSE 客户端
 * 与 agent-memory 那份不同：卡片轮回答可以为空，确认卡要能拒，所以不在缺回答时抛错
 */
final class CompactionSseClient {

    private static final String CHAT_PATH = "/agent/v1/chat";
    private static final String CONFIRM_PATH = "/agent/v1/chat/confirm";

    private final RagentHttpClient http;
    private final HttpClient client;

    CompactionSseClient(RagentHttpClient http, InitializerConfig config) {
        this.http = http;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getInt("server.connect-timeout-seconds", 5)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    Reply ask(String question, String conversationId, Duration timeout) throws IOException, InterruptedException {
        String path = CHAT_PATH + "?question=" + RagentHttpClient.encodeQuery(question)
                + (conversationId == null ? "" : "&conversationId=" + RagentHttpClient.encodeQuery(conversationId));
        return read(HttpRequest.newBuilder(URI.create(http.baseUrl() + path)).GET(), timeout, question);
    }

    /**
     * 确认卡一律拒绝：回归不许真的办事，被拒之后模型怎么接话也是被测内容
     */
    Reply deny(String conversationId, String messageId, Duration timeout) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", conversationId);
        body.put("messageId", messageId);
        body.put("approved", Boolean.FALSE);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(http.baseUrl() + CONFIRM_PATH))
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(SimpleJson.stringify(body), StandardCharsets.UTF_8));
        return read(builder, timeout, "拒绝确认卡 " + messageId);
    }

    private Reply read(HttpRequest.Builder builder, Duration timeout, String label)
            throws IOException, InterruptedException {
        HttpRequest request = builder.timeout(timeout)
                .header("Accept", "text/event-stream")
                .header("Authorization", http.authorization())
                .build();
        HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP " + response.statusCode() + "，" + label);
            }
            Accumulator accumulator = new Accumulator();
            // 请求超时只覆盖到响应头，流开始之后靠截止时间兜住一直不结束的流
            Instant deadline = Instant.now().plus(timeout);
            lines.forEach(line -> {
                if (Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException("SSE 流超过单轮超时仍未结束: " + timeout + "，" + label);
                }
                accumulator.accept(line);
            });
            accumulator.flush();
            if (!accumulator.done) {
                throw new IOException("SSE 流未收到 done 事件即结束，" + label);
            }
            return accumulator.toReply();
        }
    }

    /**
     * results 每条是「工具名\n结果」；confirmMessageId 非空表示本轮停在确认卡上，cardTools 是卡上待确认的工具名
     */
    record Reply(String conversationId, String answer, List<String> tools, List<String> results,
                 List<String> cardTools, String confirmMessageId, List<String> errors, String messageStatus) {
    }

    private static final class Accumulator {

        private final StringBuilder data = new StringBuilder();
        private final StringBuilder answer = new StringBuilder();
        private final Set<String> tools = new LinkedHashSet<>();
        private final List<String> cardTools = new ArrayList<>();
        private final List<String> results = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private String event;
        private String conversationId;
        private String confirmMessageId;
        private String messageStatus;
        private boolean done;

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
            String value = colon < 0 ? "" : line.substring(colon + 1);
            value = value.startsWith(" ") ? value.substring(1) : value;
            if ("event".equals(field)) {
                event = value;
            } else if ("data".equals(field)) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(value);
            }
        }

        void flush() {
            if (event != null || !data.isEmpty()) {
                handle(event, data.toString());
            }
            event = null;
            data.setLength(0);
        }

        private void handle(String name, String payload) {
            if (name == null) {
                return;
            }
            Map<String, Object> body = asObject(payload);
            switch (name) {
                case "meta" -> conversationId = SimpleJson.string(body, "conversationId");
                case "message" -> {
                    String text = SimpleJson.string(body, "delta");
                    if (text != null && !"reasoning".equals(SimpleJson.string(body, "type"))) {
                        answer.append(text);
                    }
                }
                case "block" -> {
                    String toolName = SimpleJson.string(body, "name");
                    if ("tool".equals(SimpleJson.string(body, "kind")) && toolName != null && !toolName.isBlank()) {
                        tools.add(toolName);
                        String result = SimpleJson.string(body, "result");
                        if (result != null && !result.isBlank()) {
                            results.add(toolName + "\n" + result);
                        }
                    }
                }
                case "confirm" -> {
                    confirmMessageId = SimpleJson.string(body, "messageId");
                    messageStatus = "AWAITING_CONFIRM";
                    Object calls = body.get("calls");
                    if (calls != null) {
                        for (Object call : SimpleJson.array(calls)) {
                            String toolName = SimpleJson.string(SimpleJson.object(call), "name");
                            if (toolName != null) {
                                cardTools.add(toolName);
                            }
                        }
                    }
                }
                case "finish" -> messageStatus = SimpleJson.string(body, "messageStatus");
                case "error", "cancel" -> errors.add(name + ": " + abbreviate(payload));
                case "done" -> done = true;
                default -> {
                    // hint 等事件与摘要判定无关
                }
            }
        }

        Reply toReply() {
            return new Reply(conversationId, answer.toString(), List.copyOf(tools), List.copyOf(results),
                    List.copyOf(cardTools), confirmMessageId, List.copyOf(errors), messageStatus);
        }

        private static Map<String, Object> asObject(String payload) {
            try {
                return SimpleJson.object(SimpleJson.parse(payload));
            } catch (RuntimeException ex) {
                return Map.of();
            }
        }

        private static String abbreviate(String value) {
            return value.length() <= 300 ? value : value.substring(0, 300) + "...";
        }
    }
}
