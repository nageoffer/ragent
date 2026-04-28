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
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryContext;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryItem;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryLoadRequest;
import com.nageoffer.ai.ragent.rag.core.memory.model.OptimizedQuery;
import com.nageoffer.ai.ragent.rag.core.memory.store.LongTermMemoryStore;
import com.nageoffer.ai.ragent.rag.core.memory.store.SemanticMemoryStore;
import com.nageoffer.ai.ragent.rag.core.memory.store.ShortTermMemoryStore;
import com.nageoffer.ai.ragent.rag.core.memory.store.WorkingMemoryStore;
import com.nageoffer.ai.ragent.rag.core.memory.support.SemanticMemorySupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认记忆激活器。
 */
@Component
@RequiredArgsConstructor
public class DefaultMemoryActivator implements MemoryActivator {

    private final WorkingMemoryStore workingMemoryStore;
    private final ShortTermMemoryStore shortTermMemoryStore;
    private final LongTermMemoryStore longTermMemoryStore;
    private final SemanticMemoryStore semanticMemoryStore;
    private final QueryOptimizer queryOptimizer;
    private final ConversationMemorySummaryService summaryService;
    private final MemoryProperties memoryProperties;

    @Override
    public MemoryContext activate(MemoryLoadRequest request) {
        List<ChatMessage> workingMemory = workingMemoryStore.load(request.getConversationId(), request.getUserId());
        MemoryContext baseContext = MemoryContext.builder()
                .conversationId(request.getConversationId())
                .userId(request.getUserId())
                .currentQuestion(request.getCurrentQuestion())
                .workingMemory(workingMemory)
                .shortTermMemories(List.of())
                .longTermMemories(List.of())
                .semanticMemories(List.of())
                .promptMessages(workingMemory)
                .build();
        OptimizedQuery optimizedQuery = queryOptimizer.optimize(request.getCurrentQuestion(), baseContext);
        List<MemoryItem> shortTermMemories = shortTermMemoryStore.search(
                request.getUserId(), optimizedQuery.getOptimizedQuery(), memoryProperties.getShortTermTopK());
        List<MemoryItem> longTermMemories = Boolean.TRUE.equals(memoryProperties.getLongTermEnabled())
                ? longTermMemoryStore.search(
                request.getUserId(), optimizedQuery.getOptimizedQuery(), memoryProperties.getLongTermTopK())
                : List.of();
        List<MemoryItem> semanticMemories = Boolean.TRUE.equals(memoryProperties.getSemanticEnabled())
                ? semanticMemoryStore.search(
                request.getUserId(), optimizedQuery.getOptimizedQuery(), memoryProperties.getSemanticTopK())
                : List.of();
        shortTermMemoryStore.markAccessed(ids(shortTermMemories));
        longTermMemoryStore.markAccessed(ids(longTermMemories));

        List<ChatMessage> promptMessages = new ArrayList<>();
        ChatMessage summary = summaryService.loadLatestSummary(request.getConversationId(), request.getUserId());
        if (summary != null) {
            promptMessages.add(summaryService.decorateIfNeeded(summary));
        }
        appendTypedMemoryPrompt(promptMessages, "短期记忆", shortTermMemories, budget(memoryProperties.getShortTermTokenRatio()));
        appendTypedMemoryPrompt(promptMessages, "长期记忆", longTermMemories, budget(memoryProperties.getLongTermTokenRatio()));
        appendSemanticMemoryPrompt(promptMessages, semanticMemories, budget(memoryProperties.getSemanticTokenRatio()));
        promptMessages.addAll(trimMessages(workingMemory, budget(memoryProperties.getWorkingMemoryTokenRatio())));

        return MemoryContext.builder()
                .conversationId(request.getConversationId())
                .userId(request.getUserId())
                .currentQuestion(request.getCurrentQuestion())
                .workingMemory(workingMemory)
                .shortTermMemories(shortTermMemories)
                .longTermMemories(longTermMemories)
                .semanticMemories(semanticMemories)
                .promptMessages(promptMessages)
                .build();
    }

