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

import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryItem;
import com.nageoffer.ai.ragent.rag.core.memory.store.LongTermMemoryStore;
import com.nageoffer.ai.ragent.rag.core.memory.store.SemanticMemoryStore;
import com.nageoffer.ai.ragent.rag.core.memory.store.ShortTermMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 记忆治理任务。
 */
@Component
@RequiredArgsConstructor
public class MemoryGovernanceJob {

    private final ShortTermMemoryStore shortTermMemoryStore;
    private final LongTermMemoryStore longTermMemoryStore;
    private final SemanticMemoryStore semanticMemoryStore;
    private final ForgettingController forgettingController;
    private final MemoryQualityAssessor memoryQualityAssessor;
    private final MemoryProperties memoryProperties;

    @Scheduled(cron = "${rag.memory.cleanup-cron:0 0/30 * * * ?}")
    public void promoteAndAssess() {
        Set<String> users = collectCandidateUsers();
        for (String userId : users) {
            for (MemoryItem item : shortTermMemoryStore.listPromotable(
                    userId, memoryProperties.getLongTermImportanceThreshold())) {
                longTermMemoryStore.save(MemoryItem.builder()
                        .userId(item.getUserId())
                        .conversationId(item.getConversationId())
                        .layer(item.getLayer())
                        .type(item.getType())
                        .content(item.getContent())
                        .metadataJson(item.getMetadataJson())
                        .sourceIdsJson(item.getSourceIdsJson())
                        .importanceScore(item.getImportanceScore())
                        .confidenceLevel(item.getConfidenceLevel())
                        .build());
                if ("PROFILE".equalsIgnoreCase(item.getType()) || "PREFERENCE".equalsIgnoreCase(item.getType())) {
                    semanticMemoryStore.upsert(item, normalizeSemanticKey(item));
                }
            }
            if (Boolean.TRUE.equals(memoryProperties.getQualityAssessmentEnabled())) {
                memoryQualityAssessor.assess(userId);
            }
        }
        forgettingController.execute();
    }

    private Set<String> collectCandidateUsers() {
        Set<String> users = new HashSet<>();
        for (MemoryItem item : shortTermMemoryStore.listPromotable("", 0D)) {
            if (item.getUserId() != null) {
                users.add(item.getUserId());
            }
        }
        return users;
    }

    private String normalizeSemanticKey(MemoryItem item) {
        String content = item.getContent() == null ? "" : item.getContent().trim();
        if (content.isBlank()) {
            return item.getType() == null ? "memory" : item.getType().toLowerCase();
        }
        return content.length() > 64 ? content.substring(0, 64) : content;
    }
}
