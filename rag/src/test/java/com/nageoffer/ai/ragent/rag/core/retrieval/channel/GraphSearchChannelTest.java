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
import com.nageoffer.ai.ragent.rag.config.GraphProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.graph.GraphEvidence;
import com.nageoffer.ai.ragent.rag.core.graph.LightRagClient;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GraphSearchChannelTest {

    private static final RetrievalBudget PRODUCTION_BUDGET = new RetrievalBudget(20, 40, 10);

    private LightRagClient lightRagClient;
    private SearchChannelProperties properties;

    @BeforeEach
    void setUp() {
        lightRagClient = mock(LightRagClient.class);
        properties = new SearchChannelProperties();
        GraphProperties graphProperties = new GraphProperties();
        channel = new GraphSearchChannel(lightRagClient, graphProperties, properties);
    }

    private GraphSearchChannel channel;

    @Test
    @DisplayName("补充证据按全图名次与命中证据混排，不被压到末位")
    void supplementEvidenceInterleavesByGraphRank() {
        // 全图名次 0/2 属命中库，1/3 属未命中库；分数即 1/(全图名次+1)
        stub(List.of(evidence("m0", 0), evidence("m2", 2)),
                List.of(evidence("u1", 1), evidence("u3", 3)));

        List<RetrievedChunk> chunks = channel.search(directedContext()).getChunks();

        assertEquals(List.of("m0", "u1", "m2", "u3"),
                chunks.stream().map(RetrievedChunk::getId).toList(),
                "还原图谱自身的相关性序；主份在前、补充份拼后会让未命中的强命中恒排在命中的弱命中之后");
    }

    @Test
    @DisplayName("两份候选各按名额截断后再混排")
    void bothSidesAreCappedBeforeMerge() {
        // 产能 = recallBudget 20，切分为主份 15 / 补充份 5
        stub(evidences("m", 0, 30), evidences("u", 30, 30));

        List<RetrievedChunk> chunks = channel.search(directedContext()).getChunks();

        assertEquals(15, count(chunks, "m"));
        assertEquals(5, count(chunks, "u"));
    }

    @Test
    @DisplayName("补充比例置零时未命中证据被丢弃而非全量放行")
    void zeroSupplementRatioDropsUnmatchedEvidence() {
        // 回归：名额 0 曾在 cap 里退化为「不封顶」，让手册写明的一行回退路径反而放开了补充路；
        // 同一条路径也覆盖「命中库已覆盖全部有效库」——两者都会切出 supplement=0
        properties.getScope().setSupplementRatio(0);
        stub(evidences("m", 0, 30), evidences("u", 30, 30));

        List<RetrievedChunk> chunks = channel.search(directedContext()).getChunks();

        assertEquals(20, chunks.size(), "关闭补充路后主路拿满产能");
        assertEquals(0, count(chunks, "u"));
    }

    @Test
    @DisplayName("全局作用域把全部有效库下发给结果侧过滤，无主证据不入池")
    void globalScopePassesActiveCollectionsForFiltering() {
        // 回归：全局曾传空集=不过滤，已删库在图上的残留会被当证据返回
        stub(evidences("m", 0, 30), evidences("u", 30, 2));

        SearchContext context = SearchContext.builder()
                .originalQuestion("报销发票贴哪张表？")
                .budget(PRODUCTION_BUDGET)
                .retrievalScope(RetrievalScope.global(0.3, List.of("kb-finance", "kb-hr")))
                .build();
        List<RetrievedChunk> chunks = channel.search(context).getChunks();

        verify(lightRagClient).retrieveByScope(anyString(), any(), eq(20), eq(List.of("kb-finance", "kb-hr")));
        assertEquals(20, chunks.size(), "全局无补充名额，名额全归主份且请求量不上浮");
        assertEquals(0, count(chunks, "u"), "过滤出的无主证据（已删库残留 / 解析失败）在全局下被丢弃");
    }

    @Test
    @DisplayName("定向作用域按上浮倍数放大请求量，补回被过滤筛掉的跨库证据")
    void directedScopeBoostsRequestTopK() {
        stub(List.of(), List.of());

        channel.search(directedContext());

        verify(lightRagClient).retrieveByScope(anyString(), any(), eq(60), eq(List.of("kb-finance")));
    }

    @Test
    @DisplayName("没有任何有效知识库时跳过检索")
    void noActiveCollectionSkipsRetrieval() {
        // 回归：空集在图谱结果侧被读作「不过滤」即查全图，会把已删知识库在图上的残留当证据返回，
        // 而向量与关键词在同一状态下都是跳过——同一份作用域三条通道语义相反
        SearchContext context = SearchContext.builder()
                .originalQuestion("报销发票贴哪张表？")
                .budget(PRODUCTION_BUDGET)
                .retrievalScope(RetrievalScope.global(0.0, List.of()))
                .build();

        assertTrue(channel.search(context).getChunks().isEmpty());
        verifyNoInteractions(lightRagClient);
    }

    private void stub(List<RetrievedChunk> matched, List<RetrievedChunk> unmatched) {
        when(lightRagClient.retrieveByScope(anyString(), any(), anyInt(), any(Collection.class)))
                .thenReturn(new GraphEvidence(matched, unmatched));
    }

    private SearchContext directedContext() {
        NodeScore intent = NodeScore.builder()
                .node(IntentNode.builder()
                        .id("finance")
                        .name("财务")
                        .collectionNames(List.of("kb-finance"))
                        .build())
                .score(0.9)
                .build();
        return SearchContext.builder()
                .originalQuestion("报销发票贴哪张表？")
                .budget(PRODUCTION_BUDGET)
                .retrievalScope(new RetrievalScope(
                        true, 0.9, List.of(intent), List.of("kb-finance"), List.of("kb-hr")))
                .build();
    }

    private static long count(List<RetrievedChunk> chunks, String prefix) {
        return chunks.stream().filter(chunk -> chunk.getId().startsWith(prefix)).count();
    }

    /**
     * 按全图名次构造一条图谱证据，分数与 {@code LightRagClient} 的中性分数口径一致
     */
    private static RetrievedChunk evidence(String id, int graphRank) {
        return RetrievedChunk.builder()
                .id(id)
                .text(id)
                .score(1.0F / (graphRank + 1))
                .build();
    }

    private static List<RetrievedChunk> evidences(String prefix, int startRank, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> evidence(prefix + index, startRank + index))
                .toList();
    }
}
