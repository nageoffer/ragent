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
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryItem;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryLayer;
import com.nageoffer.ai.ragent.rag.core.memory.store.ShortTermMemoryStore;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ShortTermMemoryDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ShortTermMemoryMapper;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 扫描空闲会话，将摘要与显式问题/待办沉淀到短期记忆。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdleConversationMemoryConsolidationJob {

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final ConversationGroupService conversationGroupService;
    private final ConversationMemorySummaryService summaryService;
    private final ShortTermMemoryStore shortTermMemoryStore;
    private final ShortTermMemoryMapper shortTermMemoryMapper;
    private final MemoryProperties memoryProperties;

    @Scheduled(cron = "${rag.memory.cleanup-cron:0 0/30 * * * ?}")
    public void consolidateIdleConversations() {
        Date cutoff = new Date(System.currentTimeMillis()
                - memoryProperties.getIdleConsolidationMinutes() * 60_000L);
        List<ConversationDO> idleConversations = conversationMapper.selectList(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getDeleted, 0)
                        .le(ConversationDO::getLastTime, cutoff)
                        .orderByAsc(ConversationDO::getLastTime)
                        .last("limit " + memoryProperties.getIdleConsolidationBatchSize()));
        for (ConversationDO conversation : idleConversations) {
            try {
                consolidate(conversation);
            } catch (Exception ex) {
                log.error("Idle conversation consolidation failed, conversationId={}, userId={}",
                        conversation.getConversationId(), conversation.getUserId(), ex);
            }
        }
    }

    private void consolidate(ConversationDO conversation) {
        ConversationMessageDO latestMessage = conversationMessageMapper.selectOne(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversation.getConversationId())
                        .eq(ConversationMessageDO::getUserId, conversation.getUserId())
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .orderByDesc(ConversationMessageDO::getId)
                        .last("limit 1"));
        if (latestMessage == null) {
            return;
        }
        ChatMessage latestSummary = summaryService.loadLatestSummary(
                conversation.getConversationId(), conversation.getUserId());
        if (latestSummary != null && latestSummary.getContent() != null && !latestSummary.getContent().isBlank()) {
            saveIfAbsent(conversation, "SUMMARY", latestSummary.getContent(), latestMessage.getId(), 0.72D, "idle-summary");
        }
        List<ConversationMessageDO> pendingMessages = conversationGroupService.listMessagesBetweenIds(
                conversation.getConversationId(),
                conversation.getUserId(),
                resolveLastSummaryMessageId(conversation.getConversationId(), conversation.getUserId()),
                null
        );
        for (ConversationMessageDO message : pendingMessages) {
            if (!"user".equalsIgnoreCase(message.getRole()) || message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            String type = classify(message.getContent());
            if (type == null) {
                continue;
            }
            saveIfAbsent(conversation, type, message.getContent(), message.getId(),
                    "ISSUE".equals(type) ? 0.78D : 0.74D, "idle-extract");
        }
    }

    private void saveIfAbsent(ConversationDO conversation,
                              String type,
                              String content,
                              String messageId,
                              double importance,
                              String source) {
        String sourceIdsJson = "[\"" + jsonEscape(messageId) + "\"]";
        ShortTermMemoryDO existing = shortTermMemoryMapper.selectOne(
                Wrappers.lambdaQuery(ShortTermMemoryDO.class)
                        .eq(ShortTermMemoryDO::getConversationId, conversation.getConversationId())
                        .eq(ShortTermMemoryDO::getUserId, conversation.getUserId())
                        .eq(ShortTermMemoryDO::getMemoryType, type)
                        .eq(ShortTermMemoryDO::getSourceMessageIds, sourceIdsJson)
                        .eq(ShortTermMemoryDO::getDeleted, 0)
                        .last("limit 1"));
        if (existing != null) {
            return;
        }
        shortTermMemoryStore.save(MemoryItem.builder()
                .userId(conversation.getUserId())
                .conversationId(conversation.getConversationId())
                .layer(MemoryLayer.SHORT_TERM)
                .type(type)
                .content(truncate(content, 240))
                .metadataJson("{\"source\":\"" + jsonEscape(source) + "\",\"content\":\"" + jsonEscape(truncate(content, 240)) + "\"}")
                .sourceIdsJson(sourceIdsJson)
                .importanceScore(importance)
                .confidenceLevel(0.8D)
                .build());
    }

    private String resolveLastSummaryMessageId(String conversationId, String userId) {
        var latestSummary = conversationGroupService.findLatestSummary(conversationId, userId);
        return latestSummary == null ? null : latestSummary.getLastMessageId();
    }

    private String classify(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        if (lower.contains("报错") || lower.contains("异常") || lower.contains("失败") || lower.contains("error") || lower.contains("问题")) {
            return "ISSUE";
        }
        if (lower.contains("待办") || lower.contains("todo") || lower.contains("需要") || lower.contains("帮我")) {
            return "TODO";
        }
        return null;
    }

    private String truncate(String content, int maxLength) {
        return content.length() <= maxLength ? content : content.substring(0, maxLength);
    }

    private String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
