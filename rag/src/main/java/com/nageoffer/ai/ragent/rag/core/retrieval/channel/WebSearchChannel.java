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

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * You.com 联网检索通道
 * <p>
 * 基于 You.com Search API 的实时网络召回，与本地知识库通道互补：
 * 擅长时效性问题、公开资讯等本地知识库覆盖不到的内容
 * <p>
 * 启用条件（缺一不可）：
 * - rag.search.channels.web-search.enabled = true
 * - 可解析到 API Key（优先取配置 api-key，为空回退环境变量 YDC_API_KEY）
 * <p>
 * 作为可选的外部通道与本地通道并行执行，结果统一进 RRF 融合，通道间无先后与优先级之分；
 * 任何失败（网络异常、非 2xx、响应格式异常、超时）只记录 warn 日志并返回空结果，
 * 绝不让联网检索故障影响本地检索链路
 * <p>
 * 注：mcp-server 的 {@code YouComSearchMcpExecutor} 内含一份并行的 You.com 调用实现，与此处属有意重复——
 * mcp-server 是零内部依赖、可独立部署的服务（与本模块不在同一 JVM、面向不同消费者），抽公共模块会打破其隔离，
 * 故按「服务级重复」处理；修改 You.com 契约（端点 / 参数 / 响应结构）时两处需同步
 */
@Slf4j
@Component
public class WebSearchChannel implements SearchChannel {

    /**
     * API Key 环境变量名（团队约定，勿改）
     */
    private static final String ENV_API_KEY = "YDC_API_KEY";

    /**
     * 单次检索返回结果数量上限
     */
    private static final int MAX_COUNT = 20;

    /**
     * 结果数量默认值（配置非法时兜底）
     */
    private static final int DEFAULT_COUNT = 5;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SearchChannelProperties properties;

    public WebSearchChannel(@Qualifier("syncHttpClient") OkHttpClient httpClient,
                                  ObjectMapper objectMapper,
                                  SearchChannelProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "YouComWebSearch";
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.WEB_SEARCH;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        SearchChannelProperties.WebSearch config = properties.getChannels().getWebSearch();
        return config.isEnabled() && StrUtil.isNotBlank(resolveApiKey());
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        try {
            String query = context.getMainQuestion();
            if (StrUtil.isBlank(query)) {
                log.info("You.com 联网检索问题为空，跳过");
                return emptyResult(System.currentTimeMillis() - startTime);
            }

            SearchChannelProperties.WebSearch config = properties.getChannels().getWebSearch();
            HttpUrl httpUrl = HttpUrl.parse(config.getApiUrl());
            if (httpUrl == null) {
                log.warn("You.com 联网检索 api-url 配置非法：{}，返回空结果", config.getApiUrl());
                return emptyResult(System.currentTimeMillis() - startTime);
            }

            Request request = new Request.Builder()
                    .url(httpUrl.newBuilder()
                            .addQueryParameter("query", query)
                            .addQueryParameter("count", String.valueOf(resolveCount(config)))
                            .build())
                    .header("X-API-Key", resolveApiKey())
                    .get()
                    .build();

            // 按通道配置收紧超时；newBuilder 复用连接池与线程池，代价可忽略
            int timeoutSeconds = Math.max(1, config.getTimeoutSeconds());
            OkHttpClient client = httpClient.newBuilder()
                    .callTimeout(Duration.ofSeconds(timeoutSeconds))
                    .readTimeout(Duration.ofSeconds(timeoutSeconds))
                    .build();

            List<RetrievedChunk> chunks;
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    // 401 鉴权失败 / 429 限流 / 5xx 服务端异常等统一降级为空结果（不打印 Key）
                    log.warn("You.com 联网检索请求失败, code={}, 返回空结果", response.code());
                    return emptyResult(System.currentTimeMillis() - startTime);
                }
                String body = response.body() != null ? response.body().string() : "";
                chunks = parseChunks(body, resolveCount(config));
            }

