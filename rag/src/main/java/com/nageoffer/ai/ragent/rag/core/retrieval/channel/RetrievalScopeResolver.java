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

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检索作用域解析器
 * <p>
 * 由引擎按子问题各算一次放进 {@link SearchContext}，各通道只读不判，
 * 避免同一子问题里向量走全局、关键词走定向这类作用域打架
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalScopeResolver {

    private final SearchChannelProperties properties;
    private final KbCollectionProvider kbCollectionProvider;

    /**
     * 解析本次请求的检索作用域
     * <p>
     * 只看 KB 意图最高分：达到置信阈值才收窄，意图个数不参与判定——多一个低分意图不应让系统更准确
     */
    public RetrievalScope resolve(List<SubQuestionIntent> subIntents) {
        List<String> activeCollections = kbCollectionProvider.listActiveCollections();
        List<NodeScore> kbIntents = extractKbIntents(subIntents);
        double topScore = kbIntents.stream().mapToDouble(NodeScore::getScore).max().orElse(0.0);

        if (kbIntents.isEmpty()) {
            log.info("未识别出有效 KB 意图，检索走全局作用域");
            return RetrievalScope.global(topScore, activeCollections);
        }
        double threshold = properties.getScope().getConfidenceThreshold();
        if (topScore < threshold) {
            log.info("KB 意图置信度过低（{} < {}），检索走全局作用域", topScore, threshold);
            return RetrievalScope.global(topScore, activeCollections);
        }

        Set<String> bound = new LinkedHashSet<>(NodeScoreFilters.kbCollections(kbIntents));
        // 意图绑定与知识库表是两套数据，删库不回写意图节点：悬空库名对向量 / ES 只是查空库，
        // 图谱却会按库名匹配到已删库的图谱残留，故绑定集先与有效库求交
        Set<String> targets = new LinkedHashSet<>(bound);
        targets.retainAll(activeCollections);
        if (targets.isEmpty()) {
            log.warn("KB 意图绑定的知识库均已失效（{}），检索退化为全局作用域", bound);
            return RetrievalScope.global(topScore, activeCollections);
        }
        if (targets.size() < bound.size()) {
            bound.removeAll(targets);
            log.warn("KB 意图绑定中已失效的知识库（{}）不参与检索", bound);
        }
        List<String> supplement = activeCollections.stream()
                .filter(collection -> !targets.contains(collection))
                .toList();
        log.info("KB 意图置信度充足（{}），检索收窄到 {} 个命中库，补充范围 {} 个库", topScore, targets.size(), supplement.size());
        return new RetrievalScope(true, topScore, kbIntents, List.copyOf(targets), supplement);
    }

    /**
     * 提取达到最低分、真正绑定了 collection 且按意图节点去重的 KB 意图
     * <p>
     * 去重是必须的：意图按子问题分别识别，多个子问题命中同一节点会产出多条 NodeScore，
     * 而定向路按 NodeScore 逐条 fan-out —— 不去重则同一库被查多次，同一 chunk 在通道原始列表里占多个名次，
     * 下游 RRF 按名次累加会把它的分数翻倍；同时也会虚高「通道产能」，连带把补充路名额算大
     * <p>
     * 绑定为空的意图既检索不到东西，也不该拉高置信度判定
     */
    private List<NodeScore> extractKbIntents(List<SubQuestionIntent> subIntents) {
        if (CollUtil.isEmpty(subIntents)) {
            return List.of();
        }
        double minScore = properties.getScope().getMinIntentScore();
        List<NodeScore> allScores = subIntents.stream()
                .flatMap(si -> si.nodeScores().stream())
                .toList();
        return NodeScoreFilters.kb(allScores, minScore).stream()
                .filter(nodeScore -> !nodeScore.getNode().getEffectiveCollectionNames().isEmpty())
                .collect(Collectors.toMap(
                        nodeScore -> nodeScore.getNode().getId(),
                        nodeScore -> nodeScore,
                        // 同一节点保留最高分：分数只用于置信度判定，取最高才是该节点的真实命中强度
                        (left, right) -> left.getScore() >= right.getScore() ? left : right,
                        LinkedHashMap::new))
                .values().stream()
                .toList();
    }
}
