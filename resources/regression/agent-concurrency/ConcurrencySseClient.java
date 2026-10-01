/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Real-service SSE recorder. Each instance borrows one user's independent login session. */
final class ConcurrencySseClient implements AutoCloseable {
    private static final String CHAT_PATH = "/agent/v1/chat";
    private final RagentHttpClient http;
    private final HttpClient client;
    private final ExecutorService readers = Executors.newCachedThreadPool(daemonFactory("concurrency-sse"));
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            daemonFactory("concurrency-deadline"));
    private final Set<LiveTurn> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    ConcurrencySseClient(RagentHttpClient http, InitializerConfig config) {
        this.http = http;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getInt("server.connect-timeout-seconds", 5)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    LiveTurn start(String question, String conversationId, Duration timeout, Path framesFile) throws IOException {
        if (closed) {
            throw new IllegalStateException("SSE client is closed");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        String authorization = http.authorization();
        LiveTurn turn = new LiveTurn(question, conversationId, framesFile, authorization);
        active.add(turn);
        turn.completion().whenComplete((result, error) -> active.remove(turn));
        String path = CHAT_PATH + "?question=" + RagentHttpClient.encodeQuery(question)
                + (conversationId == null || conversationId.isBlank() ? ""
                : "&conversationId=" + RagentHttpClient.encodeQuery(conversationId));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(http.baseUrl() + path))
                    .timeout(timeout).header("Accept", "text/event-stream")
                    .header("Authorization", authorization).GET().build();
            long remainingNanos = Math.max(0, timeout.toNanos() - (System.nanoTime() - turn.requestedNanos));
            turn.deadline = deadlines.schedule(() -> turn.terminate("wall_timeout", true),
                    remainingNanos, TimeUnit.NANOSECONDS);
            CompletableFuture<HttpResponse<InputStream>> pending = client.sendAsync(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            turn.attachRequest(pending);
            pending.whenComplete((response, failure) -> {
                if (failure != null) {
                    turn.fail("http_transport", failure);
                } else if (turn.attachResponse(response)) {
                    try {
                        readers.execute(() -> read(turn, response));
                    } catch (RuntimeException error) {
                        turn.fail("reader_dispatch", error);
                    }
                }
            });
        } catch (RuntimeException error) {
            turn.fail("request_setup", error);
        }
        return turn;
    }

    private void read(LiveTurn turn, HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            if (!turn.isSse()) {
                try (InputStreamReader reader = new InputStreamReader(body, StandardCharsets.UTF_8)) {
                    char[] buffer = new char[4096];
                    int size;
                    while ((size = reader.read(buffer)) >= 0 && !turn.hasEnded()) {
                        turn.bodyChunk(new String(buffer, 0, size));
                    }
                }
                turn.finishNonSse();
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
            String line;
            while (!turn.hasEnded() && (line = reader.readLine()) != null) {
                turn.line(line);
                // done is a protocol terminator; do not wait for an otherwise idle TCP connection.
                if (turn.hasDone()) {
                    turn.finishStream();
                    return;
                }
            }
            turn.finishStream();
        } catch (Exception error) {
            turn.fail("stream_read", error);
        }
    }

    @Override
    public void close() {
        closed = true;
        for (LiveTurn turn : List.copyOf(active)) {
            turn.terminate("client_closed", false);
        }
        deadlines.shutdownNow();
        readers.shutdownNow();
        // The borrowed RagentHttpClient remains logged in; its owner manages logout.
    }

    final class LiveTurn {
        private final CompletableFuture<Map<String, Object>> meta = new CompletableFuture<>();
        private final CompletableFuture<Result> completion = new CompletableFuture<>();
        private final BufferedWriter ledger;
        private final Path framesFile;
        private final String authorization;
        private final String question;
        private final String requestedConversationId;
        private final long requestedNanos = System.nanoTime();
        private final Map<String, Object> timings = new LinkedHashMap<>();
        private final StringBuilder answer = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final StringBuilder responseBody = new StringBuilder();
        private final List<Map<String, Object>> errors = new ArrayList<>();
        private final List<Map<String, Object>> toolUpdates = new ArrayList<>();
        private final Map<String, Map<String, Object>> toolStates = new LinkedHashMap<>();
        private final Set<String> tools = new LinkedHashSet<>();
        private final List<Long> textEventEpochMillis = new ArrayList<>();
        private final List<Long> reasoningEventEpochMillis = new ArrayList<>();
        private final List<String> pendingLines = new ArrayList<>();
        private Map<String, Object> metaPayload = Map.of();
        private Map<String, Object> finishPayload = Map.of();
        private Map<String, Object> cancelPayload = Map.of();
        private String terminalType;
        private Object nonSseJson;
        private InputStream body;
        private CompletableFuture<?> pending;
        private ScheduledFuture<?> deadline;
        private Integer httpStatus;
        private String contentType;
        private boolean sse;
        private boolean done;
        private boolean cancelled;
        private boolean timedOut;
        private boolean ended;
        private long frameCount;

        private LiveTurn(String question, String conversationId, Path framesFile, String authorization)
                throws IOException {
            this.question = question;
            this.requestedConversationId = conversationId;
            this.authorization = authorization;
            this.framesFile = framesFile.toAbsolutePath().normalize();
            if (this.framesFile.getParent() != null) {
                Files.createDirectories(this.framesFile.getParent());
            }
            ledger = Files.newBufferedWriter(this.framesFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            mark("request");
            write("request", nullableMap("path", CHAT_PATH, "question", redact(question),
                    "requestedConversationId", conversationId));
        }

        CompletableFuture<Map<String, Object>> meta() {
            return meta;
        }

        Map<String, Object> awaitMeta(Duration wait) throws InterruptedException, ExecutionException, TimeoutException {
            return meta.get(wait.toNanos(), TimeUnit.NANOSECONDS);
        }

        CompletableFuture<Result> completion() {
            return completion;
        }

        synchronized Map<String, Object> snapshot() {
            return snapshotInternal();
        }

        private synchronized void attachRequest(CompletableFuture<?> request) {
            pending = request;
            if (ended) {
                request.cancel(true);
            }
        }

        private synchronized boolean attachResponse(HttpResponse<InputStream> response) {
            if (ended) {
                closeBody(response.body());
                return false;
            }
            body = response.body();
            httpStatus = response.statusCode();
            contentType = response.headers().firstValue("content-type").orElse("");
            sse = contentType.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream");
            mark("headers");
            // Never record arbitrary headers: proxies may echo credentials in them.
            write("headers", nullableMap("httpStatus", httpStatus, "contentType", contentType));
            if (httpStatus < 200 || httpStatus >= 300) {
                addError("http_status", "HTTP " + httpStatus, null);
            }
            return true;
        }

        private synchronized boolean isSse() {
            return sse;
        }

        private synchronized boolean hasEnded() {
            return ended;
        }

        private synchronized boolean hasDone() {
            return done;
        }

        private synchronized void line(String line) {
            if (ended) {
                return;
            }
            if (line.isEmpty()) {
                flushPending(false);
            } else {
                pendingLines.add(line);
            }
        }

        private void flushPending(boolean partial) {
            if (!pendingLines.isEmpty()) {
                frame(pendingLines, partial);
                pendingLines.clear();
            }
        }

        private synchronized void bodyChunk(String chunk) {
            if (ended) {
                return;
            }
            responseBody.append(chunk);
            write("body", Map.of("data", redact(chunk)));
        }

        private synchronized void finishNonSse() {
            if (ended) {
                return;
            }
            String text = redact(responseBody.toString());
            try {
                nonSseJson = SimpleJson.parse(text);
            } catch (RuntimeException ignored) {
                // The raw body remains evidence even when an intermediary returns HTML or broken JSON.
            }
            addError("non_sse_response", "Expected text/event-stream", null);
            complete();
        }

        private synchronized void frame(List<String> sourceLines, boolean partialAtEof) {
            if (ended) {
                return;
            }
            List<String> rawLines = sourceLines.stream().map(this::redact).toList();
            String event = null;
            String id = null;
            String retry = null;
            List<String> dataLines = new ArrayList<>();
            for (String line : rawLines) {
                if (line.startsWith(":")) {
                    continue;
                }
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                switch (field) {
                    case "event" -> event = value;
                    case "data" -> dataLines.add(value);
                    case "id" -> id = value;
                    case "retry" -> retry = value;
                    default -> { }
                }
            }
            String data = String.join("\n", dataLines);
            frameCount++;
            write("frame", nullableMap("sequence", frameCount, "event", event, "id", id, "retry", retry,
                    "data", data, "rawLines", rawLines, "partialAtEof", partialAtEof));
            if (event == null && dataLines.isEmpty()) {
                return; // Preserve comment-only heartbeat frames without inventing message events.
            }
            String name = event == null ? "message" : event;
            if ("done".equals(name) || "[DONE]".equals(data)) {
                done = true;
                mark("done");
                return;
            }
            if ("cancel".equals(name)) {
                cancelled = true;
                mark("cancel");
                terminalType = "cancel";
                try {
                    cancelPayload = immutableMap(SimpleJson.object(SimpleJson.parse(data)));
                    // The service sends AgentCompletionPayload on cancel, then done; no finish frame.
                    finishPayload = cancelPayload;
                } catch (RuntimeException error) {
                    addError("malformed_event", "cancel: " + error.getMessage(), error.getClass().getSimpleName());
                }
                return;
            }
            Map<String, Object> value;
            try {
                value = SimpleJson.object(SimpleJson.parse(data));
            } catch (RuntimeException error) {
                if (Set.of("meta", "message", "block", "finish", "error", "reject").contains(name)) {
                    addError("malformed_event", name + ": " + error.getMessage(), error.getClass().getSimpleName());
                }
                return;
            }
            switch (name) {
                case "meta" -> {
                    metaPayload = immutableMap(value);
                    mark("meta");
                    meta.complete(metaPayload);
                }
                case "message" -> {
                    String delta = SimpleJson.string(value, "delta");
                    if (delta == null || delta.isEmpty()) {
                        return;
                    }
                    long now = System.currentTimeMillis();
                    markFirst("firstToken");
                    mark("lastToken");
                    if ("reasoning".equals(SimpleJson.string(value, "type"))) {
                        reasoning.append(delta);
                        reasoningEventEpochMillis.add(now);
                        markFirst("firstReasoningToken");
                        mark("lastReasoningToken");
                    } else {
                        answer.append(delta);
                        textEventEpochMillis.add(now);
                        markFirst("firstTextToken");
                        mark("lastTextToken");
                    }
                }
                case "block" -> {
                    if ("tool".equals(SimpleJson.string(value, "kind"))) {
                        Map<String, Object> update = new LinkedHashMap<>(value);
                        update.put("receivedAtEpochMillis", System.currentTimeMillis());
                        update.put("receivedElapsedMillis", elapsedMillis());
                        toolUpdates.add(immutableMap(update));
                        String tool = SimpleJson.string(value, "name");
                        if (tool != null) {
                            tools.add(tool);
                        }
                        String toolCallId = SimpleJson.string(value, "toolCallId");
                        String key = toolCallId == null ? "missing-id-" + toolUpdates.size() : toolCallId;
                        Map<String, Object> merged = new LinkedHashMap<>(toolStates.getOrDefault(key, Map.of()));
                        merged.putAll(update);
                        toolStates.put(key, immutableMap(merged));
                    } else if ("error".equals(SimpleJson.string(value, "kind"))) {
                        addError("error_block", SimpleJson.stringify(value), null);
                    }
                }
                case "finish" -> {
                    finishPayload = immutableMap(value);
                    terminalType = "finish";
                    mark("finish");
                }
                case "error", "reject" -> addError("server_" + name, SimpleJson.stringify(value), null);
                default -> { }
            }
        }

        private synchronized void finishStream() {
            if (ended) {
                return;
            }
            flushPending(true);
            if (SimpleJson.string(metaPayload, "conversationId") == null
                    || SimpleJson.string(metaPayload, "taskId") == null) {
                addError("missing_meta", "SSE has no complete conversationId/taskId metadata", null);
            }
            if (!done) {
                addError("missing_done", "SSE ended without done", null);
            }
            if (finishPayload.isEmpty() && !cancelled) {
                addError("missing_finish", "SSE ended without finish", null);
            }
            boolean hasModelOutput = !answer.isEmpty() || !reasoning.isEmpty() || !toolUpdates.isEmpty();
            if ((!cancelled || hasModelOutput) && SimpleJson.string(finishPayload, "messageId") == null) {
                addError("missing_message_id", "Completion payload has no messageId", null);
            }
            String status = SimpleJson.string(finishPayload, "messageStatus");
            if (status != null && !"NORMAL".equals(status) && !cancelled) {
                addError("message_status", status, null);
            }
            complete();
        }

        private synchronized void fail(String type, Throwable failure) {
            if (ended) {
                return;
            }
            Throwable cause = failure;
            while ((cause instanceof java.util.concurrent.CompletionException
                    || cause instanceof ExecutionException) && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof java.net.http.HttpTimeoutException) {
                timedOut = true;
            }
            addError(type, String.valueOf(cause.getMessage()), cause.getClass().getSimpleName());
            complete();
        }

        private synchronized void terminate(String reason, boolean timeout) {
            if (ended) {
                return;
            }
            timedOut = timeout;
            addError(reason, timeout ? "Request exceeded hard wall-clock deadline" : "Client closed", null);
            complete();
        }

        private void addError(String type, String message, String exceptionClass) {
            Map<String, Object> error = nullableMap("type", type, "message", redact(message),
                    "exceptionClass", exceptionClass, "atEpochMillis", System.currentTimeMillis(),
                    "elapsedMillis", elapsedMillis());
            errors.add(immutableMap(error));
            write("error", error);
        }

        /** Caller holds this monitor; no network close is allowed to delay completion/deadline. */
        private void complete() {
            if (ended) {
                return;
            }
            flushPending(true);
            ended = true;
            mark("ended");
            if (deadline != null) {
                deadline.cancel(false);
            }
            write("ended", nullableMap("done", done, "cancelled", cancelled, "timedOut", timedOut,
                    "frameCount", frameCount));
            try {
                ledger.close();
            } catch (IOException error) {
                errors.add(immutableMap(nullableMap("type", "ledger_close", "message", redact(error.getMessage()))));
            }
            Result result = new Result(snapshotInternal());
            if (!meta.isDone()) {
                meta.completeExceptionally(new IOException("Turn ended before meta: " + result.errors()));
            }
            completion.complete(result);
            if (pending != null && !pending.isDone()) {
                pending.cancel(true);
            }
            if (body != null) {
                closeBody(body);
            }
        }

        private Map<String, Object> snapshotInternal() {
            Map<String, Object> data = nullableMap("question", redact(question),
                    "requestedConversationId", requestedConversationId, "framesFile", framesFile.toString(),
                    "httpStatus", httpStatus, "contentType", contentType, "sse", sse,
                    "conversationId", SimpleJson.string(metaPayload, "conversationId"),
                    "taskId", SimpleJson.string(metaPayload, "taskId"),
                    "messageId", SimpleJson.string(finishPayload, "messageId"),
                    "messageStatus", SimpleJson.string(finishPayload, "messageStatus"),
                    "answer", answer.toString(), "reasoning", reasoning.toString(),
                    "thinkChars", reasoning.length(), "tools", List.copyOf(tools),
                    "toolUpdates", List.copyOf(toolUpdates), "toolStates", immutableMap(toolStates),
                    "meta", metaPayload, "finish", finishPayload, "cancel", cancelPayload,
                    "terminalType", terminalType,
                    "done", done, "cancelled", cancelled, "timedOut", timedOut, "ended", ended,
                    "successful", ended && done && !cancelled && !timedOut && errors.isEmpty(),
                    "errors", List.copyOf(errors), "frameCount", frameCount,
                    "responseBody", redact(responseBody.toString()), "responseJson", nonSseJson,
                    "timings", immutableMap(timings),
                    "textEventEpochMillis", List.copyOf(textEventEpochMillis),
                    "reasoningEventEpochMillis", List.copyOf(reasoningEventEpochMillis));
            // Flat aliases make interval comparisons convenient for the runner/report.
            for (String name : List.of("request", "headers", "meta", "firstToken", "lastToken", "finish", "ended",
                    "firstTextToken", "lastTextToken", "firstReasoningToken", "lastReasoningToken")) {
                data.put(name + "At", timings.get(name + "At"));
                data.put(name + "AtEpochMillis", timings.get(name + "AtEpochMillis"));
            }
            return immutableMap(data);
        }

        private void markFirst(String name) {
            if (!timings.containsKey(name + "At")) {
                mark(name);
            }
        }

        private void mark(String name) {
            long now = System.currentTimeMillis();
            timings.put(name + "At", Instant.ofEpochMilli(now).toString());
            timings.put(name + "AtEpochMillis", now);
            timings.put(name + "ElapsedMillis", elapsedMillis());
        }

        private long elapsedMillis() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestedNanos);
        }

        private void write(String kind, Map<String, Object> values) {
            Map<String, Object> record = new LinkedHashMap<>();
            long now = System.currentTimeMillis();
            record.put("kind", kind);
            record.put("at", Instant.ofEpochMilli(now).toString());
            record.put("atEpochMillis", now);
            record.put("elapsedMillis", elapsedMillis());
            record.putAll(values);
            try {
                ledger.write(redact(SimpleJson.stringify(record)));
                ledger.newLine();
                ledger.flush();
            } catch (IOException error) {
                errors.add(immutableMap(nullableMap("type", "ledger_write", "message", redact(error.getMessage()))));
            }
        }

        private String redact(String value) {
            if (value == null) {
                return null;
            }
            String cleaned = value.replace(authorization, "[REDACTED]");
            if (authorization.startsWith("Bearer ")) {
                cleaned = cleaned.replace(authorization.substring(7), "[REDACTED]");
            }
            return cleaned;
        }
    }

    record Result(Map<String, Object> data) {
        Map<String, Object> toMap() { return data; }
        String conversationId() { return SimpleJson.string(data, "conversationId"); }
        String taskId() { return SimpleJson.string(data, "taskId"); }
        String messageId() { return SimpleJson.string(data, "messageId"); }
        String answer() { return SimpleJson.string(data, "answer"); }
        boolean done() { return Boolean.TRUE.equals(data.get("done")); }
        boolean cancelled() { return Boolean.TRUE.equals(data.get("cancelled")); }
        boolean timedOut() { return Boolean.TRUE.equals(data.get("timedOut")); }
        boolean successful() { return Boolean.TRUE.equals(data.get("successful")); }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors() { return (List<Map<String, Object>>) data.get("errors"); }
    }

    private static void closeBody(InputStream input) {
        // JDK HTTP body close aborts blocked reads. Keep even a misbehaving close off the deadline thread.
        Thread closer = daemonFactory("concurrency-body-close").newThread(() -> {
            try {
                input.close();
            } catch (IOException ignored) { }
        });
        closer.start();
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static Map<String, Object> nullableMap(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    private static <T> Map<String, T> immutableMap(Map<String, T> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
