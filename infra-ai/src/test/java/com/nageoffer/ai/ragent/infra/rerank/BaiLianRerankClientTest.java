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

package com.nageoffer.ai.ragent.infra.rerank;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 精排客户端的出分契约
 * <p>
 * 候选全部带 {@code RRF_SCORE}，凡是没拿到精排分的条目若把它留在 score 上，用例必须变红
 * 用 JDK 自带 HttpServer 起真实端点：mockwebserver 4.x 与本项目的 okhttp 5.x 不同源
 */
class BaiLianRerankClientTest {

    /**
     * 名次派生值，不含相关度信息，混进精排分会让一个列表出现两把尺子
     */
    private static final float RRF_SCORE = 0.0476F;

    private final BaiLianRerankClient client = new BaiLianRerankClient(new OkHttpClient());
    private final AtomicReference<String> lastRequest = new AtomicReference<>();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("候选数没超 topN 也要发精排请求：早退会让证据闸门在库里没料时无分可读")
    void callsRerankEvenWhenCandidatesDoNotExceedTopN() throws IOException {
        serve("{\"output\":{\"results\":["
                + "{\"index\":0,\"relevance_score\":0.90},"
                + "{\"index\":1,\"relevance_score\":0.80},"
                + "{\"index\":2,\"relevance_score\":0.70}]}}");

        List<RetrievedChunk> result = client.rerank(
                "问题", List.of(chunk("a"), chunk("b"), chunk("c")), 10, target());

        assertNotNull(lastRequest.get(), "候选 3 条、topN 10，仍必须真的调用精排");
        JsonObject request = JsonParser.parseString(lastRequest.get()).getAsJsonObject();
        assertEquals(3, request.getAsJsonObject("parameters").get("top_n").getAsInt(),
                "top_n 需夹到候选数，否则要求的条数多于文档数");
        assertEquals(3, result.size());
        assertTrue(result.stream().allMatch(chunk -> chunk.getRerankScore() != null), "三条都该拿到精排分");
    }

    @Test
    @DisplayName("精排分同时写入 score 与 rerankScore：前者供下游排序，后者供闸门判定")
    void writesBothScoreFields() throws IOException {
        serve("{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.87}]}}");

        List<RetrievedChunk> result = client.rerank("问题", List.of(chunk("a")), 1, target());

        assertEquals(0.87F, result.get(0).getScore(), 1e-6F);
        assertEquals(0.87F, result.get(0).getRerankScore(), 1e-6F);
    }

    @Test
    @DisplayName("回填条目压到 0 沉底：留着 RRF 分会让名次派生值压过被判为弱相关的精排分")
    void backfilledChunksAreZeroed() throws IOException {
        serve("{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.75}]}}");

        List<RetrievedChunk> result = client.rerank(
                "问题", List.of(chunk("a"), chunk("b"), chunk("c")), 3, target());

        assertEquals(3, result.size(), "不足 topN 的部分由回填补齐");
        assertEquals("b", result.get(0).getId());
        assertEquals(0.75F, result.get(0).getRerankScore(), 1e-6F);

        for (RetrievedChunk backfilled : result.subList(1, 3)) {
            assertEquals(0F, backfilled.getScore(), 1e-6F, "回填条目必须沉底，不得留 RRF 分");
            assertNull(backfilled.getRerankScore(), "回填没经过精排，闸门要能认出来");
        }
    }

    @Test
    @DisplayName("响应缺 relevance_score 的条目按未出分处理，不拿 RRF 分冒充精排分")
    void itemsWithoutRelevanceScoreAreZeroed() throws IOException {
        serve("{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.66},{\"index\":1}]}}");

        List<RetrievedChunk> result = client.rerank("问题", List.of(chunk("a"), chunk("b")), 2, target());

        assertEquals(0.66F, result.get(0).getScore(), 1e-6F);
        assertEquals(0F, result.get(1).getScore(), 1e-6F);
        assertNull(result.get(1).getRerankScore());
    }

    @Test
    @DisplayName("响应重复 index 只保留一次，并用未命中的候选补足 topN")
    void duplicateIndexesDoNotDuplicateChunks() throws IOException {
        serve("{\"output\":{\"results\":["
                + "{\"index\":0,\"relevance_score\":0.90},"
                + "{\"index\":0,\"relevance_score\":0.80}]}}");

        List<RetrievedChunk> result = client.rerank(
                "问题", List.of(chunk("a"), chunk("b"), chunk("c")), 2, target());

        assertEquals(List.of("a", "b"), result.stream().map(RetrievedChunk::getId).toList());
        assertEquals(0.90F, result.get(0).getRerankScore(), 1e-6F);
        assertNull(result.get(1).getRerankScore());
    }

    @Test
    @DisplayName("topN <= 0 视为不限条数，原样返回且不调用远端")
    void skipsRemoteCallWhenTopNIsNotPositive() throws IOException {
        serve("{\"output\":{\"results\":[]}}");

        List<RetrievedChunk> result = client.rerank("问题", List.of(chunk("a"), chunk("b")), 0, target());

        assertEquals(2, result.size());
        assertNull(lastRequest.get(), "不限条数时没有可排的必要，不该产生远端调用");
    }

    private void serve(String responseJson) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                lastRequest.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    private ModelTarget target() {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        provider.setApiKey("test-key");
        provider.setEndpoints(Map.of("rerank", "/rerank"));

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setModel("qwen3-rerank");
        return new ModelTarget("qwen3-rerank", candidate, provider, null);
    }

    private static RetrievedChunk chunk(String id) {
        return RetrievedChunk.builder().id(id).text("正文-" + id).score(RRF_SCORE).build();
    }
}
