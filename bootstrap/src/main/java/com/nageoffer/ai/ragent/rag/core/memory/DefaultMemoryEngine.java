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

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryContext;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryItem;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryLayer;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryLoadRequest;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryQualityReport;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryWriteRequest;
import com.nageoffer.ai.ragent.rag.core.memory.store.LongTermMemoryStore;
import com.nageoffer.ai.ragent.rag.core.memory.store.SemanticMemoryStore;
import com.nageoffer.ai.ragent.rag.core.memory.store.ShortTermMemoryStore;
import com.nageoffer.ai.ragent.rag.core.memory.store.WorkingMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 默认记忆引擎。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultMemoryEngine implements MemoryEngine {

    private final MemoryActivator memoryActivator;
    private final WorkingMemoryStore workingMemoryStore;
    private final ShortTermMemoryStore shortTermMemoryStore;
    private final LongTermMemoryStore longTermMemoryStore;
    private final SemanticMemoryStore semanticMemoryStore;
    private final ConversationMemorySummaryService summaryService;
    private final MemoryQualityAssessor memoryQualityAssessor;
    private final ForgettingController forgettingController;

    @Qualifier("memoryWriteThreadPoolExecutor")
    private final Executor memoryWriteExecutor;

    @Override
    public MemoryContext loadMemory(MemoryLoadRequest request) {
        return memoryActivator.activate(request);
    }

    @Override
    public void writeMemory(MemoryWriteRequest request) {
        memoryWriteExecutor.execute(() -> doWriteMemory(request));
    }

    @Override
    public List<MemoryItem> retrieveMemories(MemoryLoadRequest request) {
        MemoryContext context = memoryActivator.activate(request);
        List<MemoryItem> items = new ArrayList<>();
        items.addAll(context.getShortTermMemories());
        items.addAll(context.getLongTermMemories());
        items.addAll(context.getSemanticMemories());
        return items;
    }

    @Override
    public void executeMemoryDecay() {
        forgettingController.execute();
    }

    @Override
    public MemoryQualityReport assessMemoryQuality(String userId) {
        return memoryQualityAssessor.assess(userId);
    }

    private void doWriteMemory(MemoryWriteRequest request) {
        ChatMessage message = request.getMessage();
        workingMemoryStore.append(request.getConversationId(), request.getUserId(), message);
        if (message.getRole() == ChatMessage.Role.USER) {
            extractUserMemories(request, message);
            return;
        }
        if (message.getRole() == ChatMessage.Role.ASSISTANT) {
            extractAssistantMemories(request, message);
        }
    }

    private void extractUserMemories(MemoryWriteRequest request, ChatMessage message) {
        String content = message.getContent();
        if (content == null || content.isBlank()) {
            return;
        }
        if (content.contains("喜欢") || content.contains("偏好") || content.contains("不喜欢")) {
            MemoryItem item = MemoryItem.builder()
                    .userId(request.getUserId())
                    .conversationId(request.getConversationId())
                    .layer(MemoryLayer.SHORT_TERM)
                    .type("PREFERENCE")
                    .content(content)
                    .metadataJson(toValueJson(content, "user"))
                    .sourceIdsJson(sourceIdsJson(request))
                    .importanceScore(0.8D)
                    .confidenceLevel(0.95D)
                    .build();
            shortTermMemoryStore.save(item);
            semanticMemoryStore.upsert(item, extractSemanticKey(content, "preference"));
        } else if (content.contains("我是") || content.contains("我在") || content.contains("我用")) {
            MemoryItem item = MemoryItem.builder()
                    .userId(request.getUserId())
                    .conversationId(request.getConversationId())
                    .layer(MemoryLayer.SHORT_TERM)
                    .type("PROFILE")
                    .content(content)
                    .metadataJson(toValueJson(content, "user"))
                    .sourceIdsJson(sourceIdsJson(request))
                    .importanceScore(0.85D)
                    .confidenceLevel(0.95D)
                    .build();
            shortTermMemoryStore.save(item);
            semanticMemoryStore.upsert(item, extractSemanticKey(content, "profile"));
        }
    }

    private void extractAssistantMemories(MemoryWriteRequest request, ChatMessage message) {
        ChatMessage summary = summaryService.loadLatestSummary(request.getConversationId(), request.getUserId());
        if (summary != null && summary.getContent() != null && !summary.getContent().isBlank()) {
            shortTermMemoryStore.save(MemoryItem.builder()
                    .userId(request.getUserId())
                    .conversationId(request.getConversationId())
                    .layer(MemoryLayer.SHORT_TERM)
                    .type("SUMMARY")
                    .content(summary.getContent())
                    .metadataJson(toValueJson(summary.getContent(), "summary"))
                    .sourceIdsJson(sourceIdsJson(request))
                    .importanceScore(0.7D)
                    .confidenceLevel(0.8D)
                    .build());
        } else if (message.getContent() != null && !message.getContent().isBlank()) {
            shortTermMemoryStore.save(MemoryItem.builder()
                    .userId(request.getUserId())
                    .conversationId(request.getConversationId())
                    .layer(MemoryLayer.SHORT_TERM)
                    .type("FACT")
                    .content(truncate(message.getContent(), 240))
                    .metadataJson(toValueJson(message.getContent(), "assistant"))
                    .sourceIdsJson(sourceIdsJson(request))
                    .importanceScore(0.55D)
                    .confidenceLevel(0.7D)
                    .build());
        }
    }

    private String extractSemanticKey(String content, String fallback) {
        String normalized = content.replace("我", "").replace("是", "").replace("喜欢", "").trim();
        if (normalized.isBlank()) {
            return fallback;
        }
        return normalized.length() > 24 ? normalized.substring(0, 24) : normalized;
    }

    private String truncate(String content, int maxLength) {
        return content.length() <= maxLength ? content : content.substring(0, maxLength);
    }

    private String sourceIdsJson(MemoryWriteRequest request) {
        if (request.getMessageId() == null || request.getMessageId().isBlank()) {
            return "[]";
        }
        return "[\"" + jsonEscape(request.getMessageId()) + "\"]";
    }

    private String toValueJson(String content, String source) {
        return "{\"source\":\"" + jsonEscape(source) + "\",\"content\":\"" + jsonEscape(content) + "\"}";
    }

    private String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
