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

package com.nageoffer.ai.ragent.rag.model;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.config.AIModelProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ModelSelector {

    private final AIModelProperties properties;
    private final ModelHealthStore healthStore;

    public List<ModelTarget> selectChatCandidates(Boolean deepThinking) {
        AIModelProperties.ModelGroup group = properties.getChat();
        if (group == null) {
            return List.of();
        }

        String firstChoiceModelId = resolveFirstChoiceModel(group, deepThinking);
        return selectCandidates(group, firstChoiceModelId, deepThinking);
    }

    public List<ModelTarget> selectEmbeddingCandidates() {
        return selectCandidates(properties.getEmbedding());
    }

    public List<ModelTarget> selectRerankCandidates() {
        return selectCandidates(properties.getRerank());
    }

    public ModelTarget selectDefaultEmbedding() {
        List<ModelTarget> targets = selectEmbeddingCandidates();
        return targets.isEmpty() ? null : targets.get(0);
    }

    /**
     * 根据模式解析首选模型
     * - 深度思考模式：优先使用 deep-thinking-model
     * - 普通模式：使用 default-model
     */
    private String resolveFirstChoiceModel(AIModelProperties.ModelGroup group, Boolean deepThinking) {
        if (Boolean.TRUE.equals(deepThinking)) {
            String deepModel = group.getDeepThinkingModel();
            if (StrUtil.isNotBlank(deepModel)) {
                return deepModel;
            }
        }
        return group.getDefaultModel();
    }

    private List<ModelTarget> selectCandidates(AIModelProperties.ModelGroup group) {
        if (group == null) {
            return List.of();
        }
        return selectCandidates(group, group.getDefaultModel(), null);
    }

    private List<ModelTarget> selectCandidates(AIModelProperties.ModelGroup group, String firstChoiceModelId, Boolean deepThinking) {
        if (group == null || group.getCandidates() == null) {
            return List.of();
        }

        List<AIModelProperties.ModelCandidate> orderedCandidates =
                prepareOrderedCandidates(group.getCandidates(), firstChoiceModelId, deepThinking);

        return buildAvailableTargets(orderedCandidates);
    }

    /**
     * 准备排序后的候选模型列表
     */
    private List<AIModelProperties.ModelCandidate> prepareOrderedCandidates(
            List<AIModelProperties.ModelCandidate> candidates,
            String firstChoiceModelId,
            Boolean deepThinking) {
        List<AIModelProperties.ModelCandidate> enabled = candidates.stream()
                .filter(c -> c != null && !Boolean.FALSE.equals(c.getEnabled()))
                .filter(c -> !Boolean.TRUE.equals(deepThinking) || Boolean.TRUE.equals(c.getSupportsThinking()))
                .sorted(Comparator
                        .comparing(AIModelProperties.ModelCandidate::getPriority,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(AIModelProperties.ModelCandidate::getId,
                                Comparator.nullsLast(String::compareTo)))
                .collect(Collectors.toCollection(ArrayList::new));

        if (Boolean.TRUE.equals(deepThinking) && enabled.isEmpty()) {
            log.warn("深度思考模式没有可用候选模型");
            return enabled;
        }

        promoteFirstChoiceModel(enabled, firstChoiceModelId);

        return enabled;
    }

    private void promoteFirstChoiceModel(
            List<AIModelProperties.ModelCandidate> candidates,
            String firstChoiceModelId) {

        if (StrUtil.isBlank(firstChoiceModelId)) {
            return;
        }

        AIModelProperties.ModelCandidate firstChoice = findCandidate(candidates, firstChoiceModelId);
        if (firstChoice != null) {
            candidates.remove(firstChoice);
            candidates.add(0, firstChoice);
        } else {
            log.warn("首选模型在候选列表中不存在: modelId={}", firstChoiceModelId);
        }
    }

    private List<ModelTarget> buildAvailableTargets(
            List<AIModelProperties.ModelCandidate> candidates) {

        Map<String, AIModelProperties.ProviderConfig> providers = properties.getProviders();

        return candidates.stream()
                .map(candidate -> buildModelTarget(candidate, providers))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private ModelTarget buildModelTarget(AIModelProperties.ModelCandidate candidate, Map<String, AIModelProperties.ProviderConfig> providers) {
        String modelId = resolveId(candidate);

        // 检查熔断状态
        if (healthStore.isOpen(modelId)) {
            return null;
        }

        // 验证 provider 配置
        AIModelProperties.ProviderConfig provider = providers.get(candidate.getProvider());
        if (provider == null && !"noop".equalsIgnoreCase(candidate.getProvider())) {
            log.warn("Provider配置缺失: provider={}, modelId={}",
                    candidate.getProvider(), modelId);
            return null;
        }

        return new ModelTarget(modelId, candidate, provider);
    }

    private AIModelProperties.ModelCandidate findCandidate(
            List<AIModelProperties.ModelCandidate> candidates,
            String id) {

        return candidates.stream()
                .filter(c -> id.equals(c.getId()))
                .findFirst()
                .orElse(null);
    }

    private String resolveId(AIModelProperties.ModelCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        if (StrUtil.isNotBlank(candidate.getId())) {
            return candidate.getId();
        }
        return String.format("%s::%s",
                Objects.toString(candidate.getProvider(), "unknown"),
                Objects.toString(candidate.getModel(), "unknown"));
    }
}