    private void appendTypedMemoryPrompt(List<ChatMessage> messages, String title, List<MemoryItem> items, int tokenBudget) {
        if (items == null || items.isEmpty() || tokenBudget <= 0) {
            return;
        }
        Map<String, List<MemoryItem>> grouped = new LinkedHashMap<>();
        for (MemoryItem item : items) {
            grouped.computeIfAbsent(memoryGroupTitle(item), key -> new ArrayList<>()).add(item);
        }
        StringBuilder builder = new StringBuilder(title).append(":\n");
        for (Map.Entry<String, List<MemoryItem>> entry : grouped.entrySet()) {
            String header = entry.getKey() + ":\n";
            if (estimateTokens(builder.length() + header.length()) > tokenBudget) {
                break;
            }
            builder.append(header);
            for (MemoryItem item : entry.getValue()) {
                String line = "- " + item.getContent() + '\n';
                if (estimateTokens(builder.length() + line.length()) > tokenBudget) {
                    break;
                }
                builder.append(line);
            }
        }
        if (builder.length() <= title.length() + 2) {
            return;
        }
        messages.add(ChatMessage.system(builder.toString().trim()));
    }

    private void appendSemanticMemoryPrompt(List<ChatMessage> messages, List<MemoryItem> items, int tokenBudget) {
        if (items == null || items.isEmpty() || tokenBudget <= 0) {
            return;
        }
        Map<String, List<MemoryItem>> grouped = new LinkedHashMap<>();
        for (MemoryItem item : items) {
            grouped.computeIfAbsent(semanticGroupTitle(item), key -> new ArrayList<>()).add(item);
        }
        StringBuilder builder = new StringBuilder("语义记忆:\n");
        for (Map.Entry<String, List<MemoryItem>> entry : grouped.entrySet()) {
            String header = entry.getKey() + ":\n";
            if (estimateTokens(builder.length() + header.length()) > tokenBudget) {
                break;
            }
            builder.append(header);
            for (MemoryItem item : entry.getValue()) {
                String line = "- " + item.getContent() + '\n';
                if (estimateTokens(builder.length() + line.length()) > tokenBudget) {
                    break;
                }
                builder.append(line);
            }
        }
        if (builder.length() <= "语义记忆:\n".length()) {
            return;
        }
        messages.add(ChatMessage.system(builder.toString().trim()));
    }

    private List<ChatMessage> trimMessages(List<ChatMessage> messages, int tokenBudget) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> result = new ArrayList<>();
        int used = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            int tokens = estimateTokens(message.getContent() == null ? 0 : message.getContent().length());
            if (!result.isEmpty() && used + tokens > tokenBudget) {
                break;
            }
            result.add(0, message);
            used += tokens;
        }
        return result;
    }

    private int budget(Double ratio) {
        return (int) Math.floor(memoryProperties.getMaxMemoryTokenBudget() * (ratio == null ? 0D : ratio));
    }

    private int estimateTokens(int chars) {
        return (int) Math.ceil(chars / 4.0D);
    }

    private List<String> ids(List<MemoryItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .map(MemoryItem::getId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
    }

    private String semanticGroupTitle(MemoryItem item) {
        if (item == null) {
            return "其他语义";
        }
        if ("PREFERENCE".equalsIgnoreCase(item.getType())) {
            return "偏好";
        }
        if ("PROFILE".equalsIgnoreCase(item.getType())) {
            String attribute = SemanticMemorySupport.extractProfileAttribute(item.getMetadataJson());
            return switch (attribute) {
                case "identity", "role" -> "身份";
                case "organization" -> "组织";
                case "tool" -> "工具";
                case "stack" -> "技术栈";
                case "location" -> "地点";
                case "language" -> "语言";
                default -> "画像";
            };
        }
        return "其他语义";
    }

    private String memoryGroupTitle(MemoryItem item) {
        if (item == null || item.getType() == null) {
            return "其他记忆";
        }
        return switch (item.getType().toUpperCase()) {
            case "SUMMARY" -> "摘要";
            case "FACT" -> "事实";
            case "ISSUE" -> "问题";
            case "TODO" -> "待办";
            case "PREFERENCE" -> "偏好";
            case "PROFILE" -> "画像";
            default -> "其他记忆";
        };
    }
}
