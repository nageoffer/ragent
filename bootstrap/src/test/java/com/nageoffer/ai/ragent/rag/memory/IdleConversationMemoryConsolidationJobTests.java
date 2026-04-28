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
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemorySummaryService;
import com.nageoffer.ai.ragent.rag.core.memory.IdleConversationMemoryConsolidationJob;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryItem;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationSummaryDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ShortTermMemoryDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ShortTermMemoryMapper;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import com.nageoffer.ai.ragent.rag.core.memory.store.ShortTermMemoryStore;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdleConversationMemoryConsolidationJobTests {

    @Test
    void shouldConsolidateSummaryAndIssueTodoForIdleConversation() {
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        ConversationMessageMapper conversationMessageMapper = mock(ConversationMessageMapper.class);
        ConversationGroupService conversationGroupService = mock(ConversationGroupService.class);
        ConversationMemorySummaryService summaryService = mock(ConversationMemorySummaryService.class);
        ShortTermMemoryStore shortTermMemoryStore = mock(ShortTermMemoryStore.class);
        ShortTermMemoryMapper shortTermMemoryMapper = mock(ShortTermMemoryMapper.class);
        MemoryProperties memoryProperties = new MemoryProperties();
        memoryProperties.setIdleConsolidationMinutes(30);
        memoryProperties.setIdleConsolidationBatchSize(10);

        ConversationDO conversation = ConversationDO.builder()
                .conversationId("c1")
                .userId("u1")
                .lastTime(new Date(System.currentTimeMillis() - 3600_000L))
                .build();
        ConversationMessageDO latestMessage = ConversationMessageDO.builder()
                .id("m3")
                .conversationId("c1")
                .userId("u1")
                .role("assistant")
                .content("最后一条回复")
                .build();
        ConversationMessageDO issueMessage = ConversationMessageDO.builder()
                .id("m4")
                .conversationId("c1")
                .userId("u1")
                .role("user")
                .content("这里报错了，帮我看下")
                .build();
        ConversationMessageDO todoMessage = ConversationMessageDO.builder()
                .id("m5")
                .conversationId("c1")
                .userId("u1")
                .role("user")
                .content("帮我整理一个待办清单")
                .build();

        when(conversationMapper.selectList(any())).thenReturn(List.of(conversation));
        when(conversationMessageMapper.selectOne(any())).thenReturn(latestMessage);
        when(summaryService.loadLatestSummary("c1", "u1")).thenReturn(ChatMessage.system("这是会话摘要"));
        when(conversationGroupService.findLatestSummary("c1", "u1")).thenReturn(
                ConversationSummaryDO.builder().lastMessageId("m2").build()
        );
        when(conversationGroupService.listMessagesBetweenIds("c1", "u1", "m2", null))
                .thenReturn(List.of(issueMessage, todoMessage));
        when(shortTermMemoryMapper.selectOne(any())).thenReturn(null, null, null);

        IdleConversationMemoryConsolidationJob job = new IdleConversationMemoryConsolidationJob(
                conversationMapper,
                conversationMessageMapper,
                conversationGroupService,
                summaryService,
                shortTermMemoryStore,
                shortTermMemoryMapper,
                memoryProperties
        );

        job.consolidateIdleConversations();

        ArgumentCaptor<MemoryItem> captor = ArgumentCaptor.forClass(MemoryItem.class);
        verify(shortTermMemoryStore, times(3)).save(captor.capture());
        List<MemoryItem> items = captor.getAllValues();
        Assertions.assertEquals("SUMMARY", items.get(0).getType());
        Assertions.assertEquals("[\"m3\"]", items.get(0).getSourceIdsJson());
        Assertions.assertEquals("ISSUE", items.get(1).getType());
        Assertions.assertEquals("TODO", items.get(2).getType());
    }

    @Test
    void shouldSkipWhenConsolidatedSourceAlreadyExists() {
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        ConversationMessageMapper conversationMessageMapper = mock(ConversationMessageMapper.class);
        ConversationGroupService conversationGroupService = mock(ConversationGroupService.class);
        ConversationMemorySummaryService summaryService = mock(ConversationMemorySummaryService.class);
        ShortTermMemoryStore shortTermMemoryStore = mock(ShortTermMemoryStore.class);
        ShortTermMemoryMapper shortTermMemoryMapper = mock(ShortTermMemoryMapper.class);
        MemoryProperties memoryProperties = new MemoryProperties();
        memoryProperties.setIdleConsolidationMinutes(30);
        memoryProperties.setIdleConsolidationBatchSize(10);

        ConversationDO conversation = ConversationDO.builder()
                .conversationId("c2")
                .userId("u2")
                .lastTime(new Date(System.currentTimeMillis() - 3600_000L))
                .build();
        ConversationMessageDO latestMessage = ConversationMessageDO.builder()
                .id("m10")
                .conversationId("c2")
                .userId("u2")
                .role("assistant")
                .content("最后一条回复")
                .build();

        when(conversationMapper.selectList(any())).thenReturn(List.of(conversation));
        when(conversationMessageMapper.selectOne(any())).thenReturn(latestMessage);
        when(summaryService.loadLatestSummary("c2", "u2")).thenReturn(ChatMessage.system("摘要"));
        when(conversationGroupService.findLatestSummary("c2", "u2")).thenReturn(null);
        when(conversationGroupService.listMessagesBetweenIds("c2", "u2", null, null)).thenReturn(List.of());
        when(shortTermMemoryMapper.selectOne(any())).thenReturn(ShortTermMemoryDO.builder().id("stm1").build());

        IdleConversationMemoryConsolidationJob job = new IdleConversationMemoryConsolidationJob(
                conversationMapper,
                conversationMessageMapper,
                conversationGroupService,
                summaryService,
                shortTermMemoryStore,
                shortTermMemoryMapper,
                memoryProperties
        );

        job.consolidateIdleConversations();

        verify(shortTermMemoryStore, never()).save(any());
    }
}
