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

package com.nageoffer.ai.ragent.rag.core.vector.decorator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.core.graph.LightRagClient;
import com.nageoffer.ai.ragent.rag.core.keyword.KeywordIndexService;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.vector.MilvusVectorStoreService;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorStoreService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class VectorStoreCleanupPhaseTest {

    @Test
    void cleanupPhaseMethodsBypassKeywordAndGraphDecorators() {
        VectorStoreService primary = mock(VectorStoreService.class);
        KeywordIndexService keyword = mock(KeywordIndexService.class);
        LightRagClient graph = mock(LightRagClient.class);
        VectorStoreService decorated = new GraphSyncingVectorStoreService(
                new KeywordSyncingVectorStoreService(primary, keyword), graph);

        decorated.deleteDocumentVectorsInTransaction("collection-1", "doc-1");
        decorated.deleteDocumentVectorsAfterCommit("collection-1", "doc-1");

        verify(primary).deleteDocumentVectorsInTransaction("collection-1", "doc-1");
        verify(primary).deleteDocumentVectorsAfterCommit("collection-1", "doc-1");
        verifyNoInteractions(keyword, graph);
    }

    @Test
    void pgVectorDeletesInLocalTransactionAndSkipsAfterCommitPhase() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PgVectorStoreService pgVector = new PgVectorStoreService(jdbcTemplate, new ObjectMapper());

        pgVector.deleteDocumentVectorsInTransaction("collection-1", "doc-1");
        pgVector.deleteDocumentVectorsAfterCommit("collection-1", "doc-1");

        verify(jdbcTemplate).update(
                "DELETE FROM t_knowledge_vector WHERE collection_name = ? AND metadata->>'doc_id' = ?",
                "collection-1",
                "doc-1");
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void milvusSkipsLocalTransactionAndDeletesOnlyAfterCommit() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        RAGDefaultProperties properties = new RAGDefaultProperties();
        properties.setCollectionName("shared-vectors");
        MilvusVectorStoreService milvus = new MilvusVectorStoreService(client, properties);

        milvus.deleteDocumentVectorsInTransaction("collection-1", "doc-1");
        verifyNoInteractions(client);

        when(client.delete(any(DeleteReq.class))).thenReturn(mock(DeleteResp.class));
        milvus.deleteDocumentVectorsAfterCommit("collection-1", "doc-1");
        verify(client).delete(any(DeleteReq.class));
    }
}
