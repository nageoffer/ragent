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

package com.nageoffer.ai.ragent.rag.core.guidance;

import com.nageoffer.ai.ragent.rag.config.GuidanceProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentGuidanceServiceTest {

    private final GuidanceProperties properties = new GuidanceProperties();
    private final CountingRegistry registry = new CountingRegistry();
    private final AmbiguityLLMChecker checker = mock(AmbiguityLLMChecker.class);
    private final IntentGuidanceService service = new IntentGuidanceService(
            properties, registry, new PromptTemplateLoader(new DefaultResourceLoader()), checker);

    @Test
    void callsLlmWhenTwoCandidatesShareLeafNameUnderSameAncestor() {
        IntentNode biz = node("biz", null, "业务系统", "业务系统");
        IntentNode oa = node("oa", "biz", "OA系统", "业务系统 > OA系统");
        IntentNode insurance = node("insurance", "biz", "保险系统", "业务系统 > 保险系统");
        IntentNode oaSecurity = node("oa-sec", "oa", "数据安全", "业务系统 > OA系统 > 数据安全");
        IntentNode insuranceSecurity = node("ins-sec", "insurance", "数据安全", "业务系统 > 保险系统 > 数据安全");
        when(checker.checkAmbiguity(anyString(), anyList())).thenReturn(true);

        GuidanceDecision decision = service.detectAmbiguity(
                "数据安全怎么做", subIntents(score(oaSecurity, 0.9D), score(insuranceSecurity, 0.86D)));

        verify(checker).checkAmbiguity(any(), anyList());
        assertTrue(decision.isPrompt());
        assertTrue(decision.getPrompt().contains(oaSecurity.getFullPath()));
        assertTrue(decision.getPrompt().contains(insuranceSecurity.getFullPath()));
        // 提示语开头用触发冲突的节点名
        assertTrue(decision.getPrompt().startsWith("关于数据安全"));
        // 公共祖先只回表一次
        assertEquals(1, registry.lookupCount(biz.getId()));
        assertEquals(1, registry.lookupCount(oa.getId()));
        assertEquals(1, registry.lookupCount(insurance.getId()));
    }

    @Test
    void callsLlmWhenForkedPathsShareNodeNameMentionedInQuestion() {
        UnevenDepthTree tree = unevenDepthTree();
        when(checker.checkAmbiguity(anyString(), anyList())).thenReturn(true);

        GuidanceDecision decision = service.detectAmbiguity(
                "数据安全怎么做", subIntents(score(tree.bizSecurity(), 0.9D), score(tree.network(), 0.7D)));

        verify(checker).checkAmbiguity(any(), anyList());
        assertTrue(decision.isPrompt());
        assertTrue(decision.getPrompt().startsWith("关于数据安全"));
    }

    @Test
    void callsLlmWhenRootCandidateAndNestedCandidateShareLeafName() {
        IntentNode rootSecurity = node("root-sec", null, "数据安全", "数据安全");
        node("biz", null, "业务系统", "业务系统");
        node("insurance", "biz", "保险系统", "业务系统 > 保险系统");
        IntentNode nestedSecurity = node("ins-sec", "insurance", "数据安全", "业务系统 > 保险系统 > 数据安全");
        when(checker.checkAmbiguity(anyString(), anyList())).thenReturn(true);

        GuidanceDecision decision = service.detectAmbiguity(
                "数据安全怎么做", subIntents(score(rootSecurity, 0.88D), score(nestedSecurity, 0.85D)));

        verify(checker).checkAmbiguity(any(), anyList());
        assertTrue(decision.isPrompt());
        assertTrue(decision.getPrompt().contains("数据安全"));
        assertTrue(decision.getPrompt().contains(nestedSecurity.getFullPath()));
    }

    @Test
    void skipsLlmWhenQuestionOnlyMentionsTheDeeperNode() {
        UnevenDepthTree tree = unevenDepthTree();

        GuidanceDecision decision = service.detectAmbiguity(
                "网络安全怎么做", subIntents(score(tree.bizSecurity(), 0.9D), score(tree.network(), 0.7D)));

        verify(checker, never()).checkAmbiguity(any(), anyList());
        assertFalse(decision.isPrompt());
    }

    @Test
    void skipsLlmWhenCandidatesOnlyShareRealAncestor() {
        node("biz", null, "业务系统", "业务系统");
        node("oa", "biz", "OA系统", "业务系统 > OA系统");
        node("insurance", "biz", "保险系统", "业务系统 > 保险系统");
        IntentNode dataSecurity = node("oa-sec", "oa", "数据安全", "业务系统 > OA系统 > 数据安全");
        IntentNode networkSecurity = node("ins-net", "insurance", "网络安全", "业务系统 > 保险系统 > 网络安全");

        GuidanceDecision decision = service.detectAmbiguity(
                "数据安全怎么做", subIntents(score(dataSecurity, 0.9D), score(networkSecurity, 0.85D)));

        verify(checker, never()).checkAmbiguity(any(), anyList());
        assertFalse(decision.isPrompt());
    }

    @Test
    void returnsNoneWhenLlmDeniesAmbiguity() {
        IntentNode oaSecurity = node("oa-sec", "oa", "数据安全", "业务系统 > OA系统 > 数据安全");
        IntentNode insuranceSecurity = node("ins-sec", "insurance", "数据安全", "业务系统 > 保险系统 > 数据安全");
        node("oa", "biz", "OA系统", "业务系统 > OA系统");
        node("insurance", "biz", "保险系统", "业务系统 > 保险系统");
        node("biz", null, "业务系统", "业务系统");
        when(checker.checkAmbiguity(anyString(), anyList())).thenReturn(false);

        GuidanceDecision decision = service.detectAmbiguity(
                "OA系统和保险系统在数据安全方面有什么共同点",
                subIntents(score(oaSecurity, 0.9D), score(insuranceSecurity, 0.88D)));

        verify(checker).checkAmbiguity(any(), anyList());
        assertFalse(decision.isPrompt());
    }

    @Test
    void passesFullConflictGroupToLlmAndTrimsDisplayedOptions() {
        node("biz", null, "业务系统", "业务系统");
        IntentNode first = node("sec-1", "biz", "数据安全", "业务系统 > 数据安全");
        IntentNode second = node("sec-2", null, "数据安全", "保险系统 > 数据安全");
        IntentNode third = node("sec-3", null, "数据安全", "中间件 > 数据安全");
        properties.setMaxOptions(2);
        List<List<NodeScore>> sentToLlm = new ArrayList<>();
        when(checker.checkAmbiguity(anyString(), anyList())).thenAnswer(invocation -> {
            sentToLlm.add(invocation.getArgument(1));
            return true;
        });

        GuidanceDecision decision = service.detectAmbiguity(
                "数据安全怎么做", subIntents(score(first, 0.9D), score(second, 0.8D), score(third, 0.7D)));

        assertEquals(1, sentToLlm.size());
        assertEquals(3, sentToLlm.get(0).size());
        assertTrue(decision.getPrompt().contains(first.getFullPath()));
        assertTrue(decision.getPrompt().contains(second.getFullPath()));
        assertFalse(decision.getPrompt().contains(third.getFullPath()));
    }

    @Test
    void skipsLlmForSingleEmptyOrMultiSubQuestionInput() {
        IntentNode oaSecurity = node("oa-sec", null, "数据安全", "OA系统 > 数据安全");
        IntentNode insuranceSecurity = node("ins-sec", null, "数据安全", "保险系统 > 数据安全");

        assertFalse(service.detectAmbiguity("数据安全怎么做", subIntents(score(oaSecurity, 0.9D))).isPrompt());
        assertFalse(service.detectAmbiguity("数据安全怎么做", subIntents()).isPrompt());
        assertFalse(service.detectAmbiguity("数据安全怎么做", List.of(
                new SubQuestionIntent("数据安全怎么做", List.of(score(oaSecurity, 0.9D))),
                new SubQuestionIntent("网络安全怎么做", List.of(score(insuranceSecurity, 0.88D)))
        )).isPrompt());

        verify(checker, never()).checkAmbiguity(any(), anyList());
    }

    @Test
    void skipsLlmWhenOnlyOneCandidateReachesMinScore() {
        IntentNode oaSecurity = node("oa-sec", null, "数据安全", "OA系统 > 数据安全");
        IntentNode insuranceSecurity = node("ins-sec", null, "数据安全", "保险系统 > 数据安全");

        GuidanceDecision decision = service.detectAmbiguity(
                "数据安全怎么做", subIntents(score(oaSecurity, 0.9D), score(insuranceSecurity, 0.2D)));

        verify(checker, never()).checkAmbiguity(any(), anyList());
        assertFalse(decision.isPrompt());
    }

    @Test
    void returnsNoneWhenGuidanceDisabled() {
        IntentNode oaSecurity = node("oa-sec", null, "数据安全", "OA系统 > 数据安全");
        IntentNode insuranceSecurity = node("ins-sec", null, "数据安全", "保险系统 > 数据安全");
        properties.setEnabled(false);

        GuidanceDecision decision = service.detectAmbiguity(
                "数据安全怎么做", subIntents(score(oaSecurity, 0.9D), score(insuranceSecurity, 0.88D)));

        verify(checker, never()).checkAmbiguity(any(), anyList());
        assertFalse(decision.isPrompt());
    }

    @Test
    void stopsClimbingOnBrokenOrCyclicParentChain() {
        // oa-sec 与 loop-parent 互为父节点，ins-sec 的父节点在注册表里查不到
        IntentNode cyclic = node("oa-sec", "loop-parent", "数据安全", "OA系统 > 数据安全");
        node("loop-parent", "oa-sec", "OA系统", "OA系统");
        IntentNode orphan = node("ins-sec", "missing-parent", "数据安全", "保险系统 > 数据安全");
        when(checker.checkAmbiguity(anyString(), anyList())).thenReturn(true);

        GuidanceDecision decision = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> service.detectAmbiguity(
                "数据安全怎么做", subIntents(score(cyclic, 0.9D), score(orphan, 0.88D))));

        verify(checker).checkAmbiguity(any(), anyList());
        assertTrue(decision.isPrompt());
    }

    private UnevenDepthTree unevenDepthTree() {
        node("biz", null, "业务系统", "业务系统");
        IntentNode bizSecurity = node("biz-sec", "biz", "数据安全", "业务系统 > 数据安全");
        node("insurance", null, "保险系统", "保险系统");
        node("ins-sec", "insurance", "数据安全", "保险系统 > 数据安全");
        IntentNode network = node("ins-net", "ins-sec", "网络安全", "保险系统 > 数据安全 > 网络安全");
        return new UnevenDepthTree(bizSecurity, network);
    }

    private IntentNode node(String id, String parentId, String name, String fullPath) {
        IntentNode node = IntentNode.builder()
                .id(id)
                .parentId(parentId)
                .name(name)
                .fullPath(fullPath)
                .build();
        registry.register(node);
        return node;
    }

    private static NodeScore score(IntentNode node, double score) {
        return new NodeScore(node, score);
    }

    private static List<SubQuestionIntent> subIntents(NodeScore... scores) {
        return List.of(new SubQuestionIntent("子问题", List.of(scores)));
    }

    /**
     * 记录每个节点被回表的次数，用于校验父节点缓存生效
     */
    private static final class CountingRegistry implements IntentNodeRegistry {

        private final Map<String, IntentNode> nodes = new HashMap<>();
        private final Map<String, AtomicInteger> lookups = new HashMap<>();

        void register(IntentNode node) {
            nodes.put(node.getId(), node);
        }

        int lookupCount(String id) {
            return lookups.getOrDefault(id, new AtomicInteger()).get();
        }

        @Override
        public IntentNode getNodeById(String id) {
            lookups.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
            return nodes.get(id);
        }

        @Override
        public List<IntentNode> listMcpToolNodes() {
            return List.of();
        }

        @Override
        public List<IntentNode> listClassifiableNodes() {
            return List.copyOf(nodes.values());
        }
    }

    /**
     * 业务系统 &gt; 数据安全 与 保险系统 &gt; 数据安全 &gt; 网络安全 的不等深结构
     */
    private record UnevenDepthTree(IntentNode bizSecurity, IntentNode network) {
    }
}
