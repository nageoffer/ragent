/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Local, JDK-only measurement proxy. Does not log prompts, authorization, or response bodies. */
public final class EvaluationUsageGatewayMain {
    private static final int MAX_REQUEST_BYTES = 16 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_EVENT_BYTES = 2 * 1024 * 1024;
    private static final Set<String> CHAT_ENDPOINTS = Set.of("chat/completions", "v1/chat/completions",
            "compatible-mode/v1/chat/completions");
    private static final Set<String> HOP_HEADERS = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length",
            "expect", "http2-settings");

    private EvaluationUsageGatewayMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.println("""
                    Usage: EvaluationUsageGatewayMain --listen-port 19090 --routes routes.properties --output usage.jsonl

                    可选用量采集器：只统计供应商返回的 token，不保存正文或密钥。
                    routes.properties 每行一个上游，例如：
                      route.deepseek.upstream=https://api.deepseek.com
                    将测试服务对应供应商的 base URL 改为 http://127.0.0.1:19090/deepseek，
                    保留原有 API 路径、模型和密钥，再启动采集器和服务。
                    测评运行器传入 --usage-log usage.jsonl；价格另用 --prices 指定。

                    要统计包含摘要的完整费用，所有主模型、摘要及回退供应商都须经过采集器，
                    测试服务关闭长期记忆且与测评配置一致，并独占服务运行；确认后传入
                    --isolated true --gateway-scope complete。否则仅展示观测用量，不声称完整费用。
                    结束后恢复测试服务的供应商 URL。
                    """);
            return;
        }
        Map<String, String> options = parseOptions(args);
        int port = Integer.parseInt(options.getOrDefault("listen-port", "19090"));
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("listen-port must be between 1 and 65535");
        }
        try {
            Gateway gateway = new Gateway(port, loadRoutes(Path.of(required(options, "routes"))),
                    Path.of(required(options, "output")));
            Runtime.getRuntime().addShutdownHook(new Thread(gateway::close, "evaluation-gateway-shutdown"));
            gateway.start();
            System.out.println("Evaluation usage gateway listening on 127.0.0.1:" + gateway.port()
                    + "; no request/response bodies or credentials are logged.");
        } catch (Exception error) {
            // Do not print exception messages: upstream URLs and bodies can contain secrets.
            System.err.println("Gateway startup failed: " + error.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            if (index + 1 >= args.length || !Set.of("--listen-port", "--routes", "--output").contains(args[index])
                    || options.put(args[index].substring(2), args[index + 1]) != null) {
                throw new IllegalArgumentException("Expected unique --listen-port, --routes and --output options");
            }
        }
        return options;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing --" + key);
        }
        return value;
    }

    static Map<String, URI> loadRoutes(Path path) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        Map<String, URI> routes = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.matches("route\\.[a-zA-Z][a-zA-Z0-9_-]{0,31}\\.upstream")) {
                throw new IllegalArgumentException("Unsupported route property");
            }
            String route = key.substring("route.".length(), key.length() - ".upstream".length());
            URI upstream;
            try {
                upstream = URI.create(properties.getProperty(key).trim());
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Invalid upstream URI");
            }
            validateUpstream(upstream);
            routes.put(route, upstream);
        }
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("At least one route is required");
        }
        return routes;
    }

    private static void validateUpstream(URI upstream) {
        String scheme = upstream.getScheme();
        String path = upstream.getRawPath();
        if (!("http".equals(scheme) || "https".equals(scheme)) || upstream.getHost() == null
                || upstream.getUserInfo() != null || upstream.getRawQuery() != null || upstream.getRawFragment() != null
                || path == null || !path.matches("(/[a-zA-Z0-9._~-]+)*/?")
                || List.of(path.split("/")).stream().anyMatch(part -> part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException("Upstream must be an http(s) origin or safe base path without credentials/query/fragment");
        }
    }

    static final class Gateway implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newFixedThreadPool(16);
        private final Map<String, URI> routes;
        private final BufferedWriter ledger;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        private volatile boolean closed;

        Gateway(int port, Map<String, URI> routes, Path output) throws IOException {
            for (Map.Entry<String, URI> entry : routes.entrySet()) {
                if (!entry.getKey().matches("[a-zA-Z][a-zA-Z0-9_-]{0,31}")) {
                    throw new IllegalArgumentException("Invalid route identifier");
                }
                validateUpstream(entry.getValue());
            }
            this.routes = Map.copyOf(routes);
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ledger = Files.newBufferedWriter(output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 32);
                server.createContext("/", this::handle);
                server.setExecutor(executor);
            } catch (IOException | RuntimeException error) {
                ledger.close();
                executor.shutdownNow();
                throw error;
            }
        }

        void start() {
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getRawPath();
            String[] pieces = path.split("/", 3);
            if (!exchange.getRequestMethod().equals("POST") || exchange.getRequestURI().getRawQuery() != null
                    || pieces.length != 3 || !routes.containsKey(pieces[1])
                    || !CHAT_ENDPOINTS.contains(pieces[2])) {
                reject(exchange, 404);
                return;
            }
            byte[] request;
            Map<String, Object> requestObject;
            try {
                request = readLimited(exchange.getRequestBody(), MAX_REQUEST_BYTES);
                requestObject = SimpleJson.object(SimpleJson.parse(new String(request, StandardCharsets.UTF_8)));
            } catch (BodyLimitException error) {
                reject(exchange, 413);
                return;
            } catch (IOException | RuntimeException error) {
                reject(exchange, 400);
                return;
            }
            String route = pieces[1];
            URI base = routes.get(route);
            String baseText = base.toASCIIString().replaceAll("/+$", "");
            URI upstream = URI.create(baseText + "/" + pieces[2]);
            Attempt attempt = new Attempt(route, safeModel(requestObject.get("model")),
                    Boolean.TRUE.equals(requestObject.get("stream")));
            boolean responseStarted = false;
            boolean recorded = false;
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(upstream).timeout(Duration.ofMinutes(5))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(request));
                Set<String> filtered = filteredHeaders(exchange.getRequestHeaders());
                exchange.getRequestHeaders().forEach((key, values) -> {
                    if (!filtered.contains(key.toLowerCase(Locale.ROOT))) {
                        for (String value : values) {
                            builder.header(key, value);
                        }
                    }
                });
                // The body, including stream_options, remains byte-for-byte unchanged.
                HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                attempt.httpStatus = response.statusCode();
                attempt.status = response.statusCode() >= 200 && response.statusCode() < 300 ? "success" : "http_error";
                String encoding = response.headers().firstValue("content-encoding").orElse("identity").trim().toLowerCase(Locale.ROOT);
                Set<String> responseFiltered = filteredHeaders(response.headers().map());
                if (encoding.equals("gzip") || encoding.equals("deflate")) {
                    responseFiltered.add("content-encoding");
                }
                response.headers().map().forEach((key, values) -> {
                    if (!responseFiltered.contains(key.toLowerCase(Locale.ROOT))) {
                        exchange.getResponseHeaders().put(key, values);
                    }
                });
                boolean sse = response.headers().firstValue("content-type").orElse("")
                        .toLowerCase(Locale.ROOT).contains("text/event-stream");
                try (InputStream rawBody = response.body(); InputStream body = switch (encoding) {
                    case "gzip" -> new GZIPInputStream(rawBody);
                    case "deflate" -> new InflaterInputStream(rawBody);
                    case "", "identity" -> rawBody;
                    default -> throw new IOException("UnsupportedContentEncoding");
                }) {
                    if (sse) {
                        exchange.sendResponseHeaders(response.statusCode(), 0);
                        responseStarted = true;
                        byte[] terminal = forwardSse(body, exchange.getResponseBody(), attempt);
                        record(attempt);
                        recorded = true;
                        if (terminal != null) {
                            exchange.getResponseBody().write(terminal);
                            exchange.getResponseBody().flush();
                        }
                    } else {
                        byte[] bodyBytes = readLimited(body, MAX_RESPONSE_BYTES);
                        attempt.acceptJson(bodyBytes);
                        // Log and flush before the client can complete this synchronous response.
                        record(attempt);
                        recorded = true;
                        exchange.sendResponseHeaders(response.statusCode(), bodyBytes.length == 0 ? -1 : bodyBytes.length);
                        responseStarted = true;
                        if (bodyBytes.length > 0) {
                            exchange.getResponseBody().write(bodyBytes);
                        }
                    }
                }
            } catch (Exception error) {
                attempt.status = "transport_error";
                attempt.errorClass = error.getClass().getSimpleName();
                if (!recorded) {
                    try {
                        record(attempt);
                        recorded = true;
                    } catch (IOException ledgerError) {
                        System.err.println("Gateway ledger write failed: " + ledgerError.getClass().getSimpleName());
                    }
                }
                if (!responseStarted) {
                    try {
                        reject(exchange, 502);
                    } catch (IOException ignored) {
                        // Downstream has disconnected; the attempt was still recorded.
                    }
                }
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                exchange.close();
            }
        }

        private byte[] forwardSse(InputStream input, OutputStream output, Attempt attempt) throws IOException {
            ByteArrayOutputStream event = new ByteArrayOutputStream();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            StringBuilder data = new StringBuilder();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                for (int index = 0; index < count; index++) {
                    byte value = buffer[index];
                    event.write(value);
                    if (event.size() > MAX_EVENT_BYTES) {
                        throw new BodyLimitException();
                    }
                    if (value == '\n') {
                        String text = line.toString(StandardCharsets.UTF_8);
                        if (text.endsWith("\r")) {
                            text = text.substring(0, text.length() - 1);
                        }
                        line.reset();
                        if (text.isEmpty()) {
                            byte[] eventBytes = event.toByteArray();
                            String eventData = data.toString();
                            event.reset();
                            data.setLength(0);
                            if (eventData.trim().equals("[DONE]")) {
                                // [DONE] finishes the API response; do not wait for the upstream to close
                                // its connection before committing usage and releasing the terminal event.
                                return eventBytes;
                            } else {
                                if (!eventData.isBlank()) {
                                    attempt.acceptJson(eventData.getBytes(StandardCharsets.UTF_8));
                                }
                                output.write(eventBytes);
                                output.flush();
                            }
                        } else if (text.startsWith("data:")) {
                            String valueText = text.substring(5);
                            if (valueText.startsWith(" ")) {
                                valueText = valueText.substring(1);
                            }
                            if (!data.isEmpty()) {
                                data.append('\n');
                            }
                            data.append(valueText);
                        }
                    } else {
                        line.write(value);
                    }
                }
            }
            // Forward non-terminated final bytes faithfully, parsing a final data line when possible.
            if (event.size() > 0) {
                String finalLine = line.toString(StandardCharsets.UTF_8);
                if (finalLine.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(finalLine.substring(5).stripLeading());
                }
                if (!data.isEmpty()) {
                    if (data.toString().trim().equals("[DONE]")) {
                        return event.toByteArray();
                    } else {
                        attempt.acceptJson(data.toString().getBytes(StandardCharsets.UTF_8));
                        output.write(event.toByteArray());
                        output.flush();
                    }
                } else {
                    output.write(event.toByteArray());
                    output.flush();
                }
            }
            if ("success".equals(attempt.status)) {
                attempt.status = "stream_incomplete";
                attempt.errorClass = "MissingStreamTerminator";
            }
            return null;
        }

        private synchronized void record(Attempt attempt) throws IOException {
            Instant finished = Instant.now();
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("schemaVersion", 1);
            record.put("id", attempt.id);
            record.put("route", attempt.route);
            record.put("model", attempt.model);
            record.put("stream", attempt.stream);
            record.put("status", attempt.status);
            record.put("httpStatus", attempt.httpStatus);
            record.put("startedAt", attempt.started.toString());
            record.put("finishedAt", finished.toString());
            record.put("durationMillis", (System.nanoTime() - attempt.startedNanos) / 1_000_000);
            record.put("inputTokens", attempt.input);
            record.put("cachedTokens", attempt.cached);
            record.put("uncachedInputTokens", attempt.miss);
            record.put("outputTokens", attempt.output);
            record.put("usageAvailable", attempt.input != null && attempt.output != null);
            record.put("cacheKnown", attempt.input != null && attempt.cached != null && attempt.miss != null);
            record.put("errorClass", attempt.errorClass);
            ledger.write(SimpleJson.stringify(record));
            ledger.newLine();
            ledger.flush();
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            server.stop(1);
            executor.shutdownNow();
            try {
                ledger.close();
            } catch (IOException ignored) {
                // No exception message: ledger paths may be sensitive.
            }
        }
    }

    private static final class Attempt {
        private final String id = UUID.randomUUID().toString();
        private final String route;
        private final String model;
        private final boolean stream;
        private final Instant started = Instant.now();
        private final long startedNanos = System.nanoTime();
        private String status = "transport_error";
        private Integer httpStatus;
        private Long input;
        private Long cached;
        private Long miss;
        private Long output;
        private String errorClass;

        private Attempt(String route, String model, boolean stream) {
            this.route = route;
            this.model = model;
            this.stream = stream;
        }

        private void acceptJson(byte[] json) {
            try {
                Map<String, Object> object = SimpleJson.object(SimpleJson.parse(new String(json, StandardCharsets.UTF_8)));
                if (object.get("error") != null && "success".equals(status)) {
                    // Compatible APIs may return errors inside an HTTP 200 JSON/SSE response.
                    // Record only the classification, never the potentially sensitive error payload.
                    status = "provider_error";
                }
                if (!(object.get("usage") instanceof Map<?, ?> usage)) {
                    return;
                }
                Long nextInput = token(usage.get("prompt_tokens"));
                Long nextOutput = token(usage.get("completion_tokens"));
                Long nextCached = token(usage.get("prompt_cache_hit_tokens"));
                Long nextMiss = token(usage.get("prompt_cache_miss_tokens"));
                if (nextCached == null && usage.get("prompt_tokens_details") instanceof Map<?, ?> details) {
                    nextCached = token(details.get("cached_tokens"));
                }
                if (nextInput == null && nextCached != null && nextMiss != null
                        && nextCached <= Long.MAX_VALUE - nextMiss) {
                    nextInput = nextCached + nextMiss;
                }
                if (nextInput != null && nextCached != null && nextMiss == null && nextCached <= nextInput) {
                    nextMiss = nextInput - nextCached;
                }
                if (nextInput != null && nextMiss != null && nextCached == null && nextMiss <= nextInput) {
                    nextCached = nextInput - nextMiss;
                }
                if (nextInput != null && ((nextCached != null && nextCached > nextInput)
                        || (nextMiss != null && nextMiss > nextInput)
                        || (nextCached != null && nextMiss != null && nextCached != nextInput - nextMiss))) {
                    nextCached = null;
                    nextMiss = null;
                }
                // A usage chunk is a cumulative snapshot, never sum chunks or fill missing values with zero.
                input = nextInput;
                output = nextOutput;
                cached = nextCached;
                miss = nextMiss;
            } catch (RuntimeException ignored) {
                // Preserve transparent proxy behavior for non-JSON and vendor-specific response events.
            }
        }
    }

    private static Long token(Object value) {
        if (!(value instanceof Number)) {
            return null;
        }
        try {
            long number = new BigDecimal(value.toString()).longValueExact();
            return number < 0 ? null : number;
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }

    private static String safeModel(Object value) {
        if (!(value instanceof String model) || !model.matches("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,159}")) {
            return null;
        }
        return model;
    }

    private static Set<String> filteredHeaders(Map<String, List<String>> headers) {
        Set<String> excluded = new HashSet<>(HOP_HEADERS);
        headers.forEach((key, values) -> {
            if (key.equalsIgnoreCase("Connection")) {
                for (String value : values) {
                    for (String named : value.split(",")) {
                        excluded.add(named.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        });
        return excluded;
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (output.size() > limit - count) {
                throw new BodyLimitException();
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void reject(HttpExchange exchange, int status) throws IOException {
        byte[] response = "{\"error\":\"evaluation_gateway_rejected\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        }
        exchange.close();
    }

    private static final class BodyLimitException extends IOException {
    }
}
