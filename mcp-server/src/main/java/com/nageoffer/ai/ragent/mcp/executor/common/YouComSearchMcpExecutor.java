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

package com.nageoffer.ai.ragent.mcp.executor.common;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.mcp.config.McpToolAnnotations;
import com.nageoffer.ai.ragent.mcp.executor.McpToolException;
import com.nageoffer.ai.ragent.mcp.executor.McpToolResults;
import com.nageoffer.ai.ragent.mcp.executor.McpToolSchema;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;
import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.integer;

/**
 * You.com 联网搜索 MCP 工具
 * <p>
 * 基于 You.com Search API（GET <a href="https://ydc-index.io/v1/search">...</a>，X-API-Key 鉴权），
 * 返回带来源链接和摘录片段的网页与新闻结果；API Key 从环境变量 YDC_API_KEY 读取
 * <p>
 * 仅当环境变量 YDC_API_KEY 存在时才注册本工具（{@code @ConditionalOnProperty}）：工具清单是给 LLM
 * 消费的能力目录，登记一个缺 Key 不可用的工具只会诱导模型调用后失败、污染清单，故「工具存在 ⟺ 可用」，
 * 与 bootstrap 通道「无 Key 即不启用」对齐（Key 运行中失效的边界仍由 handleCall 内的校验兜底）
 * <p>
 * 说明：mcp-server 是零内部依赖、可独立部署的服务（不依赖 bootstrap / framework、与其不在同一 JVM，见各模块 pom），
 * 因此此处内置精简的 You.com HTTP 调用逻辑，与 bootstrap 的 {@code YouComWebSearchChannel} 属有意重复——
 * 抽公共模块会打破该隔离，故按「服务级重复」处理；修改 You.com 契约（端点 / 参数 / 响应结构）时两处需同步
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "YDC_API_KEY")
public class YouComSearchMcpExecutor {

    private static final String TOOL_ID = "youcom_search";

    /**
     * API Key 环境变量名（团队约定，勿改）
     */
    private static final String ENV_API_KEY = "YDC_API_KEY";

    private static final int DEFAULT_COUNT = 5;

    private static final int MAX_COUNT = 20;

    private static final List<String> FRESHNESS_VALUES = List.of("day", "week", "month", "year");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * You.com Search API 地址（可测试性：单元测试可指向本地 stub 服务）
     */
    String apiUrl = "https://ydc-index.io/v1/search";

    @Bean
    public McpServerFeatures.SyncToolSpecification youComSearchToolSpecification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        JsonSchema inputSchema = McpToolSchema.object()
                .required(string("query", "检索关键词或问题"))
                .optional(integer("count", "最多返回的结果条数（网页+新闻合计），默认 5，最大 20")
                        .defaultTo(5))
                .optional(string("freshness", "结果时效过滤：day(一天内)、week(一周内)、month(一月内)、year(一年内)，不传则不限")
                        .options(FRESHNESS_VALUES))
                .build();

        return Tool.builder()
                .name(TOOL_ID)
                .description("基于 You.com Search API 的联网搜索，返回带来源链接和摘录片段的网页与新闻结果。需要配置 YDC_API_KEY 环境变量")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.READ_ONLY)
                .build();
    }

    CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String query = MapUtil.getStr(args, "query");
            Integer count = MapUtil.getInt(args, "count");
            String freshness = MapUtil.getStr(args, "freshness");

            if (StrUtil.isBlank(query)) {
                return McpToolResults.error("请提供检索关键词 query");
            }
            if (count == null || count <= 0) count = DEFAULT_COUNT;
            if (count > MAX_COUNT) count = MAX_COUNT;
            if (StrUtil.isNotBlank(freshness) && !FRESHNESS_VALUES.contains(freshness)) {
                return McpToolResults.error("freshness 参数不合法，可选值：" + String.join("、", FRESHNESS_VALUES));
            }

            String apiKey = readEnv(ENV_API_KEY);
            if (StrUtil.isBlank(apiKey)) {
                return McpToolResults.error("You.com 联网搜索未配置：请先设置环境变量 YDC_API_KEY"
                        + "（可在 https://you.com/platform/api-keys 获取），配置后重启 MCP Server 即可使用");
            }

            String result = doSearch(query, count, freshness, apiKey);

            log.info("MCP 工具调用完成, toolId={}, query={}, count={}, elapsed={}ms",
                    TOOL_ID, query, count, System.currentTimeMillis() - startMs);
            return McpToolResults.success(result);
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("搜索", e);
        }
    }

    /**
     * 调用 You.com Search API 并格式化结果文本
     */
    private String doSearch(String query, int count, String freshness, String apiKey) throws Exception {
        StringBuilder url = new StringBuilder(apiUrl)
                .append("?query=").append(URLEncoder.encode(query, StandardCharsets.UTF_8))
                .append("&count=").append(count);
        if (StrUtil.isNotBlank(freshness)) {
            url.append("&freshness=").append(freshness);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(10))
                .header("X-API-Key", apiKey)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            // 不回显响应体，避免泄露账号信息；401 鉴权失败 / 429 限流 / 5xx 服务端异常
            throw new McpToolException("You.com API 返回异常状态码: " + response.statusCode());
        }

        return formatResults(objectMapper.readTree(response.body()), count);
    }

    /**
     * 把响应格式化为编号的 标题/链接/摘录 文本
     * <p>
     * 响应中 results.web 与 results.news 均可能缺失；
     * 每条结果中除 url/title/description/snippets 之外的字段均视为可选，防御式读取
     * <p>
     * You.com 的 count 是「每 section」语义（web、news 各最多 count 条），合并两段后统一截断到 count，
     * 使 count 对外表达「返回结果总条数上限」，与直觉一致，也避免多余结果占用 LLM token
     */
    private String formatResults(JsonNode root, int count) {
        JsonNode results = root.path("results");
        List<JsonNode> items = new ArrayList<>();
        collectItems(items, results.path("web"));
        collectItems(items, results.path("news"));

        if (items.isEmpty()) {
            return "未检索到相关结果，请尝试更换关键词。";
        }
        if (items.size() > count) {
            items = items.subList(0, count);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("检索完成，共 %d 条结果：\n\n", items.size()));
        int index = 1;
        for (JsonNode item : items) {
            String title = item.path("title").asText("(无标题)");
            String url = item.path("url").asText("");
            String excerpt = resolveExcerpt(item);

            sb.append(String.format("%d. %s\n", index++, title));
            if (!url.isBlank()) {
                sb.append("   链接: ").append(url).append('\n');
            }
            if (!excerpt.isBlank()) {
                sb.append("   摘录: ").append(excerpt).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 摘录优先取 description，缺失时回退第一条 snippet
     */
    private String resolveExcerpt(JsonNode item) {
        String description = item.path("description").asText("");
        if (!description.isBlank()) {
            return description;
        }
        JsonNode snippets = item.path("snippets");
        if (snippets.isArray() && !snippets.isEmpty()) {
            return snippets.get(0).asText("");
        }
        return "";
    }

    private void collectItems(List<JsonNode> items, JsonNode array) {
        if (array != null && array.isArray()) {
            array.forEach(items::add);
        }
    }

    /**
     * 读取环境变量（可测试性：单元测试可覆盖此方法屏蔽真实环境）
     */
    protected String readEnv(String name) {
        return System.getenv(name);
    }
}
