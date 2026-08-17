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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.config.GraphProperties;
import com.nageoffer.ai.ragent.rag.config.KeywordProperties;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.RAGRateLimitProperties;
import com.nageoffer.ai.ragent.rag.config.RagStorageProperties;
import com.nageoffer.ai.ragent.rag.config.RagTraceProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.AISettings;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.BackendSettings;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.SearchSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 设置控制器，负责系统引擎、后端选型、检索管线与 AI 模型配置的只读查询
 */
@RestController
@RequiredArgsConstructor
public class RAGSettingsController {

    private final OrchestrationProperties orchestrationProperties;
    private final RagStorageProperties ragStorageProperties;
    private final KeywordProperties keywordProperties;
    private final GraphProperties graphProperties;
    private final RAGDefaultProperties ragDefaultProperties;
    private final RAGConfigProperties ragConfigProperties;
    private final RagTraceProperties ragTraceProperties;
    private final SearchChannelProperties searchChannelProperties;
    private final RAGRateLimitProperties ragRateLimitProperties;
    private final MemoryProperties memoryProperties;
    private final AIModelProperties aiModelProperties;

    @Value("${rag.vector.type:milvus}")
    private String vectorType;

    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private DataSize maxFileSize;

    @Value("${spring.servlet.multipart.max-request-size:100MB}")
    private DataSize maxRequestSize;

    /**
     * 获取系统引擎、后端选型、检索管线与 AI 模型等配置信息
     */
    @GetMapping("/rag/settings")
    public Result<SystemSettingsVO> settings() {
        SystemSettingsVO response = SystemSettingsVO.builder()
                .engine(SystemSettingsVO.EngineSettings.builder()
                        .type(orchestrationProperties.getMode().name().toLowerCase(Locale.ROOT))
                        .build())
                .backends(toBackendSettings())
                .rag(toRagSettings())
                .ai(toAISettings(aiModelProperties))
                .upload(SystemSettingsVO.UploadSettings.builder()
                        .maxFileSize(maxFileSize.toBytes())
                        .maxRequestSize(maxRequestSize.toBytes())
                        .build())
                .build();
        return Results.success(response);
    }

    private BackendSettings toBackendSettings() {
        return BackendSettings.builder()
                .storage(toStorageBackend(ragStorageProperties))
                .vector(BackendSettings.VectorBackend.builder()
                        .type(vectorType)
                        .build())
                .keyword(BackendSettings.KeywordBackend.builder()
                        .type(keywordProperties.getType())
                        .uris(keywordProperties.getEs().getUris())
                        .index(keywordProperties.getEs().getIndex())
                        .analyzer(keywordProperties.getEs().getAnalyzer())
                        .searchAnalyzer(keywordProperties.getEs().getSearchAnalyzer())
                        .build())
                .graph(BackendSettings.GraphBackend.builder()
                        .type(graphProperties.getType())
                        .baseUrl(graphProperties.getLightrag().getBaseUrl())
                        .queryMode(graphProperties.getLightrag().getQueryMode())
                        .embeddingModel(graphProperties.getEmbeddingModel())
                        .build())
                .build();
    }

    private BackendSettings.StorageBackend toStorageBackend(RagStorageProperties props) {
        boolean oss = "oss".equalsIgnoreCase(props.getType());
        return BackendSettings.StorageBackend.builder()
                .type(props.getType())
                .kbBucket(props.getKbBucket())
                .assetBucket(props.getAssetBucket())
                .endpoint(oss ? props.getOss().getEndpoint() : props.getS3().getEndpoint())
                .publicUrl(oss ? props.getOss().getPublicUrl() : props.getS3().resolvePublicUrl())
                .region(oss ? props.getOss().getRegion() : props.getS3().getRegion())
                .build();
    }

    private SystemSettingsVO.RagSettings toRagSettings() {
        return SystemSettingsVO.RagSettings.builder()
                .defaultConfig(SystemSettingsVO.DefaultSettings.builder()
                        .collectionName(ragDefaultProperties.getCollectionName())
                        .dimension(ragDefaultProperties.getDimension())
                        .metricType(ragDefaultProperties.getMetricType())
                        .sseTimeoutMs(ragDefaultProperties.getSseTimeoutMs())
                        .build())
                .features(SystemSettingsVO.FeatureSettings.builder()
                        .queryRewrite(ragConfigProperties.getQueryRewriteEnabled())
                        .rerank(ragConfigProperties.getRerankEnabled())
                        .citation(ragConfigProperties.getCitationEnabled())
                        .contextEnrich(ragConfigProperties.getContextEnrichEnabled())
                        .trace(ragTraceProperties.isEnabled())
                        .build())
                .search(toSearchSettings(searchChannelProperties))
                .rateLimit(SystemSettingsVO.RateLimitSettings.builder()
                        .global(SystemSettingsVO.GlobalRateLimit.builder()
                                .enabled(ragRateLimitProperties.getGlobalEnabled())
                                .maxConcurrent(ragRateLimitProperties.getGlobalMaxConcurrent())
                                .maxWaitSeconds(ragRateLimitProperties.getGlobalMaxWaitSeconds())
                                .leaseSeconds(ragRateLimitProperties.getGlobalLeaseSeconds())
                                .pollIntervalMs(ragRateLimitProperties.getGlobalPollIntervalMs())
                                .build())
                        .build())
                .memory(SystemSettingsVO.MemorySettings.builder()
                        .historyKeepTurns(memoryProperties.getHistoryKeepTurns())
                        .summaryEnabled(memoryProperties.getSummaryEnabled())
                        .summaryStartTurns(memoryProperties.getSummaryStartTurns())
                        .summaryMaxChars(memoryProperties.getSummaryMaxChars())
                        .titleMaxLength(memoryProperties.getTitleMaxLength())
                        .build())
                .build();
    }

