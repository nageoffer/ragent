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

import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 块级贪心打包回归测试
 * <p>
 * 覆盖两个历史缺陷：
 * 1. 冲刷后重叠预算未扣除分隔符长度，合并块可超出 maxChars 上限
 * 2. merge() 丢弃 embeddingText / sectionContext，图片块特意构造的
 *    「无 URL 噪声」向量化文本在合并后退化为携带原始 URL 的 content
 */
class ChunkPackerTest {

    private VectorChunk chunk(String content, String blockType, String embeddingText) {
        return VectorChunk.builder()
                .chunkId(content)
                .content(content)
                .blockType(blockType)
                .embeddingText(embeddingText)
                .build();
    }

    @Test
    void packedChunkNeverExceedsMaxChars() {
        List<VectorChunk> packed = new ChunkPacker().pack(List.of(
                chunk("AAAAA", "PARAGRAPH", null),
                chunk("BBBBB", "PARAGRAPH", null)
        ), 10, 9);

        for (VectorChunk c : packed) {
            assertTrue(c.getContent().length() <= 10,
                    "合并块长度 " + c.getContent().length() + " 超出 maxChars=10: [" + c.getContent() + "]");
        }
    }

    @Test
    void mergePreservesEmbeddingText() {
        List<VectorChunk> packed = new ChunkPacker().pack(List.of(
                chunk("![](http://img.example.com/a.png)", "IMAGE", "一张架构图：展示检索链路各组件关系"),
                chunk("正文段落", "PARAGRAPH", null)
        ), 200, 20);

        boolean found = false;
        for (VectorChunk c : packed) {
            if (c.getEmbeddingText() != null && c.getEmbeddingText().contains("架构图")) {
                found = true;
                assertTrue(!c.getEmbeddingText().contains("http://img.example.com"),
                        "embeddingText 不应退化为携带 URL 噪声的原文");
            }
        }
        assertTrue(found, "合并后 embeddingText 不允许被丢弃");
        assertNotNull(packed.get(0).getContent());
    }
}
