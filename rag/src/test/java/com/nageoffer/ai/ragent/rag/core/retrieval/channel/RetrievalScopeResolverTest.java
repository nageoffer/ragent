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

import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalScopeResolverTest {

    private static final List<String> ACTIVE = List.of("kb-finance", "kb-hr", "kb-tech");

    @Test
    @DisplayName("意图置信度充足时收窄到命中库，未命中库进入补充范围")
    void confidentIntentNarrowsScopeAndSplitsSupplement() {
        RetrievalScope scope = resolve(intent("kb-finance", 0.9));

        assertTrue(scope.directed());
        assertEquals(Set.of("intent-kb-finance"), scope.directedIntentIds());
        assertEquals(List.of("kb-finance"), scope.targetCollections());
        assertEquals(List.of("kb-hr", "kb-tech"), scope.supplementCollections());
    }

    @Test
    @DisplayName("意图置信度低于阈值时退化为全局作用域")
    void lowConfidenceIntentFallsBackToGlobal() {
        RetrievalScope scope = resolve(intent("kb-finance", 0.5));

        assertFalse(scope.directed());
        assertTrue(scope.directedIntentIds().isEmpty());
        assertEquals(ACTIVE, scope.targetCollections());
        assertTrue(scope.supplementCollections().isEmpty());
    }

    @Test
    @DisplayName("同样的最高分下多一个低分意图不会改变作用域")
    void extraLowScoringIntentDoesNotChangeScope() {
        RetrievalScope single = resolve(intent("kb-finance", 0.65));
        RetrievalScope paired = resolve(intent("kb-finance", 0.65), intent("kb-hr", 0.42));

        assertEquals(single.directed(), paired.directed(),
                "作用域只由最高分决定，意图个数不应参与判定");
        assertTrue(single.directed());
    }

    @Test
    @DisplayName("未绑定Collection的意图不参与判定，退化为全局作用域")
    void intentWithoutCollectionsFallsBackToGlobal() {
        NodeScore unbound = NodeScore.builder()
                .node(IntentNode.builder().id("empty-kb-intent").name("未配置知识库").build())
                .score(0.95)
                .build();

        RetrievalScope scope = resolve(unbound);

        assertFalse(scope.directed());
        assertEquals(ACTIVE, scope.targetCollections());
    }

    @Test
    @DisplayName("意图绑定的知识库均已失效时退化为全局作用域")
    void intentsOnRetiredCollectionsFallBackToGlobal() {
        // 意图绑定与知识库表是两套数据，删库不回写意图节点；若照常收窄，主路名额全打在空库上，
        // 而每条查询都「成功」返回 0 条，没有任何报错——典型的静默召回归零
        RetrievalScope scope = resolve(intent("kb-retired", 0.9));

        assertFalse(scope.directed());
        assertEquals(ACTIVE, scope.targetCollections());
        assertTrue(scope.supplementCollections().isEmpty());
    }

    @Test
    @DisplayName("意图绑定中已失效的库被剔除出检索范围，剩余有效库照常收窄")
    void retiredBindingIsDroppedFromDirectedScope() {
        // 回归：悬空库名会让图谱在结果侧按库名匹配到已删库的残留证据
        RetrievalScope scope = resolve(intent("kb-finance", 0.9), intent("kb-retired", 0.8));

        assertTrue(scope.directed());
        assertEquals(List.of("kb-finance"), scope.targetCollections());
        assertEquals(List.of("kb-hr", "kb-tech"), scope.supplementCollections());
        assertEquals(Set.of("intent-kb-finance", "intent-kb-retired"), scope.directedIntentIds(),
                "悬空意图保留在定向意图集里，由下游按「定向未命中」处理，而非伪装成从未识别");
    }

    @Test
    @DisplayName("命中库覆盖全部有效库时补充范围为空")
    void fullCoverageLeavesNoSupplement() {
        RetrievalScope scope = resolve(
                intent("kb-finance", 0.9), intent("kb-hr", 0.85), intent("kb-tech", 0.8));

        assertTrue(scope.directed());
        assertTrue(scope.supplementCollections().isEmpty());
    }

    @Test
    @DisplayName("多个子问题命中同一个库时命中范围去重")
    void duplicatedIntentCollectionsAreDeduplicated() {
        RetrievalScope scope = resolve(intent("kb-finance", 0.9), intent("kb-finance", 0.7));

        assertEquals(List.of("kb-finance"), scope.targetCollections());
        assertEquals(List.of("kb-hr", "kb-tech"), scope.supplementCollections());
    }

    @Test
    @DisplayName("多个子问题命中同一意图节点时按节点去重并保留最高分")
    void duplicatedIntentNodesAreDeduplicatedKeepingTopScore() {
        // 回归：定向路按 NodeScore 逐条 fan-out，不去重则同一库被查两次、
        // 同一 chunk 在通道原始列表占两个名次，下游 RRF 按名次累加会把分数翻倍
        RetrievalScope scope = resolve(intent("kb-finance", 0.7), intent("kb-finance", 0.9));

        assertEquals(1, scope.intents().size(), "同一意图节点只应保留一条");
        assertEquals(0.9, scope.intents().get(0).getScore(), "应保留该节点的最高命中分");
        assertEquals(scope.targetCollections(),
                scope.intents().stream()
                        .flatMap(nodeScore -> nodeScore.getNode().getEffectiveCollectionNames().stream())
                        .distinct()
                        .toList(),
                "定向路实际查询范围须与 targetCollections 一致，否则补充范围的差集算不准");
    }

    @Test
    @DisplayName("不同意图节点绑定同一个库时都保留，去重交由扇出层按查询身份处理")
    void distinctIntentNodesOnSameCollectionAreKept() {
        RetrievalScope scope = resolve(
                intent("报销", "kb-finance", 0.9), intent("发票", "kb-finance", 0.7));

        assertEquals(2, scope.intents().size(),
                "节点不同就是不同意图，各自的 node.topK 可能不同，作用域这层没有信息合并它们");
        assertEquals(List.of("kb-finance"), scope.targetCollections());
        assertEquals(List.of("kb-hr", "kb-tech"), scope.supplementCollections());
    }

    /**
     * 每条意图挂在各自的子问题下，还原「意图按子问题分别识别、再合并」的真实流程
     */
    private RetrievalScope resolve(NodeScore... nodeScores) {
        KbCollectionProvider provider = mock(KbCollectionProvider.class);
        when(provider.listActiveCollections()).thenReturn(ACTIVE);
        RetrievalScopeResolver resolver = new RetrievalScopeResolver(new SearchChannelProperties(), provider);
        List<SubQuestionIntent> subIntents = IntStream.range(0, nodeScores.length)
                .mapToObj(index -> new SubQuestionIntent("子问题" + index, List.of(nodeScores[index])))
                .toList();
        return resolver.resolve(subIntents);
    }

    private static NodeScore intent(String collection, double score) {
        return intent("intent-" + collection, collection, score);
    }

    /**
     * 意图 id 与库名解耦：真实配置里两个不同意图完全可能挂同一个库
     */
    private static NodeScore intent(String id, String collection, double score) {
        return NodeScore.builder()
                .node(IntentNode.builder()
                        .id(id)
                        .name(collection)
                        .collectionNames(List.of(collection))
                        .build())
                .score(score)
                .build();
    }
}
