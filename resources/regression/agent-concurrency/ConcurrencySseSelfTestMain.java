/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline regression for the recorder: loopback fake HTTP only, no application, Redis or model. */
public final class ConcurrencySseSelfTestMain {
    private static final String SECRET = "fake-concurrency-token-must-not-be-recorded-";
    private static final Duration NORMAL_TIMEOUT = Duration.ofSeconds(5);

    private ConcurrencySseSelfTestMain() { }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("agent-concurrency-sse-selftest-");
        CountDownLatch releaseBlocked = new CountDownLatch(1);
        Map<String, String> authByQuestion = new ConcurrentHashMap<>();
        AtomicInteger logoutCalls = new AtomicInteger();
        var serverWorkers = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        server.setExecutor(serverWorkers);
        server.createContext("/auth/login", exchange -> {
            Map<String, Object> body = SimpleJson.object(SimpleJson.parse(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            String username = SimpleJson.string(body, "username");
            json(exchange, 200, Map.of("code", "0", "data", Map.of("token", SECRET + username,
                    "userId", username, "role", "user")));
        });
        server.createContext("/auth/logout", exchange -> {
            logoutCalls.incrementAndGet();
            json(exchange, 200, Map.of("code", "0"));
        });
        server.createContext("/agent/v1/chat", exchange -> {
            String question = query(exchange, "question");
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authByQuestion.put(question, auth == null ? "missing" : auth);
            if ("json-reject".equals(question)) {
                json(exchange, 200, Map.of("code", "A0400", "message", "同一用户已有请求执行中",
                        "echo", SECRET + "alice"));
                return;
            }
            if ("http-error".equals(question)) {
                json(exchange, 403, Map.of("code", "A0403", "message", "forbidden"));
                return;
            }
            if ("headers-stall".equals(question)) {
                await(releaseBlocked);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                if ("body-stall".equals(question)) {
                    output.flush();
                    await(releaseBlocked);
                    return;
                }
                write(exchange, ": heartbeat\r\n\r\n");
                write(exchange, "id: 1\r\nevent: meta\r\ndata: {\"conversationId\":\"c-" + question
                        + "\",\r\ndata: \"taskId\":\"t-" + question + "\"}\r\n\r\n");
                if ("partial-stall".equals(question)) {
                    write(exchange, "event: message\ndata: {\"type\":\"reasoning\",\"delta\":\"partial evidence\"}\n");
                    await(releaseBlocked);
                    return;
                }
                write(exchange, "event: message\ndata: {\"type\":\"reasoning\",\"delta\":\"正在分析\"}\n\n");
                if ("truncated".equals(question)) {
                    return;
                }
                if ("malformed".equals(question)) {
                    write(exchange, "event: message\ndata: not-json\n\n");
                }
                if ("server-error".equals(question)) {
                    write(exchange, "event: error\ndata: {\"message\":\"explicit server error\"}\n\n");
                    write(exchange, "event: block\ndata: {\"kind\":\"error\",\"text\":\"error block\"}\n\n");
                }
                if ("cancelled".equals(question)) {
                    write(exchange, "event: cancel\ndata: {\"messageId\":\"m-" + question
                            + "\",\"messageStatus\":\"INTERRUPTED\"}\n\nevent: done\ndata: {}\n\n");
                    return;
                }
                write(exchange, "event: block\ndata: {\"kind\":\"tool\",\"name\":\"search_knowledge\","
                        + "\"toolCallId\":\"tool-" + question + "\",\"status\":\"running\"}\n\n");
                write(exchange, "event: message\ndata: {\"type\":\"answer\",\"delta\":\"答案-" + question + "\"}\n\n");
                write(exchange, "event: block\ndata: {\"kind\":\"tool\",\"name\":\"search_knowledge\","
                        + "\"toolCallId\":\"tool-" + question + "\",\"status\":\"done\",\"result\":\"证据\"}\n\n");
                finish(exchange, question, "NORMAL");
                if ("done-open".equals(question)) {
                    await(releaseBlocked);
                }
            } catch (IOException ignored) {
                // Expected when the client's hard deadline aborts the body.
            } finally {
                exchange.close();
            }
        });
        server.start();
        Path configFile = directory.resolve("test.properties");
        Files.writeString(configFile, "server.base-url=http://127.0.0.1:" + server.getAddress().getPort()
                + "\nserver.connect-timeout-seconds=1\n", StandardCharsets.UTF_8);
        InitializerConfig config = InitializerConfig.load(configFile);
        try (RagentHttpClient alice = new RagentHttpClient(config);
             RagentHttpClient bob = new RagentHttpClient(config)) {
            alice.login("alice", "fake");
            bob.login("bob", "fake");
            try (ConcurrencySseClient a = new ConcurrencySseClient(alice, config);
                 ConcurrencySseClient b = new ConcurrencySseClient(bob, config)) {
                var normalA = a.start("normal-alice", null, NORMAL_TIMEOUT, directory.resolve("normal-alice.jsonl"));
                var normalB = b.start("normal-bob", null, NORMAL_TIMEOUT, directory.resolve("normal-bob.jsonl"));
                Map<String, Object> liveMeta = normalA.awaitMeta(Duration.ofSeconds(2));
                check("t-normal-alice".equals(liveMeta.get("taskId")), "Live meta future exposes taskId");
                var resultA = normalA.completion().get(3, TimeUnit.SECONDS);
                var resultB = normalB.completion().get(3, TimeUnit.SECONDS);
                check(resultA.successful() && resultB.successful(), "Both independent users complete");
                check("答案-normal-alice".equals(resultA.answer()), "Answer deltas exclude reasoning");
                check("正在分析".equals(resultA.toMap().get("reasoning")), "Reasoning captured in full");
                check("c-normal-alice".equals(resultA.conversationId()), "Multiline meta JSON parsed");
                check("m-normal-alice".equals(resultA.messageId()), "finish messageId retained");
                check(List.of("search_knowledge").equals(resultA.toMap().get("tools")), "Tool names collected");
                Map<String, Object> toolStates = SimpleJson.object(resultA.toMap().get("toolStates"));
                check("done".equals(SimpleJson.object(toolStates.get("tool-normal-alice")).get("status")),
                        "Latest tool status keyed by toolCallId");
                check(SimpleJson.array(resultA.toMap().get("toolUpdates")).size() == 2, "Every tool update retained");
                check(SimpleJson.array(resultA.toMap().get("textEventEpochMillis")).size() == 1,
                        "Answer timestamps separate from reasoning");
                check(SimpleJson.array(resultA.toMap().get("reasoningEventEpochMillis")).size() == 1,
                        "Reasoning timestamps captured");
                check(resultA.toMap().get("firstTokenAt") != null && resultA.toMap().get("finishAt") != null,
                        "Token and finish timestamps exist");
                check((SECRET + "alice").equals(authByQuestion.get("normal-alice")), "Alice auth remains Alice");
                check((SECRET + "bob").equals(authByQuestion.get("normal-bob")), "Bob auth remains Bob");
                List<Map<String, Object>> normalLedger = records(directory.resolve("normal-alice.jsonl"));
                check(normalLedger.stream().anyMatch(record -> "frame".equals(record.get("kind"))
                        && SimpleJson.array(record.get("rawLines")).contains(": heartbeat")),
                        "Comment-only raw frame preserved");
                check(normalLedger.stream().anyMatch(record -> "1".equals(record.get("id"))), "SSE id retained");

                var refusal = await(a, "json-reject", NORMAL_TIMEOUT, directory);
                check(!refusal.successful() && hasError(refusal, "non_sse_response"), "HTTP 200 JSON refusal captured");
                check("A0400".equals(SimpleJson.object(refusal.toMap().get("responseJson")).get("code")),
                        "Business rejection code retained");
                check(!SimpleJson.stringify(refusal.toMap()).contains(SECRET), "Echoed auth redacted from results");
                var forbidden = await(a, "http-error", NORMAL_TIMEOUT, directory);
                check(hasError(forbidden, "http_status") && ((Number) forbidden.toMap().get("httpStatus")).intValue() == 403,
                        "Non-2xx body/status evidence retained");
                var truncated = await(a, "truncated", NORMAL_TIMEOUT, directory);
                check(hasError(truncated, "missing_finish") && hasError(truncated, "missing_done"),
                        "Truncated SSE reports both terminal omissions");
                check("正在分析".equals(truncated.toMap().get("reasoning")), "Failure retains preceding deltas");
                check(hasError(await(a, "malformed", NORMAL_TIMEOUT, directory), "malformed_event"),
                        "Malformed known event cannot silently pass");
                var serverError = await(a, "server-error", NORMAL_TIMEOUT, directory);
                check(hasError(serverError, "server_error") && hasError(serverError, "error_block"),
                        "Error event and error block both fail a turn");
                var cancelled = await(a, "cancelled", NORMAL_TIMEOUT, directory);
                check(cancelled.cancelled() && cancelled.done() && !cancelled.successful(),
                        "Cancelled is independently observable with cancel/done");
                check(cancelled.errors().isEmpty() && "m-cancelled".equals(cancelled.messageId())
                                && "INTERRUPTED".equals(cancelled.toMap().get("messageStatus")),
                        "Real cancel payload is completion evidence without a finish frame");
                var openDone = await(a, "done-open", NORMAL_TIMEOUT, directory);
                check(openDone.successful(), "done completes without awaiting server EOF");

                for (String scenario : List.of("body-stall", "headers-stall", "partial-stall")) {
                    long start = System.nanoTime();
                    var stalled = await(a, scenario, Duration.ofMillis(500), directory);
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    check(stalled.timedOut() || hasTimeoutException(stalled), "Blocked " + scenario + " gets deadline");
                    check(elapsed < 2500, "Deadline works without another line: " + scenario);
                    List<Map<String, Object>> ledger = records(directory.resolve(scenario + ".jsonl"));
                    check("ended".equals(ledger.get(ledger.size() - 1).get("kind")),
                            "Deadline flushes an ended record: " + scenario);
                    if (scenario.equals("partial-stall")) {
                        check("partial evidence".equals(stalled.toMap().get("reasoning")),
                                "Deadline preserves lines from an unfinished frame");
                        check(ledger.stream().anyMatch(record -> Boolean.TRUE.equals(record.get("partialAtEof"))),
                                "Partial frame labelled in raw ledger");
                    }
                }
                Path blockedFile = directory.resolve("client-close.jsonl");
                var blocked = a.start("body-stall", null, Duration.ofSeconds(30), blockedFile);
                a.close();
                check(hasError(blocked.completion().get(2, TimeUnit.SECONDS), "client_closed"),
                        "close completes active turns and releases reader resources");
                check(logoutCalls.get() == 0, "SSE client never logs out borrowed sessions");
            }
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".jsonl")).toList()) {
                    check(!Files.readString(file).contains(SECRET), "No authorization token in " + file.getFileName());
                    records(file); // Every written JSONL line parses, including failure/timeout records.
                }
            }
            check(logoutCalls.get() == 0, "Only HTTP session owner controls logout");
        } finally {
            releaseBlocked.countDown();
            server.stop(0);
            serverWorkers.shutdownNow();
        }
        check(logoutCalls.get() == 2, "Both independent HTTP session owners eventually logout");
        System.out.println("Concurrency SSE self-test passed: concurrent identities, multiline/raw frames, reasoning, tools, "
                + "timings, JSON/HTTP errors, malformed/truncated streams, cancel/done, blocked-body/header deadlines, "
                + "partial evidence, close ownership, token redaction. Evidence: " + directory);
    }

    private static ConcurrencySseClient.Result await(ConcurrencySseClient client, String question,
                                                    Duration timeout, Path directory) throws Exception {
        return client.start(question, null, timeout, directory.resolve(question + ".jsonl"))
                .completion().get(Math.max(3, timeout.toSeconds() + 1), TimeUnit.SECONDS);
    }

    private static boolean hasError(ConcurrencySseClient.Result result, String type) {
        return result.errors().stream().anyMatch(error -> type.equals(error.get("type")));
    }

    private static boolean hasTimeoutException(ConcurrencySseClient.Result result) {
        return result.errors().stream().anyMatch(error -> String.valueOf(error.get("exceptionClass")).contains("Timeout"));
    }

    private static String query(HttpExchange exchange, String key) {
        for (String field : exchange.getRequestURI().getRawQuery().split("&")) {
            String[] pair = field.split("=", 2);
            if (key.equals(URLDecoder.decode(pair[0], StandardCharsets.UTF_8))) {
                return pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static void json(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = SimpleJson.stringify(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
        exchange.close();
    }

    private static void write(HttpExchange exchange, String frame) throws IOException {
        exchange.getResponseBody().write(frame.getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }

    private static void finish(HttpExchange exchange, String question, String status) throws IOException {
        write(exchange, "event: finish\ndata: {\"messageId\":\"m-" + question
                + "\",\"messageStatus\":\"" + status + "\"}\n\nevent: done\ndata: {}\n\n");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<Map<String, Object>> records(Path file) throws IOException {
        List<Map<String, Object>> records = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                records.add(SimpleJson.object(SimpleJson.parse(line)));
            }
        }
        return records;
    }

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}
