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

package com.nageoffer.ai.ragent.knowledge.mq;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeDocumentCleanupEvent;
import com.nageoffer.ai.ragent.rag.core.graph.LightRagClient;
import com.nageoffer.ai.ragent.rag.core.keyword.KeywordIndexService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeDocumentCleanupConsumerTest {

    private VectorStoreService vectorStoreService;
    private FileStorageService fileStorageService;
    private KeywordIndexService keywordIndexService;
    private LightRagClient lightRagClient;
    private KnowledgeDocumentCleanupConsumer consumer;
    private MessageWrapper<KnowledgeDocumentCleanupEvent> message;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        vectorStoreService = mock(VectorStoreService.class);
        fileStorageService = mock(FileStorageService.class);
        keywordIndexService = mock(KeywordIndexService.class);
        lightRagClient = mock(LightRagClient.class);
        ObjectProvider<KeywordIndexService> keywordProvider = mock(ObjectProvider.class);
        ObjectProvider<LightRagClient> lightRagProvider = mock(ObjectProvider.class);
        when(keywordProvider.getIfAvailable()).thenReturn(keywordIndexService);
        when(lightRagProvider.getIfAvailable()).thenReturn(lightRagClient);
        consumer = new KnowledgeDocumentCleanupConsumer(
                vectorStoreService, fileStorageService, keywordProvider, lightRagProvider);
        message = MessageWrapper.<KnowledgeDocumentCleanupEvent>builder()
                .body(KnowledgeDocumentCleanupEvent.builder()
                        .docId("doc-1")
                        .collectionName("collection-1")
                        .fileUrl("collection-1/file.pdf")
                        .build())
                .build();
    }

    @Test
    void failureTriggersRetryOnlyAfterEveryResourceWasAttempted() {
        doThrow(new IllegalStateException("milvus unavailable"))
                .when(vectorStoreService).deleteDocumentVectorsAfterCommit("collection-1", "doc-1");
        doThrow(new IllegalStateException("elasticsearch unavailable"))
                .when(keywordIndexService).deleteDocumentIndex("collection-1", "doc-1");
        doThrow(new IllegalStateException("lightrag unavailable"))
                .when(lightRagClient).deleteByDocOrThrow("doc-1");
        doThrow(new IllegalStateException("object store unavailable"))
                .when(fileStorageService).deleteByUrl("collection-1/file.pdf");

        assertThrows(ServiceException.class, () -> consumer.onMessage(message));

        verify(vectorStoreService).deleteDocumentVectorsAfterCommit("collection-1", "doc-1");
        verify(keywordIndexService).deleteDocumentIndex("collection-1", "doc-1");
        verify(lightRagClient).deleteByDocOrThrow("doc-1");
        verify(fileStorageService).deleteByUrl("collection-1/file.pdf");
    }

    @Test
    void repeatedConsumptionRemainsSafeForIdempotentResources() {
        assertDoesNotThrow(() -> consumer.onMessage(message));
        assertDoesNotThrow(() -> consumer.onMessage(message));

        verify(vectorStoreService, times(2)).deleteDocumentVectorsAfterCommit("collection-1", "doc-1");
        verify(keywordIndexService, times(2)).deleteDocumentIndex("collection-1", "doc-1");
        verify(lightRagClient, times(2)).deleteByDocOrThrow("doc-1");
        verify(fileStorageService, times(2)).deleteByUrl("collection-1/file.pdf");
    }
}
