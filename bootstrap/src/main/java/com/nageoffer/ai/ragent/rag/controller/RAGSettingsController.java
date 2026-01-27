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
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.RAGRateLimitProperties;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.AISettings;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.DefaultSettings;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.MemorySettings;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 设置控制器，负责系统 RAG、AI 模型等配置信息的查询
 */
@RestController
@RequiredArgsConstructor
public class RAGSettingsController {

    private final RAGDefaultProperties ragDefaultProperties;
    private final RAGConfigProperties ragConfigProperties;
    private final RAGRateLimitProperties ragRateLimitProperties;
    private final MemoryProperties memoryProperties;
    private final AIModelProperties aiModelProperties;

    /**
     * 获取系统 RAG、AI 模型等配置信息
     */
    @GetMapping("/rag/settings")
    public Result<SystemSettingsVO> settings() {
        SystemSettingsVO response = SystemSettingsVO.builder()
                .rag(SystemSettingsVO.RagSettings.builder()
                        .defaultConfig(toDefaultSettings(ragDefaultProperties))
                        .queryRewrite(SystemSettingsVO.QueryRewriteSettings.builder()
                                .enabled(ragConfigProperties.getQueryRewriteEnabled())
                                .maxHistoryMessages(ragConfigProperties.getQueryRewriteMaxHistoryMessages())
                                .maxHistoryChars(ragConfigProperties.getQueryRewriteMaxHistoryChars())
                                .build())
                        .rateLimit(SystemSettingsVO.RateLimitSettings.builder()
                                .global(SystemSettingsVO.GlobalRateLimit.builder()
                                        .enabled(ragRateLimitProperties.getGlobalEnabled())
                                        .maxConcurrent(ragRateLimitProperties.getGlobalMaxConcurrent())
                                        .maxWaitSeconds(ragRateLimitProperties.getGlobalMaxWaitSeconds())
                                        .leaseSeconds(ragRateLimitProperties.getGlobalLeaseSeconds())
                                        .pollIntervalMs(ragRateLimitProperties.getGlobalPollIntervalMs())
                                        .build())
                                .build())
                        .memory(toMemorySettings(memoryProperties))
                        .build())
                .ai(toAISettings(aiModelProperties))
                .build();
        return Results.success(response);
    }

    private DefaultSettings toDefaultSettings(RAGDefaultProperties props) {
        return DefaultSettings.builder()
                .collectionName(props.getCollectionName())
                .dimension(props.getDimension())
                .metricType(props.getMetricType())
                .build();
    }

    private MemorySettings toMemorySettings(MemoryProperties props) {
        return MemorySettings.builder()
                .historyKeepTurns(props.getHistoryKeepTurns())
                .ttlMinutes(props.getTtlMinutes())
                .summaryEnabled(props.getSummaryEnabled())
                .summaryStartTurns(props.getSummaryStartTurns())
                .summaryMaxChars(props.getSummaryMaxChars())
                .titleMaxLength(props.getTitleMaxLength())
                .build();
    }

    private AISettings toAISettings(AIModelProperties props) {
        Map<String, AISettings.ProviderConfig> providers = new HashMap<>();
        if (props.getProviders() != null) {
            props.getProviders().forEach((k, v) -> providers.put(k, AISettings.ProviderConfig.builder()
                    .url(v.getUrl())
                    .apiKey(v.getApiKey())
                    .endpoints(v.getEndpoints())
                    .build()));
        }

        return AISettings.builder()
                .providers(providers)
                .chat(toModelGroup(props.getChat()))
                .embedding(toModelGroup(props.getEmbedding()))
                .rerank(toModelGroup(props.getRerank()))
                .selection(props.getSelection() == null ? null : AISettings.Selection.builder()
                        .failureThreshold(props.getSelection().getFailureThreshold())
                        .openDurationMs(props.getSelection().getOpenDurationMs())
                        .build())
                .stream(props.getStream() == null ? null : AISettings.Stream.builder()
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
                .deepThinkingModel(group.getDeepThinkingModel())
                .candidates(group.getCandidates() == null ? null : group.getCandidates().stream()
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
                .build();
    }
}
