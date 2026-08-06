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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.core.ingest.IngestionKernel;
import com.nageoffer.ai.ragent.core.ingest.sink.ChunkIndexWriter;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.knowledge.schedule.DocumentStatusHelper;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentScheduleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证事务消息入口、过期消费和删除快速失败/CAS 冲突时不会继续执行副作用。 */
@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentServiceStateTest {

    @Mock private KnowledgeDocumentMapper documentMapper;
    @Mock private IngestionKernel ingestionKernel;
    @Mock private ChunkIndexWriter chunkIndexWriter;
    @Mock private KnowledgeDocumentScheduleService scheduleService;
    @Mock private MessageQueueProducer messageQueueProducer;
    @Mock private BizChangeLogContext bizChangeLogContext;
    @Mock private DocumentStatusHelper documentStatusHelper;

    @InjectMocks
    private KnowledgeDocumentServiceImpl service;

    @Test
    void startChunkDoesNotCommitLocalWorkWhenStateClaimFails() {
        KnowledgeDocumentDO document = document(DocumentStatus.SUCCESS);
        when(documentMapper.selectById("doc-1")).thenReturn(document);
        when(documentStatusHelper.tryStartChunk(eq("doc-1"), isNull())).thenReturn(false);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<Object> callback = invocation.getArgument(4);
            callback.accept(null);
            return null;
        }).when(messageQueueProducer).sendInTransaction(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.startChunk("doc-1"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("无法开始分块");

        verify(scheduleService, never()).upsertSchedule(any());
    }

    @Test
    void executeChunkSkipsDelayedMessageWhenDocumentIsNotRunning() {
        when(documentMapper.selectById("doc-1")).thenReturn(document(DocumentStatus.SUCCESS));

        service.executeChunk("doc-1");

        verifyNoInteractions(ingestionKernel);
    }

    @Test
    void deleteFailsFastWithoutCasWhenDocumentIsRunning() {
        KnowledgeDocumentDO document = document(DocumentStatus.RUNNING);
        when(documentMapper.selectById("doc-1")).thenReturn(document);

        assertThatThrownBy(() -> service.delete("doc-1"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("正在分块中");

        verifyNoInteractions(documentStatusHelper);
        verifyNoInteractions(scheduleService, chunkIndexWriter);
        verify(documentMapper, never()).deleteById(any(KnowledgeDocumentDO.class));
    }

    @Test
    void deleteStopsBeforeCleanupWhenDeletingClaimFails() {
        KnowledgeDocumentDO document = document(DocumentStatus.SUCCESS);
        when(documentMapper.selectById("doc-1")).thenReturn(document);
        when(documentStatusHelper.tryMarkDeleting(eq("doc-1"), isNull())).thenReturn(false);

        assertThatThrownBy(() -> service.delete("doc-1"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("无法删除");

        verifyNoInteractions(scheduleService, chunkIndexWriter);
        verify(documentMapper, never()).deleteById(any(KnowledgeDocumentDO.class));
    }

    private KnowledgeDocumentDO document(DocumentStatus status) {
        return KnowledgeDocumentDO.builder()
                .id("doc-1")
                .kbId("kb-1")
                .docName("guide.md")
                .status(status.getCode())
                .build();
    }
}
