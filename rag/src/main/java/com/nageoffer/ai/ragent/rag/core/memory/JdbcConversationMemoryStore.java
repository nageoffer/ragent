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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.core.source.CitationMarkup;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.enums.ConversationMessageOrder;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.ConversationService;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationCreateBO;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationMessageBO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class JdbcConversationMemoryStore implements ConversationMemoryStore {

    private final ConversationService conversationService;
    private final ConversationMessageService conversationMessageService;
    private final MemoryProperties memoryProperties;

    public JdbcConversationMemoryStore(ConversationService conversationService,
                                       ConversationMessageService conversationMessageService,
                                       MemoryProperties memoryProperties) {
        this.conversationService = conversationService;
        this.conversationMessageService = conversationMessageService;
        this.memoryProperties = memoryProperties;
    }

    @Override
    public List<ChatMessage> loadHistory(String conversationId, String userId) {
        int maxMessages = resolveMaxHistoryMessages();
        List<ConversationMessageVO> dbMessages = conversationMessageService.listMessages(
                conversationId,
                userId,
                maxMessages,
                ConversationMessageOrder.DESC
        );
        if (CollUtil.isEmpty(dbMessages)) {
            return List.of();
        }

        List<ChatMessage> result = dbMessages.stream()
                .map(this::toChatMessage)
                .filter(this::isHistoryMessage)
                .collect(Collectors.toList());

        return normalizeHistory(result);
    }

    @Override
    public String append(String conversationId, String userId, ChatMessage message) {
        ConversationMessageBO conversationMessage = ConversationMessageBO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(message.getRole().name().toLowerCase())
                .content(message.getContent())
                .thinkingContent(message.getThinkingContent())
                .thinkingDuration(message.getThinkingDuration())
                .sources(message.getSources())
                .retrievedChunks(message.getRetrievedChunks())
                .replyToMessageId(message.getReplyToMessageId())
                .messageStatus(message.getMessageStatus() == null ? null : message.getMessageStatus().name())
                .build();
        String messageId = conversationMessageService.addMessage(conversationMessage);

        if (message.getRole() == ChatMessage.Role.USER) {
            ConversationCreateBO conversation = ConversationCreateBO.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .question(message.getContent())
                    .lastTime(new Date())
                    .build();
            conversationService.createOrUpdate(conversation);
        }
        return messageId;
    }

    @Override
    public void refreshCache(String conversationId, String userId) {
        // JDBC 直读模式，无需刷新缓存
    }

    private ChatMessage toChatMessage(ConversationMessageVO record) {
        if (record == null || StrUtil.isBlank(record.getContent())) {
            return null;
        }
        ChatMessage.Role role = ChatMessage.Role.fromString(record.getRole());
        String content = role == ChatMessage.Role.ASSISTANT
                ? CitationMarkup.strip(record.getContent())
                : record.getContent();
        return new ChatMessage(
                role,
                content
        );
    }

    private List<ChatMessage> normalizeHistory(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int start = 0;
        while (start < messages.size() && messages.get(start).getRole() == ChatMessage.Role.ASSISTANT) {
            start++;
        }
        if (start >= messages.size()) {
            return List.of();
        }
        return messages.subList(start, messages.size());
    }

    private boolean isHistoryMessage(ChatMessage message) {
        return message != null
                && (message.getRole() == ChatMessage.Role.USER || message.getRole() == ChatMessage.Role.ASSISTANT)
                && StrUtil.isNotBlank(message.getContent());
    }

    private int resolveMaxHistoryMessages() {
        int maxTurns = memoryProperties.getHistoryKeepTurns();
        return maxTurns * 2;
    }
}
