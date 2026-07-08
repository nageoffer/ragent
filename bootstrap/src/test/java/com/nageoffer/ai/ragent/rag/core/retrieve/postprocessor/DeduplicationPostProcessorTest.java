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
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 去重处理器回归测试
 * <p>
 * 覆盖两个历史缺陷：
 * 1. 无 id 的 Chunk 用 String.hashCode() 做去重键，哈希碰撞（如 "Aa" 与 "BB"）
 *    会把内容不同的 Chunk 误判为重复并静默丢弃
 * 2. score 为 null 时同键比较直接拆箱导致 NPE，去重处理器整体失效
 */
class DeduplicationPostProcessorTest {

    private RetrievedChunk chunk(String id, String text, Float score) {
        RetrievedChunk c = new RetrievedChunk();
        c.setId(id);
        c.setText(text);
        c.setScore(score);
        return c;
    }

    private List<SearchChannelResult> singleChannel(List<RetrievedChunk> chunks) {
        return List.of(SearchChannelResult.builder()
                .channelType(SearchChannelType.KEYWORD)
                .channelName("Keyword")
                .chunks(chunks)
                .latencyMs(1)
                .build());
    }

    @Test
    void hashCollidingTextsAreNotDeduplicated() {
        // "Aa" 与 "BB" 的 String.hashCode() 相同（均为 2112），但内容不同，不允许合并
        assertEquals("Aa".hashCode(), "BB".hashCode(), "前置条件：两段文本哈希碰撞");
        List<RetrievedChunk> chunks = new ArrayList<>();
        chunks.add(chunk(null, "Aa", 0.9f));
        chunks.add(chunk(null, "BB", 0.8f));

        List<RetrievedChunk> out = new DeduplicationPostProcessor()
                .process(chunks, singleChannel(chunks), SearchContext.builder().originalQuestion("q").topK(10).build());

        assertEquals(2, out.size(), "哈希碰撞的不同内容 Chunk 不允许被去重合并");
    }

    @Test
    void nullScoreDoesNotThrowOnTieBreak() {
        List<RetrievedChunk> chunks = new ArrayList<>();
        chunks.add(chunk("same-id", "文本A", null));
        chunks.add(chunk("same-id", "文本B", 0.5f));

        assertDoesNotThrow(() -> {
            List<RetrievedChunk> out = new DeduplicationPostProcessor()
                    .process(chunks, singleChannel(chunks), SearchContext.builder().originalQuestion("q").topK(10).build());
            assertEquals(1, out.size(), "同 id 应去重为 1 条");
            assertEquals(Float.valueOf(0.5f), out.get(0).getScore(), "应保留有分数的那条");
        });
    }
}
