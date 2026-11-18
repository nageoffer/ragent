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

package com.nageoffer.ai.ragent.rag.core.retrieval.channel;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.FusionPostProcessor;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.RerankPostProcessor;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VectorSearchChannelModelTest {

    private static final String QUERY = "travel policy";
    private static final float[] VECTOR_A = {1F, 0F};
    private static final float[] VECTOR_B = {0F, 1F};
    private static final RetrievalBudget BUDGET = new RetrievalBudget(4, 8, 2);

    private VectorRetrieverService retriever;
    private KbCollectionProvider provider;
    private SearchChannelProperties properties;

    @BeforeEach
    void setUp() {
        retriever = mock(VectorRetrieverService.class);
        provider = mock(KbCollectionProvider.class);
        properties = new SearchChannelProperties();
        when(retriever.supportsGlobalRetrieval()).thenReturn(true);
        when(retriever.embedAndNormalize(QUERY, "model-a")).thenReturn(VECTOR_A);
        when(retriever.embedAndNormalize(QUERY, "model-b")).thenReturn(VECTOR_B);
        when(provider.listActiveCollectionModels()).thenReturn(Map.of("kb-a", "model-a", "kb-b", "model-b"));
        when(retriever.retrieveByVector(any(float[].class), any(RetrieveRequest.class))).thenReturn(List.of());
    }

    @Test
    void eachModelVectorMustOnlySearchItsOwnCollections() {
        when(provider.listActiveCollectionModels()).thenReturn(Map.of(
                "kb-a", "model-a", "kb-c", "model-a", "kb-b", "model-b"));

        search(RetrievalScope.global(0.2, List.of("kb-a", "kb-b", "kb-c")), BUDGET);

        ArgumentCaptor<float[]> vectors = ArgumentCaptor.forClass(float[].class);
        ArgumentCaptor<RetrieveRequest> requests = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retriever, times(2)).retrieveByVector(vectors.capture(), requests.capture());
        for (int i = 0; i < requests.getAllValues().size(); i++) {
            List<String> collections = requests.getAllValues().get(i).getEffectiveCollectionNames();
            if (collections.contains("kb-a")) {
                assertThat(collections).containsExactly("kb-a", "kb-c");
                assertSame(VECTOR_A, vectors.getAllValues().get(i));
            } else {
                assertThat(collections).containsExactly("kb-b");
                assertSame(VECTOR_B, vectors.getAllValues().get(i));
            }
        }
        verify(retriever).embedAndNormalize(QUERY, "model-a");
        verify(retriever).embedAndNormalize(QUERY, "model-b");
        verify(retriever, never()).embedAndNormalize(QUERY);
    }

    @Test
    void interleavesModelRanksInsteadOfComparingRawScores() {
        returnByCollection(Map.of(
                "kb-a", List.of(chunk("a2", "kb-a", 0.1F), chunk("a1", "kb-a", 0.2F)),
                "kb-b", List.of(chunk("b2", "kb-b", 0.9F), chunk("b1", "kb-b", 0.99F))));

        List<RetrievedChunk> chunks = global("kb-b", "kb-a");

        assertThat(chunks).extracting(RetrievedChunk::getId).containsExactly("a1", "b1", "a2", "b2");
        assertThat(chunks).extracting(RetrievedChunk::getScore).containsExactly(0.2F, 0.99F, 0.1F, 0.9F);
    }

    @Test
    void truncationUsesRankAndDoesNotMultiplyTheOutputBudget() {
        returnByCollection(Map.of("kb-a", rows("a", "kb-a", 0.2F, 4),
                "kb-b", rows("b", "kb-b", 0.99F, 4)));

        List<RetrievedChunk> chunks = search(RetrievalScope.global(0.2, List.of("kb-b", "kb-a")),
                new RetrievalBudget(3, 8, 2));

        assertThat(chunks).extracting(RetrievedChunk::getId).containsExactly("a0", "b0", "a1");
    }

    @Test
    void continuesWithRemainingGroupWhenAnotherGroupIsExhausted() {
        returnByCollection(Map.of("kb-a", rows("a", "kb-a", 0.2F, 1),
                "kb-b", rows("b", "kb-b", 0.99F, 3)));

        assertThat(global("kb-a", "kb-b")).extracting(RetrievedChunk::getId)
                .containsExactly("a0", "b0", "b1", "b2");
    }

    @Test
    void tiedScoresWithinOneModelHaveStableIdentityOrder() {
        returnByCollection(Map.of("kb-a", List.of(chunk("z", "kb-a", 0.8F), chunk("a", "kb-a", 0.8F))));

        assertThat(global("kb-a")).extracting(RetrievedChunk::getId).containsExactly("a", "z");
    }

    @Test
    void failedModelIsSkippedAndHealthyModelContinuesWithoutDefaultFallback() {
        when(retriever.embedAndNormalize(QUERY, "model-a")).thenThrow(new IllegalStateException("unavailable"));
        returnByCollection(Map.of("kb-b", rows("b", "kb-b", 0.9F, 2)));

        assertThat(global("kb-a", "kb-b")).extracting(RetrievedChunk::getId).containsExactly("b0", "b1");
        assertThat(requests()).allSatisfy(request -> assertThat(request.getEffectiveCollectionNames()).containsExactly("kb-b"));
        verify(retriever, never()).embedAndNormalize(QUERY);
    }

    @Test
    void failedModelQueryDoesNotDiscardAnotherModelsResults() {
        when(retriever.retrieveByVector(any(float[].class), any(RetrieveRequest.class))).thenAnswer(invocation -> {
            RetrieveRequest request = invocation.getArgument(1);
            if (request.getEffectiveCollectionNames().contains("kb-a")) {
                throw new IllegalStateException("vector store unavailable for group a");
            }
            return rows("b", "kb-b", 0.8F, 2);
        });

        assertThat(global("kb-a", "kb-b")).extracting(RetrievedChunk::getId).containsExactly("b0", "b1");
    }

    @Test
    void missingBlankAndUnknownModelBindingsDoNotUseDefaultRouting() {
        Map<String, String> models = new LinkedHashMap<>();
        models.put("kb-a", null);
        models.put("kb-blank", " ");
        models.put("kb-b", "model-b");
        when(provider.listActiveCollectionModels()).thenReturn(models);
        returnByCollection(Map.of("kb-b", rows("b", "kb-b", 0.8F, 1)));

        assertThat(global("kb-a", "kb-blank", "kb-unknown", "kb-b")).hasSize(1);
        verify(retriever, never()).embedAndNormalize(QUERY, "model-a");
        verify(retriever, never()).embedAndNormalize(QUERY);
        assertThat(requests()).singleElement().satisfies(request ->
                assertThat(request.getEffectiveCollectionNames()).containsExactly("kb-b"));
    }

    @Test
    void missingModelMapSkipsEntireVectorChannelWithoutGuessingModels() {
        when(provider.listActiveCollectionModels()).thenThrow(new IllegalStateException("metadata unavailable"));

        assertThat(global("kb-a", "kb-b")).isEmpty();
        verifyNoInteractions(retriever);
    }

    @Test
    void invalidModelVectorDoesNotReachTheVectorStore() {
        when(retriever.embedAndNormalize(QUERY, "model-a")).thenReturn(new float[]{Float.NaN, 0F});
        returnByCollection(Map.of("kb-b", rows("b", "kb-b", 0.8F, 1)));

        assertThat(global("kb-a", "kb-b")).hasSize(1);
        assertThat(requests()).allSatisfy(request -> assertThat(request.getEffectiveCollectionNames()).containsExactly("kb-b"));
    }

    @Test
    void failedSupplementEmbeddingKeepsDirectedResults() {
        when(retriever.embedAndNormalize(QUERY, "model-b")).thenThrow(new IllegalStateException("supplement unavailable"));
        returnByCollection(Map.of("kb-a", rows("a", "kb-a", 0.8F, 4)));

        List<RetrievedChunk> chunks = search(new RetrievalScope(true, 0.9, List.of(),
                List.of("kb-a"), List.of("kb-b")), BUDGET);

        assertThat(chunks).hasSize(3).allSatisfy(chunk -> assertThat(chunk.getCollectionName()).isEqualTo("kb-a"));
        verify(retriever, never()).embedAndNormalize(QUERY);
    }

    @Test
    void failedDirectedEmbeddingKeepsSupplementResultsWithinItsQuota() {
        when(retriever.embedAndNormalize(QUERY, "model-a")).thenThrow(new IllegalStateException("directed unavailable"));
        returnByCollection(Map.of("kb-b", rows("b", "kb-b", 0.8F, 4)));

        List<RetrievedChunk> chunks = search(new RetrievalScope(true, 0.9, List.of(),
                List.of("kb-a"), List.of("kb-b")), BUDGET);

        assertThat(chunks).extracting(RetrievedChunk::getId).containsExactly("b0");
        verify(retriever, never()).embedAndNormalize(QUERY);
    }

    @Test
    void singleChannelFusionPreservesModelRanksBeforeUnifiedRerank() {
        returnByCollection(Map.of("kb-a", rows("a", "kb-a", 0.2F, 2),
                "kb-b", rows("b", "kb-b", 0.99F, 2)));
        List<RetrievedChunk> chunks = global("kb-a", "kb-b");
        SearchContext context = SearchContext.builder().originalQuestion(QUERY)
                .budget(new RetrievalBudget(4, 3, 2)).build();
        List<SearchChannelResult> channels = List.of(SearchChannelResult.builder()
                .channelType(SearchChannelType.VECTOR).chunks(chunks).build());

        List<RetrievedChunk> candidates = new FusionPostProcessor(properties).process(chunks, channels, context);

        assertThat(candidates).extracting(RetrievedChunk::getId).containsExactly("a0", "b0", "a1");
        RerankService reranker = mock(RerankService.class);
        when(reranker.rerank(QUERY, candidates, 2)).thenReturn(List.of(candidates.get(2), candidates.get(1)));
        List<RetrievedChunk> result = new RerankPostProcessor(reranker, new RAGConfigProperties())
                .process(candidates, channels, context);
        assertThat(result).extracting(RetrievedChunk::getId).containsExactly("a1", "b0");
        verify(reranker).rerank(QUERY, candidates, 2);
    }

    @Test
    void disabledSupplementDoesNotEmbedItsModel() {
        properties.getScope().setSupplementRatio(0);
        returnByCollection(Map.of("kb-a", rows("a", "kb-a", 0.8F, 4)));

        assertThat(search(new RetrievalScope(true, 0.9, List.of(), List.of("kb-a"), List.of("kb-b")), BUDGET)).hasSize(4);
        verify(retriever, never()).embedAndNormalize(QUERY, "model-b");
    }

    @Test
    void zeroBudgetDoesNotEmbedOrQueryAnyModel() {
        assertThat(search(RetrievalScope.global(0.2, List.of("kb-a", "kb-b")),
                new RetrievalBudget(0, 0, 0))).isEmpty();
        verifyNoInteractions(retriever);
    }

    @Test
    void directedAndSupplementQuotasSurviveFinalCrossModelMerge() {
        when(provider.listActiveCollectionModels()).thenReturn(Map.of(
                "kb-a", "model-a", "kb-b", "model-b", "sup-a", "model-a", "sup-b", "model-b"));
        returnByCollection(Map.of("kb-a", rows("primary-a", "kb-a", 0.2F, 8),
                "kb-b", rows("primary-b", "kb-b", 0.99F, 8),
                "sup-a", rows("supplement-a", "sup-a", 0.9F, 8),
                "sup-b", rows("supplement-b", "sup-b", 0.1F, 8)));

        List<RetrievedChunk> chunks = search(new RetrievalScope(true, 0.9, List.of(),
                List.of("kb-b", "kb-a"), List.of("sup-b", "sup-a")), new RetrievalBudget(8, 16, 2));

        assertThat(chunks).hasSize(8);
        assertThat(chunks.stream().filter(chunk -> chunk.getId().startsWith("primary"))).hasSize(6);
        assertThat(chunks.stream().filter(chunk -> chunk.getId().startsWith("supplement"))).hasSize(2);
        assertThat(chunks.get(0).getId()).isEqualTo("supplement-a0");
        assertThat(chunks.stream().map(chunk -> chunk.getCollectionName().endsWith("a") ? "a" : "b"))
                .containsExactly("a", "b", "a", "b", "a", "b", "a", "b");
        verify(retriever).embedAndNormalize(QUERY, "model-a");
        verify(retriever).embedAndNormalize(QUERY, "model-b");
    }

    @Test
    void fanOutUsesEachModelsVectorAndStillCapsTheMergedCandidates() {
        when(retriever.supportsGlobalRetrieval()).thenReturn(false);
        when(provider.listActiveCollectionModels()).thenReturn(Map.of(
                "kb-a", "model-a", "kb-c", "model-a", "kb-b", "model-b"));
        returnByCollection(Map.of("kb-a", rows("a", "kb-a", 0.2F, 4),
                "kb-c", rows("c", "kb-c", 0.3F, 4), "kb-b", rows("b", "kb-b", 0.99F, 4)));

        List<RetrievedChunk> chunks = global("kb-a", "kb-c", "kb-b");

        assertThat(chunks).extracting(RetrievedChunk::getId).containsExactly("c0", "b0", "c1", "b1");
        assertThat(requests()).hasSize(3);
        verify(retriever).embedAndNormalize(QUERY, "model-a");
        verify(retriever).embedAndNormalize(QUERY, "model-b");
    }

    private List<RetrievedChunk> global(String... collections) {
        return search(RetrievalScope.global(0.2, List.of(collections)), BUDGET);
    }

    private List<RetrievedChunk> search(RetrievalScope scope, RetrievalBudget budget) {
        return new VectorSearchChannel(retriever, properties, provider, Runnable::run)
                .search(SearchContext.builder().originalQuestion(QUERY).retrievalScope(scope).budget(budget).build())
                .getChunks();
    }

    private void returnByCollection(Map<String, List<RetrievedChunk>> chunks) {
        when(retriever.retrieveByVector(any(float[].class), any(RetrieveRequest.class))).thenAnswer(invocation -> {
            RetrieveRequest request = invocation.getArgument(1);
            return request.getEffectiveCollectionNames().stream()
                    .flatMap(collection -> chunks.getOrDefault(collection, List.of()).stream()).toList();
        });
    }

    private List<RetrieveRequest> requests() {
        ArgumentCaptor<RetrieveRequest> requests = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retriever, org.mockito.Mockito.atLeastOnce()).retrieveByVector(any(float[].class), requests.capture());
        return requests.getAllValues();
    }

    private static List<RetrievedChunk> rows(String prefix, String collection, float score, int size) {
        return IntStream.range(0, size).mapToObj(i -> chunk(prefix + i, collection, score - i * 0.001F)).toList();
    }

    private static RetrievedChunk chunk(String id, String collection, float score) {
        return RetrievedChunk.builder().id(id).collectionName(collection).text(id).score(score).build();
    }
}
