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
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 精排打分用带文档名的副本，输出仍是原文
 * <p>
 * 桩按 BaiLianRerankClient 的形态返回：倒序、toBuilder 出的新对象带分；noop 降级原样回传输入对象另测
 */
class RerankPostProcessorTest {

    private static final String SAME_BODY = "### 前置摄像头\n- 1800 万像素 Center Stage 摄像头";

    private final List<String> rerankerSaw = new ArrayList<>();

    @Test
    @DisplayName("精排收到文档名加正文，结果 text 是原文、分数取精排的，输入对象不被改写")
    void rerankerSeesDocNameButResultKeepsOriginalText() {
        List<RetrievedChunk> chunks = candidates();

        List<RetrievedChunk> result = processor(true, copyingReranker()).process(chunks, List.of(), context());

        assertEquals(List.of("IPHONE-18-PRO.md\n" + SAME_BODY, "IPHONE-18-PRO-MAX.md\n" + SAME_BODY, "网页正文"),
                rerankerSaw);
        assertEquals(List.of("w0", "pm2", "p2"), result.stream().map(RetrievedChunk::getId).toList());
        assertEquals(List.of("网页正文", SAME_BODY, SAME_BODY), result.stream().map(RetrievedChunk::getText).toList());
        assertEquals(0.6f, result.get(1).getRerankScore());
        assertEquals("IPHONE-18-PRO-MAX.md", result.get(1).getDocName());
        assertEquals(SAME_BODY, chunks.get(0).getText(), "输入列表里的原对象不得被改写");
        assertNull(chunks.get(0).getRerankScore());
    }

    @Test
    @DisplayName("精排降级 noop 原样回传输入对象时，输出同样是原文且不带分")
    void noopFallbackStillReturnsOriginalText() {
        RerankService passthrough = (query, candidates, topN) -> {
            candidates.forEach(c -> rerankerSaw.add(c.getText()));
            return candidates;
        };

        List<RetrievedChunk> result = processor(true, passthrough).process(candidates(), List.of(), context());

        assertEquals("IPHONE-18-PRO.md\n" + SAME_BODY, rerankerSaw.get(0));
        assertEquals(SAME_BODY, result.get(0).getText());
        assertNull(result.get(0).getRerankScore());
    }

    @Test
    @DisplayName("开关关闭时精排拿到的就是原文")
    void disabledFlagSendsOriginalText() {
        processor(false, copyingReranker()).process(candidates(), List.of(), context());

        assertEquals(List.of(SAME_BODY, SAME_BODY, "网页正文"), rerankerSaw);
    }

    @Test
    @DisplayName("文档名由元数据富化回表填入，富化必须排在精排之前")
    void enrichmentRunsBeforeRerank() {
        int enrichment = new MetadataEnrichmentPostProcessor(null, null).getOrder();

        assertTrue(enrichment < processor(true, copyingReranker()).getOrder());
    }

    private RerankService copyingReranker() {
        float[] scores = {0.3f, 0.6f, 0.9f};
        return (query, candidates, topN) -> {
            candidates.forEach(c -> rerankerSaw.add(c.getText()));
            List<RetrievedChunk> reranked = new ArrayList<>();
            for (int i = candidates.size() - 1; i >= 0; i--) {
                reranked.add(candidates.get(i).toBuilder().score(scores[i]).rerankScore(scores[i]).build());
            }
            return reranked;
        };
    }

    private static RerankPostProcessor processor(boolean includeDocName, RerankService rerankService) {
        SearchChannelProperties props = new SearchChannelProperties();
        props.setIncludeDocName(includeDocName);
        return new RerankPostProcessor(rerankService, new RAGConfigProperties(), props);
    }

    private static SearchContext context() {
        return SearchContext.builder().originalQuestion("iPhone 18 Pro 前置摄像头多少像素").budget(RetrievalBudget.uniform(10)).build();
    }

    private static List<RetrievedChunk> candidates() {
        return List.of(
                RetrievedChunk.builder().id("p2").text(SAME_BODY).docName("IPHONE-18-PRO.md").score(0.05f).build(),
                RetrievedChunk.builder().id("pm2").text(SAME_BODY).docName("IPHONE-18-PRO-MAX.md").score(0.04f).build(),
                RetrievedChunk.builder().id("w0").text("网页正文").score(0.03f).build());
    }
}
