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

package com.nageoffer.ai.ragent.rag.memory;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryStore;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemorySummaryService;
import com.nageoffer.ai.ragent.rag.core.memory.DefaultConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.memory.MemoryEngine;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultConversationMemoryServiceTests {

    @Test
    void shouldAppendAndDelegateToEngine() {
        ConversationMemoryStore memoryStore = mock(ConversationMemoryStore.class);
        ConversationMemorySummaryService summaryService = mock(ConversationMemorySummaryService.class);
        MemoryEngine memoryEngine = mock(MemoryEngine.class);
        when(memoryStore.append(eq("c1"), eq("u1"), any(ChatMessage.class))).thenReturn("m1");

        DefaultConversationMemoryService service = new DefaultConversationMemoryService(
                memoryStore, summaryService, memoryEngine
        );

        String messageId = service.append("c1", "u1", ChatMessage.user("hello"));

        Assertions.assertEquals("m1", messageId);
        verify(summaryService).compressIfNeeded(eq("c1"), eq("u1"), any(ChatMessage.class));
        verify(memoryEngine).writeMemory(any());
    }

    @Test
    void shouldPassCurrentQuestionWhenLoadAndAppend() {
        ConversationMemoryStore memoryStore = mock(ConversationMemoryStore.class);
        ConversationMemorySummaryService summaryService = mock(ConversationMemorySummaryService.class);
        MemoryEngine memoryEngine = mock(MemoryEngine.class);
        when(memoryStore.append(eq("c1"), eq("u1"), any(ChatMessage.class))).thenReturn("m1");
        when(memoryEngine.loadMemory(any())).thenReturn(MemoryContext.builder()
                .promptMessages(List.of(ChatMessage.system("memory")))
                .build());

        DefaultConversationMemoryService service = new DefaultConversationMemoryService(
                memoryStore, summaryService, memoryEngine
        );

        List<ChatMessage> result = service.loadAndAppend("c1", "u1", ChatMessage.user("当前问题"));

        Assertions.assertEquals(1, result.size());
        ArgumentCaptor<com.nageoffer.ai.ragent.rag.core.memory.model.MemoryLoadRequest> captor =
                ArgumentCaptor.forClass(com.nageoffer.ai.ragent.rag.core.memory.model.MemoryLoadRequest.class);
        verify(memoryEngine).loadMemory(captor.capture());
        Assertions.assertEquals("当前问题", captor.getValue().getCurrentQuestion());
    }
}
