/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** Ragent HTTP API client implemented with java.net.http.HttpClient. */
final class RagentHttpClient implements AutoCloseable {

    private final String baseUrl;
    private final Duration requestTimeout;
    private final HttpClient client;
    private String token;

    RagentHttpClient(InitializerConfig config) {
        this.baseUrl = stripTrailingSlash(config.require("server.base-url"));
        this.requestTimeout = Duration.ofSeconds(config.getInt("server.request-timeout-seconds", 60));
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getInt("server.connect-timeout-seconds", 5)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    LoginSession login(String username, String password) throws IOException, InterruptedException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("username", username);
        request.put("password", password);
        Object data = send("POST", "/auth/login", "application/json;charset=UTF-8",
                HttpRequest.BodyPublishers.ofString(SimpleJson.stringify(request), StandardCharsets.UTF_8), false);
        Map<String, Object> session = SimpleJson.object(data);
        String receivedToken = SimpleJson.string(session, "token");
        String role = SimpleJson.string(session, "role");
        String userId = SimpleJson.string(session, "userId");
        if (receivedToken == null || receivedToken.isBlank()) {
            throw new IllegalStateException("登录成功响应中缺少 token");
        }
        token = receivedToken;
        return new LoginSession(userId, role);
    }

    Object get(String path) throws IOException, InterruptedException {
        return send("GET", path, null, HttpRequest.BodyPublishers.noBody(), true);
    }

    Object postJson(String path, Map<String, Object> body) throws IOException, InterruptedException {
        return send("POST", path, "application/json;charset=UTF-8",
                HttpRequest.BodyPublishers.ofString(SimpleJson.stringify(body), StandardCharsets.UTF_8), true);
    }

    Object putJson(String path, Map<String, Object> body) throws IOException, InterruptedException {
        return send("PUT", path, "application/json;charset=UTF-8",
                HttpRequest.BodyPublishers.ofString(SimpleJson.stringify(body), StandardCharsets.UTF_8), true);
    }

    Object postEmpty(String path) throws IOException, InterruptedException {
        return send("POST", path, null, HttpRequest.BodyPublishers.noBody(), true);
    }

    Object delete(String path) throws IOException, InterruptedException {
        return send("DELETE", path, null, HttpRequest.BodyPublishers.noBody(), true);
    }

