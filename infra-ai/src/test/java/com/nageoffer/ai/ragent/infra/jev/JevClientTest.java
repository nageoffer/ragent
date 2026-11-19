/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.infra.jev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jev 传输层契约
 * <p>
 * 用 JDK 自带 HttpServer 起真实端点：mockwebserver 4.x 与本项目的 okhttp 5.x 不同源
 */
class JevClientTest {

    private final AtomicReference<String> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("请求按 System One 协议发出：state 带问题、每个候选一条 noul、Bearer 鉴权")
    void sendsSystemOneProtocol() throws IOException {
        serve(200, "{\"model\":\"jev-1.14.0\",\"answers\":{"
                + "\"n0\":{\"type\":\"noul\",\"noul\":0.91},"
                + "\"n1\":{\"type\":\"noul\",\"noul\":0.04}},"
                + "\"usage\":{\"input_tokens\":312,\"output_tokens\":48}}");

        JevResponse response = client(2000, 5000).evaluate(new JevRequest(
                "AirPods Pro 左耳没声音想退",
                List.of(new JevNoulQuestion("n0", "用户问题属于类目「售后咨询 > 退换货政策」", "覆盖退换条件", "不是该类目"),
                        new JevNoulQuestion("n1", "用户问题属于类目「售前咨询 > 参数与配置」", "覆盖参数规格", "不是该类目"))
        ), target());

        assertEquals("/api/v1/systemone", lastPath.get(), "Jev 不走 chat/completions");
        assertEquals("Bearer test-key", lastAuthHeader.get());

        JsonObject request = JsonParser.parseString(lastRequest.get()).getAsJsonObject();
        assertEquals("typesafe/jev-1.14", request.get("model").getAsString(), "模型版本固定，不发 latest");
        assertEquals("AirPods Pro 左耳没声音想退", request.get("state").getAsString());

        JsonObject questions = request.getAsJsonObject("questions");
        assertEquals(2, questions.size(), "多条判断在同一次请求里提交");
        assertEquals("noul", questions.getAsJsonObject("n0").get("type").getAsString());
        assertEquals("覆盖退换条件",
                questions.getAsJsonObject("n0").getAsJsonObject("criteria").get("true").getAsString());
        assertEquals("不是该类目",
                questions.getAsJsonObject("n0").getAsJsonObject("criteria").get("false").getAsString());

        assertEquals(0.91D, response.nouls().get("n0"), 1e-9);
        assertEquals(0.04D, response.nouls().get("n1"), 1e-9);
        assertEquals(1, requestCount.get(), "首版不重试");
    }

    @Test
    @DisplayName("请求 key 全部有答时，响应里多出的未知 key 忽略")
    void ignoresUnknownKeysWhenAllRequestKeysAnswered() throws IOException {
        serve(200, "{\"answers\":{"
                + "\"n0\":{\"type\":\"noul\",\"noul\":0.8},"
                + "\"n1\":{\"type\":\"noul\",\"noul\":0.2},"
                + "\"n9\":{\"type\":\"noul\",\"noul\":0.99}}}");

        JevResponse response = client(2000, 5000).evaluate(new JevRequest(
                "问题", List.of(new JevNoulQuestion("n0", "命题0", null, null), new JevNoulQuestion("n1", "命题1", null, null))), target());

        assertEquals(Map.of("n0", 0.8D, "n1", 0.2D), response.nouls());
        assertNull(response.nouls().get("n9"), "未知 key 不进结果");
    }

    @Test
    @DisplayName("完整但全是低概率或 0 是正常结果，与协议失败区分开")
    void acceptsCompleteLowProbabilityResponse() throws IOException {
        serve(200, "{\"answers\":{"
                + "\"n0\":{\"type\":\"noul\",\"noul\":0.0},"
                + "\"n1\":{\"type\":\"noul\",\"noul\":0.03}}}");

        JevResponse response = client(2000, 5000).evaluate(new JevRequest(
                "问题", List.of(new JevNoulQuestion("n0", "命题0", null, null), new JevNoulQuestion("n1", "命题1", null, null))), target());

        assertEquals(Map.of("n0", 0.0D, "n1", 0.03D), response.nouls(),
                "每个请求 key 都有答案，低分是模型的判断不是故障");
    }

    @Test
    @DisplayName("缺任一请求 key 判为响应不完整：剩下的高分会照常收窄检索，漏召回补不回来")
    void rejectsMissingRequestKey() throws IOException {
        serve(200, "{\"answers\":{\"n0\":{\"type\":\"noul\",\"noul\":0.9}}}");

        ModelClientException e = assertThrows(ModelClientException.class, () -> client(2000, 5000)
                .evaluate(new JevRequest("问题", List.of(
                        new JevNoulQuestion("n0", "命题0", null, null), new JevNoulQuestion("n1", "命题1", null, null))), target()));

        assertEquals(ModelClientErrorType.INVALID_RESPONSE, e.getErrorType());
        assertTrue(e.getMessage().contains("n1"), "异常要点明缺的是哪个 key，实际消息: " + e.getMessage());
    }

    @Test
    @DisplayName("answers 为空对象但请求非空，同样判为响应不完整")
    void rejectsEmptyAnswersForNonEmptyRequest() throws IOException {
        serve(200, "{\"model\":\"jev-1.14.0\",\"answers\":{}}");

        ModelClientException e = assertThrows(ModelClientException.class, () -> client(2000, 5000)
                .evaluate(new JevRequest("问题", List.of(
                        new JevNoulQuestion("n0", "命题0", null, null), new JevNoulQuestion("n1", "命题1", null, null))), target()));

        assertEquals(ModelClientErrorType.INVALID_RESPONSE, e.getErrorType());
    }

