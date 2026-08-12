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

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeDocumentCleanupEvent;
import com.nageoffer.ai.ragent.knowledge.schedule.DocumentStatusHelper;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentScheduleService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentServiceDeleteTest {

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Mock
    private KnowledgeDocumentMapper documentMapper;

    @Mock
    private KnowledgeDocumentScheduleService scheduleService;

    @Mock
    private KnowledgeDocumentChunkLogMapper chunkLogMapper;

    @Mock
    private KnowledgeChunkMapper chunkMapper;

    @Mock
    private VectorStoreService vectorStoreService;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private MessageQueueProducer messageQueueProducer;

    @Mock
    private DocumentStatusHelper documentStatusHelper;

    @Mock
    private BizChangeLogContext bizChangeLogContext;

    @InjectMocks
    private KnowledgeDocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        initTableInfo(KnowledgeDocumentDO.class);
        initTableInfo(com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentChunkLogDO.class);
        initTableInfo(com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO.class);
        ReflectionTestUtils.setField(service, "cleanupTopic", "document-cleanup");
    }

    private void initTableInfo(Class<?> entityType) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), entityType);
    }

    @Test
    @SuppressWarnings("unchecked")
    void localTransactionDeletesOnlyDatabaseResourcesAndPrimaryTransactionalVectors() {
        KnowledgeDocumentDO document = KnowledgeDocumentDO.builder()
                .id("doc-1")
                .kbId("kb-1")
                .docName("report.pdf")
                .fileUrl("collection-1/report.pdf")
                .status(DocumentStatus.SUCCESS.getCode())
                .documentVersion("version-1")
                .deleted(0)
                .build();
        when(documentMapper.selectById("doc-1")).thenReturn(document);
        when(knowledgeBaseMapper.selectById("kb-1")).thenReturn(KnowledgeBaseDO.builder()
                .id("kb-1")
                .collectionName("collection-1")
                .build());
        when(documentStatusHelper.tryMarkDeleting(eq("doc-1"), eq("version-1"), isNull()))
                .thenReturn("deleting-version");
        when(documentMapper.update(any(LambdaUpdateWrapper.class))).thenReturn(1);
        doAnswer(invocation -> {
            Consumer<Object> transaction = invocation.getArgument(4);
            transaction.accept(null);
            return null;
        }).when(messageQueueProducer)
                .sendInTransaction(eq("document-cleanup"), eq("doc-1"), eq("文档删除清理"), any(), any());

        service.delete("doc-1");

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messageQueueProducer).sendInTransaction(
                eq("document-cleanup"), eq("doc-1"), eq("文档删除清理"), eventCaptor.capture(), any());
        assertThat(eventCaptor.getValue()).isInstanceOfSatisfying(KnowledgeDocumentCleanupEvent.class, event -> {
            assertThat(event.getDocId()).isEqualTo("doc-1");
            assertThat(event.getCollectionName()).isEqualTo("collection-1");
            assertThat(event.getFileUrl()).isEqualTo("collection-1/report.pdf");
        });
        verify(scheduleService).deleteByDocId("doc-1");
        verify(chunkLogMapper).delete(any());
        verify(chunkMapper).delete(any());
        verify(vectorStoreService).deleteDocumentVectorsInTransaction("collection-1", "doc-1");
        verifyNoInteractions(fileStorageService);
    }
}
