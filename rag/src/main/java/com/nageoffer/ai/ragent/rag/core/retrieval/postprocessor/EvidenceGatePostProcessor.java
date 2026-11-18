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

package com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 证据相关性闸门
 * <p>
 * 检索只保证返回最像的 N 条，库里没答案时照样满额返回，下游又只看证据文本非空，噪声必然进提示词
 * 闸门按整批最高精排分判定，不合格整批丢弃；只管批级去留，过线后弱证据一并保留
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvidenceGatePostProcessor implements SearchResultPostProcessor, InitializingBean {

    private final SearchChannelProperties searchChannelProperties;
    private final RAGConfigProperties ragConfigProperties;

    /**
     * 闸门的唯一判据来自精排
     */
    @Override
    public void afterPropertiesSet() {
        double minRerankScore = searchChannelProperties.getEvidence().getMinRerankScore();
        if (minRerankScore > 0 && Boolean.FALSE.equals(ragConfigProperties.getRerankEnabled())) {
            throw new IllegalStateException(String.format(
                    "rag.search.evidence.min-rerank-score(%s) 需要精排出分，但 rag.rerank.enabled=false："
                            + "闸门将无分可读、恒放行；请开启精排或把下限填 0",
                    minRerankScore));
        }
    }

    @Override
    public String getName() {
        return "EvidenceGate";
    }

    @Override
    public int getOrder() {
        return 15;  // Rerank(10) 出分之后
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return searchChannelProperties.getEvidence().getMinRerankScore() > 0;
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        if (chunks.isEmpty()) {
            return chunks;
        }

        // 无分可读一律放行：noop 降级只截断不打分
        // 照拦等于在精排最不稳时关掉整条 KB 侧，且表现与库里没资料一致
        // 走到这里说明闸门在空转，按 warn 打——精排正常时不该出现
        Float topScore = maxRerankScore(chunks);
        if (topScore == null) {
            log.warn("检索归因 - 证据闸门: 本批 {} 条无精排分可读，闸门空转放行", chunks.size());
            return chunks;
        }

        double minRerankScore = searchChannelProperties.getEvidence().getMinRerankScore();
        if (topScore >= minRerankScore) {
            return chunks;
        }

        log.info("检索归因 - 证据闸门: 最高精排分 {} 低于下限 {}，丢弃全部 {} 条证据",
                topScore, minRerankScore, chunks.size());
        return List.of();
    }

    /**
     * 全批缺分返回 null
     * 按最高分而非逐条判：误丢比误放贵
     * 不取首条：{@code RerankClient} 未承诺返回序，回填条目也没分
     */
    private Float maxRerankScore(List<RetrievedChunk> chunks) {
        Float max = null;
        for (RetrievedChunk chunk : chunks) {
            Float score = chunk.getRerankScore();
            if (score == null || !Float.isFinite(score)) {
                continue;
            }
            if (max == null || score > max) {
                max = score;
            }
        }
        return max;
    }
}
