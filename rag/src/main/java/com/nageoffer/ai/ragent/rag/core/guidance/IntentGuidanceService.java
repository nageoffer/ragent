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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.config.GuidanceProperties;
import com.nageoffer.ai.ragent.rag.constant.RAGConstant;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentLevel;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntentGuidanceService {

    private final GuidanceProperties guidanceProperties;
    private final IntentNodeRegistry intentNodeRegistry;
    private final PromptTemplateLoader promptTemplateLoader;
    private final AmbiguityLLMChecker ambiguityLLMChecker;

    @RagTraceNode(name = "guidance-detect", type = "GUIDANCE")
    public GuidanceDecision detectAmbiguity(String question, List<SubQuestionIntent> subIntents) {
        if (!Boolean.TRUE.equals(guidanceProperties.getEnabled())) {
            return GuidanceDecision.none();
        }

        AmbiguityGroup group = findAmbiguityGroup(question, subIntents);
        if (group == null || CollUtil.isEmpty(group.ranked())) {
            return GuidanceDecision.none();
        }

        String prompt = buildPrompt(group.topicName(), group.ranked());
        return GuidanceDecision.prompt(prompt);
    }

    private AmbiguityGroup findAmbiguityGroup(String question, List<SubQuestionIntent> subIntents) {
        if (CollUtil.isEmpty(subIntents) || subIntents.size() != 1) {
            return null;
        }

        List<NodeScore> candidates = filterCandidates(subIntents.get(0).nodeScores());
        if (candidates.size() < 2) {
            return null;
        }

        Map<String, NodeScore> systemBest = candidates.stream()
                .filter(ns -> StrUtil.isNotBlank(resolveSystemNodeId(ns.getNode())))
                .collect(Collectors.toMap(
                        ns -> resolveSystemNodeId(ns.getNode()),
                        ns -> ns,
                        (a, b) -> a.getScore() >= b.getScore() ? a : b
                ));

        List<NodeScore> ranked = systemBest.values().stream()
                .sorted(Comparator.comparingDouble(NodeScore::getScore).reversed())
                .toList();

        if (ranked.size() < 2) {
            return null;
        }

        if (shouldSkipGuidance(question, ranked)) {
            return null;
        }

        if (!confirmAmbiguity(question, ranked)) {
            return null;
        }

        List<NodeScore> trimmedRanked = trimRankedOptions(ranked);
        String topicName = trimmedRanked.get(0).getNode().getName();
        return new AmbiguityGroup(topicName, trimmedRanked);
    }

    private boolean shouldSkipGuidance(String question, List<NodeScore> ranked) {
        double top = ranked.get(0).getScore();
        if (top <= 0) {
            return true;
        }

        // 快速通道 1：分数比值低于边界下限，意图明确
        double ratio = ranked.get(1).getScore() / top;
        double threshold = Optional.ofNullable(guidanceProperties.getAmbiguityScoreRatio()).orElse(0.8D);
        double margin = Optional.ofNullable(guidanceProperties.getAmbiguityMargin()).orElse(0.15D);
        if (ratio < threshold - margin) {
            log.debug("分数比值(ratio={})低于边界下限({}), 跳过澄清", ratio, threshold - margin);
            return true;
        }

        // 快速通道 2：用户问题中显式提到了某个系统的 DOMAIN 级名称
        if (StrUtil.isNotBlank(question)) {
            List<String> domainNames = ranked.stream()
                    .map(ns -> resolveDomainName(ns.getNode()))
                    .filter(StrUtil::isNotBlank)
                    .distinct()
                    .toList();

            String normalizedQuestion = normalizeName(question);
            for (String name : domainNames) {
                for (String alias : buildSystemAliases(name)) {
                    if (alias.length() >= 2 && normalizedQuestion.contains(alias)) {
                        log.debug("用户问题包含系统名[{}], 跳过澄清", name);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean confirmAmbiguity(String question, List<NodeScore> ranked) {
        double top = ranked.get(0).getScore();
        double second = ranked.get(1).getScore();
        if (top <= 0) {
            return false;
        }

        double ratio = second / top;
        double threshold = guidanceProperties.getAmbiguityScoreRatio();
        double margin = guidanceProperties.getAmbiguityMargin();

        if (ratio >= threshold) {
            log.info("分数比值(ratio={})超过阈值({}), 判定为歧义", ratio, threshold);
            return true;
        }

        if (ratio >= threshold - margin) {
            log.info("分数比值(ratio={})在边界区间[{}, {}), 调 LLM 确认", ratio, threshold - margin, threshold);
            return ambiguityLLMChecker.checkAmbiguity(question, ranked);
        }

        // ratio < threshold - margin 但 > skipThreshold，不触发澄清
        return false;
    }

    private List<NodeScore> filterCandidates(List<NodeScore> scores) {
        if (CollUtil.isEmpty(scores)) {
            return List.of();
        }
        return NodeScoreFilters.kb(scores, RAGConstant.INTENT_MIN_SCORE);
    }

    private String resolveDomainName(IntentNode node) {
        if (node == null) {
            return "";
        }
        IntentNode current = node;
        while (current != null) {
            if (current.getLevel() == IntentLevel.DOMAIN) {
                return StrUtil.blankToDefault(current.getName(), "");
            }
            current = fetchParent(current);
        }
        return "";
    }

    private List<String> buildSystemAliases(String systemName) {
        if (StrUtil.isBlank(systemName)) {
            return List.of();
        }
        String normalized = normalizeName(systemName);
        List<String> aliases = new ArrayList<>();
        if (StrUtil.isNotBlank(normalized)) {
            aliases.add(normalized);
        }
        return aliases;
    }

    private String resolveSystemNodeId(IntentNode node) {
        if (node == null) {
            return "";
        }
        IntentNode current = node;
        IntentNode parent = fetchParent(current);
        for (; ; ) {
            IntentLevel level = current.getLevel();
            if (level == IntentLevel.CATEGORY && (parent == null || parent.getLevel() == IntentLevel.DOMAIN)) {
                return current.getId();
            }
            if (parent == null) {
                return current.getId();
            }
            current = parent;
            parent = fetchParent(current);
        }
    }

    private IntentNode fetchParent(IntentNode node) {
        if (node == null || StrUtil.isBlank(node.getParentId())) {
            return null;
        }
        return intentNodeRegistry.getNodeById(node.getParentId());
    }

    private List<NodeScore> trimRankedOptions(List<NodeScore> ranked) {
        int maxOptions = Optional.ofNullable(guidanceProperties.getMaxOptions()).orElse(ranked.size());
        if (ranked.size() <= maxOptions) {
            return ranked;
        }
        return ranked.subList(0, maxOptions);
    }

    private String buildPrompt(String topicName, List<NodeScore> ranked) {
        String options = renderOptions(ranked);
        return promptTemplateLoader.render(
                RAGConstant.GUIDANCE_PROMPT_PATH,
                Map.of(
                        "topic_name", StrUtil.blankToDefault(topicName, ""),
                        "options", options
                )
        );
    }

    private String renderOptions(List<NodeScore> ranked) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ranked.size(); i++) {
            IntentNode node = ranked.get(i).getNode();
            String display = resolveOptionDisplay(node);
            sb.append(i + 1).append(") ").append(display).append("\n");
        }
        return sb.toString().trim();
    }

    private String resolveOptionDisplay(IntentNode node) {
        if (node == null) {
            return "";
        }
        if (StrUtil.isNotBlank(node.getFullPath())) {
            return node.getFullPath();
        }
        return StrUtil.blankToDefault(node.getName(), node.getId());
    }

    private String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.trim().toLowerCase(Locale.ROOT);
        return cleaned.replaceAll("[\\p{Punct}\\s]+", "");
    }

    private record AmbiguityGroup(String topicName, List<NodeScore> ranked) {
    }
}
