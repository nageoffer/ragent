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

package com.nageoffer.ai.ragent.rag.core.retrieval.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * You.com 联网检索通道单元测试（离线，使用 JDK 内置 HttpServer 作为 stub，不依赖真实 Key）
 */
@DisplayName("You.com 联网检索通道")
class WebSearchChannelTest {

    private static final String SAMPLE_BODY = """
            {
              "results": {
                "web": [
                  {
                    "url": "https://example.com/a",
                    "title": "网页结果A",
                    "description": "描述A",
                    "snippets": ["片段A1", "片段A2"]
                  },
                  {
                    "url": "https://example.com/b",
                    "title": "网页结果B",
                    "description": "描述B"
                  }
                ],
                "news": [
                  {
                    "url": "https://example.com/news",
                    "title": "新闻结果",
                    "description": "新闻描述"
                  }
                ]
              },
              "metadata": {"query": "test"}
            }
            """;

    private HttpServer server;
    private String stubUrl;

    /**
     * stub 收到的最近一次请求信息
     */
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastApiKey = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> lastQueryParams = new AtomicReference<>();

    /**
     * stub 的响应控制
     */
    private volatile int responseCode = 200;
    private volatile String responseBody = SAMPLE_BODY;
    private volatile long responseDelayMs = 0;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/search", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            lastQueryParams.set(parseQuery(exchange.getRequestURI().getRawQuery()));
            if (responseDelayMs > 0) {
                try {
                    Thread.sleep(responseDelayMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        stubUrl = "http://localhost:" + server.getAddress().getPort() + "/v1/search";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("请求构造：路径、X-API-Key 头、query 与 count 参数")
    void requestConstruction() {
        WebSearchChannel channel = newChannel(defaultProperties("test-key"));

        channel.search(context("什么是 RAG"));

        assertEquals("/v1/search", lastPath.get());
        assertEquals("test-key", lastApiKey.get());
        assertEquals("什么是 RAG", lastQueryParams.get().get("query"));
        assertEquals("5", lastQueryParams.get().get("count"));
    }

    @Test
    @DisplayName("响应映射：web 与 news 均映射为 Chunk，id 取 url，text 含标题/描述/片段/来源")
    void mapsWebAndNewsResults() {
        WebSearchChannel channel = newChannel(defaultProperties("test-key"));

        SearchChannelResult result = channel.search(context("test"));

        List<RetrievedChunk> chunks = result.getChunks();
        assertEquals(3, chunks.size());
        assertEquals(SearchChannelType.WEB_SEARCH, result.getChannelType());

        RetrievedChunk first = chunks.get(0);
        assertEquals("https://example.com/a", first.getId());
        assertTrue(first.getText().contains("网页结果A"));
        assertTrue(first.getText().contains("描述A"));
        assertTrue(first.getText().contains("片段A1"));
        assertTrue(first.getText().contains("来源: https://example.com/a"));

        // news 也在列表内
        assertEquals("https://example.com/news", chunks.get(2).getId());

        // 分数非空且按名次递减（无量纲，仅表达通道内相对顺序）
        for (int i = 0; i < chunks.size(); i++) {
            assertNotNull(chunks.get(i).getScore());
            if (i > 0) {
                assertTrue(chunks.get(i).getScore() < chunks.get(i - 1).getScore());
            }
        }
    }

    @Test
    @DisplayName("count 截断：合并 web+news 后按 count 截断为返回总条数上限")
    void countCapsTotalResults() {
        SearchChannelProperties properties = defaultProperties("test-key");
        properties.getChannels().getWebSearch().setCount(2);

        List<RetrievedChunk> chunks = newChannel(properties).search(context("test")).getChunks();

        // SAMPLE_BODY 共 3 条（2 web + 1 news），count=2 截断为 2 条，保留合并顺序中靠前的两条 web
        assertEquals(2, chunks.size());
        assertEquals("https://example.com/a", chunks.get(0).getId());
        assertEquals("https://example.com/b", chunks.get(1).getId());
    }

    @Test
    @DisplayName("响应映射：news 缺失、可选字段缺失均不报错")
    void mapsWhenNewsAbsentAndOptionalFieldsMissing() {
        responseBody = """
                {"results": {"web": [{"url": "https://example.com/only-url"}, {"title": "只有标题"}]}}
                """;
        WebSearchChannel channel = newChannel(defaultProperties("test-key"));

        List<RetrievedChunk> chunks = channel.search(context("test")).getChunks();

        assertEquals(2, chunks.size());
        assertEquals("https://example.com/only-url", chunks.get(0).getId());
        assertNull(chunks.get(1).getId());
        assertTrue(chunks.get(1).getText().contains("只有标题"));
    }

    @Test
    @DisplayName("count 参数：非法值回退默认 5，超过上限截断为 20")
    void countDefaultAndCap() {
        SearchChannelProperties properties = defaultProperties("test-key");
        properties.getChannels().getWebSearch().setCount(50);
        newChannel(properties).search(context("test"));
        assertEquals("20", lastQueryParams.get().get("count"));

        properties.getChannels().getWebSearch().setCount(0);
        newChannel(properties).search(context("test"));
        assertEquals("5", lastQueryParams.get().get("count"));
    }

    @Test
    @DisplayName("启用判定：开关与 Key 缺一不可，配置 Key 为空时回退环境变量")
    void isEnabledRequiresSwitchAndKey() {
        // 开关开 + 配置 Key -> 启用
        assertTrue(newChannel(defaultProperties("test-key")).isEnabled(context("q")));

        // 开关关 + 有 Key -> 不启用
        SearchChannelProperties disabled = defaultProperties("test-key");
        disabled.getChannels().getWebSearch().setEnabled(false);
        assertFalse(newChannel(disabled).isEnabled(context("q")));

        // 开关开 + 无配置 Key + 无环境变量 -> 不启用
        SearchChannelProperties noKey = defaultProperties("");
        assertFalse(newChannelWithEnv(noKey, null).isEnabled(context("q")));

        // 开关开 + 无配置 Key + 有环境变量 -> 启用（YDC_API_KEY 回退）
        assertTrue(newChannelWithEnv(noKey, "env-key").isEnabled(context("q")));
    }

    @Test
    @DisplayName("错误降级：401/429/500 返回空结果且不抛异常")
    void httpErrorsDegradeToEmpty() {
        for (int code : new int[]{401, 429, 500}) {
            responseCode = code;
            SearchChannelResult result = newChannel(defaultProperties("test-key")).search(context("test"));
            assertTrue(result.getChunks().isEmpty(), "HTTP " + code + " 应降级为空结果");
        }
    }

    @Test
    @DisplayName("错误降级：响应非 JSON 返回空结果且不抛异常")
    void malformedJsonDegradesToEmpty() {
        responseBody = "not-a-json{{{";
        SearchChannelResult result = newChannel(defaultProperties("test-key")).search(context("test"));
        assertTrue(result.getChunks().isEmpty());
    }

    @Test
    @DisplayName("错误降级：请求超时返回空结果且不抛异常")
    void timeoutDegradesToEmpty() {
        responseDelayMs = 3000;
        SearchChannelProperties properties = defaultProperties("test-key");
        properties.getChannels().getWebSearch().setTimeoutSeconds(1);

        SearchChannelResult result = newChannel(properties).search(context("test"));

        assertTrue(result.getChunks().isEmpty());
    }

    @Test
    @DisplayName("问题为空时跳过请求，返回空结果")
    void blankQuerySkips() {
        SearchChannelResult result = newChannel(defaultProperties("test-key")).search(context(" "));
        assertTrue(result.getChunks().isEmpty());
        assertNull(lastPath.get(), "空问题不应发起请求");
    }

    @Test
    @DisplayName("通道元信息：类型 WEB_SEARCH、名称正确")
    void channelWiring() {
        WebSearchChannel channel = newChannel(defaultProperties("test-key"));
        assertEquals(SearchChannelType.WEB_SEARCH, channel.getType());
        assertEquals("YouComWebSearch", channel.getName());
    }

    // ============== helpers ==============

    private SearchChannelProperties defaultProperties(String apiKey) {
        SearchChannelProperties properties = new SearchChannelProperties();
        SearchChannelProperties.WebSearch webSearch = properties.getChannels().getWebSearch();
        webSearch.setEnabled(true);
        webSearch.setApiKey(apiKey);
        webSearch.setApiUrl(stubUrl);
        webSearch.setTimeoutSeconds(5);
        return properties;
    }

    private WebSearchChannel newChannel(SearchChannelProperties properties) {
        return newChannelWithEnv(properties, null);
    }

    /**
     * 构造通道并固定环境变量读取结果，屏蔽真实运行环境，保证测试确定性
     */
    private WebSearchChannel newChannelWithEnv(SearchChannelProperties properties, String envValue) {
        return new WebSearchChannel(new OkHttpClient(), new ObjectMapper(), properties) {
            @Override
            protected String readEnv(String name) {
                return envValue;
            }
        };
    }

    private SearchContext context(String question) {
        return SearchContext.builder()
                .originalQuestion(question)
                .budget(RetrievalBudget.uniform(10))
                .build();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                params.put(URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }
}
