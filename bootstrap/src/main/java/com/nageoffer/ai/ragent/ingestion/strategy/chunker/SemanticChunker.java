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

package com.nageoffer.ai.ragent.ingestion.strategy.chunker;

import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentChunk;
import com.nageoffer.ai.ragent.ingestion.domain.enums.ChunkStrategy;
import com.nageoffer.ai.ragent.ingestion.domain.settings.ChunkerSettings;
import com.nageoffer.ai.ragent.rag.chunk.Chunk;
import com.nageoffer.ai.ragent.rag.chunk.StructureAwareSemanticChunkService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义分片策略实现类
 * 该类通过集成 {@link StructureAwareSemanticChunkService}，基于文档内容的语义结构进行智能分片
 * 适用于需要保持上下文语义连贯性的文档处理场景
 */
@Component
@RequiredArgsConstructor
public class SemanticChunker implements ChunkingStrategy {

    private final StructureAwareSemanticChunkService semanticChunkService;

    @Override
    public ChunkStrategy getStrategyType() {
        return ChunkStrategy.SEMANTIC;
    }

    @Override
    public List<DocumentChunk> chunk(String text, ChunkerSettings settings) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<Chunk> chunks = semanticChunkService.split(text);
        List<DocumentChunk> out = new ArrayList<>();
        int cursor = 0;
        for (Chunk chunk : chunks) {
            String content = chunk.getContent();
            if (!StringUtils.hasText(content)) {
                continue;
            }
            int start = text.indexOf(content, cursor);
            if (start < 0) {
                start = cursor;
            }
            int end = Math.min(text.length(), start + content.length());
            cursor = end;
            out.add(DocumentChunk.builder()
                    .chunkId(chunk.getChunkId())
                    .index(chunk.getIndex() == null ? out.size() : chunk.getIndex())
                    .content(content)
                    .startOffset(start)
                    .endOffset(end)
                    .build());
        }
        return out;
    }
}
