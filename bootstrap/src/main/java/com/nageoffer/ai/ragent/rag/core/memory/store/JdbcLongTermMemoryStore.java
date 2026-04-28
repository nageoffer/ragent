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
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryItem;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryLayer;
import com.nageoffer.ai.ragent.rag.dao.entity.LongTermMemoryDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.LongTermMemoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JDBC 长期记忆存储。
 */
@Component
@RequiredArgsConstructor
public class JdbcLongTermMemoryStore implements LongTermMemoryStore {

    private final LongTermMemoryMapper longTermMemoryMapper;
    private final LongTermMemoryVectorStore vectorStore;
    private final MemoryProperties memoryProperties;

    @Override
    public void save(MemoryItem memoryItem) {
        LongTermMemoryDO existing = longTermMemoryMapper.selectOne(
                Wrappers.lambdaQuery(LongTermMemoryDO.class)
                        .eq(LongTermMemoryDO::getUserId, memoryItem.getUserId())
                        .eq(LongTermMemoryDO::getContent, memoryItem.getContent())
                        .eq(LongTermMemoryDO::getDeleted, 0)
                        .last("limit 1"));
        if (existing != null) {
            return;
        }
        LongTermMemoryDO record = LongTermMemoryDO.builder()
                .userId(memoryItem.getUserId())
                .memoryCategory(memoryItem.getType())
                .title(memoryItem.getContent().length() > 64 ? memoryItem.getContent().substring(0, 64) : memoryItem.getContent())
                .content(memoryItem.getContent())
                .sourceType("EXTRACTED")
                .sourceIds(defaultJsonArray(memoryItem.getSourceIdsJson()))
                .tags(memoryItem.getMetadataJson())
                .importanceScore(memoryItem.getImportanceScore())
                .confidenceLevel(memoryItem.getConfidenceLevel())
                .embeddingModel(memoryProperties.getLongTermEmbeddingModel())
                .build();
        longTermMemoryMapper.insert(record);
        longTermMemoryMapper.update(
                LongTermMemoryDO.builder().vectorRefId(record.getId()).build(),
                Wrappers.lambdaUpdate(LongTermMemoryDO.class).eq(LongTermMemoryDO::getId, record.getId())
        );
        vectorStore.upsert(record.getId(), record.getUserId(), record.getContent(), record.getEmbeddingModel());
    }

    @Override
    public List<MemoryItem> search(String userId, String query, int topK) {
        if (query == null || query.isBlank()) {
            return longTermMemoryMapper.selectList(
                            Wrappers.lambdaQuery(LongTermMemoryDO.class)
                                    .eq(LongTermMemoryDO::getUserId, userId)
                                    .eq(LongTermMemoryDO::getDeleted, 0)
                                    .orderByDesc(LongTermMemoryDO::getImportanceScore)
                                    .last("limit " + topK))
                    .stream()
                    .map(this::toMemoryItem)
                    .toList();
        }
        List<String> ids = vectorStore.search(userId, query, Math.max(topK * 3, 10));
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<LongTermMemoryDO> records = longTermMemoryMapper.selectList(
                Wrappers.lambdaQuery(LongTermMemoryDO.class)
                        .in(LongTermMemoryDO::getId, ids)
                        .eq(LongTermMemoryDO::getDeleted, 0));
        Map<String, LongTermMemoryDO> byId = new LinkedHashMap<>();
        for (LongTermMemoryDO record : records) {
            byId.put(record.getId(), record);
        }
        String category = queryMemoryCategory(query);
        return ids.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .map(item -> new ScoredLongTermItem(item, score(item, query, category)))
                .sorted(Comparator
                        .comparingDouble(ScoredLongTermItem::score).reversed()
                        .thenComparingDouble(ScoredLongTermItem::importance).reversed()
                        .thenComparingDouble(ScoredLongTermItem::confidence).reversed())
                .limit(topK)
                .map(ScoredLongTermItem::item)
                .map(this::toMemoryItem)
                .toList();
    }

