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

package com.nageoffer.ai.ragent.mcp.executor;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * youcom_search MCP 工具单元测试（离线，使用 JDK 内置 HttpServer 作为 stub，不依赖真实 Key）
 */
@DisplayName("youcom_search MCP 工具")
class YouComSearchMcpExecutorTest {

    private static final String SAMPLE_BODY = """
            {
              "results": {
                "web": [
                  {
                    "url": "https://example.com/a",
                    "title": "网页结果A",
                    "description": "描述A",
                    "snippets": ["片段A1"]
                  },
                  {
                    "url": "https://example.com/b",
                    "title": "网页结果B",
                    "snippets": ["片段B1"]
                  }
                ],
                "news": [
                  {"url": "https://example.com/news", "title": "新闻结果", "description": "新闻描述"}
                ]
              }
            }
            """;

    private HttpServer server;
    private String stubUrl;

    private final AtomicReference<String> lastApiKey = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> lastQueryParams = new AtomicReference<>();

    private volatile int responseCode = 200;
    private volatile String responseBody = SAMPLE_BODY;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/search", exchange -> {
            lastApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            lastQueryParams.set(parseQuery(exchange.getRequestURI().getRawQuery()));
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
    @DisplayName("工具 Schema：名称、必填参数与可选参数定义正确")
    void toolSchema() {
        Tool tool = new YouComSearchMcpExecutor().youComSearchToolSpecification().tool();

        assertEquals("youcom_search", tool.name());
        assertTrue(tool.description().contains("You.com"));
        assertTrue(tool.description().contains("YDC_API_KEY"));
        assertEquals(java.util.List.of("query"), tool.inputSchema().required());
        assertTrue(tool.inputSchema().properties().containsKey("query"));
        assertTrue(tool.inputSchema().properties().containsKey("count"));
        assertTrue(tool.inputSchema().properties().containsKey("freshness"));
    }

    @Test
    @DisplayName("缺少 query 参数返回 errorResult")
    void missingQueryReturnsError() {
        CallToolResult result = executor("any-key").handleCall(request(Map.of()));

        assertTrue(result.isError());
        assertTrue(text(result).contains("query"));
    }

    @Test
    @DisplayName("缺少 YDC_API_KEY 返回带配置指引的 errorResult")
    void missingKeyReturnsFriendlyError() {
        CallToolResult result = executor(null).handleCall(request(Map.of("query", "test")));

        assertTrue(result.isError());
        assertTrue(text(result).contains("YDC_API_KEY"));
        assertTrue(text(result).contains("you.com/platform/api-keys"));
    }

    @Test
    @DisplayName("freshness 参数不合法返回 errorResult")
    void invalidFreshnessReturnsError() {
        CallToolResult result = executor("k").handleCall(
                request(Map.of("query", "test", "freshness", "hour")));

        assertTrue(result.isError());
        assertTrue(text(result).contains("freshness"));
    }

    @Test
    @DisplayName("成功检索：请求携带 X-API-Key，结果格式化为编号的标题/链接/摘录")
    void successMapping() {
        CallToolResult result = executor("test-key").handleCall(
                request(Map.of("query", "什么是 RAG", "count", 3, "freshness", "week")));

        assertFalse(result.isError());
        assertEquals("test-key", lastApiKey.get());
        assertEquals("什么是 RAG", lastQueryParams.get().get("query"));
        assertEquals("3", lastQueryParams.get().get("count"));
        assertEquals("week", lastQueryParams.get().get("freshness"));

        String text = text(result);
        assertTrue(text.contains("共 3 条结果"));
        assertTrue(text.contains("1. 网页结果A"));
        assertTrue(text.contains("链接: https://example.com/a"));
        assertTrue(text.contains("摘录: 描述A"));
        // description 缺失时回退第一条 snippet
        assertTrue(text.contains("摘录: 片段B1"));
        // news 结果也在列表内
        assertTrue(text.contains("3. 新闻结果"));
    }

    @Test
    @DisplayName("count 参数：超过上限截断为 20，非法值回退默认 5")
    void countDefaultAndCap() {
        executor("k").handleCall(request(Map.of("query", "t", "count", 50)));
        assertEquals("20", lastQueryParams.get().get("count"));

        executor("k").handleCall(request(Map.of("query", "t", "count", -1)));
        assertEquals("5", lastQueryParams.get().get("count"));
    }

    @Test
    @DisplayName("API 返回非 200 时返回 errorResult 且不抛异常")
    void httpErrorReturnsErrorResult() {
        responseCode = 500;
        CallToolResult result = executor("k").handleCall(request(Map.of("query", "t")));

        assertTrue(result.isError());
        assertTrue(text(result).contains("500"));
    }

    @Test
    @DisplayName("空结果集返回友好提示文本")
    void emptyResults() {
        responseBody = "{\"results\": {}}";
        CallToolResult result = executor("k").handleCall(request(Map.of("query", "t")));

        assertFalse(result.isError());
        assertTrue(text(result).contains("未检索到相关结果"));
    }

    // ============== helpers ==============

    /**
     * 构造 executor：指向本地 stub，并固定环境变量读取结果，屏蔽真实运行环境
     */
    private YouComSearchMcpExecutor executor(String envKey) {
        YouComSearchMcpExecutor executor = new YouComSearchMcpExecutor() {
            @Override
            protected String readEnv(String name) {
                return envKey;
            }
        };
        executor.apiUrl = stubUrl;
        return executor;
    }

    private CallToolRequest request(Map<String, Object> args) {
        return new CallToolRequest("youcom_search", args);
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
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
