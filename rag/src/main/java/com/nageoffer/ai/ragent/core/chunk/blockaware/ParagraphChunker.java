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

package com.nageoffer.ai.ragent.core.chunk.blockaware;

import com.nageoffer.ai.ragent.core.chunk.model.ChunkDraft;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkMetadata;
import com.nageoffer.ai.ragent.core.chunk.text.TextSplitter;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 段落 chunker：优先整段保留，超出容忍上限才按块大小降级切分
 * <p>
 * 切分一律委托 {@link TextSplitter}，由它做边界回溯（换行 / 中文句末 / 英文句末）与文本归一化
 * （URL 断行修复、CJK 软换行合并），本类不自行按下标截断
 */
@Component
public class ParagraphChunker implements BlockChunker<ParagraphBlock> {

    @Override
    public Class<ParagraphBlock> blockType() {
        return ParagraphBlock.class;
    }

    @Override
    public List<ChunkDraft> chunk(ParagraphBlock block, ChunkContext ctx) {
        if (block == null) {
            return List.of();
        }
        int overlap = ctx.budget().overlapChars();
        // 先按容忍上限量一次，切不动说明整段撑得住；量出多片才退回块大小重切
        List<String> pieces = TextSplitter.split(block.text(), ctx.budget().toleranceChars(), overlap);
        if (pieces.size() > 1) {
            pieces = TextSplitter.split(block.text(), ctx.budget().maxChars(), overlap);
        }
        if (pieces.isEmpty()) {
            return List.of();
        }

        ChunkMetadata metadata = ChunkMetadata.builder()
                .outlinePath(ctx.outlinePath())
                .provenance(block.provenance())
                .build();

        List<ChunkDraft> result = new ArrayList<>(pieces.size());
        for (String piece : pieces) {
            result.add(ChunkDraft.of(piece, metadata));
        }
        return ChunkDraft.pieces(result);
    }
}
