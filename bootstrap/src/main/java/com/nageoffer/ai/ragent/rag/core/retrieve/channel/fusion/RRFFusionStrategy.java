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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel.fusion;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF (Reciprocal Rank Fusion) 融合策略
 * RRF_score(d) = Σ 1/(k + rank_i(d))
 * 不需要分数归一化，仅依赖各通道内的排名。
 */
public class RRFFusionStrategy implements FusionStrategy {

    static final int K = 60;

    @Override
    public List<RetrievedChunk> fuse(Map<String, List<RetrievedChunk>> rankedChunks,
                                     Map<String, Float> weights) {
        // chunkKey → RRF score
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        // chunkKey → chunk
        Map<String, RetrievedChunk> chunkMap = new HashMap<>();

        for (Map.Entry<String, List<RetrievedChunk>> entry : rankedChunks.entrySet()) {
            List<RetrievedChunk> chunks = entry.getValue();
            for (int rank = 0; rank < chunks.size(); rank++) {
                RetrievedChunk chunk = chunks.get(rank);
                String key = chunk.getId() != null ? chunk.getId() : String.valueOf(chunk.getText().hashCode());

                double score = 1.0 / (K + rank + 1);
                rrfScores.merge(key, score, Double::sum);

                chunkMap.putIfAbsent(key, chunk);
            }
        }

        List<RetrievedChunk> fused = new ArrayList<>();
        for (Map.Entry<String, Double> entry : rrfScores.entrySet()) {
            RetrievedChunk chunk = chunkMap.get(entry.getKey());
            if (chunk != null) {
                RetrievedChunk fusedChunk = RetrievedChunk.builder()
                        .id(chunk.getId())
                        .text(chunk.getText())
                        .score(entry.getValue().floatValue())
                        .build();
                fused.add(fusedChunk);
            }
        }

        fused.sort(Comparator.comparingDouble(RetrievedChunk::getScore).reversed());
        return fused;
    }
}
