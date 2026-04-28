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

package com.nageoffer.ai.ragent.rag.core.memory.store;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryItem;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryLayer;
import com.nageoffer.ai.ragent.rag.core.memory.support.SemanticMemorySupport;
import com.nageoffer.ai.ragent.rag.dao.entity.SemanticMemoryDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.SemanticMemoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * JDBC 语义记忆存储。
 */
@Component
@RequiredArgsConstructor
public class JdbcSemanticMemoryStore implements SemanticMemoryStore {

    private final SemanticMemoryMapper semanticMemoryMapper;

    @Override
    public void upsert(MemoryItem memoryItem, String semanticKey) {
        String normalizedValueJson = valueJson(memoryItem);
        SemanticMemoryDO existing = semanticMemoryMapper.selectOne(
                Wrappers.lambdaQuery(SemanticMemoryDO.class)
                        .eq(SemanticMemoryDO::getUserId, memoryItem.getUserId())
                        .eq(SemanticMemoryDO::getSemanticKey, semanticKey)
                        .eq(SemanticMemoryDO::getSemanticType, memoryItem.getType())
                        .eq(SemanticMemoryDO::getDeleted, 0)
                        .last("limit 1"));
        if (existing == null) {
            semanticMemoryMapper.insert(SemanticMemoryDO.builder()
                    .userId(memoryItem.getUserId())
                    .semanticKey(semanticKey)
                    .semanticType(memoryItem.getType())
                    .valueJson(normalizedValueJson)
                    .confidenceLevel(memoryItem.getConfidenceLevel())
                    .sourceMemoryIds(defaultJsonArray(memoryItem.getSourceIdsJson()))
                    .build());
            return;
        }
        if (!shouldReplace(existing, normalizedValueJson, memoryItem)) {
            semanticMemoryMapper.update(
                    SemanticMemoryDO.builder()
                            .confidenceLevel(Math.max(defaultDouble(existing.getConfidenceLevel()), defaultDouble(memoryItem.getConfidenceLevel())))
                            .sourceMemoryIds(defaultJsonArray(memoryItem.getSourceIdsJson()))
                            .updateTime(LocalDateTime.now())
                            .build(),
                    Wrappers.lambdaUpdate(SemanticMemoryDO.class).eq(SemanticMemoryDO::getId, existing.getId())
            );
            return;
        }
        semanticMemoryMapper.update(
                SemanticMemoryDO.builder()
                        .valueJson(normalizedValueJson)
                        .confidenceLevel(memoryItem.getConfidenceLevel())
                        .sourceMemoryIds(defaultJsonArray(memoryItem.getSourceIdsJson()))
                        .updateTime(LocalDateTime.now())
                        .build(),
                Wrappers.lambdaUpdate(SemanticMemoryDO.class).eq(SemanticMemoryDO::getId, existing.getId())
        );
    }

    @Override
    public List<MemoryItem> search(String userId, String query, int topK) {
        List<SemanticMemoryDO> candidates = semanticMemoryMapper.selectList(
                Wrappers.lambdaQuery(SemanticMemoryDO.class)
                        .eq(SemanticMemoryDO::getUserId, userId)
                        .eq(SemanticMemoryDO::getDeleted, 0)
                        .orderByDesc(SemanticMemoryDO::getConfidenceLevel)
                        .last("limit " + Math.max(topK * 5, 20)));
        if (query == null || query.isBlank()) {
            return candidates.stream()
                    .limit(topK)
                    .map(this::toMemoryItem)
                    .toList();
        }
        String category = SemanticMemorySupport.querySemanticCategory(query);
        List<String> tokens = tokenize(query);
        return candidates.stream()
                .map(item -> new ScoredSemanticItem(item, score(item, query, tokens, category)))
                .filter(item -> item.score > 0D)
                .sorted(Comparator
                        .comparingDouble(ScoredSemanticItem::score).reversed()
                        .thenComparingDouble(ScoredSemanticItem::confidence).reversed())
                .limit(topK)
                .map(ScoredSemanticItem::item)
                .map(this::toMemoryItem)
                .toList();
    }

    @Override
    public List<MemoryItem> listByUser(String userId) {
        return semanticMemoryMapper.selectList(
                        Wrappers.lambdaQuery(SemanticMemoryDO.class)
                                .eq(SemanticMemoryDO::getUserId, userId)
                                .eq(SemanticMemoryDO::getDeleted, 0))
                .stream()
                .map(this::toMemoryItem)
                .toList();
    }

