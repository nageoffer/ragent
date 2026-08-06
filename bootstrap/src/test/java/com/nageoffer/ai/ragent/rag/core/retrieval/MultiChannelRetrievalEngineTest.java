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

package com.nageoffer.ai.ragent.rag.core.retrieval;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScopeResolver;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.SearchResultPostProcessor;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultiChannelRetrievalEngineTest {

    @Test
    void filtersAttributionByFinalChunksAndKeepsGlobalEvidence() {
        RetrievedChunk originalA = chunk(null, "A资料", 0.9F);
        RetrievedChunk chunkA = chunk(null, "A资料", 0.9F);
        RetrievedChunk discardedA = chunk("discarded", "被淘汰的A资料", 0.8F);
        RetrievedChunk globalChunk = chunk("global", "全局资料", 0.7F);

        SearchChannel vector = channel(
                "vector",
                SearchChannelType.VECTOR,
                SearchChannelResult.builder()
                        .channelType(SearchChannelType.VECTOR)
                        .channelName("vector")
                        .chunks(List.of(originalA, discardedA))
                        .intentIdsByChunkKey(Map.of(
                                RetrievedChunkKey.of(originalA), Set.of("A", "B"),
                                RetrievedChunkKey.of(discardedA), Set.of("A")))
                        .build()
        );
        SearchChannel keyword = channel(
                "keyword",
                SearchChannelType.KEYWORD,
                SearchChannelResult.builder()
                        .channelType(SearchChannelType.KEYWORD)
                        .channelName("keyword")
                        .chunks(List.of(globalChunk))
                        .build()
        );

        SearchResultPostProcessor finalSelection = mock(SearchResultPostProcessor.class);
        when(finalSelection.getOrder()).thenReturn(1);
        when(finalSelection.isEnabled(any(SearchContext.class))).thenReturn(true);
        when(finalSelection.process(anyList(), anyList(), any(SearchContext.class)))
                .thenReturn(List.of(globalChunk, chunkA));

        KnowledgeRetrievalResult result = new MultiChannelRetrievalEngine(
                List.of(vector, keyword),
                List.of(finalSelection),
                mock(RetrievalScopeResolver.class),
                Runnable::run)
                .retrieveKnowledgeChannels(
                        new SubQuestionIntent("问题", List.of()),
                        RetrievalBudget.uniform(10)
                );
        Map<String, List<RetrievedChunk>> grouped = result.groupByIntent("multi_channel");

        assertEquals(List.of(globalChunk, chunkA), result.chunks(), "不得改变最终后处理顺序");
        assertEquals(List.of(chunkA), grouped.get("A"));
        assertEquals(List.of(chunkA), grouped.get("B"));
        assertEquals(List.of(globalChunk), grouped.get("multi_channel"));
        assertEquals(Set.of("A", "B"), result.retrievedIntentIds());
        assertFalse(result.intentIdsByChunkKey().containsKey(RetrievedChunkKey.of(discardedA)));
    }

    private SearchChannel channel(String name, SearchChannelType type, SearchChannelResult result) {
        SearchChannel channel = mock(SearchChannel.class);
        when(channel.getName()).thenReturn(name);
        when(channel.getType()).thenReturn(type);
        when(channel.isEnabled(any(SearchContext.class))).thenReturn(true);
        when(channel.search(any(SearchContext.class))).thenReturn(result);
        return channel;
    }

    private RetrievedChunk chunk(String id, String text, float score) {
        return RetrievedChunk.builder().id(id).text(text).score(score).build();
    }
}
