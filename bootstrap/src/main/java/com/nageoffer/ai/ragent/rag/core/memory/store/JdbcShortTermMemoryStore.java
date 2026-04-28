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
import com.nageoffer.ai.ragent.rag.dao.entity.ShortTermMemoryDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ShortTermMemoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * JDBC 短期记忆存储。
 */
@Component
@RequiredArgsConstructor
public class JdbcShortTermMemoryStore implements ShortTermMemoryStore {

    private final ShortTermMemoryMapper shortTermMemoryMapper;
    private final MemoryProperties memoryProperties;

    @Override
    public void save(MemoryItem memoryItem) {
        ShortTermMemoryDO record = ShortTermMemoryDO.builder()
                .userId(memoryItem.getUserId())
                .conversationId(memoryItem.getConversationId())
                .memoryType(memoryItem.getType())
                .content(memoryItem.getContent())
                .metadataJson(memoryItem.getMetadataJson())
                .sourceMessageIds(defaultJsonArray(memoryItem.getSourceIdsJson()))
                .importanceScore(defaultDouble(memoryItem.getImportanceScore()))
                .accessCount(0)
                .decayScore(1D)
                .expiresTime(Instant.ofEpochMilli(System.currentTimeMillis() + memoryProperties.getShortTermRetentionDays() * 24L * 3600 * 1000)
                                .atZone(ZoneId.systemDefault()).toLocalDateTime())
                .build();
        shortTermMemoryMapper.insert(record);
    }

    @Override
    public List<MemoryItem> search(String userId, String query, int topK) {
        List<ShortTermMemoryDO> candidates = shortTermMemoryMapper.selectList(
                Wrappers.lambdaQuery(ShortTermMemoryDO.class)
                        .eq(ShortTermMemoryDO::getUserId, userId)
                        .eq(ShortTermMemoryDO::getDeleted, 0)
                        .and(wrapper -> wrapper.isNull(ShortTermMemoryDO::getExpiresTime)
                                .or()
                                .gt(ShortTermMemoryDO::getExpiresTime, new Date()))
                        .orderByDesc(ShortTermMemoryDO::getImportanceScore)
                        .orderByDesc(ShortTermMemoryDO::getCreateTime)
                        .last("limit " + Math.max(topK * 5, 20)));
        return candidates.stream()
                .filter(item -> match(item.getContent(), query))
                .limit(topK)
                .map(this::toMemoryItem)
                .toList();
    }

    @Override
    public List<MemoryItem> listPromotable(String userId, double threshold) {
        return shortTermMemoryMapper.selectList(
                        Wrappers.lambdaQuery(ShortTermMemoryDO.class)
                                .eq(userId != null && !userId.isBlank(), ShortTermMemoryDO::getUserId, userId)
                                .eq(ShortTermMemoryDO::getDeleted, 0)
                                .ge(ShortTermMemoryDO::getImportanceScore, threshold)
                                .orderByDesc(ShortTermMemoryDO::getImportanceScore)
                                .last("limit 50"))
                .stream()
                .map(this::toMemoryItem)
                .toList();
    }

    @Override
    public void markAccessed(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (String id : ids) {
            shortTermMemoryMapper.update(
                    ShortTermMemoryDO.builder()
                            .lastAccessTime(LocalDateTime.now())
                            .build(),
                    Wrappers.lambdaUpdate(ShortTermMemoryDO.class)
                            .eq(ShortTermMemoryDO::getId, id)
                            .setSql("access_count = coalesce(access_count, 0) + 1")
            );
        }
    }

    @Override
    public void updateDecayScores(double threshold) {
        List<ShortTermMemoryDO> records = shortTermMemoryMapper.selectList(
                Wrappers.lambdaQuery(ShortTermMemoryDO.class)
                        .eq(ShortTermMemoryDO::getDeleted, 0)
                        .last("limit 500"));

        for (ShortTermMemoryDO record : records) {
            double ageDays = Math.max(0D, Instant.now().toEpochMilli() - record.getCreateTime().atZone(ZoneId.of("Asia/Shanghai"))
                    .toInstant()
                    .toEpochMilli()) / 86400000D;
            double accessBoost = Math.log1p(record.getAccessCount() == null ? 0 : record.getAccessCount());
            double decayScore = Math.max(0D, defaultDouble(record.getImportanceScore())
                    - ageDays * memoryProperties.getShortTermDecayFactor()
                    + accessBoost * 0.05D);
            if (record.getExpiresTime() != null && record.getExpiresTime().isBefore(LocalDateTime.now())) {
                shortTermMemoryMapper.deleteById(record.getId());
                continue;
            }
            if (decayScore < threshold) {
                shortTermMemoryMapper.deleteById(record.getId());
                continue;
            }
            shortTermMemoryMapper.update(
                    ShortTermMemoryDO.builder().decayScore(decayScore).build(),
                    Wrappers.lambdaUpdate(ShortTermMemoryDO.class).eq(ShortTermMemoryDO::getId, record.getId())
            );
        }
    }

    private MemoryItem toMemoryItem(ShortTermMemoryDO item) {
        return MemoryItem.builder()
                .id(item.getId())
                .userId(item.getUserId())
                .conversationId(item.getConversationId())
                .layer(MemoryLayer.SHORT_TERM)
                .type(item.getMemoryType())
                .content(item.getContent())
                .metadataJson(item.getMetadataJson())
                .sourceIdsJson(item.getSourceMessageIds())
                .importanceScore(item.getImportanceScore())
                .relevanceScore(item.getDecayScore())
                .createTime(item.getCreateTime())
                .build();
    }

    private boolean match(String content, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String lower = content == null ? "" : content.toLowerCase(Locale.ROOT);
        for (String token : query.toLowerCase(Locale.ROOT).split("[\\s,，。！？;；]+")) {
            if (token.length() >= 2 && lower.contains(token)) {
                return true;
            }
        }
        return lower.contains(query.toLowerCase(Locale.ROOT));
    }

    private double defaultDouble(Double value) {
        return value == null ? 0D : value;
    }

    private String defaultJsonArray(String value) {
        return value == null || value.isBlank() ? "[]" : value;
    }
}
