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
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 意图澄清判定
 * 意图树层级由用户自行配置，因此只按「根到叶的节点路径」找重名分叉，再由 LLM 确认是否真的需要用户选择
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentGuidanceService {

    /**
     * 参与重名比较的最短名称长度，避免单字名把无关路径判成冲突
     */
    private static final int MIN_COMPARABLE_NAME_LENGTH = 2;

    private final GuidanceProperties guidanceProperties;
    private final IntentNodeRegistry intentNodeRegistry;
    private final PromptTemplateLoader promptTemplateLoader;
    private final AmbiguityLLMChecker ambiguityLLMChecker;

    @RagTraceNode(name = "guidance-detect", type = "GUIDANCE")
    public GuidanceDecision detectAmbiguity(String question, List<SubQuestionIntent> subIntents) {
        //1. 若全局未开启歧义引导，直接结束。
        if (!Boolean.TRUE.equals(guidanceProperties.getEnabled())) {
            return GuidanceDecision.none();
        }

        //2. 规则层找“候选重名路径”，排除大部分无关情况
        AmbiguityGroup group = findAmbiguityGroup(question, subIntents);
        if (group == null || CollUtil.isEmpty(group.ranked())) {
            return GuidanceDecision.none();
        }

        // 真正的“是否歧义”由 LLM 判定
        String prompt = buildPrompt(group.topicName(), group.ranked());
        return GuidanceDecision.prompt(prompt);
    }

    private AmbiguityGroup findAmbiguityGroup(String question, List<SubQuestionIntent> subIntents) {
        //排除多子问题及单意图场景（意图唯一，根本无歧义）。
        if (CollUtil.isEmpty(subIntents) || subIntents.size() != 1) {
            return null;
        }
        List<NodeScore> ranked = rankCandidates(filterCandidates(subIntents.get(0).nodeScores()));
        if (ranked.size() < 2) {
            return null;
        }
        //即使存在多个候选意图，若它们在业务树/分类树路径上无互斥冲突（例如同属于可叠加的次级意图），直接排除
        PathConflict conflict = collectPathConflicts(question, ranked);
        if (conflict == null) {
            return null;
        }
        //仅对满足“单一问题 + 多候选意图 + 静态路径冲突”的极少数疑难请求，调用 LLM 判定真实语义。
        if (!ambiguityLLMChecker.checkAmbiguity(question, conflict.ranked())) {
            log.info("LLM 判定候选路径不构成歧义, 跳过澄清, question={}", question);
            return null;
        }

        return new AmbiguityGroup(conflict.topicName(), trimRankedOptions(conflict.ranked()));
    }

    private List<NodeScore> filterCandidates(List<NodeScore> scores) {
        if (CollUtil.isEmpty(scores)) {
            return List.of();
        }
        return NodeScoreFilters.kb(scores, RAGConstant.INTENT_MIN_SCORE);
    }

    /**
     * 按节点去重并按分数降序，同一节点重复命中时保留高分那条
     */
    private List<NodeScore> rankCandidates(List<NodeScore> candidates) {
        Map<String, NodeScore> bestByNode = new LinkedHashMap<>();
        for (NodeScore candidate : candidates) {
            IntentNode node = candidate.getNode();
            String key = StrUtil.blankToDefault(node.getId(), StrUtil.emptyIfNull(node.getName()));
            bestByNode.merge(key, candidate, (kept, current) -> kept.getScore() >= current.getScore() ? kept : current);
        }
        return bestByNode.values().stream()
                .sorted(Comparator.comparingDouble(NodeScore::getScore).reversed())
                .toList();
    }

    /**
     * 以最高分候选为主候选，收集与它构成路径重名的其它候选
     */
    private PathConflict collectPathConflicts(String question, List<NodeScore> ranked) {
        Map<String, IntentNode> nodeCache = new HashMap<>();
        NodeScore primary = ranked.get(0);
        List<IntentNode> primaryPath = buildNodePath(primary.getNode(), nodeCache);
        String normalizedQuestion = normalizeName(question);

        List<NodeScore> conflicts = new ArrayList<>();
        conflicts.add(primary);
        String topicName = null;
        for (NodeScore other : ranked.subList(1, ranked.size())) {
            List<IntentNode> otherPath = buildNodePath(other.getNode(), nodeCache);
            String hitName = detectConflictName(primaryPath, otherPath, normalizedQuestion);
            if (StrUtil.isBlank(hitName)) {
                continue;
            }
            conflicts.add(other);
            if (topicName == null) {
                topicName = hitName;
            }
        }

        if (conflicts.size() < 2) {
            return null;
        }
        log.info("候选意图路径重名[{}], 调 LLM 确认是否需要澄清, question={}", topicName, question);
        return new PathConflict(topicName, conflicts);
    }

    /**
     * 返回两条路径的冲突名称，无冲突返回 null
     * 叶子重名直接算冲突；分叉后的中间节点重名还要求用户问题里提到了这个名称，否则用户问的并不是这个岔路口
     */
    private String detectConflictName(List<IntentNode> primaryPath, List<IntentNode> otherPath, String normalizedQuestion) {
        if (CollUtil.isEmpty(primaryPath) || CollUtil.isEmpty(otherPath)) {
            return null;
        }

        IntentNode primaryLeaf = primaryPath.get(primaryPath.size() - 1);
        String leafName = normalizeName(primaryLeaf.getName());
        if (isComparableName(leafName) && leafName.equals(normalizeName(otherPath.get(otherPath.size() - 1).getName()))) {
            return primaryLeaf.getName();
        }

        // 公共前缀是同一批真实节点，共享它不构成歧义，只比较分叉之后的部分
        int common = commonPrefixLength(primaryPath, otherPath);
        Set<String> otherNames = otherPath.subList(common, otherPath.size()).stream()
                .map(node -> normalizeName(node.getName()))
                .filter(this::isComparableName)
                .collect(Collectors.toSet());
        for (IntentNode node : primaryPath.subList(common, primaryPath.size())) {
            String name = normalizeName(node.getName());
            if (isComparableName(name) && otherNames.contains(name) && normalizedQuestion.contains(name)) {
                return node.getName();
            }
        }
        return null;
    }

    /**
     * 沿 parentId 上溯出「根 → ... → 候选」的完整节点路径
     * visited 兜住配置错误形成的父子环，父节点缺失时停在已取到的链路上
     */
    private List<IntentNode> buildNodePath(IntentNode node, Map<String, IntentNode> nodeCache) {
        LinkedList<IntentNode> path = new LinkedList<>();
        Set<String> visited = new HashSet<>();
        IntentNode current = node;
        while (current != null) {
            path.addFirst(current);
            visited.add(current.getId());
            String parentId = current.getParentId();
            if (StrUtil.isBlank(parentId) || visited.contains(parentId)) {
                break;
            }
            current = fetchNode(parentId, nodeCache);
        }
        return path;
    }

    /**
     * 按节点 ID 计算最长公共前缀长度
     */
    private int commonPrefixLength(List<IntentNode> left, List<IntentNode> right) {
        int max = Math.min(left.size(), right.size());
        int index = 0;
        while (index < max && isSameNode(left.get(index), right.get(index))) {
            index++;
        }
        return index;
    }

    private boolean isSameNode(IntentNode left, IntentNode right) {
        return StrUtil.isNotBlank(left.getId()) && left.getId().equals(right.getId());
    }

    private boolean isComparableName(String normalizedName) {
        return StrUtil.isNotBlank(normalizedName) && normalizedName.length() >= MIN_COMPARABLE_NAME_LENGTH;
    }

    /**
     * 本次判定内缓存注册表读取结果，缺失的父节点同样缓存，避免反复回表
     */
    private IntentNode fetchNode(String nodeId, Map<String, IntentNode> nodeCache) {
        if (nodeCache.containsKey(nodeId)) {
            return nodeCache.get(nodeId);
        }
        IntentNode node = intentNodeRegistry.getNodeById(nodeId);
        nodeCache.put(nodeId, node);
        return node;
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

    /**
     * 待 LLM 确认的路径重名候选组
     */
    private record PathConflict(String topicName, List<NodeScore> ranked) {
    }

    private record AmbiguityGroup(String topicName, List<NodeScore> ranked) {
    }
}