    @Test
    @DisplayName("任一请求 key 的答案无效即判失败，不丢弃单条后照常返回")
    void rejectsIllegalProbability() throws IOException {
        // 越界、非 noul 形态、非数字各自单独成一轮：逐条确认它们都不再被静默丢弃
        for (String badAnswer : List.of(
                "{\"type\":\"noul\",\"noul\":1.7}",
                "{\"type\":\"noul\",\"noul\":-0.2}",
                "{\"type\":\"choice\",\"choice\":\"x\"}",
                "{\"type\":\"noul\",\"noul\":\"高\"}")) {
            serve(200, "{\"answers\":{"
                    + "\"n0\":{\"type\":\"noul\",\"noul\":0.55},"
                    + "\"n1\":" + badAnswer + "}}");

            ModelClientException e = assertThrows(ModelClientException.class, () -> client(2000, 5000)
                    .evaluate(new JevRequest("问题", List.of(
                            new JevNoulQuestion("n0", "a", null, null), new JevNoulQuestion("n1", "b", null, null))), target()),
                    "该答案应判为协议失败: " + badAnswer);
            assertEquals(ModelClientErrorType.INVALID_RESPONSE, e.getErrorType(), badAnswer);
            assertTrue(e.getMessage().contains("n1"), badAnswer + " -> " + e.getMessage());
        }
    }

    @Test
    @DisplayName("响应缺 answers 判为响应不完整")
    void rejectsResponseWithoutAnswers() throws IOException {
        serve(200, "{\"model\":\"jev-1.14.0\",\"usage\":{\"input_tokens\":1}}");

        ModelClientException e = assertThrows(ModelClientException.class, () -> client(2000, 5000)
                .evaluate(new JevRequest("问题", List.of(new JevNoulQuestion("n0", "a", null, null))), target()));
        assertEquals(ModelClientErrorType.INVALID_RESPONSE, e.getErrorType());
    }

    @Test
    @DisplayName("401/403 判为鉴权失败，429 判为限流，5xx 判为服务错误，均不重试")
    void mapsHttpStatusToErrorType() throws IOException {
        assertEquals(ModelClientErrorType.UNAUTHORIZED, callExpectingFailure(401).getErrorType());
        assertEquals(1, requestCount.get(), "鉴权失败不重试");
        assertEquals(ModelClientErrorType.UNAUTHORIZED, callExpectingFailure(403).getErrorType());
        assertEquals(ModelClientErrorType.RATE_LIMITED, callExpectingFailure(429).getErrorType());
        assertEquals(ModelClientErrorType.SERVER_ERROR, callExpectingFailure(503).getErrorType());
    }

    @Test
    @DisplayName("超过总预算按网络错误失败，且实际等待不超过预算")
    void failsWithinTotalBudget() throws IOException {
        serveSlow(1500);

        long startAt = System.currentTimeMillis();
        ModelClientException e = assertThrows(ModelClientException.class, () -> client(200, 400)
                .evaluate(new JevRequest("问题", List.of(new JevNoulQuestion("n0", "a", null, null))), target()));
        long elapsed = System.currentTimeMillis() - startAt;

        assertEquals(ModelClientErrorType.NETWORK_ERROR, e.getErrorType());
        assertTrue(elapsed < 1200, "总预算 400ms，实际等待 " + elapsed + "ms");
    }

    @Test
    @DisplayName("空判断列表不发请求")
    void rejectsEmptyQuestions() {
        assertThrows(IllegalArgumentException.class,
                () -> clientWith(properties(p -> {
                })).evaluate(new JevRequest("问题", List.of()), target()));
    }

    private ModelClientException callExpectingFailure(int status) throws IOException {
        requestCount.set(0);
        serve(status, "{\"error\":{\"code\":\"" + status + "\"}}");
        return assertThrows(ModelClientException.class, () -> client(2000, 5000)
                .evaluate(new JevRequest("问题", List.of(new JevNoulQuestion("n0", "a", null, null))), target()));
    }

    private JevTarget target() {
        return clientWith(properties(p -> {
        })).requireTarget();
    }

    /**
     * 生产的超时预算挂在 jevHttpClient bean 上，这里按用例需要自建一个同形客户端
     */
    private JevClient client(long connectTimeoutMs, long timeoutMs) {
        return new JevClient(properties(p -> {
        }), new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build());
    }

    private JevClient clientWith(AIModelProperties properties) {
        return new JevClient(properties, new OkHttpClient());
    }

    /**
     * 造一份可用配置，再按用例需要改坏其中一处
     */
    private AIModelProperties properties(java.util.function.Consumer<AIModelProperties> mutator) {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl(server == null
                ? "http://127.0.0.1:1"
                : "http://127.0.0.1:" + server.getAddress().getPort());
        provider.setApiKey("test-key");
        provider.setEndpoints(new java.util.HashMap<>(Map.of("jev", "/api/v1/systemone")));

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("jev-stable");
        candidate.setProvider("zenmux");
        candidate.setModel("typesafe/jev-1.14");

        AIModelProperties properties = new AIModelProperties();
        properties.setProviders(new java.util.HashMap<>(Map.of("zenmux", provider)));
        properties.getJev().setDefaultModel("jev-stable");
        properties.getJev().setCandidates(new java.util.ArrayList<>(List.of(candidate)));
        mutator.accept(properties);
        return properties;
    }

    private void serve(int status, String responseJson) throws IOException {
        startServer(exchange -> {
            requestCount.incrementAndGet();
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try (InputStream in = exchange.getRequestBody()) {
                lastRequest.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    private void serveSlow(long delayMs) throws IOException {
        startServer(exchange -> {
            requestCount.incrementAndGet();
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            byte[] body = "{\"answers\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    private void startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        if (server != null) {
            server.stop(0);
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/systemone", handler);
        server.start();
    }
}
