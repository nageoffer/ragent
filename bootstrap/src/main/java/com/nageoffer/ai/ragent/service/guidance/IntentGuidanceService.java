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

package com.nageoffer.ai.ragent.service.guidance;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.config.GuidanceProperties;
import com.nageoffer.ai.ragent.constant.RAGConstant;
import com.nageoffer.ai.ragent.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.enums.IntentLevel;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntentGuidanceService {

    private final GuidanceProperties guidanceProperties;
    private final IntentNodeRegistry intentNodeRegistry;
    private final GuidanceStateStore stateStore;
    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;

    public GuidanceDecision handleExistingSession(String conversationId, String userId, String question) {
        if (!Boolean.TRUE.equals(guidanceProperties.getEnabled())) {
            return GuidanceDecision.none();
        }

        GuidanceState state = loadState(conversationId, userId);
        if (state == null || CollUtil.isEmpty(state.getOptionIds())) {
            return GuidanceDecision.none();
        }

        SelectionDecision selection = resolveSelectionDecision(question, state);
        if (selection.action() == SelectionAction.NEW_QUESTION) {
            clearState(conversationId, userId);
            return GuidanceDecision.none();
        }
        if (selection.action() != SelectionAction.SELECT) {
            return GuidanceDecision.prompt(buildPrompt(state, state.getOptionIds()));
        }

        List<String> selectedIds = mapIndexesToIds(selection.selectedIndexes(), state.getOptionIds());
        if (CollUtil.isEmpty(selectedIds)) {
            return GuidanceDecision.prompt(buildPrompt(state, state.getOptionIds()));
        }

        return applySelection(conversationId, userId, state, selectedIds);
    }

    public GuidanceDecision detectAmbiguity(String question,
                                                 List<SubQuestionIntent> subIntents,
                                                 String conversationId,
                                                 String userId) {
        if (!Boolean.TRUE.equals(guidanceProperties.getEnabled())) {
            return GuidanceDecision.none();
        }
        if (CollUtil.isEmpty(subIntents) || subIntents.size() != 1) {
            return GuidanceDecision.none();
        }

        List<NodeScore> candidates = subIntents.get(0).nodeScores().stream()
                .filter(ns -> ns.getScore() >= RAGConstant.INTENT_MIN_SCORE)
                .filter(ns -> ns.getNode() != null && ns.getNode().isKB())
                .toList();
        if (candidates.size() < 2) {
            return GuidanceDecision.none();
        }

        Map<String, List<NodeScore>> grouped = candidates.stream()
                .filter(ns -> StrUtil.isNotBlank(ns.getNode().getName()))
                .collect(Collectors.groupingBy(ns -> normalizeName(ns.getNode().getName())));

        Optional<Map.Entry<String, List<NodeScore>>> ambiguousGroup = grouped.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> Map.entry(entry.getKey(), sortByScore(entry.getValue())))
                .filter(entry -> passScoreRatio(entry.getValue()))
                .filter(entry -> hasMultipleSystems(entry.getValue()))
                .max(Comparator.comparingDouble(entry -> entry.getValue().get(0).getScore()));

        if (ambiguousGroup.isEmpty()) {
            return GuidanceDecision.none();
        }

        List<NodeScore> groupScores = ambiguousGroup.get().getValue();
        List<String> leafIds = groupScores.stream()
                .map(ns -> ns.getNode().getId())
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        if (leafIds.size() < 2) {
            return GuidanceDecision.none();
        }

        String topicName = Optional.ofNullable(groupScores.get(0).getNode().getName())
                .orElse(ambiguousGroup.get().getKey());
        GuidanceState state = buildInitialState(question, leafIds, topicName);
        if (state == null || CollUtil.isEmpty(state.getOptionIds())) {
            return GuidanceDecision.none();
        }

        saveState(conversationId, userId, state);
        return GuidanceDecision.prompt(buildPrompt(state, state.getOptionIds()));
    }

    private GuidanceDecision applySelection(String conversationId,
                                                 String userId,
                                                 GuidanceState state,
                                                 List<String> selectedIds) {
        if (selectedIds.size() == 1) {
            return applySingleSelection(conversationId, userId, state, selectedIds.get(0));
        }
        return applyMultiSelection(conversationId, userId, state, selectedIds);
    }

    private GuidanceDecision applySingleSelection(String conversationId,
                                                       String userId,
                                                       GuidanceState state,
                                                       String selectedId) {
        List<String> filteredLeafs = filterLeafs(state.getCandidateLeafIds(), selectedId);
        if (filteredLeafs.isEmpty()) {
            return GuidanceDecision.prompt(buildPrompt(state, state.getOptionIds()));
        }

        String currentNodeId = selectedId;
        List<String> optionIds = computeNextOptions(currentNodeId, filteredLeafs);
        while (optionIds.size() == 1) {
            currentNodeId = optionIds.get(0);
            filteredLeafs = filterLeafs(filteredLeafs, currentNodeId);
            if (filteredLeafs.size() == 1) {
                return resolveAndClear(conversationId, userId, state, filteredLeafs.get(0));
            }
            optionIds = computeNextOptions(currentNodeId, filteredLeafs);
        }

        if (optionIds.isEmpty()) {
            if (isLeaf(currentNodeId)) {
                return resolveAndClear(conversationId, userId, state, currentNodeId);
            }
            if (filteredLeafs.size() == 1) {
                return resolveAndClear(conversationId, userId, state, filteredLeafs.get(0));
            }
            return GuidanceDecision.prompt(buildPrompt(state, state.getOptionIds()));
        }

        state.setCurrentNodeId(currentNodeId);
        state.setCandidateLeafIds(filteredLeafs);
        state.setOptionIds(trimOptions(optionIds));
        saveState(conversationId, userId, state);
        return GuidanceDecision.prompt(buildPrompt(state, state.getOptionIds()));
    }

    private GuidanceDecision applyMultiSelection(String conversationId,
                                                      String userId,
                                                      GuidanceState state,
                                                      List<String> selectedIds) {
        Set<String> selectedLeafs = new java.util.LinkedHashSet<>();
        for (String selectedId : selectedIds) {
            selectedLeafs.addAll(filterLeafs(state.getCandidateLeafIds(), selectedId));
        }
        if (selectedLeafs.isEmpty()) {
            return GuidanceDecision.prompt(buildPrompt(state, state.getOptionIds()));
        }
        return resolveAndClear(conversationId, userId, state, new ArrayList<>(selectedLeafs));
    }

    private GuidanceDecision resolveAndClear(String conversationId,
                                                  String userId,
                                                  GuidanceState state,
                                                  String leafId) {
        return resolveAndClear(conversationId, userId, state, List.of(leafId));
    }

    private GuidanceDecision resolveAndClear(String conversationId,
                                                  String userId,
                                                  GuidanceState state,
                                                  List<String> leafIds) {
        clearState(conversationId, userId);
        if (CollUtil.isEmpty(leafIds)) {
            return GuidanceDecision.none();
        }
        List<IntentNode> nodes = leafIds.stream()
                .map(intentNodeRegistry::getNodeById)
                .filter(Objects::nonNull)
                .toList();
        if (nodes.isEmpty()) {
            return GuidanceDecision.none();
        }
        return GuidanceDecision.resolved(state.getOriginalQuestion(), nodes);
    }

    private GuidanceState buildInitialState(String question, List<String> leafIds, String topicName) {
        List<List<String>> paths = leafIds.stream()
                .map(this::pathRootToLeaf)
                .filter(CollUtil::isNotEmpty)
                .toList();
        if (paths.size() < 2) {
            return null;
        }

        String lcaId = findLca(paths);
        if (StrUtil.isBlank(lcaId)) {
            return null;
        }

        List<String> optionIds = computeNextOptions(lcaId, leafIds);
        if (optionIds.size() <= 1) {
            return null;
        }

        return GuidanceState.builder()
                .originalQuestion(question)
                .topicName(topicName)
                .currentNodeId(lcaId)
                .candidateLeafIds(leafIds)
                .optionIds(trimOptions(optionIds))
                .build();
    }

    private List<String> computeNextOptions(String currentNodeId, List<String> leafIds) {
        Set<String> optionSet = new HashSet<>();
        Map<String, String> nameById = new HashMap<>();
        for (String leafId : leafIds) {
            List<String> path = pathRootToLeaf(leafId);
            if (path.isEmpty()) {
                continue;
            }
            int idx = path.indexOf(currentNodeId);
            if (idx < 0 || idx >= path.size() - 1) {
                continue;
            }
            String nextId = path.get(idx + 1);
            if (optionSet.add(nextId)) {
                IntentNode node = intentNodeRegistry.getNodeById(nextId);
                if (node != null) {
                    nameById.put(nextId, node.getName());
                }
            }
        }

        return optionSet.stream()
                .sorted(Comparator.comparing(id -> Optional.ofNullable(nameById.get(id)).orElse(id)))
                .toList();
    }

    private List<String> filterLeafs(List<String> leafIds, String ancestorId) {
        if (CollUtil.isEmpty(leafIds)) {
            return List.of();
        }
        return leafIds.stream()
                .filter(id -> isDescendantOrSelf(ancestorId, id))
                .toList();
    }

    private boolean isDescendantOrSelf(String ancestorId, String nodeId) {
        if (StrUtil.isBlank(ancestorId) || StrUtil.isBlank(nodeId)) {
            return false;
        }
        String current = nodeId;
        while (StrUtil.isNotBlank(current)) {
            if (ancestorId.equals(current)) {
                return true;
            }
            IntentNode node = intentNodeRegistry.getNodeById(current);
            if (node == null || StrUtil.isBlank(node.getParentId())) {
                return false;
            }
            current = node.getParentId();
        }
        return false;
    }

    private List<String> pathRootToLeaf(String leafId) {
        List<String> reversed = new ArrayList<>();
        String current = leafId;
        while (StrUtil.isNotBlank(current)) {
            reversed.add(current);
            IntentNode node = intentNodeRegistry.getNodeById(current);
            if (node == null || StrUtil.isBlank(node.getParentId())) {
                break;
            }
            current = node.getParentId();
        }
        List<String> path = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            path.add(reversed.get(i));
        }
        return path;
    }

    private String findLca(List<List<String>> paths) {
        if (paths.isEmpty()) {
            return null;
        }
        List<String> first = paths.get(0);
        String lca = null;
        for (int i = 0; i < first.size(); i++) {
            String candidate = first.get(i);
            for (int j = 1; j < paths.size(); j++) {
                List<String> path = paths.get(j);
                if (i >= path.size() || !Objects.equals(candidate, path.get(i))) {
                    return lca;
                }
            }
            lca = candidate;
        }
        return lca;
    }

    private SelectionDecision resolveSelectionDecision(String question, GuidanceState state) {
        if (StrUtil.isBlank(question) || state == null || CollUtil.isEmpty(state.getOptionIds())) {
            return SelectionDecision.repeat();
        }
        String options = buildOptionList(state.getOptionIds());
        String originalQuestion = StrUtil.blankToDefault(state.getOriginalQuestion(), "");
        String systemPrompt = promptTemplateLoader.render(
                RAGConstant.GUIDANCE_SELECTION_PROMPT_PATH,
                Map.of(
                        "original_question", originalQuestion,
                        "options", options
                )
        );
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system(systemPrompt),
                        ChatMessage.user(question)
                ))
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();
        String raw = llmService.chat(request);
        return parseSelectionDecision(raw);
    }

    private SelectionDecision parseSelectionDecision(String raw) {
        if (StrUtil.isBlank(raw)) {
            return SelectionDecision.repeat();
        }
        String payload = extractJsonObject(raw.trim());
        try {
            JsonObject obj = JsonParser.parseString(payload).getAsJsonObject();
            String actionRaw = obj.has("action") ? obj.get("action").getAsString() : null;
            SelectionAction action = SelectionAction.from(actionRaw);
            List<Integer> indexes = parseSelectedIndexes(obj);
            return new SelectionDecision(action, indexes);
        } catch (Exception ex) {
            log.warn("解析引导式问答选择失败, 原始内容: {}", raw, ex);
            return SelectionDecision.repeat();
        }
    }

    private List<Integer> parseSelectedIndexes(JsonObject obj) {
        if (obj == null) {
            return List.of();
        }
        JsonArray arr = null;
        if (obj.has("selected_indexes") && obj.get("selected_indexes").isJsonArray()) {
            arr = obj.getAsJsonArray("selected_indexes");
        } else if (obj.has("selectedIndexes") && obj.get("selectedIndexes").isJsonArray()) {
            arr = obj.getAsJsonArray("selectedIndexes");
        }
        if (arr == null) {
            return List.of();
        }
        List<Integer> indexes = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonPrimitive()) {
                continue;
            }
            try {
                indexes.add(el.getAsInt());
            } catch (Exception ignored) {
                // ignore invalid numbers
            }
        }
        return indexes;
    }

    private List<String> mapIndexesToIds(List<Integer> indexes, List<String> optionIds) {
        if (CollUtil.isEmpty(indexes) || CollUtil.isEmpty(optionIds)) {
            return List.of();
        }
        Set<String> selected = new java.util.LinkedHashSet<>();
        for (Integer idx : indexes) {
            if (idx == null || idx < 1 || idx > optionIds.size()) {
                continue;
            }
            selected.add(optionIds.get(idx - 1));
        }
        return new ArrayList<>(selected);
    }

    private boolean passScoreRatio(List<NodeScore> group) {
        if (group.size() < 2) {
            return false;
        }
        double top = group.get(0).getScore();
        double second = group.get(1).getScore();
        if (top <= 0) {
            return false;
        }
        double ratio = second / top;
        return ratio >= Optional.ofNullable(guidanceProperties.getAmbiguityScoreRatio()).orElse(0.0D);
    }

    private boolean hasMultipleSystems(List<NodeScore> group) {
        Set<String> systems = group.stream()
                .map(NodeScore::getNode)
                .map(this::resolveSystemNodeId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
        return systems.size() > 1;
    }

    private String resolveSystemNodeId(IntentNode node) {
        if (node == null) {
            return "";
        }
        IntentNode current = node;
        IntentNode parent = fetchParent(current);
        while (current != null) {
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
        return node.getId();
    }

    private IntentNode fetchParent(IntentNode node) {
        if (node == null || StrUtil.isBlank(node.getParentId())) {
            return null;
        }
        return intentNodeRegistry.getNodeById(node.getParentId());
    }

    private boolean isLeaf(String nodeId) {
        IntentNode node = intentNodeRegistry.getNodeById(nodeId);
        return node != null && node.isLeaf();
    }

    private List<NodeScore> sortByScore(List<NodeScore> scores) {
        return scores.stream()
                .sorted(Comparator.comparingDouble(NodeScore::getScore).reversed())
                .toList();
    }

    private List<String> trimOptions(List<String> optionIds) {
        int maxOptions = Optional.ofNullable(guidanceProperties.getMaxOptions()).orElse(optionIds.size());
        if (optionIds.size() <= maxOptions) {
            return optionIds;
        }
        return optionIds.subList(0, maxOptions);
    }

    private String buildPrompt(GuidanceState state, List<String> optionIds) {
        String topicName = StrUtil.isBlank(state.getTopicName()) ? "" : state.getTopicName();
        IntentNode current = intentNodeRegistry.getNodeById(state.getCurrentNodeId());
        StringBuilder sb = new StringBuilder();

        if (current != null && current.getLevel() == IntentLevel.DOMAIN && StrUtil.isNotBlank(topicName)) {
            sb.append("我可以帮你查询").append(topicName).append("相关内容。请问你是想了解哪个系统的")
                    .append(topicName).append("？\n");
        } else if (current != null) {
            String currentName = StrUtil.isBlank(current.getName()) ? current.getId() : current.getName();
            if (StrUtil.isBlank(topicName) || Objects.equals(currentName, topicName)) {
                sb.append("请问你想了解").append(currentName).append("下的哪个方向？\n");
            } else {
                sb.append("请问你想了解").append(currentName).append("下的哪个").append(topicName).append("方向？\n");
            }
        } else if (StrUtil.isNotBlank(topicName)) {
            sb.append("我可以帮你查询").append(topicName).append("相关内容，请从以下选项中选择：\n");
        } else {
            sb.append("请从以下选项中选择：\n");
        }

        for (int i = 0; i < optionIds.size(); i++) {
            String id = optionIds.get(i);
            IntentNode node = intentNodeRegistry.getNodeById(id);
            String name = node == null || StrUtil.isBlank(node.getName()) ? id : node.getName();
            sb.append(i + 1).append(") ").append(name).append("\n");
        }
        sb.append("请回复数字选择（可多选，如 1,2），或回复“都/全部”，也可直接描述更多细节。");
        return sb.toString();
    }

    private String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.trim().toLowerCase(Locale.ROOT);
        return cleaned.replaceAll("[\\p{Punct}\\s]+", "");
    }

    private String buildOptionList(List<String> optionIds) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < optionIds.size(); i++) {
            String id = optionIds.get(i);
            IntentNode node = intentNodeRegistry.getNodeById(id);
            String name = node == null || StrUtil.isBlank(node.getName()) ? id : node.getName();
            sb.append(i + 1).append(") ").append(name).append("\n");
        }
        return sb.toString().trim();
    }

    private String extractJsonObject(String raw) {
        if (StrUtil.isBlank(raw)) {
            return raw;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private GuidanceState loadState(String conversationId, String userId) {
        return stateStore.load(conversationId, userId);
    }

    private void saveState(String conversationId, String userId, GuidanceState state) {
        stateStore.save(conversationId, userId, state);
    }

    private void clearState(String conversationId, String userId) {
        stateStore.clear(conversationId, userId);
    }

    private enum SelectionAction {
        SELECT,
        NEW_QUESTION,
        REPEAT;

        static SelectionAction from(String raw) {
            if (raw == null) {
                return REPEAT;
            }
            String value = raw.trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "select" -> SELECT;
                case "new_question" -> NEW_QUESTION;
                case "repeat" -> REPEAT;
                default -> REPEAT;
            };
        }
    }

    private record SelectionDecision(SelectionAction action, List<Integer> selectedIndexes) {
        static SelectionDecision repeat() {
            return new SelectionDecision(SelectionAction.REPEAT, List.of());
        }
    }
}
