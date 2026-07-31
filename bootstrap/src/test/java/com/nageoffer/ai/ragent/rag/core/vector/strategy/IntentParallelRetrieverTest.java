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

package com.nageoffer.ai.ragent.rag.core.vector.strategy;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievedChunkKey;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.KbCollectionProvider;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.VectorSearchChannel;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IntentParallelRetrieverTest {

    @Test
    @DisplayName("单个意图关联多个Collection时只检索一次且共享节点TopK")
    void multipleCollectionsShareOneIntentTopK() {
        VectorRetrieverService retrieverService = mock(VectorRetrieverService.class);
        when(retrieverService.embedAndNormalize("如何申请？")).thenReturn(new float[]{0.6F, 0.8F});
        when(retrieverService.retrieveByVector(any(float[].class), any(RetrieveRequest.class))).thenReturn(List.of());

        IntentNode node = IntentNode.builder()
                .id("multi-kb-intent")
                .name("多知识库意图")
                .collectionNames(List.of("kb-policy", "kb-process"))
                .topK(6)
                .build();
        NodeScore nodeScore = NodeScore.builder().node(node).score(0.95).build();

        IntentParallelRetriever retriever = new IntentParallelRetriever(retrieverService, Runnable::run);
        retriever.retrieveByIntents("如何申请？", List.of(nodeScore), 20);

        ArgumentCaptor<RetrieveRequest> requestCaptor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrieverService, times(1)).retrieveByVector(any(float[].class), requestCaptor.capture());
        RetrieveRequest request = requestCaptor.getValue();
        assertEquals(List.of("kb-policy", "kb-process"), request.getEffectiveCollectionNames());
        assertEquals(6, request.getTopK(), "TopK 应是该意图跨所有 Collection 的总预算");
    }

    @Test
    @DisplayName("未绑定Collection的意图不会误查全库")
    void emptyCollectionsSkipDirectedRetrieval() {
        VectorRetrieverService retrieverService = mock(VectorRetrieverService.class);
        KbCollectionProvider kbCollectionProvider = mock(KbCollectionProvider.class);
        when(kbCollectionProvider.listActiveCollections()).thenReturn(List.of());

        IntentNode node = IntentNode.builder()
                .id("empty-kb-intent")
                .name("未配置知识库")
                .build();
        SearchContext context = SearchContext.builder()
                .originalQuestion("如何申请？")
                .intents(List.of(new SubQuestionIntent(
                        "如何申请？",
                        List.of(NodeScore.builder().node(node).score(0.95).build())
                )))
                .budget(RetrievalBudget.uniform(20))
                .build();

        VectorSearchChannel channel = new VectorSearchChannel(
                retrieverService,
                new SearchChannelProperties(),
                kbCollectionProvider,
                Runnable::run
        );

        SearchChannelResult result = channel.search(context);

        assertTrue(result.getChunks().isEmpty());
        assertEquals("global", result.getMetadata().get("scope"));
        verify(kbCollectionProvider).listActiveCollections();
        verifyNoInteractions(retrieverService);
    }

    @Test
    @DisplayName("意图定向检索只记录真实召回归属")
    void recordsOnlyActualIntentMatches() {
        VectorRetrieverService retrieverService = mock(VectorRetrieverService.class);
        when(retrieverService.embedAndNormalize("问题")).thenReturn(new float[]{1F});
        RetrievedChunk chunkA = RetrievedChunk.builder().id("chunk-a").text("A资料").score(0.9F).build();
        when(retrieverService.retrieveByVector(any(float[].class), any(RetrieveRequest.class)))
                .thenAnswer(invocation -> {
                    RetrieveRequest request = invocation.getArgument(1);
                    return request.getEffectiveCollectionNames().contains("collection-a")
                            ? List.of(chunkA)
                            : List.of();
                });

        IntentParallelRetriever.IntentRetrievalResult result = new IntentParallelRetriever(
                retrieverService, Runnable::run).retrieveByIntents(
                "问题", List.of(intent("A", "collection-a"), intent("B", "collection-b")), 10);
        Map<String, Set<String>> attribution = result.intentIdsByChunkKey();

        assertEquals(Set.of("A"), attribution.get(RetrievedChunkKey.of(chunkA)));
        assertFalse(attribution.values().stream().anyMatch(intentIds -> intentIds.contains("B")));
    }

    private NodeScore intent(String id, String collectionName) {
        return NodeScore.builder()
                .node(IntentNode.builder()
                        .id(id)
                        .name(id)
                        .collectionNames(List.of(collectionName))
                        .build())
                .score(0.95)
                .build();
    }
}
