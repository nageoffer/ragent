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
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemorySummaryService;
import com.nageoffer.ai.ragent.rag.core.memory.DefaultMemoryEngine;
import com.nageoffer.ai.ragent.rag.core.memory.ForgettingController;
import com.nageoffer.ai.ragent.rag.core.memory.MemoryActivator;
import com.nageoffer.ai.ragent.rag.core.memory.MemoryQualityAssessor;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryItem;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryWriteRequest;
import com.nageoffer.ai.ragent.rag.core.memory.store.LongTermMemoryStore;
import com.nageoffer.ai.ragent.rag.core.memory.store.SemanticMemoryStore;
import com.nageoffer.ai.ragent.rag.core.memory.store.ShortTermMemoryStore;
import com.nageoffer.ai.ragent.rag.core.memory.store.WorkingMemoryStore;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultMemoryEngineTests {

    private final MemoryActivator memoryActivator = mock(MemoryActivator.class);
    private final WorkingMemoryStore workingMemoryStore = mock(WorkingMemoryStore.class);
    private final ShortTermMemoryStore shortTermMemoryStore = mock(ShortTermMemoryStore.class);
    private final LongTermMemoryStore longTermMemoryStore = mock(LongTermMemoryStore.class);
    private final SemanticMemoryStore semanticMemoryStore = mock(SemanticMemoryStore.class);
    private final ConversationMemorySummaryService summaryService = mock(ConversationMemorySummaryService.class);
    private final MemoryQualityAssessor memoryQualityAssessor = mock(MemoryQualityAssessor.class);
    private final ForgettingController forgettingController = mock(ForgettingController.class);
    private final Executor directExecutor = Runnable::run;

    @Test
    void shouldExtractPreferenceMemoryFromUserMessage() {
        DefaultMemoryEngine engine = new DefaultMemoryEngine(
                memoryActivator,
                workingMemoryStore,
                shortTermMemoryStore,
                longTermMemoryStore,
                semanticMemoryStore,
                summaryService,
                memoryQualityAssessor,
                forgettingController,
                directExecutor
        );

        engine.writeMemory(MemoryWriteRequest.builder()
                .conversationId("c1")
                .userId("u1")
                .messageId("m1")
                .message(ChatMessage.user("我喜欢中文回复"))
                .build());

        verify(workingMemoryStore).append("c1", "u1", ChatMessage.user("我喜欢中文回复"));
        ArgumentCaptor<MemoryItem> itemCaptor = ArgumentCaptor.forClass(MemoryItem.class);
        verify(shortTermMemoryStore).save(itemCaptor.capture());
        Assertions.assertEquals("PREFERENCE", itemCaptor.getValue().getType());
        Assertions.assertEquals("我喜欢中文回复", itemCaptor.getValue().getContent());
        Assertions.assertEquals("[\"m1\"]", itemCaptor.getValue().getSourceIdsJson());
        Assertions.assertTrue(itemCaptor.getValue().getMetadataJson().contains("我喜欢中文回复"));
        verify(semanticMemoryStore).upsert(any(MemoryItem.class), eq("中文回复"));
        verify(summaryService, never()).loadLatestSummary(any(), any());
    }

    @Test
    void shouldPersistSummaryForAssistantMessageWhenAvailable() {
        DefaultMemoryEngine engine = new DefaultMemoryEngine(
                memoryActivator,
                workingMemoryStore,
                shortTermMemoryStore,
                longTermMemoryStore,
                semanticMemoryStore,
                summaryService,
                memoryQualityAssessor,
                forgettingController,
                directExecutor
        );
        when(summaryService.loadLatestSummary("c2", "u2")).thenReturn(ChatMessage.system("这是摘要"));

        engine.writeMemory(MemoryWriteRequest.builder()
                .conversationId("c2")
                .userId("u2")
                .messageId("m2")
                .message(ChatMessage.assistant("这是一次很长的回答"))
                .build());

        ArgumentCaptor<MemoryItem> itemCaptor = ArgumentCaptor.forClass(MemoryItem.class);
        verify(shortTermMemoryStore).save(itemCaptor.capture());
        Assertions.assertEquals("SUMMARY", itemCaptor.getValue().getType());
        Assertions.assertEquals("这是摘要", itemCaptor.getValue().getContent());
        Assertions.assertEquals("[\"m2\"]", itemCaptor.getValue().getSourceIdsJson());
        verify(semanticMemoryStore, never()).upsert(any(), any());
    }
}
