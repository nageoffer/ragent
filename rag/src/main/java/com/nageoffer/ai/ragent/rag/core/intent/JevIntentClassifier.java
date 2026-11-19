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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.jev.JevClient;
import com.nageoffer.ai.ragent.infra.jev.JevNoulQuestion;
import com.nageoffer.ai.ragent.infra.jev.JevRequest;
import com.nageoffer.ai.ragent.infra.jev.JevTarget;
import com.nageoffer.ai.ragent.rag.config.IntentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jev 意图分类器
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(prefix = "rag.intent", name = "type", havingValue = "jev")
public class JevIntentClassifier implements IntentClassifier {

    /**
     * Jev 上下文 32K，单请求使用 60%
     */
    private static final int MAX_REQUEST_TOKENS = 19_200;

    /**
     * 每字符的 token 数，中文 payload 实测值（3529 字符对应 3794 token）；换语种要重新标定
     */
    private static final double TOKENS_PER_CHAR = 1.075D;

    /**
     * 每个候选类目独立判断，分数越高表示越匹配，支持多意图
     */
    private static final String INSTRUCTIONS = "用户问题属于类目「%s」";
    private static final String TRUE_CRITERIA = "类目「%s」的覆盖范围说明：%s";
    private static final String EXAMPLES_SUFFIX = "。典型问法：%s";
    private static final String FALSE_CRITERIA = "用户问题中没有任何诉求属于该类目的覆盖范围";
    private static final String NO_DESCRIPTION = "未提供";

    private final IntentNodeRegistry intentNodeRegistry;
    private final JevClient jevClient;
    private final IntentProperties intentProperties;

    /**
     * 构造期解析一次：配置运行期不会变，缺失即启动失败，调用期不再重复校验
     */
    private final JevTarget target;

    public JevIntentClassifier(IntentNodeRegistry intentNodeRegistry,
                               JevClient jevClient,
                               IntentProperties intentProperties) {
        this.intentNodeRegistry = intentNodeRegistry;
        this.jevClient = jevClient;
        this.intentProperties = intentProperties;
        this.target = jevClient.requireTarget();
    }

    @Override
    public List<NodeScore> classifyTargets(String question) {
        List<IntentNode> candidates = intentNodeRegistry.listClassifiableNodes();
        if (CollUtil.isEmpty(candidates)) {
            return List.of();
        }
        Map<String, IntentNode> candidateByKey = indexByKey(candidates);
        List<JevNoulQuestion> questions = candidateByKey.entrySet().stream()
                .map(entry -> buildQuestion(entry.getKey(), entry.getValue()))
                .toList();

        long startAt = System.currentTimeMillis();
        List<List<JevNoulQuestion>> batches = splitByBudget(question, questions);
        Map<String, Double> nouls = new LinkedHashMap<>();
        try {
            for (List<JevNoulQuestion> batch : batches) {
                nouls.putAll(jevClient.evaluate(new JevRequest(question, batch), target).nouls());
            }
        } catch (RuntimeException e) {
            String reason = e instanceof ModelClientException ex ? String.valueOf(ex.getErrorType()) : "UNEXPECTED";
            log.warn("Jev 意图识别降级为空意图, 原因={}, 候选数={}, 耗时={}ms",
                    reason, candidates.size(), System.currentTimeMillis() - startAt, e);
            return List.of();
        }

        double minNoul = intentProperties.getJev().getMinNoul();
        return toScores(nouls, candidateByKey, minNoul);
    }

    /**
     * 给候选编本地序号
     */
    private static Map<String, IntentNode> indexByKey(List<IntentNode> candidates) {
        Map<String, IntentNode> byKey = new LinkedHashMap<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            byKey.put("n" + i, candidates.get(i));
        }
        return byKey;
    }

    /**
     * 过线的候选按概率降序出分
     * Noul 与大模型自报的 score 不是一套量纲，不缩放、不归一化
     */
    private static List<NodeScore> toScores(Map<String, Double> nouls,
                                            Map<String, IntentNode> candidateByKey,
                                            double minNoul) {
        return nouls.entrySet().stream()
                .filter(entry -> entry.getValue() >= minNoul)
                .map(entry -> new NodeScore(candidateByKey.get(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparingDouble(NodeScore::getScore).reversed())
                .toList();
    }

    /**
     * 按预估 token 切批，不超预算时只有一批
     */
    private List<List<JevNoulQuestion>> splitByBudget(String state, List<JevNoulQuestion> questions) {
        int stateTokens = estimateTokens(state);
        int questionTokens = questions.stream().mapToInt(JevIntentClassifier::estimateTokens).sum();
        if (stateTokens + questionTokens <= MAX_REQUEST_TOKENS) {
            return List.of(questions);
        }

        // state 每批都要重发，所以从每批预算里先扣掉它
        int perBatchBudget = MAX_REQUEST_TOKENS - stateTokens;
        List<List<JevNoulQuestion>> batches = new ArrayList<>();
        List<JevNoulQuestion> batch = new ArrayList<>();
        int used = 0;
        for (JevNoulQuestion each : questions) {
            int cost = estimateTokens(each);
            if (!batch.isEmpty() && used + cost > perBatchBudget) {
                batches.add(List.copyOf(batch));
                batch.clear();
                used = 0;
            }
            batch.add(each);
            used += cost;
        }
        if (!batch.isEmpty()) {
            batches.add(List.copyOf(batch));
        }
        log.info("Jev 判断超出单请求预算，切成 {} 批串行发送, 候选数={}, 预估={}token",
                batches.size(), questions.size(), stateTokens + questionTokens);
        return batches;
    }

    private static int estimateTokens(JevNoulQuestion question) {
        return estimateTokens(question.key())
                + estimateTokens(question.instructions())
                + estimateTokens(question.trueCriteria())
                + estimateTokens(question.falseCriteria());
    }

    /**
     * 按字符数估算 token
     */
    private static int estimateTokens(String text) {
        return (int) Math.ceil(StrUtil.length(text) * TOKENS_PER_CHAR);
    }

    /**
     * 把候选节点写成一条命题
     * 判的是「问题归不归这一类」，不是「知识库里有没有答案」：Jev 看不到知识正文
     */
    private static JevNoulQuestion buildQuestion(String key, IntentNode node) {
        String criteria = TRUE_CRITERIA.formatted(node.getName(),
                StrUtil.blankToDefault(node.getDescription(), NO_DESCRIPTION));
        if (CollUtil.isNotEmpty(node.getExamples())) {
            criteria += EXAMPLES_SUFFIX.formatted(String.join(" / ", node.getExamples()));
        }
        return new JevNoulQuestion(
                key,
                INSTRUCTIONS.formatted(StrUtil.blankToDefault(node.getFullPath(), node.getName())),
                criteria,
                FALSE_CRITERIA);
    }
}
