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

package com.nageoffer.ai.ragent.rag.core.vector;

import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import io.milvus.v2.client.MilvusClientV2;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorRetrieverModelTest {

    @ParameterizedTest
    @ValueSource(strings = {"pg", "milvus"})
    void usesExplicitModelAndNormalizesItsVector(String backend) {
        EmbeddingService embeddings = mock(EmbeddingService.class);
        when(embeddings.embed("query", "bound-model")).thenReturn(List.of(3F, 4F));

        float[] vector = backend(backend, embeddings).embedAndNormalize("query", "bound-model");

        assertArrayEquals(new float[]{0.6F, 0.8F}, vector, 0.00001F);
        verify(embeddings).embed("query", "bound-model");
        verify(embeddings, never()).embed("query");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pg", "milvus"})
    void explicitModelFailureIsPropagatedWithoutDefaultFallback(String backend) {
        EmbeddingService embeddings = mock(EmbeddingService.class);
        IllegalStateException failure = new IllegalStateException("model unavailable");
        when(embeddings.embed("query", "bound-model")).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> backend(backend, embeddings).embedAndNormalize("query", "bound-model")));
        verify(embeddings, never()).embed("query");
    }

    private VectorRetrieverService backend(String backend, EmbeddingService embeddings) {
        return "pg".equals(backend)
                ? new PgVectorRetrieverService(mock(JdbcTemplate.class), embeddings)
                : new MilvusVectorRetrieverService(embeddings, mock(MilvusClientV2.class), new RAGDefaultProperties());
    }
}
