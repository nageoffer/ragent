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
import com.nageoffer.ai.ragent.infra.jev.JevNoulQuestion;
import com.nageoffer.ai.ragent.infra.jev.JevRequest;
import com.nageoffer.ai.ragent.infra.jev.JevResponse;
import com.nageoffer.ai.ragent.rag.config.IntentProperties;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Jev 意图分类的节点选择与出分契约
 */
@ExtendWith(MockitoExtension.class)
class JevIntentClassifierTest {

    @Mock
    private IntentNodeRegistry intentNodeRegistry;

    @Mock
    private JevClient jevClient;

    private IntentProperties intentProperties;
    private JevIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        intentProperties = new IntentProperties();
        intentProperties.setType("jev");
        when(jevClient.requireTarget()).thenReturn(target());
        classifier = new JevIntentClassifier(intentNodeRegistry, jevClient, intentProperties);
    }

    @Test
    @DisplayName("候选定义只带名称、路径、描述与示例，不混入知识正文或历史")
    void sendsOnlyCategoryDefinitions() {
        when(intentNodeRegistry.listClassifiableNodes()).thenReturn(List.of(
                node("support-aftersale-return", "退换货政策", "产品与服务咨询 > 售后咨询 > 退换货政策",
                        "退换条件与时限", List.of("七天无理由怎么算"))));
        when(jevClient.evaluate(any(), any())).thenReturn(response(Map.of("n0", 0.88D)));

        classifier.classifyTargets("AirPods 左耳没声音能退吗");

        ArgumentCaptor<JevRequest> captor = ArgumentCaptor.forClass(JevRequest.class);
        verify(jevClient).evaluate(captor.capture(), any());
        JevRequest request = captor.getValue();
        assertEquals("AirPods 左耳没声音能退吗", request.state(), "待判断输入是用户问题本身");
        assertEquals(1, request.questions().size());
        JevNoulQuestion question = request.questions().get(0);
        assertTrue(question.instructions().contains("产品与服务咨询 > 售后咨询 > 退换货政策"));
        assertTrue(question.trueCriteria().contains("退换条件与时限"));
        assertTrue(question.trueCriteria().contains("七天无理由怎么算"));
        assertTrue(question.instructions().contains("属于类目"),
                "判的是归不归这一类，不是知识库里有没有答案");
    }

    @Test
    @DisplayName("命题三段按固定措辞拼装：类目路径、覆盖范围、典型问法")
    void composesQuestionFromFixedWording() {
        when(intentNodeRegistry.listClassifiableNodes()).thenReturn(List.of(
                node("support-aftersale-return", "退换货政策", "产品与服务咨询 > 售后咨询 > 退换货政策",
                        "退换条件与时限", List.of("七天无理由怎么算", "运费谁出"))));
        when(jevClient.evaluate(any(), any())).thenReturn(response(Map.of("n0", 0.88D)));

        classifier.classifyTargets("能退吗");

        ArgumentCaptor<JevRequest> captor = ArgumentCaptor.forClass(JevRequest.class);
        verify(jevClient).evaluate(captor.capture(), any());
        JevNoulQuestion question = captor.getValue().questions().get(0);
        assertEquals("用户问题属于类目「产品与服务咨询 > 售后咨询 > 退换货政策」", question.instructions());
        assertEquals("类目「退换货政策」的覆盖范围说明：退换条件与时限。典型问法：七天无理由怎么算 / 运费谁出",
                question.trueCriteria());
        assertEquals("用户问题中没有任何诉求属于该类目的覆盖范围", question.falseCriteria());
    }

    @Test
    @DisplayName("节点没有示例或描述时各自退化，不拼出空段落")
    void degradesWhenNodeLacksExamplesOrDescription() {
        when(intentNodeRegistry.listClassifiableNodes()).thenReturn(List.of(
                IntentNode.builder().id("bare").name("裸节点").kind(IntentKind.KB)
                        .collectionNames(List.of("bit_policy")).build()));
        when(jevClient.evaluate(any(), any())).thenReturn(response(Map.of("n0", 0.5D)));

        classifier.classifyTargets("随便问问");

        ArgumentCaptor<JevRequest> captor = ArgumentCaptor.forClass(JevRequest.class);
        verify(jevClient).evaluate(captor.capture(), any());
        JevNoulQuestion question = captor.getValue().questions().get(0);
        assertEquals("用户问题属于类目「裸节点」", question.instructions(), "无 fullPath 时退回名称");
        assertEquals("类目「裸节点」的覆盖范围说明：未提供", question.trueCriteria(),
                "无描述时给占位，无示例时不拼「典型问法」那段");
    }

    @Test
    @DisplayName("多个候选同时过线时全部保留，按概率降序")
    void keepsMultipleIntents() {
        when(intentNodeRegistry.listClassifiableNodes()).thenReturn(List.of(
                node("a", "退换货政策"), node("b", "保修政策"), node("c", "参数与配置")));
        when(jevClient.evaluate(any(), any())).thenReturn(response(Map.of("n0", 0.62D, "n1", 0.81D, "n2", 0.05D)));

        List<NodeScore> scores = classifier.classifyTargets("过保了还能换吗");

        assertEquals(List.of("b", "a"), scores.stream().map(ns -> ns.getNode().getId()).toList());
        assertEquals(0.81D, scores.get(0).getScore(), 1e-9, "Noul 原值进 score，不缩放不归一化");
        assertEquals(0.62D, scores.get(1).getScore(), 1e-9);
    }

    @Test
    @DisplayName("响应不完整由客户端判失败，分类器走已有降级路径返回空意图")
    void degradesOnIncompleteResponse() {
        when(intentNodeRegistry.listClassifiableNodes()).thenReturn(List.of(node("a", "退换货政策"), node("b", "保修政策")));
        when(jevClient.evaluate(any(), any())).thenThrow(new ModelClientException(
                "jev 缺少判断 n0 的答案", ModelClientErrorType.INVALID_RESPONSE, null));

        assertTrue(classifier.classifyTargets("保修多久").isEmpty(),
                "协议失败不能当成「无匹配」给出部分意图");
    }

    @Test
    @DisplayName("完整但全部低于下限是正常无匹配，与协议失败区分开")
    void completeLowProbabilityIsNormalNoMatch() {
        when(intentNodeRegistry.listClassifiableNodes()).thenReturn(List.of(node("a", "退换货政策"), node("b", "保修政策")));
        when(jevClient.evaluate(any(), any())).thenReturn(response(Map.of("n0", 0.05D, "n1", 0.0D)));

        assertTrue(classifier.classifyTargets("讲个笑话").isEmpty());
        verify(jevClient).evaluate(any(), any());
    }

    @Test
    @DisplayName("意图树没有可用候选时不发请求")
    void skipsCallWhenNoCandidate() {
        when(intentNodeRegistry.listClassifiableNodes()).thenReturn(List.of());

        assertTrue(classifier.classifyTargets("能退吗").isEmpty());
        verify(jevClient, never()).evaluate(any(), any());
    }

    @Test
    @DisplayName("下限按配置生效，边界值算过线")
    void appliesConfiguredThreshold() {
        intentProperties.getJev().setMinNoul(0.4D);
        when(intentNodeRegistry.listClassifiableNodes()).thenReturn(List.of(node("a", "退换货政策"), node("b", "保修政策")));
        when(jevClient.evaluate(any(), any())).thenReturn(response(Map.of("n0", 0.4D, "n1", 0.39D)));

        List<NodeScore> scores = classifier.classifyTargets("能退吗");

        assertEquals(List.of("a"), scores.stream().map(ns -> ns.getNode().getId()).toList());
    }

    @Test
    @DisplayName("分类器不碰意图树装配，注册表仍由原实现提供")
    void doesNotTouchRegistryBeyondCandidateListing() {
        when(intentNodeRegistry.listClassifiableNodes()).thenReturn(List.of(node("a", "退换货政策")));
        when(jevClient.evaluate(any(), any())).thenReturn(response(Map.of("n0", 0.9D)));

        classifier.classifyTargets("能退吗");

        verify(intentNodeRegistry, never()).listMcpToolNodes();
        verify(intentNodeRegistry, never()).getNodeById(any());
    }

    @Test
    @DisplayName("候选超出单请求预算时串行分批，合并结果与单批等价")
    void splitsIntoSerialBatchesBeyondBudget() {
        // 100 个带真实长度描述的候选会超出 19200 token 预估预算；节点是合成的，只用来验证容量机制
        List<IntentNode> many = syntheticCandidates(100);
        when(intentNodeRegistry.listClassifiableNodes()).thenReturn(many);
        // 每批按本批实际收到的 key 作答，模拟服务端只回本批的题
        when(jevClient.evaluate(any(), any())).thenAnswer(invocation -> {
            JevRequest request = invocation.getArgument(0);
            Map<String, Double> answers = new HashMap<>();
            request.questions().forEach(q -> answers.put(q.key(), "n7".equals(q.key()) ? 0.93D : 0.01D));
            return response(answers);
        });

        List<NodeScore> scores = classifier.classifyTargets("保修多久");

        ArgumentCaptor<JevRequest> captor = ArgumentCaptor.forClass(JevRequest.class);
        verify(jevClient, atLeast(2)).evaluate(captor.capture(), any());
        List<JevRequest> sent = captor.getAllValues();
        assertTrue(sent.size() >= 2, "100 个候选必须切批，实际批次 " + sent.size());

        List<String> allKeys = sent.stream()
                .flatMap(r -> r.questions().stream())
                .map(JevNoulQuestion::key)
                .toList();
        assertEquals(many.size(), allKeys.size(), "各批题目数之和须等于候选数，不重不漏");
        assertEquals(allKeys.size(), allKeys.stream().distinct().count(), "同一道题不得出现在两批里");
        sent.forEach(r -> assertEquals("保修多久", r.state(), "每批都要带上待判断的问题"));

        assertEquals(1, scores.size(), "合并后只有过线的那一个候选");
        assertEquals("syn-7", scores.get(0).getNode().getId());
        assertEquals(0.93D, scores.get(0).getScore(), 1e-9);
    }

    @Test
    @DisplayName("跨批结果全局合并后统一排序，不按批各取前几名")
    void mergesBatchesGloballyBeforeRanking() {
        when(intentNodeRegistry.listClassifiableNodes()).thenReturn(syntheticCandidates(100));
        // 每批给 4 个过线分：按批截断（哪怕留前 3）都会丢掉第 4 个，全局合并则全部保留
        AtomicInteger round = new AtomicInteger();
        when(jevClient.evaluate(any(), any())).thenAnswer(invocation -> {
            JevRequest request = invocation.getArgument(0);
            Map<String, Double> answers = new HashMap<>();
            request.questions().forEach(q -> answers.put(q.key(), 0.01D));
            List<String> keys = request.questions().stream().map(JevNoulQuestion::key).toList();
            List<Double> highs = round.getAndIncrement() == 0
                    ? List.of(0.90D, 0.80D, 0.70D, 0.60D, 0.30D)
                    : List.of(0.95D, 0.85D, 0.75D, 0.65D, 0.40D);
            for (int i = 0; i < highs.size(); i++) {
                answers.put(keys.get(i), highs.get(i));
            }
            return response(answers);
        });

        List<NodeScore> scores = classifier.classifyTargets("保修多久");

        assertEquals(
                List.of(0.95D, 0.90D, 0.85D, 0.80D, 0.75D, 0.70D, 0.65D, 0.60D, 0.40D),
                scores.stream().map(NodeScore::getScore).toList(),
                "两批各 4 个过线分交错排序，一个都不能少；0.30 低于下限被丢");
    }

    @Test
    @DisplayName("任一批失败即整次降级，不按已成功的批给出部分意图")
    void failingBatchDegradesWholeClassification() {
        when(intentNodeRegistry.listClassifiableNodes()).thenReturn(syntheticCandidates(100));
        when(jevClient.evaluate(any(), any()))
                .thenReturn(response(Map.of("n0", 0.95D)))
                .thenThrow(new ModelClientException(
                        "jev 缺少判断 n60 的答案", ModelClientErrorType.INVALID_RESPONSE, null));

        assertTrue(classifier.classifyTargets("保修多久").isEmpty(),
                "第一批的高分意图不能在后续批失败后仍参与定向检索");
    }

    /**
     * 造够量的候选以越过预算；描述长度取真实节点的量级
     */
    private static List<IntentNode> syntheticCandidates(int count) {
        List<IntentNode> nodes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            nodes.add(IntentNode.builder()
                    .id("syn-" + i)
                    .name("合成类目" + i)
                    .fullPath("产品与服务咨询 > 合成域 > 合成类目" + i)
                    // 描述与示例的篇幅对齐线上量级（实测 description 均值 73 字、examples 均值 89 字），
                    // 否则 100 个候选凑不到预算线，分批分支根本走不到
                    .description("这是用于容量验证的合成类目描述，长度与线上意图节点对齐，覆盖范围说明占七十余字，"
                            + "这里补足到同等篇幅便于检验容量" + i)
                    .examples(List.of(
                            "合成问法一需要有足够长度以对齐线上示例的平均篇幅" + i,
                            "合成问法二同样要补足长度才能对齐真实量级" + i,
                            "合成问法三再补一些" + i))
                    .kind(IntentKind.KB)
                    .collectionNames(List.of("bit_policy"))
                    .build());
        }
        return nodes;
    }

    private static JevTarget target() {
        return new JevTarget("http://127.0.0.1/api/v1/systemone", "test-key", "typesafe/jev-1.14");
    }

    private static JevResponse response(Map<String, Double> nouls) {
        return new JevResponse(nouls);
    }

    private static IntentNode node(String id, String name) {
        return node(id, name, name, "说明-" + name, List.of());
    }

    private static IntentNode node(String id, String name, String fullPath, String description, List<String> examples) {
        return IntentNode.builder()
                .id(id)
                .name(name)
                .fullPath(fullPath)
                .description(description)
                .examples(examples)
                .kind(IntentKind.KB)
                .collectionNames(List.of("bit_policy"))
                .build();
    }
}