            long latency = System.currentTimeMillis() - startTime;
            log.info("You.com 联网检索完成，检索到 {} 个 Chunk，耗时 {}ms", chunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.WEB_SEARCH)
                    .channelName(getName())
                    .chunks(chunks)
                    .latencyMs(latency)
                    .build();
        } catch (Exception e) {
            // 联网检索属于补充通道，任何异常（含超时、响应解析失败）都不允许向上抛出
            log.warn("You.com 联网检索失败，降级为空结果: {}", e.getMessage());
            return emptyResult(System.currentTimeMillis() - startTime);
        }
    }

    /**
     * 解析 You.com 响应为 RetrievedChunk 列表
     * <p>
     * 响应结构 {@code {"results": {"web": [...], "news": [...]}}}；
     * news 可能缺失，每条结果中除 url/title/description/snippets 之外的字段均视为可选，防御式读取
     * <p>
     * You.com 的 count 是「每 section」语义（web、news 各最多 count 条），这里合并两段后统一
     * 截断到 maxResults，使 count 对外表达「返回结果总条数上限」，与直觉一致
     */
    private List<RetrievedChunk> parseChunks(String body, int maxResults) throws Exception {
        JsonNode results = objectMapper.readTree(body).path("results");

        List<JsonNode> items = new ArrayList<>();
        collectItems(items, results.path("web"));
        collectItems(items, results.path("news"));

        // 初始分数为按名次递减的中性分数 1/(rank+1)：无量纲，仅表达通道内相对顺序，不与向量余弦 / BM25
        // 分数做量纲比较。多通道时 FusionPostProcessor(RRF) 会按各通道名次重算并覆盖此分；单通道且关闭
        // Rerank 时它保留本通道召回顺序；开启 Rerank 时最终由精排模型重新打分。去重处理器对 null 分数
        // 已空值安全，此分数非为其而设
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (JsonNode item : items) {
            RetrievedChunk chunk = toChunk(item, chunks.size());
            if (chunk != null) {
                chunks.add(chunk);
            }
        }
        return chunks.size() > maxResults ? new ArrayList<>(chunks.subList(0, maxResults)) : chunks;
    }

    /**
     * 单条结果映射
     * <p>
     * 把标题、描述、摘录、来源链接编排进 text，保证下游拼接 Prompt 时引用信息不丢失；
     * id 取 url（联网结果的天然唯一键，供去重处理器使用）
     * <p>
     * {@link RetrievedChunk} 的 docId/chunkIndex/docName 供本地库 chunk 回表富化，联网结果无库记录，
     * 富化阶段按 id 查不到会跳过、这些字段保持 null，组装上下文时作为「无标题文档块」渲染，不影响出证据
     */
    private RetrievedChunk toChunk(JsonNode item, int rank) {
        String url = item.path("url").asText("");
        String title = item.path("title").asText("");
        String description = item.path("description").asText("");

        StringBuilder text = new StringBuilder();
        if (StrUtil.isNotBlank(title)) {
            text.append("【").append(title).append("】\n");
        }
        if (StrUtil.isNotBlank(description)) {
            text.append(description).append('\n');
        }
        JsonNode snippets = item.path("snippets");
        if (snippets.isArray()) {
            for (JsonNode snippet : snippets) {
                String s = snippet.asText("");
                if (StrUtil.isNotBlank(s)) {
                    text.append(s).append('\n');
                }
            }
        }
        if (StrUtil.isNotBlank(url)) {
            text.append("来源: ").append(url);
        }

        String content = text.toString().trim();
        if (StrUtil.isBlank(content)) {
            return null;
        }
        return RetrievedChunk.builder()
                .id(StrUtil.isNotBlank(url) ? url : null)
                .text(content)
                .score(1.0f / (rank + 1))
                .build();
    }

    private void collectItems(List<JsonNode> items, JsonNode array) {
        if (array != null && array.isArray()) {
            array.forEach(items::add);
        }
    }

    /**
     * 解析结果数量：配置非法时回退默认值，超过上限截断
     */
    private int resolveCount(SearchChannelProperties.WebSearch config) {
        int count = config.getCount() > 0 ? config.getCount() : DEFAULT_COUNT;
        return Math.min(count, MAX_COUNT);
    }

    /**
     * 解析 API Key：优先取配置 api-key，为空回退环境变量 YDC_API_KEY
     */
    private String resolveApiKey() {
        String apiKey = properties.getChannels().getWebSearch().getApiKey();
        return StrUtil.isNotBlank(apiKey) ? apiKey : readEnv(ENV_API_KEY);
    }

    /**
     * 读取环境变量（可测试性：单元测试可覆盖此方法屏蔽真实环境）
     */
    protected String readEnv(String name) {
        return System.getenv(name);
    }

}
