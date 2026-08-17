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
import com.nageoffer.ai.ragent.core.parser.model.ListBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 列表 chunker：按渲染后的字符体量把列表项贪心分组，绝不从项中间切断
 * <p>
 * 度量取字符而非项数：项数是体量的坏代理，十几条词条的清单合起来两百来字，按项数切开后打包器又原样并回来，
 * 只在正文里留下一串多余空行
 */
@Component
public class ListChunker implements BlockChunker<ListBlock> {

    @Override
    public Class<ListBlock> blockType() {
        return ListBlock.class;
    }

    @Override
    public List<ChunkDraft> chunk(ListBlock block, ChunkContext ctx) {
        if (block == null || block.items() == null || block.items().isEmpty()) {
            return List.of();
        }
        List<String> items = block.items();
        ChunkMetadata metadata = ChunkMetadata.builder()
                .outlinePath(ctx.outlinePath())
                .provenance(block.provenance())
                .build();

        // 整份清单撑得住容忍上限就不切，切开后「要交哪些材料」这类问题只能召回半份
        int budget = Math.max(1, renderedLength(block) <= ctx.budget().toleranceChars()
                ? ctx.budget().toleranceChars()
                : ctx.budget().maxChars());
        List<ChunkDraft> result = new ArrayList<>();
        int start = 0;
        int cost = 0;
        for (int i = 0; i < items.size(); i++) {
            // 加一算项间换行；单项自身超预算时独立成块，硬切只会把词条腰斩
            int itemCost = renderItem(block, i + 1, items.get(i)).length() + 1;
            if (i > start && cost + itemCost > budget) {
                result.add(buildDraft(items.subList(start, i), start + 1, block, metadata));
                start = i;
                cost = 0;
            }
            cost += itemCost;
        }
        result.add(buildDraft(items.subList(start, items.size()), start + 1, block, metadata));
        return ChunkDraft.pieces(result);
    }

    /**
     * {@code startNumber} 仅对有序列表生效，作为本块的起始编号
     */
    private ChunkDraft buildDraft(List<String> items, int startNumber, ListBlock block,
                                 ChunkMetadata metadata) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(renderItem(block, startNumber + i, items.get(i)));
        }
        return ChunkDraft.of(sb.toString(), metadata);
    }

    /**
     * 整份清单渲染后的体量，含项间换行，用于判断切不切
     */
    private static int renderedLength(ListBlock block) {
        int total = 0;
        List<String> items = block.items();
        for (int i = 0; i < items.size(); i++) {
            total += renderItem(block, i + 1, items.get(i)).length() + 1;
        }
        return total;
    }

    /**
     * 单项渲染，同时用作预算切分的体量度量
     */
    private static String renderItem(ListBlock block, int number, String item) {
        return block.ordered() ? number + ". " + item : "- " + item;
    }
}
