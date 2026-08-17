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

package com.nageoffer.ai.ragent.rag.core.retrieval.channel;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkRankingTest {

    @Test
    @DisplayName("补充路为空时出口仍然有序，不依赖调用方传入已排序的主路")
    void emptySupplementStillSortsPrimary() {
        // 「出口有序」是本类兑现的契约而非入参前置条件：后端返回乱序（如 PG relaxed_order）时也必须被兜住
        List<RetrievedChunk> merged = ChunkRanking.mergeByScore(
                List.of(chunk("a", 0.5F), chunk("b", 0.9F), chunk("c", 0.7F)),
                List.of());

        assertEquals(List.of("b", "c", "a"), merged.stream().map(RetrievedChunk::getId).toList());
    }

    @Test
    @DisplayName("两路合并后按分降序混排")
    void mergeSortsAcrossBothLanes() {
        List<RetrievedChunk> merged = ChunkRanking.mergeByScore(
                List.of(chunk("p1", 0.9F), chunk("p2", 0.3F)),
                List.of(chunk("s1", 0.6F)));

        assertEquals(List.of("p1", "s1", "p2"), merged.stream().map(RetrievedChunk::getId).toList());
    }

    @Test
    @DisplayName("NaN 分数沉底，不得凭不可比较的毒值抢占最高名次")
    void nanScoreSinksToBottom() {
        // Float.compare 把 NaN 当最大值，若不归一它会排第一并拿走最高 RRF 名次
        List<RetrievedChunk> merged = ChunkRanking.mergeByScore(
                List.of(chunk("nan", Float.NaN), chunk("low", 0.1F)),
                List.of(chunk("high", 0.9F)));

        assertEquals(List.of("high", "low", "nan"), merged.stream().map(RetrievedChunk::getId).toList());
    }

    @Test
    @DisplayName("正无穷分数沉底，异常值不参与排名竞争")
    void positiveInfinitySinksToBottom() {
        // 余弦 / BM25 / 倒数名次的合法分数均有限，+Infinity 只能来自上游缺陷，与 NaN 同等处置
        List<RetrievedChunk> merged = ChunkRanking.mergeByScore(
                List.of(chunk("inf", Float.POSITIVE_INFINITY), chunk("low", 0.1F)),
                List.of(chunk("high", 0.9F)));

        assertEquals(List.of("high", "low", "inf"), merged.stream().map(RetrievedChunk::getId).toList());
    }

    @Test
    @DisplayName("缺分沉底")
    void nullScoreSinksToBottom() {
        List<RetrievedChunk> merged = ChunkRanking.mergeByScore(
                List.of(chunk("none", null), chunk("scored", 0.2F)),
                List.of());

        assertEquals(List.of("scored", "none"), merged.stream().map(RetrievedChunk::getId).toList());
    }

    private static RetrievedChunk chunk(String id, Float score) {
        return RetrievedChunk.builder().id(id).text(id).score(score).build();
    }
}
