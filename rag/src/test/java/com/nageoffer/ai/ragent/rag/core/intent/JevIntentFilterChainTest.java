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

package com.nageoffer.ai.ragent.rag.core.intent;

import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.jev.JevClient;
import com.nageoffer.ai.ragent.infra.jev.JevTarget;
import com.nageoffer.ai.ragent.infra.jev.JevResponse;
import com.nageoffer.ai.ragent.rag.config.IntentProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.KbCollectionProvider;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScope;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScopeResolver;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MAX_INTENT_COUNT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Jev 概率在整条过滤链上的实际效果
 * <p>
 * 适配层的 {@code min-noul} 之后还有三道过滤，这些用例把每道门的分界点固定下来：
 * 适配层下限 → {@code INTENT_MIN_SCORE} 与 {@code MAX_INTENT_COUNT} → {@code scope.min-intent-score}
 * → {@code scope.confidence-threshold}
 */
class JevIntentFilterChainTest {

    private static final List<String> ACTIVE = List.of("bit_policy", "bit_product", "bit_manual", "bit_faq");

    private JevClient jevClient;
    private IntentProperties intentProperties;
    private IntentResolver intentResolver;
    private RetrievalScopeResolver scopeResolver;

    @BeforeEach
    void setUp() {
        jevClient = mock(JevClient.class);
        intentProperties = new IntentProperties();
        intentProperties.setType("jev");

        IntentNodeRegistry registry = mock(IntentNodeRegistry.class);
        when(registry.listClassifiableNodes()).thenReturn(List.of(
                node("support-aftersale-return", "退换货政策", "bit_policy"),
                node("support-aftersale-warranty", "保修政策", "bit_policy"),
                node("support-presale-spec", "参数与配置", "bit_product"),
                node("support-usage-operation", "操作指引", "bit_manual"),
                node("feedback-fault", "故障报告与排查", "bit_faq")));

        when(jevClient.requireTarget()).thenReturn(
                new JevTarget("http://127.0.0.1/api/v1/systemone", "test-key", "typesafe/jev-1.14"));
        intentResolver = new IntentResolver(
                new JevIntentClassifier(registry, jevClient, intentProperties), Runnable::run);

        KbCollectionProvider provider = mock(KbCollectionProvider.class);
        when(provider.listActiveCollections()).thenReturn(ACTIVE);
        scopeResolver = new RetrievalScopeResolver(new SearchChannelProperties(), provider);
    }

    @Test
    @DisplayName("高概率单意图：收窄到该意图绑定的库，其余库进补充范围")
    void confidentSingleIntentNarrowsScope() {
        RetrievalScope scope = chain(nouls(0.93, 0.11, 0.02, 0.03, 0.04));

        assertTrue(scope.directed());
        assertEquals(List.of("bit_policy"), scope.targetCollections());
        assertEquals(List.of("bit_product", "bit_manual", "bit_faq"), scope.supplementCollections());
    }

    @Test
    @DisplayName("过线但不足收窄：0.45 能进意图列表，却仍走全库检索")
    void intentAboveAdapterGateStillFallsBackToGlobal() {
        // 这是适配层下限与下游收窄阈值之间的真实落差：min-noul=0.4 让意图活下来，
        // 但 scope.confidence-threshold=0.6 决定检索宽度，不要以为配了 0.4 就会定向
        RetrievalScope scope = chain(nouls(0.45, 0.21, 0.03, 0.05, 0.02));

        assertEquals(1, intents(nouls(0.45, 0.21, 0.03, 0.05, 0.02)).size(), "意图确实识别出来了");
        assertFalse(scope.directed(), "最高分低于 0.6，检索仍走全局");
        assertEquals(ACTIVE, scope.targetCollections());
    }

    @Test
    @DisplayName("多意图：强意图触发收窄后，0.4~0.6 的次意图照样贡献自己的库")
    void secondaryIntentContributesCollectionOnceNarrowed() {
        RetrievalScope scope = chain(nouls(0.88, 0.09, 0.47, 0.06, 0.03));

        assertTrue(scope.directed());
        assertEquals(List.of("bit_policy", "bit_product"), scope.targetCollections());
        assertEquals(List.of("bit_manual", "bit_faq"), scope.supplementCollections());
    }

    @Test
    @DisplayName("低于适配层下限的候选全被丢掉，等同无匹配，走全库兜底")
    void allBelowGateMeansNoIntent() {
        List<SubQuestionIntent> intents = intents(nouls(0.38, 0.22, 0.11, 0.07, 0.05));

        assertTrue(intents.get(0).nodeScores().isEmpty());
        assertFalse(scopeResolver.resolve(intents).directed());
        assertEquals(ACTIVE, scopeResolver.resolve(intents).targetCollections());
    }

    @Test
    @DisplayName("过线候选多于 MAX_INTENT_COUNT 时按概率降序截断")
    void capsIntentCountByMaxIntentCount() {
        List<SubQuestionIntent> intents = intents(nouls(0.91, 0.84, 0.72, 0.66, 0.61));

        assertEquals(MAX_INTENT_COUNT, intents.get(0).nodeScores().size());
        assertEquals(List.of("support-aftersale-return", "support-aftersale-warranty", "support-presale-spec"),
                intents.get(0).nodeScores().stream().map(ns -> ns.getNode().getId()).toList());
    }

    @Test
    @DisplayName("把下限压到 INTENT_MIN_SCORE 以下不会放宽任何事：0.35 那道门仍在")
    void loweringAdapterGateBelowResolverGateChangesNothing() {
        intentProperties.getJev().setMinNoul(0.1D);

        List<SubQuestionIntent> intents = intents(nouls(0.34, 0.33, 0.2, 0.15, 0.12));

        assertTrue(intents.get(0).nodeScores().isEmpty(),
                "低于 INTENT_MIN_SCORE 的意图被 IntentResolver 过滤，适配层放宽也到不了下游");
    }

    @Test
    @DisplayName("服务故障仍按原有 catch-all 降级为空意图")
    void serviceErrorStillDegradesInResolver() {
        when(jevClient.evaluate(any(), any())).thenThrow(new ModelClientException(
                "503", ModelClientErrorType.SERVER_ERROR, 503));

        List<SubQuestionIntent> intents = intentResolver.resolve(new RewriteResult("能退吗", List.of()));

        assertEquals(1, intents.size());
        assertTrue(intents.get(0).nodeScores().isEmpty());
    }

    /**
     * 走完分类 → 意图过滤 → 作用域解析
     */
    private RetrievalScope chain(Map<String, Double> nouls) {
        return scopeResolver.resolve(intents(nouls));
    }

    private List<SubQuestionIntent> intents(Map<String, Double> nouls) {
        when(jevClient.evaluate(any(), any())).thenReturn(new JevResponse(nouls));
        return intentResolver.resolve(new RewriteResult("AirPods Pro 左耳没声音能退吗", List.of()));
    }

    /**
     * 按候选顺序给出各节点的 Noul 概率
     */
    private static Map<String, Double> nouls(double... values) {
        Map<String, Double> nouls = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            nouls.put("n" + i, values[i]);
        }
        return nouls;
    }

    private static IntentNode node(String id, String name, String collection) {
        return IntentNode.builder()
                .id(id)
                .name(name)
                .fullPath("产品与服务咨询 > " + name)
                .description("说明-" + name)
                .kind(IntentKind.KB)
                .collectionNames(List.of(collection))
                .build();
    }
}
