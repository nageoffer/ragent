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

import java.util.ArrayList;
import java.util.List;

/**
 * 通道出口的名次整理
 * <p>
 * 「通道出口按相关性有序」是下游 RRF 按名次取分依赖的不变式，三条 KB 通道共用这一份实现，
 * 规则改动只落一处，不再有复制粘贴漏改一份的口子
 */
public final class ChunkRanking {

    private ChunkRanking() {
    }

    /**
     * 合并主路与补充路候选并按相关性降序
     * <p>
     * 「出口有序」是本方法兑现的契约而非入参前置条件——补充路为空时主路同样重排，
     * 后端返回乱序（如 PG relaxed_order）也被兜住。另不能主路在前补充路拼在后：两路分数同源，
     * 拼接序会让补充路的强命中恒排在主路弱命中之后，RRF 按名次取分，等于名额给了、排序又把它按回去
     */
    public static List<RetrievedChunk> mergeByScore(List<RetrievedChunk> primary, List<RetrievedChunk> supplement) {
        if (supplement.isEmpty()) {
            return sortedByScore(primary);
        }
        List<RetrievedChunk> merged = new ArrayList<>(primary.size() + supplement.size());
        merged.addAll(primary);
        merged.addAll(supplement);
        merged.sort(RetrievedChunk.BY_SCORE_DESC);
        return merged;
    }

    /**
     * 按相关性降序返回副本，入参不足两条时原样返回
     */
    public static List<RetrievedChunk> sortedByScore(List<RetrievedChunk> chunks) {
        if (chunks.size() < 2) {
            return chunks;
        }
        List<RetrievedChunk> sorted = new ArrayList<>(chunks);
        sorted.sort(RetrievedChunk.BY_SCORE_DESC);
        return sorted;
    }

    /**
     * 取一路候选的最高分，供阈值校准观测，空列表为 0
     */
    public static float topScoreOf(List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) {
            return 0F;
        }
        Float score = chunks.get(0).getScore();
        return score == null ? Float.NEGATIVE_INFINITY : score;
    }
}