    private MemoryItem toMemoryItem(SemanticMemoryDO item) {
        return MemoryItem.builder()
                .id(item.getId())
                .userId(item.getUserId())
                .layer(MemoryLayer.SEMANTIC)
                .type(item.getSemanticType())
                .content(SemanticMemorySupport.renderContent(
                        item.getSemanticKey(), item.getSemanticType(), item.getValueJson()
                ))
                .metadataJson(item.getValueJson())
                .sourceIdsJson(item.getSourceMemoryIds())
                .confidenceLevel(item.getConfidenceLevel())
                .createTime(item.getCreateTime())
                .build();
    }

    private double score(SemanticMemoryDO item, String query, List<String> tokens, String category) {
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        String semanticKey = lower(item.getSemanticKey());
        String valueJson = lower(item.getValueJson());
        String rendered = lower(SemanticMemorySupport.renderContent(
                item.getSemanticKey(), item.getSemanticType(), item.getValueJson()
        ));
        double score = 0D;
        if (semanticKey.contains(lowerQuery)) {
            score += 4D;
        }
        if (rendered.contains(lowerQuery)) {
            score += 3D;
        }
        if (valueJson.contains(lowerQuery)) {
            score += 2D;
        }
        for (String token : tokens) {
            if (token.length() < 2) {
                continue;
            }
            if (semanticKey.contains(token)) {
                score += 1.8D;
            }
            if (rendered.contains(token)) {
                score += 1.2D;
            }
            if (valueJson.contains(token)) {
                score += 0.8D;
            }
        }
        score += categoryBoost(item, category, lowerQuery, tokens);
        return score + defaultDouble(item.getConfidenceLevel()) * 0.1D;
    }

    private double categoryBoost(SemanticMemoryDO item, String category, String lowerQuery, List<String> tokens) {
        if (category == null || category.isBlank()) {
            return 0D;
        }
        if ("preference".equals(category) && "PREFERENCE".equalsIgnoreCase(item.getSemanticType())) {
            String subject = lower(SemanticMemorySupport.extractPreferenceSubject(item.getValueJson()));
            double score = 4D;
            for (String token : tokens) {
                if (subject.contains(token)) {
                    score += 1.5D;
                }
            }
            return score;
        }
        if (!"PROFILE".equalsIgnoreCase(item.getSemanticType())) {
            return 0D;
        }
        String attribute = lower(SemanticMemorySupport.extractProfileAttribute(item.getValueJson()));
        String value = lower(SemanticMemorySupport.extractProfileValue(item.getValueJson()));
        double score = 0D;
        if (attribute.equals(category)) {
            score += 4.5D;
        }
        if (lowerQuery.contains(attribute) || lowerQuery.contains(value)) {
            score += 2D;
        }
        for (String token : tokens) {
            if (value.contains(token)) {
                score += 1.6D;
            }
            if (attribute.contains(token)) {
                score += 1D;
            }
        }
        return score;
    }

    private boolean shouldReplace(SemanticMemoryDO existing, String incomingValueJson, MemoryItem incoming) {
        int existingPriority = sourcePriority(existing.getValueJson());
        int incomingPriority = sourcePriority(incomingValueJson);
        if (incomingPriority != existingPriority) {
            return incomingPriority > existingPriority;
        }
        return defaultDouble(incoming.getConfidenceLevel()) >= defaultDouble(existing.getConfidenceLevel());
    }

    private int sourcePriority(String valueJson) {
        String source = SemanticMemorySupport.extractSource(valueJson);
        if ("user".equalsIgnoreCase(source)) {
            return 3;
        }
        if ("idle-summary".equalsIgnoreCase(source) || "summary".equalsIgnoreCase(source)) {
            return 2;
        }
        if ("assistant".equalsIgnoreCase(source)) {
            return 1;
        }
        return 0;
    }

    private String valueJson(MemoryItem memoryItem) {
        return SemanticMemorySupport.normalizeValueJson(
                memoryItem.getType(),
                memoryItem.getContent(),
                SemanticMemorySupport.extractSource(memoryItem.getMetadataJson()),
                memoryItem.getMetadataJson()
        );
    }

    private List<String> tokenize(String query) {
        List<String> tokens = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return tokens;
        }
        for (String token : query.toLowerCase(Locale.ROOT).split("[\\s,，。！？；:：]+")) {
            if (token.length() >= 2 && !tokens.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private double defaultDouble(Double value) {
        return value == null ? 0D : value;
    }

    private String defaultJsonArray(String value) {
        return value == null || value.isBlank() ? "[]" : value;
    }

    private record ScoredSemanticItem(SemanticMemoryDO item, double score) {

        double confidence() {
            return item.getConfidenceLevel() == null ? 0D : item.getConfidenceLevel();
        }
    }
}
