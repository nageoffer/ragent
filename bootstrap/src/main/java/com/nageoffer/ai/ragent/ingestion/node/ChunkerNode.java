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

package com.nageoffer.ai.ragent.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentChunk;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.ChunkStrategy;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.domain.settings.ChunkerSettings;
import com.nageoffer.ai.ragent.ingestion.strategy.chunker.ChunkingStrategy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文本分块节点
 * 负责将输入的完整文本（原始文本或增强后的文本）按照指定的策略切分成多个较小的文本块（Chunk）
 */
@Component
public class ChunkerNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final Map<ChunkStrategy, ChunkingStrategy> strategies;

    public ChunkerNode(ObjectMapper objectMapper, List<ChunkingStrategy> strategies) {
        this.objectMapper = objectMapper;
        this.strategies = strategies.stream()
                .collect(Collectors.toMap(ChunkingStrategy::getStrategyType, Function.identity()));
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.CHUNKER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        String text = StringUtils.hasText(context.getEnhancedText()) ? context.getEnhancedText() : context.getRawText();
        if (!StringUtils.hasText(text)) {
            return NodeResult.fail(new ClientException("可分块文本为空"));
        }
        ChunkerSettings settings = parseSettings(config.getSettings());
        ChunkStrategy strategyType = settings.getStrategy() == null ? ChunkStrategy.FIXED_SIZE : settings.getStrategy();
        ChunkingStrategy strategy = strategies.get(strategyType);
        if (strategy == null) {
            return NodeResult.fail(new ClientException("未找到分块策略: " + strategyType));
        }
        List<DocumentChunk> chunks = strategy.chunk(text, settings);
        context.setChunks(chunks);
        return NodeResult.ok("已分块 " + (chunks == null ? 0 : chunks.size()) + " 段");
    }

    private ChunkerSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return ChunkerSettings.builder().strategy(ChunkStrategy.FIXED_SIZE).chunkSize(512).overlapSize(128).build();
        }
        ChunkerSettings settings = objectMapper.convertValue(node, ChunkerSettings.class);
        if (settings.getStrategy() == null) {
            settings.setStrategy(ChunkStrategy.FIXED_SIZE);
        }
        if (settings.getChunkSize() == null || settings.getChunkSize() <= 0) {
            settings.setChunkSize(512);
        }
        if (settings.getOverlapSize() == null || settings.getOverlapSize() < 0) {
            settings.setOverlapSize(128);
        }
        return settings;
    }
}
