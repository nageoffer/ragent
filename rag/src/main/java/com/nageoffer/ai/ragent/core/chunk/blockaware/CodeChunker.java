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
import com.nageoffer.ai.ragent.core.parser.model.CodeBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码块 chunker：优先整块保留，超出容忍上限才按行边界降级切分，每块重复围栏
 * <p>
 * 半截行与缺失围栏会破坏渲染与理解，所以默认不切；但 txt 的缩进段落会被解析成代码块、
 * 真代码文件本身也可能远超预算，单块顶穿嵌入模型输入上限会被静默截断、尾部等于没入库
 */
@Component
public class CodeChunker implements BlockChunker<CodeBlock> {

    @Override
    public Class<CodeBlock> blockType() {
        return CodeBlock.class;
    }

    @Override
    public List<ChunkDraft> chunk(CodeBlock block, ChunkContext ctx) {
        if (block == null) {
            return List.of();
        }
        String language = block.language() == null ? "" : block.language();
        String code = block.code() == null ? "" : block.code();

        ChunkMetadata metadata = ChunkMetadata.builder()
                .outlinePath(ctx.outlinePath())
                .provenance(block.provenance())
                .build();

        List<String> segments = code.length() <= ctx.budget().toleranceChars()
                ? List.of(code)
                : splitByLines(code, ctx.budget().maxChars());

        List<ChunkDraft> result = new ArrayList<>(segments.size());
        for (String segment : segments) {
            String markdown = "```" + language + "\n" + segment + "\n```";
            result.add(ChunkDraft.of(markdown, segment, metadata));
        }
        return ChunkDraft.pieces(result);
    }

    /**
     * 按行累加切分：单行超预算时整行独立成块，绝不从行中间切断
     */
    private static List<String> splitByLines(String code, int maxChars) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : code.split("\n", -1)) {
            int addition = current.isEmpty() ? line.length() : current.length() + 1 + line.length();
            if (!current.isEmpty() && addition > maxChars) {
                segments.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
        }
        if (!current.isEmpty()) {
            segments.add(current.toString());
        }
        return segments.isEmpty() ? List.of(code) : segments;
    }
}
