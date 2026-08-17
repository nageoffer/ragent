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
import com.nageoffer.ai.ragent.core.chunk.ChunkingService;
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.core.ingest.embed.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.domain.settings.ChunkerSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分块节点：把 Block 列表按预算切块并向量化
 * <p>
 * 两处随内核化收窄的适配：分块参数从"策略枚举 + 一堆自由键"收敛成 {@link ChunkBudget}；
 * 向量化从 {@code embed(chunks, null)} 改为按上下文里的向量落点，因此不再与上传路径用不同的模型
 */
@Component
@RequiredArgsConstructor
public class ChunkerNode implements IngestionNode {

    /**
     * 不分块哨兵：沿用前端既有约定的 {@code -1}，在此翻译成整文档预算
     */
    private static final int WHOLE_DOCUMENT_SENTINEL = -1;

    private final ObjectMapper objectMapper;
    private final ChunkEmbeddingService chunkEmbeddingService;
    private final ChunkingService chunkingService;

    @Override
    public String getNodeType() {
        return IngestionNodeType.CHUNKER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        VectorTarget target = context.getVectorTarget();
        if (target == null) {
            return NodeResult.fail(new ClientException("分块节点缺少向量落点（分区 / 嵌入模型 / 维度）"));
        }

        List<Block> blocks = context.getDocument() == null ? null : context.getDocument().getBlocks();
        List<Chunk> chunks = chunkingService.chunk(blocks, toBudget(parseSettings(config.getSettings())));
        if (chunks.isEmpty()) {
            return NodeResult.fail(new ClientException("分块结果为空"));
        }

        List<EmbeddedChunk> embedded = chunkEmbeddingService.embed(chunks, target);
        context.setChunks(embedded);
        return NodeResult.ok("已分块 " + embedded.size() + " 段");
    }

    private ChunkerSettings parseSettings(JsonNode node) {
        ChunkerSettings settings = objectMapper.convertValue(node, ChunkerSettings.class);
        return settings == null ? ChunkerSettings.builder().build() : settings;
    }

    /**
     * 把管道设置里的三个整数翻译成预算；缺失或非法一律取系统默认，默认值只有一份
     */
    private ChunkBudget toBudget(ChunkerSettings settings) {
        Integer chunkSize = settings.getChunkSize();
        if (chunkSize != null && chunkSize == WHOLE_DOCUMENT_SENTINEL) {
            return ChunkBudget.wholeDocument();
        }
        ChunkBudget defaults = ChunkBudget.defaults();
        int maxChars = chunkSize != null && chunkSize > 0 ? chunkSize : defaults.maxChars();
        // 重叠缺省按块大小等比给，而不是照搬默认预算里那个配 1024 的数
        int overlap = settings.getOverlapSize() != null && settings.getOverlapSize() >= 0
                ? settings.getOverlapSize()
                : ChunkBudget.defaultOverlapFor(maxChars);
        // 重叠必须小于块大小，否则切分无法推进
        if (overlap >= maxChars) {
            overlap = Math.max(0, maxChars - 1);
        }
        int rowsPerChunk = settings.getRowsPerChunk() != null && settings.getRowsPerChunk() > 0
                ? settings.getRowsPerChunk()
                : defaults.rowsPerChunk();
        return new ChunkBudget(maxChars, overlap, rowsPerChunk);
    }
}
