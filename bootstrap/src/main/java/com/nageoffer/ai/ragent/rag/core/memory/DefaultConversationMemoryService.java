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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryContext;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryLoadRequest;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryWriteRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 默认对话记忆服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultConversationMemoryService implements ConversationMemoryService {

    private final ConversationMemoryStore memoryStore;
    private final ConversationMemorySummaryService summaryService;
    private final MemoryEngine memoryEngine;

    @Override
    public List<ChatMessage> load(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return List.of();
        }
        try {
            MemoryContext memoryContext = memoryEngine.loadMemory(MemoryLoadRequest.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .build());
            return memoryContext.getPromptMessages();
        } catch (Exception ex) {
            log.error("Load memory failed, conversationId={}, userId={}", conversationId, userId, ex);
            return List.of();
        }
    }

    @Override
    public String append(String conversationId, String userId, ChatMessage message) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return null;
        }
        String messageId = memoryStore.append(conversationId, userId, message);
        summaryService.compressIfNeeded(conversationId, userId, message);
        memoryEngine.writeMemory(MemoryWriteRequest.builder()
                .conversationId(conversationId)
                .userId(userId)
                .messageId(messageId)
                .message(message)
                .build());
        return messageId;
    }

    @Override
    public List<ChatMessage> loadAndAppend(String conversationId, String userId, ChatMessage message) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId) || message == null) {
            return List.of();
        }
        MemoryContext memoryContext = memoryEngine.loadMemory(MemoryLoadRequest.builder()
                .conversationId(conversationId)
                .userId(userId)
                .currentQuestion(message.getContent())
                .build());
        append(conversationId, userId, message);
        return memoryContext.getPromptMessages();
    }
}
