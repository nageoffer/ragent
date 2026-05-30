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
import java.util.List;
import java.util.Map;

/**
 * 加权求和融合策略
 * weighted_score(d) = w_i * norm(score_i(d))
 * 对各通道的分数做 min-max 归一化后再加权求和。
 */
public class WeightedSumFusionStrategy implements FusionStrategy {

    @Override
    public List<RetrievedChunk> fuse(Map<String, List<RetrievedChunk>> rankedChunks,
                                     Map<String, Float> weights) {
        // chunkKey → weighted score
        Map<String, Double> weightedScores = new HashMap<>();
        // chunkKey → chunk
        Map<String, RetrievedChunk> chunkMap = new HashMap<>();

        for (Map.Entry<String, List<RetrievedChunk>> entry : rankedChunks.entrySet()) {
            String channel = entry.getKey();
            List<RetrievedChunk> chunks = entry.getValue();
            if (chunks.isEmpty()) continue;

            float weight = weights != null && weights.containsKey(channel) ? weights.get(channel) : 1.0f;

            // min-max normalize scores within this channel
            double maxScore = chunks.stream().mapToDouble(c -> c.getScore() != null ? c.getScore() : 0).max().orElse(1.0);
            double minScore = chunks.stream().mapToDouble(c -> c.getScore() != null ? c.getScore() : 0).min().orElse(0.0);
            double range = maxScore - minScore;
            if (range == 0) range = 1.0;

            for (RetrievedChunk chunk : chunks) {
                String key = chunk.getId() != null ? chunk.getId() : String.valueOf(chunk.getText().hashCode());
                double normScore = ((chunk.getScore() != null ? chunk.getScore() : 0) - minScore) / range;
                weightedScores.merge(key, weight * normScore, Double::sum);
                chunkMap.putIfAbsent(key, chunk);
            }
        }

        List<RetrievedChunk> fused = new ArrayList<>();
        for (Map.Entry<String, Double> entry : weightedScores.entrySet()) {
            RetrievedChunk chunk = chunkMap.get(entry.getKey());
            if (chunk != null) {
                fused.add(RetrievedChunk.builder()
                        .id(chunk.getId())
                        .text(chunk.getText())
                        .score(entry.getValue().floatValue())
                        .build());
            }
        }

        fused.sort(Comparator.comparingDouble(RetrievedChunk::getScore).reversed());
        return fused;
    }
}