    private SearchSettings toSearchSettings(SearchChannelProperties props) {
        SearchChannelProperties.Channels channels = props.getChannels();
        SearchChannelProperties.ChannelWeights weights = props.getFusion().getChannelWeights();
        return SearchSettings.builder()
                .defaultTopK(props.getDefaultTopK())
                .recallBudget(props.resolveRecallBudget(props.getDefaultTopK()))
                .scope(SearchSettings.ScopeSettings.builder()
                        .minIntentScore(props.getScope().getMinIntentScore())
                        .confidenceThreshold(props.getScope().getConfidenceThreshold())
                        .supplementRatio(props.getScope().getSupplementRatio())
                        .build())
                .channels(SearchSettings.ChannelSettings.builder()
                        .timeoutMs(channels.getTimeoutMs())
                        .vector(SearchSettings.Channel.builder()
                                .enabled(channels.getVector().isEnabled())
                                .weight(weights.getVector())
                                .build())
                        .keyword(SearchSettings.Channel.builder()
                                .enabled(channels.getKeyword().isEnabled())
                                .weight(weights.getKeyword())
                                .build())
                        .graph(SearchSettings.Channel.builder()
                                .enabled(channels.getGraph().isEnabled())
                                .weight(weights.getGraph())
                                .build())
                        .webSearch(SearchSettings.WebSearchChannel.builder()
                                .enabled(channels.getWebSearch().isEnabled())
                                .weight(weights.getWebSearch())
                                .count(channels.getWebSearch().getCount())
                                .timeoutSeconds(channels.getWebSearch().getTimeoutSeconds())
                                .apiKeyConfigured(StringUtils.hasText(channels.getWebSearch().getApiKey()))
                                .build())
                        .build())
                .fusion(SearchSettings.FusionSettings.builder()
                        .strategy(props.getFusion().getStrategy())
                        .rrfK(props.getFusion().getRrfK())
                        .rerankCandidateLimit(props.getFusion().getRerankCandidateLimit())
                        .build())
                .build();
    }

    private AISettings toAISettings(AIModelProperties props) {
        Map<String, AISettings.ProviderConfig> providers = new HashMap<>();
        if (props.getProviders() != null) {
            props.getProviders().forEach((k, v) -> providers.put(k, AISettings.ProviderConfig.builder()
                    .url(v.getUrl())
                    .apiKey(maskApiKey(v.getApiKey()))
                    .endpoints(v.getEndpoints())
                    .build()));
        }

        return AISettings.builder()
                .providers(providers)
                .chat(toModelGroup(props.getChat()))
                .embedding(toModelGroup(props.getEmbedding()))
                .rerank(toModelGroup(props.getRerank()))
                .vlm(toModelGroup(props.getVlm()))
                .selection(props.getSelection() == null
                        ? null
                        : AISettings.Selection.builder()
                          .failureThreshold(props.getSelection().getFailureThreshold())
                          .openDurationMs(props.getSelection().getOpenDurationMs())
                          .build())
                .stream(props.getStream() == null
                        ? null
                        : AISettings.Stream.builder()
                          .messageChunkSize(props.getStream().getMessageChunkSize())
                          .build())
                .build();
    }

    private AISettings.ModelGroup toModelGroup(AIModelProperties.ModelGroup group) {
        if (group == null) {
            return null;
        }
        return AISettings.ModelGroup.builder()
                .defaultModel(group.getDefaultModel())
                .candidates(group.getCandidates() == null
                        ? null
                        : group.getCandidates().stream()
                          .map(c -> AISettings.ModelCandidate.builder()
                                    .id(c.getId())
                                    .provider(c.getProvider())
                                    .model(c.getModel())
                                    .url(c.getUrl())
                                    .dimension(c.getDimension())
                                    .priority(c.getPriority())
                                    .enabled(c.getEnabled())
                                    .supportsThinking(c.getSupportsThinking())
                                    .build())
                          .collect(Collectors.toList()))
                .defaultTier(group.getDefaultTier())
                .deepThinkingTier(group.getDeepThinkingTier())
                .tiers(toTiers(group.getTiers()))
                .build();
    }

    private Map<String, AISettings.TierConfig> toTiers(Map<String, AIModelProperties.TierConfig> tiers) {
        if (tiers == null) {
            return null;
        }
        return tiers.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> AISettings.TierConfig.builder()
                                .candidates(e.getValue().getCandidates())
                                .timeoutMs(e.getValue().getTimeoutMs())
                                .build()));
    }

    private String maskApiKey(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return null;
        }
        String trimmed = apiKey.trim();
        if (trimmed.length() <= 10) {
            return "******";
        }
        return trimmed.substring(0, 6) + "***" + trimmed.substring(trimmed.length() - 4);
    }
}
