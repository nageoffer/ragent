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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategyFactory;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.core.parser.DocumentParserSelector;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import com.nageoffer.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.nageoffer.ai.ragent.knowledge.schedule.DocumentStatusHelper;
import com.nageoffer.ai.ragent.knowledge.service.CleanupTaskService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentScheduleService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentServiceImplRaceTest {

    @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock private KnowledgeDocumentMapper documentMapper;
    @Mock private DocumentParserSelector parserSelector;
    @Mock private ChunkingStrategyFactory chunkingStrategyFactory;
    @Mock private FileStorageService fileStorageService;
    @Mock private VectorStoreService vectorStoreService;
    @Mock private KnowledgeChunkService knowledgeChunkService;
    @Mock private ObjectMapper objectMapper;
    @Mock private KnowledgeDocumentScheduleService scheduleService;
    @Mock private IngestionPipelineService ingestionPipelineService;
    @Mock private IngestionPipelineMapper ingestionPipelineMapper;
    @Mock private IngestionEngine ingestionEngine;
    @Mock private ChunkEmbeddingService chunkEmbeddingService;
    @Mock private KnowledgeDocumentChunkLogMapper chunkLogMapper;
    @Mock private KnowledgeChunkMapper chunkMapper;
    @Mock private TransactionOperations transactionOperations;
    @Mock private MessageQueueProducer messageQueueProducer;
    @Mock private KnowledgeScheduleProperties scheduleProperties;
    @Mock private RemoteFileFetcher remoteFileFetcher;
    @Mock private DocumentStatusHelper documentStatusHelper;
    @Mock private CleanupTaskService cleanupTaskService;
    @Mock private TransactionStatus transactionStatus;

    @InjectMocks
    private KnowledgeDocumentServiceImpl service;

    @Test
    void persistChunksAndVectorsAtomically_writesChunksAndReturnsCount() {
        runTransactionCallback();
        when(documentMapper.selectStatusById("doc-1")).thenReturn(DocumentStatus.RUNNING.getCode());
        when(documentStatusHelper.tryMarkSuccess("doc-1")).thenReturn(true);

        Integer saved = invokePersist();

        assertThat(saved).isEqualTo(1);
        verify(documentStatusHelper).tryMarkSuccess("doc-1");
        ArgumentCaptor<KnowledgeDocumentDO> captor = ArgumentCaptor.forClass(KnowledgeDocumentDO.class);
        verify(documentMapper).updateById(captor.capture());
        assertThat(captor.getValue().getChunkCount()).isEqualTo(1);
    }

    @Test
    void persistChunksAndVectorsAtomically_whenDocumentNoLongerRunning_rollsBackAndSkipsWrites() {
        runTransactionCallback();
        when(documentMapper.selectStatusById("doc-1")).thenReturn(DocumentStatus.DELETING.getCode());

        assertThatThrownBy(this::invokePersist)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("document status changed");

        verify(transactionStatus).setRollbackOnly();
        verifyNoInteractions(knowledgeChunkService, vectorStoreService);
        verify(documentMapper, never()).updateById(any(KnowledgeDocumentDO.class));
    }

    @Test
    void persistChunksAndVectorsAtomically_whenSuccessCasFailsAfterVectorWrite_doesNotEnqueueVectorCleanup() {
        runTransactionCallback();
        when(documentMapper.selectStatusById("doc-1")).thenReturn(DocumentStatus.RUNNING.getCode());
        when(documentStatusHelper.tryMarkSuccess("doc-1")).thenReturn(false);

        assertThatThrownBy(this::invokePersist)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("mark success failed");

        verify(transactionStatus).setRollbackOnly();
        verify(vectorStoreService).deleteDocumentVectors("collection", "doc-1");
        verify(vectorStoreService).indexDocumentChunks(eq("collection"), eq("doc-1"), any());
        verifyNoInteractions(cleanupTaskService);
        verify(documentMapper, never()).updateById(any(KnowledgeDocumentDO.class));
    }

    private void runTransactionCallback() {
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(transactionStatus);
            return null;
        }).when(transactionOperations).executeWithoutResult(any());
    }

    private Integer invokePersist() {
        List<VectorChunk> chunks = List.of(VectorChunk.builder()
                .chunkId("chunk-1")
                .index(0)
                .content("content")
                .build());
        return (Integer) ReflectionTestUtils.invokeMethod(
                service,
                "persistChunksAndVectorsAtomically",
                "collection",
                "doc-1",
                chunks
        );
    }
}
