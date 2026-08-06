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
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorSearchChannelAttributionTest {

    private static final String QUESTION = "报销发票贴哪张表？";
    private static final RetrievalBudget BUDGET = new RetrievalBudget(20, 40, 10);

    private VectorRetrieverService retrieverService;

    @BeforeEach
    void setUp() {
        retrieverService = mock(VectorRetrieverService.class);
        when(retrieverService.embedAndNormalize(QUESTION)).thenReturn(new float[]{0.6F, 0.8F});
        when(retrieverService.supportsGlobalRetrieval()).thenReturn(true);
        when(retrieverService.retrieveByVector(any(float[].class), any(RetrieveRequest.class)))
                .thenAnswer(invocation -> chunks(invocation.getArgument(1)));
    }

    @Test
    @DisplayName("等价定向查询保留全部意图归属")
    void equivalentDirectedQueriesKeepAllIntentOwners() {
        RetrievalScope scope = new RetrievalScope(true, 0.9,
                List.of(intent("报销", "kb-finance"), intent("发票", "kb-finance")),
                List.of("kb-finance"), List.of());

        SearchChannelResult result = search(scope, BUDGET);

        assertTrue(result.getChunks().stream().allMatch(chunk ->
                Set.of("报销", "发票").equals(
                        result.getIntentIdsByChunkKey().get(RetrievedChunkKey.of(chunk)))));
    }

    @Test
    @DisplayName("定向截断后只保留入选证据的归属")
    void attributionFollowsDirectedCap() {
        RetrievalScope scope = new RetrievalScope(
                true, 0.9, List.of(intent("kb-finance", "kb-finance")),
                List.of("kb-finance"), List.of());

        SearchChannelResult result = search(scope, new RetrievalBudget(20, 1, 1));

        assertEquals(1, result.getChunks().size());
        assertEquals(Map.of(
                        RetrievedChunkKey.of(result.getChunks().get(0)),
                        Set.of("kb-finance")),
                result.getIntentIdsByChunkKey());
    }

    @Test
    @DisplayName("补充与全局召回不写入精确意图归属")
    void nonDirectedChunksRemainUnattributed() {
        RetrievalScope directedScope = new RetrievalScope(
                true, 0.9, List.of(intent("finance", "kb-finance")),
                List.of("kb-finance"), List.of("kb-hr"));
        SearchChannelResult directed = search(directedScope, BUDGET);

        assertTrue(directed.getChunks().stream()
                .filter(chunk -> chunk.getId().startsWith("kb-hr"))
                .noneMatch(chunk -> directed.getIntentIdsByChunkKey()
                        .containsKey(RetrievedChunkKey.of(chunk))));

        SearchChannelResult global = search(
                RetrievalScope.global(0.3, List.of("kb-finance")), BUDGET);
        assertTrue(global.getIntentIdsByChunkKey().isEmpty());
    }

    private SearchChannelResult search(RetrievalScope scope, RetrievalBudget budget) {
        SearchContext context = SearchContext.builder()
                .originalQuestion(QUESTION)
                .budget(budget)
                .retrievalScope(scope)
                .build();
        return new VectorSearchChannel(
                retrieverService, new SearchChannelProperties(), Runnable::run).search(context);
    }

    private static NodeScore intent(String id, String collection) {
        return NodeScore.builder()
                .node(IntentNode.builder()
                        .id(id)
                        .name(id)
                        .collectionNames(List.of(collection))
                        .build())
                .score(0.9)
                .build();
    }

    private static List<RetrievedChunk> chunks(RetrieveRequest request) {
        String collection = request.getEffectiveCollectionNames().get(0);
        return IntStream.range(0, request.getTopK())
                .mapToObj(index -> RetrievedChunk.builder()
                        .id(collection + "-" + index)
                        .text(collection + "-" + index)
                        .score(0.9F - index * 0.001F)
                        .build())
                .toList();
    }
}
