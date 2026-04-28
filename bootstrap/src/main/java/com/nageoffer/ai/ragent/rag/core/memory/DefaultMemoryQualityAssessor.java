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

package com.nageoffer.ai.ragent.rag.core.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryQualityReport;
import com.nageoffer.ai.ragent.rag.core.memory.store.LongTermMemoryStore;
import com.nageoffer.ai.ragent.rag.core.memory.store.SemanticMemoryStore;
import com.nageoffer.ai.ragent.rag.dao.entity.MemoryConflictLogDO;
import com.nageoffer.ai.ragent.rag.dao.entity.SemanticMemoryDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.MemoryConflictLogMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.SemanticMemoryMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ShortTermMemoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认记忆质量评估器。
 */
@Component
@RequiredArgsConstructor
public class DefaultMemoryQualityAssessor implements MemoryQualityAssessor {

    private final ShortTermMemoryMapper shortTermMemoryMapper;
    private final LongTermMemoryStore longTermMemoryStore;
    private final SemanticMemoryStore semanticMemoryStore;
    private final SemanticMemoryMapper semanticMemoryMapper;
    private final MemoryConflictLogMapper memoryConflictLogMapper;

    @Override
    public MemoryQualityReport assess(String userId) {
        List<SemanticMemoryDO> semanticRecords = semanticMemoryMapper.selectList(
                Wrappers.lambdaQuery(SemanticMemoryDO.class)
                        .eq(SemanticMemoryDO::getUserId, userId)
                        .eq(SemanticMemoryDO::getDeleted, 0));
        detectSemanticConflicts(userId, semanticRecords);
        detectPreferencePolarityConflicts(userId, semanticRecords);
        int shortCount = Math.toIntExact(shortTermMemoryMapper.selectCount(
                Wrappers.lambdaQuery(com.nageoffer.ai.ragent.rag.dao.entity.ShortTermMemoryDO.class)
                        .eq(com.nageoffer.ai.ragent.rag.dao.entity.ShortTermMemoryDO::getUserId, userId)
                        .eq(com.nageoffer.ai.ragent.rag.dao.entity.ShortTermMemoryDO::getDeleted, 0)));
        int longCount = longTermMemoryStore.listByUser(userId).size();
        int semanticCount = semanticMemoryStore.listByUser(userId).size();
        int conflictCount = Math.toIntExact(memoryConflictLogMapper.selectCount(
                Wrappers.lambdaQuery(MemoryConflictLogDO.class)
                        .eq(MemoryConflictLogDO::getUserId, userId)
                        .eq(MemoryConflictLogDO::getDeleted, 0)));
        return MemoryQualityReport.builder()
                .userId(userId)
                .shortTermCount(shortCount)
                .longTermCount(longCount)
                .semanticCount(semanticCount)
                .conflictCount(conflictCount)
                .build();
    }

    private void detectSemanticConflicts(String userId, List<SemanticMemoryDO> records) {
        Map<String, SemanticMemoryDO> seen = new HashMap<>();
        for (SemanticMemoryDO record : records) {
            String key = record.getSemanticKey() + ":" + record.getSemanticType();
            SemanticMemoryDO previous = seen.putIfAbsent(key, record);
            if (previous == null) {
                continue;
            }
            if (previous.getValueJson() != null && previous.getValueJson().equals(record.getValueJson())) {
                continue;
            }
            MemoryConflictLogDO conflict = MemoryConflictLogDO.builder()
                    .userId(userId)
                    .memoryId1(previous.getId())
                    .memoryId2(record.getId())
                    .conflictType("CONTRADICTION")
                    .severity("MEDIUM")
                    .resolutionStatus("PENDING")
                    .resolvedAt(new Date())
                    .build();
            memoryConflictLogMapper.insert(conflict);
        }
    }

    private void detectPreferencePolarityConflicts(String userId, List<SemanticMemoryDO> records) {
        Map<String, SemanticMemoryDO> positive = new HashMap<>();
        Map<String, SemanticMemoryDO> negative = new HashMap<>();
        for (SemanticMemoryDO record : records) {
            if (!"PREFERENCE".equalsIgnoreCase(record.getSemanticType())) {
                continue;
            }
            String key = normalizePreferenceKey(record.getSemanticKey());
            if (key.isBlank()) {
                continue;
            }
            if (isNegativePreference(record.getSemanticKey())) {
                negative.putIfAbsent(key, record);
            } else if (isPositivePreference(record.getSemanticKey())) {
                positive.putIfAbsent(key, record);
            }
        }
        for (Map.Entry<String, SemanticMemoryDO> entry : positive.entrySet()) {
            SemanticMemoryDO negativeRecord = negative.get(entry.getKey());
            if (negativeRecord == null) {
                continue;
            }
            insertConflict(userId, entry.getValue(), negativeRecord, "PREFERENCE_POLARITY");
        }
    }

    private void insertConflict(String userId, SemanticMemoryDO first, SemanticMemoryDO second, String conflictType) {
        MemoryConflictLogDO conflict = MemoryConflictLogDO.builder()
                .userId(userId)
                .memoryId1(first.getId())
                .memoryId2(second.getId())
                .conflictType(conflictType)
                .severity("MEDIUM")
                .resolutionStatus("PENDING")
                .resolvedAt(new Date())
                .build();
        memoryConflictLogMapper.insert(conflict);
    }

    private boolean isPositivePreference(String value) {
        return value != null && (value.contains("喜欢") || value.contains("偏好"));
    }

    private boolean isNegativePreference(String value) {
        return value != null && value.contains("不喜欢");
    }

    private String normalizePreferenceKey(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("不喜欢", "")
                .replace("喜欢", "")
                .replace("偏好", "")
                .replace("我", "")
                .trim();
    }
}