    @Override
    public List<MemoryItem> listByUser(String userId) {
        return longTermMemoryMapper.selectList(
                        Wrappers.lambdaQuery(LongTermMemoryDO.class)
                                .eq(LongTermMemoryDO::getUserId, userId)
                                .eq(LongTermMemoryDO::getDeleted, 0))
                .stream()
                .map(this::toMemoryItem)
                .toList();
    }

    @Override
    public void markAccessed(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        longTermMemoryMapper.update(
                LongTermMemoryDO.builder().build(),
                Wrappers.lambdaUpdate(LongTermMemoryDO.class)
                        .in(LongTermMemoryDO::getId, ids)
                        .setSql("update_time = CURRENT_TIMESTAMP")
        );
    }

    @Override
    public void decayDormantMemories(int dormantDays, double decayStep, double minImportance) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(1, dormantDays));
        List<LongTermMemoryDO> dormantRecords = longTermMemoryMapper.selectList(
                Wrappers.lambdaQuery(LongTermMemoryDO.class)
                        .eq(LongTermMemoryDO::getDeleted, 0)
                        .le(LongTermMemoryDO::getUpdateTime, cutoff)
                        .last("limit 200"));
        for (LongTermMemoryDO record : dormantRecords) {
            double current = record.getImportanceScore() == null ? 0D : record.getImportanceScore();
            double next = Math.max(minImportance, current - Math.max(0D, decayStep));
            if (Double.compare(current, next) == 0) {
                continue;
            }
            longTermMemoryMapper.update(
                    LongTermMemoryDO.builder()
                            .importanceScore(next)
                            .build(),
                    Wrappers.lambdaUpdate(LongTermMemoryDO.class)
                            .eq(LongTermMemoryDO::getId, record.getId())
            );
        }
    }

    private MemoryItem toMemoryItem(LongTermMemoryDO item) {
        return MemoryItem.builder()
                .id(item.getId())
                .userId(item.getUserId())
                .layer(MemoryLayer.LONG_TERM)
                .type(item.getMemoryCategory())
                .content(item.getContent())
                .metadataJson(item.getTags())
                .sourceIdsJson(item.getSourceIds())
                .importanceScore(item.getImportanceScore())
                .confidenceLevel(item.getConfidenceLevel())
                .createTime(item.getCreateTime())
                .build();
    }

    private double score(LongTermMemoryDO item, String query, String category) {
        String lowerQuery = lower(query);
        String content = lower(item.getContent());
        String type = lower(item.getMemoryCategory());
        double score = 0D;
        if (content.contains(lowerQuery)) {
            score += 2.5D;
        }
        if (!category.isBlank() && category.equalsIgnoreCase(type)) {
            score += 3.5D;
        }
        score += defaultDouble(item.getImportanceScore()) * 0.25D;
        score += defaultDouble(item.getConfidenceLevel()) * 0.15D;
        return score;
    }

    private String queryMemoryCategory(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        if (containsAny(query, "待办", "todo", "后续")) {
            return "TODO";
        }
        if (containsAny(query, "问题", "报错", "异常", "失败", "bug", "error")) {
            return "ISSUE";
        }
        if (containsAny(query, "总结", "摘要", "概括")) {
            return "SUMMARY";
        }
        if (containsAny(query, "偏好", "喜欢", "不喜欢", "讨厌")) {
            return "PREFERENCE";
        }
        if (containsAny(query, "画像", "身份", "职业", "组织", "公司", "工具", "技术栈", "地点", "语言")) {
            return "PROFILE";
        }
        if (containsAny(query, "事实", "信息", "记录", "情况")) {
            return "FACT";
        }
        return "";
    }

    private boolean containsAny(String value, String... keywords) {
        if (value == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (value.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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

    private record ScoredLongTermItem(LongTermMemoryDO item, double score) {

        double importance() {
            return item.getImportanceScore() == null ? 0D : item.getImportanceScore();
        }

        double confidence() {
            return item.getConfidenceLevel() == null ? 0D : item.getConfidenceLevel();
        }
    }
}
