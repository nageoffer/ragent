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
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.core.ingest.IngestionKernel;
import com.nageoffer.ai.ragent.core.ingest.sink.ChunkIndexWriter;
import com.nageoffer.ai.ragent.core.parser.registry.ParserRegistry;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import com.nageoffer.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentScheduleService;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecCodec;
import com.nageoffer.ai.ragent.knowledge.support.VectorTargetResolver;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentServiceImplUploadTest {

    private static final String KB_ID = "kb-1";
    private static final String COLLECTION_NAME = "collection-1";
    private static final String FILE_URL = "collection-1/document.pdf";

    @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock private KnowledgeDocumentMapper documentMapper;
    @Mock private ParserRegistry parserRegistry;
    @Mock private IngestionKernel ingestionKernel;
    @Mock private ChunkIndexWriter chunkIndexWriter;
    @Mock private IngestionSpecCodec ingestionSpecCodec;
    @Mock private FileStorageService fileStorageService;
    @Mock private VectorStoreService vectorStoreService;
    @Mock private KnowledgeChunkService knowledgeChunkService;
    @Mock private KnowledgeDocumentScheduleService scheduleService;
    @Mock private IngestionPipelineService ingestionPipelineService;
    @Mock private IngestionPipelineMapper ingestionPipelineMapper;
    @Mock private IngestionEngine ingestionEngine;
    @Mock private KnowledgeDocumentChunkLogMapper chunkLogMapper;
    @Mock private KnowledgeChunkMapper chunkMapper;
    @Mock private TransactionOperations transactionOperations;
    @Mock private MessageQueueProducer messageQueueProducer;
    @Mock private KnowledgeScheduleProperties scheduleProperties;
    @Mock private RemoteFileFetcher remoteFileFetcher;
    @Mock private VectorTargetResolver vectorTargetResolver;
    @Mock private BizChangeLogContext bizChangeLogContext;
    @Mock private MultipartFile file;

    private KnowledgeDocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeDocumentServiceImpl(
                knowledgeBaseMapper,
                documentMapper,
                parserRegistry,
                ingestionKernel,
                chunkIndexWriter,
                ingestionSpecCodec,
                fileStorageService,
                vectorStoreService,
                knowledgeChunkService,
                new ObjectMapper(),
                scheduleService,
                ingestionPipelineService,
                ingestionPipelineMapper,
                ingestionEngine,
                chunkLogMapper,
                chunkMapper,
                transactionOperations,
                messageQueueProducer,
                scheduleProperties,
                remoteFileFetcher,
                vectorTargetResolver,
                bizChangeLogContext
        );
        when(knowledgeBaseMapper.selectById(KB_ID)).thenReturn(KnowledgeBaseDO.builder()
                .id(KB_ID)
                .collectionName(COLLECTION_NAME)
                .build());
    }

    @Test
    void invalidProcessModeConfigurationDoesNotUploadFile() {
        KnowledgeDocumentUploadRequest request = pipelineRequestWithoutId();

        assertThrows(ClientException.class, () -> service.upload(KB_ID, request, file));

        verify(fileStorageService, never()).upload(COLLECTION_NAME, file);
    }

    @Test
    void invalidUrlProcessModeConfigurationDoesNotFetchRemoteFile() {
        KnowledgeDocumentUploadRequest request = pipelineRequestWithoutId();
        request.setSourceType("url");
        request.setSourceLocation("https://example.com/document.pdf");

        assertThrows(ClientException.class, () -> service.upload(KB_ID, request, null));

        verifyNoInteractions(remoteFileFetcher);
    }

    @Test
    void insertFailureDeletesUploadedFileAndRethrowsOriginalException() {
        RuntimeException original = new RuntimeException("insert failed");
        stubSuccessfulUploadPreparation();
        stubInsertFailureWithAssignedId(original);
        when(documentMapper.selectById("doc-1")).thenReturn(null);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> service.upload(KB_ID, chunkRequest(), file)
        );

        assertSame(original, thrown);
        verify(fileStorageService).deleteByUrl(FILE_URL);
        verify(documentMapper).selectById("doc-1");
    }

    @Test
    void cleanupFailureDoesNotReplaceInsertFailure() {
        RuntimeException original = new RuntimeException("insert failed");
        stubSuccessfulUploadPreparation();
        stubInsertFailureWithAssignedId(original);
        when(documentMapper.selectById("doc-1")).thenReturn(null);
        doThrow(new RuntimeException("delete failed")).when(fileStorageService).deleteByUrl(FILE_URL);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> service.upload(KB_ID, chunkRequest(), file)
        );

        assertSame(original, thrown);
        verify(fileStorageService).deleteByUrl(FILE_URL);
        verify(documentMapper).selectById("doc-1");
    }

    @Test
    void insertExceptionDoesNotDeleteFileWhenDocumentExists() {
        RuntimeException original = new RuntimeException("insert result unknown");
        stubSuccessfulUploadPreparation();
        stubInsertFailureWithAssignedId(original);
        when(documentMapper.selectById("doc-1")).thenReturn(KnowledgeDocumentDO.builder().id("doc-1").build());

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> service.upload(KB_ID, chunkRequest(), file)
        );

        assertSame(original, thrown);
        verify(fileStorageService, never()).deleteByUrl(FILE_URL);
    }

    @Test
    void insertExceptionWithoutAssignedIdKeepsFileAndOriginalException() {
        RuntimeException original = new RuntimeException("insert failed before id assignment");
        stubSuccessfulUploadPreparation();
        when(documentMapper.insert(any(KnowledgeDocumentDO.class))).thenThrow(original);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> service.upload(KB_ID, chunkRequest(), file)
        );

        assertSame(original, thrown);
        verify(documentMapper, never()).selectById(any());
        verify(fileStorageService, never()).deleteByUrl(FILE_URL);
    }

    @Test
    void verificationFailureDoesNotDeleteFileOrReplaceInsertFailure() {
        RuntimeException original = new RuntimeException("insert result unknown");
        stubSuccessfulUploadPreparation();
        stubInsertFailureWithAssignedId(original);
        when(documentMapper.selectById("doc-1")).thenThrow(new RuntimeException("query failed"));

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> service.upload(KB_ID, chunkRequest(), file)
        );

        assertSame(original, thrown);
        verify(fileStorageService, never()).deleteByUrl(FILE_URL);
    }

    @Test
    void successfulUploadDoesNotDeleteStoredFile() {
        stubSuccessfulUploadPreparation();
        doAnswer(invocation -> {
            KnowledgeDocumentDO document = invocation.getArgument(0);
            document.setId("doc-1");
            return 1;
        }).when(documentMapper).insert(any(KnowledgeDocumentDO.class));

        service.upload(KB_ID, chunkRequest(), file);

        verify(fileStorageService, never()).deleteByUrl(FILE_URL);
    }

    @Test
    void zeroInsertResultDeletesFileOnlyAfterConfirmingDocumentIsAbsent() {
        stubSuccessfulUploadPreparation();
        doAnswer(invocation -> {
            KnowledgeDocumentDO document = invocation.getArgument(0);
            document.setId("doc-1");
            return 0;
        }).when(documentMapper).insert(any(KnowledgeDocumentDO.class));
        when(documentMapper.selectById("doc-1")).thenReturn(null);

        assertThrows(
                ClientException.class,
                () -> service.upload(KB_ID, chunkRequest(), file)
        );

        verify(documentMapper).selectById("doc-1");
        verify(fileStorageService).deleteByUrl(FILE_URL);
    }

    @Test
    void auditFailureAfterInsertDoesNotDeleteStoredFile() {
        RuntimeException auditFailure = new RuntimeException("audit failed");
        stubSuccessfulUploadPreparation();
        doAnswer(invocation -> {
            KnowledgeDocumentDO document = invocation.getArgument(0);
            document.setId("doc-1");
            return 1;
        }).when(documentMapper).insert(any(KnowledgeDocumentDO.class));
        doThrow(auditFailure).when(bizChangeLogContext).put(any(), any(), any());

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> service.upload(KB_ID, chunkRequest(), file)
        );

        assertSame(auditFailure, thrown);
        verify(fileStorageService, never()).deleteByUrl(FILE_URL);
    }

    @Test
    void unsupportedMimeTypeDeletesStoredFileExactlyOnce() {
        when(fileStorageService.upload(COLLECTION_NAME, file)).thenReturn(storedFile());

        assertThrows(ClientException.class, () -> service.upload(KB_ID, chunkRequest(), file));

        verify(fileStorageService).deleteByUrl(FILE_URL);
    }

    private void stubSuccessfulUploadPreparation() {
        when(fileStorageService.upload(COLLECTION_NAME, file)).thenReturn(storedFile());
        when(parserRegistry.canParse("application/pdf")).thenReturn(true);
    }

    private void stubInsertFailureWithAssignedId(RuntimeException failure) {
        when(documentMapper.insert(any(KnowledgeDocumentDO.class))).thenAnswer(invocation -> {
            KnowledgeDocumentDO document = invocation.getArgument(0);
            document.setId("doc-1");
            throw failure;
        });
    }

    private KnowledgeDocumentUploadRequest chunkRequest() {
        KnowledgeDocumentUploadRequest request = new KnowledgeDocumentUploadRequest();
        request.setSourceType("file");
        request.setProcessMode("chunk");
        return request;
    }

    private KnowledgeDocumentUploadRequest pipelineRequestWithoutId() {
        KnowledgeDocumentUploadRequest request = new KnowledgeDocumentUploadRequest();
        request.setSourceType("file");
        request.setProcessMode("pipeline");
        return request;
    }

    private StoredFileDTO storedFile() {
        return StoredFileDTO.builder()
                .url(FILE_URL)
                .detectedType("pdf")
                .mimeType("application/pdf")
                .size(128L)
                .originalFilename("document.pdf")
                .build();
    }
}
