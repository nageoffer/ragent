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

import cn.hutool.crypto.digest.DigestUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 融合后置处理器（RRF）
 * <p>
 * 使用 Reciprocal Rank Fusion（倒数名次融合）合并多个检索通道的结果
 * 向量分（余弦）与关键词分（BM25）量纲不同、不可直接比较，RRF 只依据名次，天然跨模态可比
 * <p>
 * score(chunk) = Σ_channel 1 / (k + rank_channel)
 * <p>
 * 名次取自不可变的 {@link SearchChannelResult} 列表（每个通道的原始召回顺序），
 * 因此即便上游去重处理器已合并 chunks，也不会丢失「多路命中」信息
 * <p>
 * 融合排序后按 rerankCandidateLimit 截断候选池，只把高分前 N 个送入下游 Rerank：
 * 一方面控制 Rerank 成本与延迟，另一方面让多路命中的候选凭 RRF 分数优先入选，
 * 使「粗排（本处）+ 精排（Rerank）」的两阶段分工真正落地
 * <p>
 * 位于去重（order=1）之后、Rerank（order=10）之前；单通道时跳过融合，仅做截断
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FusionPostProcessor implements SearchResultPostProcessor {

    private static final String STRATEGY_RRF = "rrf";

    private final SearchChannelProperties properties;

    @Override
    public String getName() {
        return "Fusion";
    }

    @Override
    public int getOrder() {
        return 5;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return STRATEGY_RRF.equalsIgnoreCase(properties.getFusion().getStrategy());
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        if (chunks.isEmpty()) {
            return chunks;
        }

        // 多通道才做 RRF 融合重排；单通道保持原召回顺序
        List<RetrievedChunk> ranked = results != null && results.size() > 1
                ? fuseByRrf(chunks, results)
                : chunks;

        // 截断候选池：仅保留高分前 N 个送入 Rerank，控制其成本与延迟
        return truncateForRerank(ranked, results);
    }

    /**
     * 依据各通道原始召回名次累计 RRF 分，回写到去重后的 chunks 并按分数倒序
     */
    private List<RetrievedChunk> fuseByRrf(List<RetrievedChunk> chunks, List<SearchChannelResult> results) {
        int k = properties.getFusion().getRrfK();

        Map<String, Double> rrfScores = new LinkedHashMap<>();
        for (SearchChannelResult result : results) {
            List<RetrievedChunk> channelChunks = result.getChunks();
            for (int rank = 0; rank < channelChunks.size(); rank++) {
                String key = chunkKey(channelChunks.get(rank));
                double delta = 1.0 / (k + rank + 1);
                rrfScores.merge(key, delta, Double::sum);
            }
        }

        List<RetrievedChunk> fused = new ArrayList<>(chunks);
        for (RetrievedChunk chunk : fused) {
            Double score = rrfScores.get(chunkKey(chunk));
            chunk.setScore(score != null ? score.floatValue() : 0f);
        }
        fused.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
        return fused;
    }

    /**
     * 按 rerankCandidateLimit 截断候选池，仅保留前 N 个送入下游 Rerank
     * limit <= 0 表示不截断（全量透传）
     */
    private List<RetrievedChunk> truncateForRerank(List<RetrievedChunk> ranked, List<SearchChannelResult> results) {
        int limit = properties.getFusion().getRerankCandidateLimit();
        boolean truncate = limit > 0 && ranked.size() > limit;
        List<RetrievedChunk> candidates = truncate
                ? new ArrayList<>(ranked.subList(0, limit))
                : ranked;

        int channelCount = results == null ? 0 : results.size();
        log.info("RRF 融合完成 - 通道数: {}, k: {}, 融合后: {} 个, 截断上限: {}, 送入 Rerank: {} 个",
                channelCount, properties.getFusion().getRrfK(), ranked.size(),
                limit > 0 ? String.valueOf(limit) : "不限", candidates.size());
        return candidates;
    }

    /**
     * 生成 Chunk 融合键，与去重处理器保持一致（优先 id，缺失时退化为文本哈希）
     */
    private String chunkKey(RetrievedChunk chunk) {
        // 与去重处理器一致改用 SHA-256：String.hashCode() 碰撞会让不同 Chunk 的
        // RRF 分数被错误地累加到同一个键上
        return chunk.getId() != null
                ? chunk.getId()
                : DigestUtil.sha256Hex(chunk.getText() == null ? "" : chunk.getText());
    }
}
