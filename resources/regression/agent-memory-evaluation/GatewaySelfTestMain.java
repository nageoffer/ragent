/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

/** Offline tests: only loopback fake upstreams, no API keys or production services. */
public final class GatewaySelfTestMain {
    private static final String SECRET = "fake-secret-must-never-appear-in-ledger";

    private GatewaySelfTestMain() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("evaluation-gateway-test-");
        Path ledger = temporary.resolve("usage.jsonl");
        AtomicReference<String> forwardedRequest = new AtomicReference<>();
        AtomicReference<String> forwardedAuth = new AtomicReference<>();
        AtomicReference<String> forwardedPath = new AtomicReference<>();
        CountDownLatch releaseStream = new CountDownLatch(1);
        CountDownLatch releaseDoneConnection = new CountDownLatch(1);
        var upstreamExecutor = Executors.newFixedThreadPool(4);
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        upstream.setExecutor(upstreamExecutor);
        upstream.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            forwardedRequest.set(new String(body, StandardCharsets.UTF_8));
            forwardedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            forwardedPath.set(exchange.getRequestURI().getPath());
            Map<String, Object> request = SimpleJson.object(SimpleJson.parse(forwardedRequest.get()));
            String model = String.valueOf(request.get("model"));
            boolean stream = Boolean.TRUE.equals(request.get("stream"));
            if (stream) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(200, 0);
                try (var output = exchange.getResponseBody()) {
                    if (model.equals("truncated") || model.equals("stream-error") || model.equals("done-open")) {
                        String event = model.equals("stream-error")
                                ? "data: {\"error\":{\"message\":\"" + SECRET + "\"}}\n\n"
                                : "data: {\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":4,\"prompt_cache_hit_tokens\":80}}\n\n";
                        output.write(event.getBytes(StandardCharsets.UTF_8));
                        if (!model.equals("truncated")) {
                            output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                        }
                        output.flush();
                        if (model.equals("done-open")) {
                            try {
                                releaseDoneConnection.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        return;
                    }
                    output.write("data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    try {
                        if (!releaseStream.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Streaming test timed out");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                    output.write("data: {\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":4,\"prompt_tokens_details\":{\"cached_tokens\":80}}}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            String response = switch (model) {
                case "cached" -> "{\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":5,\"prompt_cache_hit_tokens\":70,\"prompt_cache_miss_tokens\":30}}";
                case "cache-missing" -> "{\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":5}}";
                case "usage-missing" -> "{\"choices\":[]}";
                case "inconsistent" -> "{\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":5,\"prompt_cache_hit_tokens\":80,\"prompt_cache_miss_tokens\":50}}";
                case "gzip" -> "{\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":5,\"prompt_tokens_details\":{\"cached_tokens\":0}}}";
                default -> "{\"error\":\"" + SECRET + "\"}";
            };
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            if (model.equals("gzip")) {
                ByteArrayOutputStream compressed = new ByteArrayOutputStream();
                try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
                    gzip.write(responseBytes);
                }
                responseBytes = compressed.toByteArray();
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(model.equals("failure") ? 401 : 200, responseBytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(responseBytes);
            }
        });
        upstream.start();
        URI base = URI.create("http://127.0.0.1:" + upstream.getAddress().getPort());
        try (var gateway = new EvaluationUsageGatewayMain.Gateway(0, Map.of("deepseek", base, "bailian", base), ledger)) {
            gateway.start();
            HttpClient client = HttpClient.newHttpClient();
            String target = "http://127.0.0.1:" + gateway.port();
            String request = "{\"model\":\"cached\",\"messages\":[{\"role\":\"user\",\"content\":\"" + SECRET + "\"}],\"stream\":false}";
            HttpResponse<String> cached = send(client, target + "/deepseek/chat/completions", request);
            check(cached.statusCode() == 200, "JSON response status");
            check(request.equals(forwardedRequest.get()), "Request body passed unchanged");
            check(("Bearer " + SECRET).equals(forwardedAuth.get()), "Authorization passed unchanged");
            check("/chat/completions".equals(forwardedPath.get()), "Route prefix removed");
            List<Map<String, Object>> records = records(ledger);
            check(records.size() == 1, "Ledger flushed before JSON response finished");
            expect(records.get(0), 100L, 70L, 30L, 5L, true, true);
            check(Boolean.FALSE.equals(records.get(0).get("stream")), "Sync flag recorded");
            send(client, target + "/bailian/compatible-mode/v1/chat/completions", "{\"model\":\"cache-missing\"}");
            check("/compatible-mode/v1/chat/completions".equals(forwardedPath.get()), "Bailian compatible-mode path preserved");
            records = records(ledger);
            expect(records.get(1), 100L, null, null, 5L, true, false);
            check("bailian".equals(records.get(1).get("route")), "Provider route recorded");
            send(client, target + "/deepseek/chat/completions", "{\"model\":\"usage-missing\"}");
            expect(records(ledger).get(2), null, null, null, null, false, false);
            send(client, target + "/deepseek/chat/completions", "{\"model\":\"inconsistent\"}");
            expect(records(ledger).get(3), 100L, null, null, 5L, true, false);
            HttpResponse<String> gzip = send(client, target + "/deepseek/chat/completions", "{\"model\":\"gzip\"}");
            check(gzip.headers().firstValue("content-encoding").isEmpty(), "Decoded gzip header removed");
            expect(records(ledger).get(4), 100L, 0L, 100L, 5L, true, true);
            HttpResponse<String> failure = send(client, target + "/deepseek/chat/completions", "{\"model\":\"failure\"}");
            check(failure.statusCode() == 401 && failure.body().contains(SECRET), "Upstream error passed through unchanged");
            check("http_error".equals(records(ledger).get(5).get("status")), "HTTP failure attempt recorded");
            check(!Files.readString(ledger).contains(SECRET), "Secret excluded from ledger");
            int beforeInvalid = records(ledger).size();
            for (String invalid : List.of("/unknown/chat/completions", "/deepseek/%2e%2e/chat/completions",
                    "/deepseek/chat/completions?secret=" + SECRET, "/deepseek/v1/models")) {
                check(send(client, target + invalid, "{}").statusCode() == 404, "Unconfigured path rejected");
            }
            check(records(ledger).size() == beforeInvalid, "Rejected local requests are not upstream attempts");
            HttpRequest streamRequest = HttpRequest.newBuilder(URI.create(target + "/deepseek/chat/completions"))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"streaming\",\"stream\":true}")).build();
            HttpResponse<InputStream> streamed = client.send(streamRequest, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = streamed.body()) {
                ByteArrayOutputStream firstEvent = new ByteArrayOutputStream();
                while (!firstEvent.toString(StandardCharsets.UTF_8).endsWith("\r\n\r\n")) {
                    int value = input.read();
                    check(value >= 0, "First stream event available before upstream completion");
                    firstEvent.write(value);
                }
                check(firstEvent.toString(StandardCharsets.UTF_8).contains("first"), "Streaming forwarded incrementally");
                check(records(ledger).size() == beforeInvalid, "Open stream not counted prematurely");
                releaseStream.countDown();
                String remainder = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                check(remainder.endsWith("data: [DONE]\n\n"), "SSE terminal event preserved");
                check(records(ledger).size() == beforeInvalid + 1, "Ledger flushed before SSE completion");
            }
            Map<String, Object> streamRecord = records(ledger).get(beforeInvalid);
            expect(streamRecord, 100L, 80L, 20L, 4L, true, true);
            check(Boolean.TRUE.equals(streamRecord.get("stream")), "Streaming flag recorded");
            check(!Files.readString(ledger).contains(SECRET), "All records exclude prompts, auth, and error bodies");
            boolean overwriteRefused = false;
            try (var ignored = new EvaluationUsageGatewayMain.Gateway(0, Map.of("deepseek", base), ledger)) {
                // Must not reach this branch.
            } catch (java.nio.file.FileAlreadyExistsException expected) {
                overwriteRefused = true;
            }
            check(overwriteRefused, "Existing ledger cannot be overwritten");
            send(client, target + "/deepseek/v1/chat/completions", "{\"model\":\"cached\"}");
            check("/v1/chat/completions".equals(forwardedPath.get()), "v1 path preserved");
            expect(records(ledger).get(beforeInvalid + 1), 100L, 70L, 30L, 5L, true, true);
            int beforeEdgeCases = records(ledger).size();
            HttpResponse<String> jsonError = send(client, target + "/deepseek/chat/completions", "{\"model\":\"provider-error\"}");
            check(jsonError.statusCode() == 200 && jsonError.body().contains(SECRET), "HTTP 200 error body remains unchanged");
            check("provider_error".equals(records(ledger).get(beforeEdgeCases).get("status")), "HTTP 200 provider error classified");
            send(client, target + "/deepseek/chat/completions", "{\"model\":\"stream-error\",\"stream\":true}");
            check("provider_error".equals(records(ledger).get(beforeEdgeCases + 1).get("status")), "SSE provider error classified");
            send(client, target + "/deepseek/chat/completions", "{\"model\":\"truncated\",\"stream\":true}");
            Map<String, Object> truncated = records(ledger).get(beforeEdgeCases + 2);
            expect(truncated, 100L, 80L, 20L, 4L, true, true);
            check("stream_incomplete".equals(truncated.get("status")), "Usage alone does not make a truncated stream successful");
            HttpRequest openDoneRequest = HttpRequest.newBuilder(URI.create(target + "/deepseek/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"done-open\",\"stream\":true}")).build();
            HttpResponse<String> openDone = client.sendAsync(openDoneRequest, HttpResponse.BodyHandlers.ofString())
                    .get(5, TimeUnit.SECONDS);
            check(openDone.body().endsWith("data: [DONE]\n\n"), "DONE is released while upstream connection remains open");
            check(records(ledger).size() == beforeEdgeCases + 4, "Terminal usage committed without waiting for upstream EOF");
            releaseDoneConnection.countDown();
            int beforeDisconnect = records(ledger).size();
            upstream.stop(0);
            HttpResponse<String> disconnected = send(client, target + "/deepseek/chat/completions", "{\"model\":\"cached\"}");
            check(disconnected.statusCode() == 502, "Unavailable upstream returns generic gateway error");
            Map<String, Object> transport = records(ledger).get(beforeDisconnect);
            check("transport_error".equals(transport.get("status")), "Transport failure recorded");
            check(transport.get("httpStatus") == null && transport.get("errorClass") != null, "Failed attempt has class only");
            check(!Files.readString(ledger).contains(SECRET), "Transport failure does not expose credentials");
        } finally {
            releaseStream.countDown();
            releaseDoneConnection.countDown();
            upstream.stop(0);
            upstreamExecutor.shutdownNow();
        }
        Path config = temporary.resolve("routes.properties");
        for (String invalid : List.of("file:///etc/passwd", "https://user:password@example.com", "https://example.com/a/../b",
                "https://example.com?token=" + SECRET, "https://example.com/%2e%2e")) {
            Files.writeString(config, "route.deepseek.upstream=" + invalid);
            boolean refused = false;
            try {
                EvaluationUsageGatewayMain.loadRoutes(config);
            } catch (IllegalArgumentException expected) {
                refused = true;
            }
            check(refused, "Unsafe route configuration rejected");
        }
        Files.delete(config);
        Files.delete(ledger);
        Files.delete(temporary);
        System.out.println("Gateway self-test passed: JSON, incremental SSE, terminal/EOF handling, usage/cache missing, gzip, HTTP/provider/transport errors, route restrictions, no-secret ledger, overwrite guard.");
    }

    private static HttpResponse<String> send(HttpClient client, String target, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + SECRET)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<Map<String, Object>> records(Path ledger) throws Exception {
        List<Map<String, Object>> records = new ArrayList<>();
        for (String line : Files.readAllLines(ledger)) {
            if (!line.isBlank()) {
                records.add(SimpleJson.object(SimpleJson.parse(line)));
            }
        }
        return records;
    }

    private static void expect(Map<String, Object> record, Long input, Long cached, Long miss, Long output,
                               boolean usage, boolean cache) {
        check(equalNumber(record.get("inputTokens"), input), "input tokens");
        check(equalNumber(record.get("cachedTokens"), cached), "cached tokens");
        check(equalNumber(record.get("uncachedInputTokens"), miss), "uncached tokens");
        check(equalNumber(record.get("outputTokens"), output), "output tokens");
        check(Boolean.valueOf(usage).equals(record.get("usageAvailable")), "usage available flag");
        check(Boolean.valueOf(cache).equals(record.get("cacheKnown")), "cache known flag");
    }

    private static boolean equalNumber(Object actual, Long expected) {
        return expected == null ? actual == null : actual instanceof Number number && number.longValue() == expected;
    }

    private static void check(boolean value, String description) {
        if (!value) {
            throw new AssertionError(description);
        }
    }
}
