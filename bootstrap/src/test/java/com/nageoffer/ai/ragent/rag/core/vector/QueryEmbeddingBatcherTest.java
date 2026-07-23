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
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryEmbeddingBatcherTest {

    private KnowledgeBaseMapper knowledgeBaseMapper;
    private EmbeddingService embeddingService;
    private QueryEmbeddingBatcher batcher;

    @BeforeEach
    void setUp() {
        knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        embeddingService = mock(EmbeddingService.class);
        batcher = new QueryEmbeddingBatcher(knowledgeBaseMapper, embeddingService);
    }

    @Test
    void shouldQueryCollectionModelsOnceAndReuseOneBatchForSameModel() {
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                knowledgeBase("collection-a", "model-a"),
                knowledgeBase("collection-b", "model-a")
        ));
        when(embeddingService.embedBatch(List.of("question"), "model-a"))
                .thenReturn(List.of(List.of(3F, 4F)));

        QueryEmbeddingContext context = batcher.prepare(
                List.of(
                        new SearchTask("question", "collection-a"),
                        new SearchTask("question", "collection-b")
                ),
                List.of()
        );

        verify(knowledgeBaseMapper).selectList(any());
        verify(embeddingService).embedBatch(List.of("question"), "model-a");
        assertThat(context.vectorFor("question", "collection-a")).containsExactly(0.6F, 0.8F);
        assertThat(context.vectorFor("question", "collection-b")).containsExactly(0.6F, 0.8F);
    }

    @Test
    void shouldBatchUniqueQuestionsPerActualModelAndUseDefaultRouteForBlankModel() {
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                knowledgeBase("collection-a", "model-a"),
                knowledgeBase("collection-default", " ")
        ));
        when(embeddingService.embedBatch(List.of("question-1", "question-2"), "model-a"))
                .thenReturn(List.of(List.of(1F, 0F), List.of(0F, 2F)));
        when(embeddingService.embedBatch(List.of("question-1")))
                .thenReturn(List.of(List.of(0F, 3F)));

        QueryEmbeddingContext context = batcher.prepare(
                List.of(
                        new SearchTask("question-1", "collection-a"),
                        new SearchTask("question-1", "collection-default"),
                        new SearchTask("question-2", "collection-a")
                ),
                List.of()
        );

        verify(knowledgeBaseMapper).selectList(any());
        verify(embeddingService).embedBatch(List.of("question-1", "question-2"), "model-a");
        verify(embeddingService).embedBatch(List.of("question-1"));
        assertThat(context.modelFor("collection-default"))
                .isEqualTo(QueryEmbeddingContext.DEFAULT_MODEL_KEY);
        assertThat(context.vectorFor("question-2", "collection-a")).containsExactly(0F, 1F);
        assertThat(context.vectorFor("question-1", "collection-default")).containsExactly(0F, 1F);
    }

    @Test
    void shouldExpandGlobalQuestionToCollectionsFromSameMetadataQuery() {
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                knowledgeBase("collection-a", "model-a"),
                knowledgeBase("collection-b", "model-b")
        ));
        when(embeddingService.embedBatch(List.of("global-question"), "model-a"))
                .thenReturn(List.of(List.of(1F)));
        when(embeddingService.embedBatch(List.of("global-question"), "model-b"))
                .thenReturn(List.of(List.of(2F)));

        QueryEmbeddingContext context = batcher.prepare(List.of(), List.of("global-question"));

        verify(knowledgeBaseMapper).selectList(any());
        assertThat(context.collectionsFor("global-question"))
                .containsExactly("collection-a", "collection-b");
        assertThat(context.vectorFor("global-question", "collection-a")).containsExactly(1F);
        assertThat(context.vectorFor("global-question", "collection-b")).containsExactly(1F);
    }

    @Test
    void shouldNotFallBackToDefaultModelWhenExplicitModelBatchFails() {
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                knowledgeBase("collection-a", "model-a")
        ));
        when(embeddingService.embedBatch(List.of("question"), "model-a"))
                .thenThrow(new IllegalStateException("remote error"));

        QueryEmbeddingContext context = batcher.prepare(
                List.of(new SearchTask("question", "collection-a")),
                List.of()
        );

        assertThat(context.isPrepared()).isTrue();
        assertThat(context.vectorFor("question", "collection-a")).isNull();
        verify(embeddingService, never()).embedBatch(List.of("question"));
    }

    private KnowledgeBaseDO knowledgeBase(String collection, String model) {
        return KnowledgeBaseDO.builder()
                .collectionName(collection)
                .embeddingModel(model)
                .build();
    }
}
