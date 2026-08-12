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
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeBaseCleanupEvent;
import com.nageoffer.ai.ragent.rag.core.graph.LightRagClient;
import com.nageoffer.ai.ragent.rag.core.keyword.KeywordIndexService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseCleanupConsumerTest {

    @Test
    @SuppressWarnings("unchecked")
    void lightRagFailureStillTriggersKnowledgeBaseCleanupRetry() {
        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        KeywordIndexService keywordIndexService = mock(KeywordIndexService.class);
        LightRagClient lightRagClient = mock(LightRagClient.class);
        ObjectProvider<KeywordIndexService> keywordProvider = mock(ObjectProvider.class);
        ObjectProvider<LightRagClient> graphProvider = mock(ObjectProvider.class);
        when(keywordProvider.getIfAvailable()).thenReturn(keywordIndexService);
        when(graphProvider.getIfAvailable()).thenReturn(lightRagClient);
        doThrow(new IllegalStateException("lightrag unavailable"))
                .when(lightRagClient).deleteByCollectionOrThrow("collection-1");
        KnowledgeBaseCleanupConsumer consumer = new KnowledgeBaseCleanupConsumer(
                vectorStoreAdmin, fileStorageService, keywordProvider, graphProvider);
        MessageWrapper<KnowledgeBaseCleanupEvent> message = MessageWrapper.<KnowledgeBaseCleanupEvent>builder()
                .body(KnowledgeBaseCleanupEvent.builder()
                        .kbId("kb-1")
                        .collectionName("collection-1")
                        .build())
                .build();

        assertThrows(ServiceException.class, () -> consumer.onMessage(message));

        verify(vectorStoreAdmin).dropVectorSpace("collection-1");
        verify(fileStorageService).deleteKnowledgeSpace("collection-1");
        verify(keywordIndexService).deleteByCollection("collection-1");
        verify(lightRagClient).deleteByCollectionOrThrow("collection-1");
    }
}
