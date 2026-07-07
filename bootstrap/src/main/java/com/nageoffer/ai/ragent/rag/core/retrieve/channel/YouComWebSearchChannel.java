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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel;

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
 * 优先级排在所有本地通道（意图定向 1 / 关键词 5 / 向量全局 10）之后；
 * 任何失败（网络异常、非 2xx、响应格式异常、超时）只记录 warn 日志并返回空结果，
 * 绝不让联网检索故障影响本地检索链路
 */
@Slf4j
@Component
public class YouComWebSearchChannel implements SearchChannel {

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

    public YouComWebSearchChannel(@Qualifier("syncHttpClient") OkHttpClient httpClient,
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
    public int getPriority() {
        return 20;  // 联网检索排在所有本地通道之后
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
                return emptyResult(startTime);
            }

            SearchChannelProperties.WebSearch config = properties.getChannels().getWebSearch();
            HttpUrl httpUrl = HttpUrl.parse(config.getApiUrl());
            if (httpUrl == null) {
                log.warn("You.com 联网检索 api-url 配置非法：{}，返回空结果", config.getApiUrl());
                return emptyResult(startTime);
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
                    return emptyResult(startTime);
                }
                String body = response.body() != null ? response.body().string() : "";
                chunks = parseChunks(body);
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
            return emptyResult(startTime);
        }
    }

    /**
     * 解析 You.com 响应为 RetrievedChunk 列表
     * <p>
     * 响应结构 {@code {"results": {"web": [...], "news": [...]}}}；
     * news 可能缺失，每条结果中除 url/title/description/snippets 之外的字段均视为可选，防御式读取
     */
    private List<RetrievedChunk> parseChunks(String body) throws Exception {
        JsonNode results = objectMapper.readTree(body).path("results");

        List<JsonNode> items = new ArrayList<>();
        collectItems(items, results.path("web"));
        collectItems(items, results.path("news"));

        // 初始分数决策：多通道场景下 FusionPostProcessor(RRF) 只按各通道内名次融合并覆盖分数，
        // 去重处理器会对分数做数值比较（不能为 null），因此这里给出按名次递减的中性分数 1/(rank+1)，
        // 仅表达通道内相对顺序，不与向量/BM25 分数做量纲比较；开启 Rerank 时最终由精排模型重新打分
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (JsonNode item : items) {
            RetrievedChunk chunk = toChunk(item, chunks.size());
            if (chunk != null) {
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    /**
     * 单条结果映射
     * <p>
     * {@link RetrievedChunk} 仅有 id/text/score 三个字段（无 metadata 扩展位），
     * 因此把标题与来源链接直接编排进 text，保证下游拼接 Prompt 时引用信息不丢失；
     * id 取 url（联网结果的天然唯一键，供去重处理器使用）
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

    private SearchChannelResult emptyResult(long startTime) {
        return SearchChannelResult.builder()
                .channelType(SearchChannelType.WEB_SEARCH)
                .channelName(getName())
                .chunks(List.of())
                .latencyMs(System.currentTimeMillis() - startTime)
                .build();
    }
}