    Object uploadDocument(String kbId, Path file, String ingestionSpec) throws IOException, InterruptedException {
        String boundary = "----RagentInitializer" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeField(body, boundary, "sourceType", "file");
        writeField(body, boundary, "processMode", "chunk");
        writeField(body, boundary, "scheduleEnabled", "false");
        if (ingestionSpec != null && !ingestionSpec.isBlank()) {
            writeField(body, boundary, "ingestionSpec", ingestionSpec);
        }
        writeFile(body, boundary, file);
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send("POST", "/knowledge-base/" + encodePath(kbId) + "/docs/upload",
                "multipart/form-data; boundary=" + boundary,
                HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()), true);
    }

    /**
     * 提问一次并把 SSE 流读到服务端关闭
     * conversationId 为空表示新开会话，传入已有会话则作为追问，服务端据此带上历史
     * 对话接口失败时只断流不发错误事件，因此只有收到终止事件 done、且没出现 reject 和 cancel 才算成功
     */
    ChatStreamResult chatStream(String question, String conversationId, Duration timeout)
            throws IOException, InterruptedException {
        String path = CHAT_PATH + "?question=" + encodeQuery(question)
                + (conversationId == null || conversationId.isBlank()
                ? "" : "&conversationId=" + encodeQuery(conversationId));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + normalizePath(path)))
                .timeout(timeout)
                .header("Accept", "text/event-stream")
                .header("Authorization", requireToken(path))
                .GET()
                .build();

        HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP " + response.statusCode() + " GET " + CHAT_PATH + ", question=" + question);
            }
            SseAccumulator accumulator = new SseAccumulator();
            // 请求超时只覆盖到响应头，流开始之后靠这里的截止时间兜住服务端一直不结束的情况
            Instant deadline = Instant.now().plus(timeout);
            lines.forEach(line -> {
                if (Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException("SSE 流超过单题超时仍未结束: " + timeout + "，问题: " + question);
                }
                accumulator.accept(line);
            });
            accumulator.flush();
            return accumulator.toResult();
        }
    }

    /**
     * 预热走 Agent 链而不是 RAG 直答链
     * <p>
     * 两条链是两个接口：示例问题在 Agent 对话页同样渲染成卡片，用户点下去发往这里，
     * 而 engine.type=agent 时它才是主路径。只热 RAG 那条等于把用户最可能走的路留着不验
     */
    private static final String CHAT_PATH = "/agent/v1/chat";

    static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String encodePath(String value) {
        return encodeQuery(value).replace("%2F", "/");
    }

    private Object send(String method, String path, String contentType, HttpRequest.BodyPublisher body,
                        boolean authenticated) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + normalizePath(path)))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .method(method, body);
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (authenticated) {
            request.header("Authorization", requireToken(path));
        }

        HttpResponse<String> response = client.send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String responseBody = response.body() == null ? "" : response.body().trim();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " " + method + " " + path
                    + ", response=" + abbreviate(responseBody));
        }
        if (responseBody.isEmpty()) {
            return null;
        }

        Object parsed;
        try {
            parsed = SimpleJson.parse(responseBody);
        } catch (IllegalArgumentException ex) {
            throw new IOException("接口没有返回合法 JSON: " + method + " " + path
                    + ", response=" + abbreviate(responseBody), ex);
        }
        if (!(parsed instanceof Map<?, ?>)) {
            return parsed;
        }
        Map<String, Object> envelope = SimpleJson.object(parsed);
        if (!envelope.containsKey("code")) {
            return envelope;
        }
        String code = SimpleJson.string(envelope, "code");
        if (!"0".equals(code)) {
            String message = SimpleJson.string(envelope, "message");
            String requestId = SimpleJson.string(envelope, "requestId");
            throw new IllegalStateException("Ragent API 失败: " + message
                    + (requestId == null ? "" : " (requestId=" + requestId + ")"));
        }
        return envelope.get("data");
    }

    private String requireToken(String path) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("请求接口前必须先登录: " + path);
        }
        return token;
    }

    private static void writeField(ByteArrayOutputStream output, String boundary, String name, String value)
            throws IOException {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFile(ByteArrayOutputStream output, String boundary, Path file) throws IOException {
        String filename = file.getFileName().toString();
        String encodedFilename = encodeQuery(filename);
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"file\"; filename=\"upload.bin\"; filename*=UTF-8''"
                + encodedFilename + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Type: " + contentType(filename) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(Files.readAllBytes(file));
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String contentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "text/markdown;charset=UTF-8";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain;charset=UTF-8";
        }
        return "application/octet-stream";
    }

    private static String normalizePath(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String stripTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String abbreviate(String value) {
        return value.length() <= 2000 ? value : value.substring(0, 2000) + "...";
    }

    @Override
    public void close() {
        if (token == null) {
            return;
        }
        try {
            postEmpty("/auth/logout");
        } catch (Exception ignored) {
            // Initialization result must not be hidden by best-effort logout.
        } finally {
            token = null;
        }
    }

    record LoginSession(String userId, String role) {
    }

    record ChatStreamResult(String conversationId, String messageId, int answerLength) {
    }

    /** Reassembles SSE frames line by line and keeps only what the warm-up needs to judge the run. */
    private static final class SseAccumulator {

        private final StringBuilder data = new StringBuilder();
        private String event;
        private String conversationId;
        private String messageId;
        private String messageStatus;
        private int answerLength;
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
                    // id 与 retry 字段对初始化没有意义
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
                case "meta" -> conversationId = SimpleJson.string(asObject(payload), "conversationId");
                case "message" -> {
                    Map<String, Object> delta = asObject(payload);
                    String text = SimpleJson.string(delta, "delta");
                    // 思考内容不计入回答长度，否则开了深度思考就永远判不出空回答
                    if (text != null && !"think".equals(SimpleJson.string(delta, "type"))) {
                        answerLength += text.length();
                    }
                }
                case "finish" -> {
                    Map<String, Object> completion = asObject(payload);
                    messageId = SimpleJson.string(completion, "messageId");
                    messageStatus = SimpleJson.string(completion, "messageStatus");
                }
                case "done" -> done = true;
                // 限流拒绝与主动取消同样会走 finish/done，必须单独判死
                case "reject" -> failure = "请求被限流拒绝: " + abbreviate(payload);
                case "cancel" -> failure = "任务被取消: " + abbreviate(payload);
                default -> {
                    // 其余事件对初始化没有意义
                }
            }
        }

        ChatStreamResult toResult() throws IOException {
            if (failure != null) {
                throw new IOException(failure + describeConversation());
            }
            if (!done) {
                throw new IOException("SSE 流未收到 done 事件即结束" + describeConversation());
            }
            if (answerLength == 0) {
                throw new IOException("模型没有产出任何回答内容" + describeConversation());
            }
            // messageId 为空说明服务端没能把答案写进消息表，后续推荐追问也无从生成
            if (messageId == null || messageId.isBlank()) {
                throw new IOException("答案没有落库，finish 事件缺少 messageId" + describeConversation());
            }
            if (messageStatus != null && !"NORMAL".equals(messageStatus)) {
                throw new IOException("答案落库状态异常: " + messageStatus + describeConversation());
            }
            return new ChatStreamResult(conversationId, messageId, answerLength);
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
    }
}
