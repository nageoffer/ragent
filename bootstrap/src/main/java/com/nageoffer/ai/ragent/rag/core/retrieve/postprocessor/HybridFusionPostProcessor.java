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

package com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties.FusionMode;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.fusion.FusionStrategy;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.fusion.RRFFusionStrategy;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.fusion.WeightedSumFusionStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索融合后置处理器
 * <p>
 * 将向量检索通道(VECTOR_GLOBAL / INTENT_DIRECTED)和关键词检索通道(KEYWORD_ES)的结果
 * 按照配置的融合策略(RRF 或加权求和)合并重排。
 * <p>
 * 执行顺序位于去重之后、Rerank 之前，确保融合后的结果能进一步由 Rerank 精排。
 */
@Slf4j
@Component
public class HybridFusionPostProcessor implements SearchResultPostProcessor {

    private final SearchChannelProperties properties;

    public HybridFusionPostProcessor(SearchChannelProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "HybridFusion";
    }

    @Override
    public int getOrder() {
        return 5;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        // 只在混合融合启用 且 存在关键词检索结果时才执行
        if (!properties.getChannels().getHybrid().isEnabled()) {
            return false;
        }
        // 检查 results 中是否同时包含向量和关键词两类结果，在 process() 中判断
        return true;
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        // 按通道类型分组
        Map<String, List<RetrievedChunk>> vectorGroups = new LinkedHashMap<>();
        SearchChannelResult keywordResult = null;

        for (SearchChannelResult result : results) {
            if (result.getChunks().isEmpty()) continue;
            if (result.getChannelType() == SearchChannelType.KEYWORD_ES) {
                keywordResult = result;
            } else if (isVectorChannel(result.getChannelType())) {
                vectorGroups.put(result.getChannelName(), result.getChunks());
            }
        }

        // 没有关键词结果或没有向量结果时不需要融合，直接返回原始 chunks
        if (keywordResult == null || vectorGroups.isEmpty()) {
            log.debug("混合融合跳过：向量通道={}，关键词通道={}",
                    !vectorGroups.isEmpty(),
                    keywordResult != null);
            return chunks;
        }

        // 合并所有向量通道的结果为一路
        Map<String, List<RetrievedChunk>> inputs = new LinkedHashMap<>();
        inputs.put("vector", chunks.stream()
                .filter(c -> vectorGroups.values().stream().anyMatch(v -> v.contains(c)))
                .toList());
        inputs.put("keyword", keywordResult.getChunks());

        // 选择融合策略
        FusionStrategy strategy = createStrategy();

        // 权重配置
        Map<String, Float> weights = Map.of(
                "vector", properties.getChannels().getHybrid().getVectorWeight(),
                "keyword", 1.0f - properties.getChannels().getHybrid().getVectorWeight()
        );

        List<RetrievedChunk> fused = strategy.fuse(inputs, weights);
        log.info("混合融合完成：向量 {} 个 + 关键词 {} 个 → 融合后 {} 个",
                inputs.get("vector").size(),
                inputs.get("keyword").size(),
                fused.size());

        return fused;
    }

    private boolean isVectorChannel(SearchChannelType type) {
        return type == SearchChannelType.VECTOR_GLOBAL || type == SearchChannelType.INTENT_DIRECTED;
    }

    private FusionStrategy createStrategy() {
        FusionMode mode = properties.getChannels().getHybrid().getFusion();
        if (mode == FusionMode.WEIGHTED_SUM) {
            return new WeightedSumFusionStrategy();
        }
        return new RRFFusionStrategy();
    }
}
