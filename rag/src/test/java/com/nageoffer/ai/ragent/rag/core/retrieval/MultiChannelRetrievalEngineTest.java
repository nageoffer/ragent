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
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScope;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiChannelRetrievalEngineTest {

    @Test
    void derivesAttributionFromFinalChunksByCollection() {
        // 归属按「chunk 的库 ∈ 意图绑定库」推导，与到达通道无关：关键词证据同样获得归属，
        // 补充库证据不在任何命中意图绑定里、天然无归属，被后处理淘汰的证据不参与推导
        RetrievedChunk vectorChunk = chunk("v1", "A资料", "kb-a", 0.9F);
        RetrievedChunk discarded = chunk("v2", "被淘汰的A资料", "kb-a", 0.8F);
        RetrievedChunk keywordChunk = chunk("k1", "B资料", "kb-b", 0.7F);
        RetrievedChunk supplementChunk = chunk("s1", "补充库资料", "kb-c", 0.6F);

        SearchChannel vector = channel("vector", SearchChannelType.VECTOR,
                channelResult(SearchChannelType.VECTOR, "vector", vectorChunk, discarded));
        SearchChannel keyword = channel("keyword", SearchChannelType.KEYWORD,
                channelResult(SearchChannelType.KEYWORD, "keyword", keywordChunk, supplementChunk));

        SearchResultPostProcessor finalSelection = mock(SearchResultPostProcessor.class);
        when(finalSelection.getOrder()).thenReturn(1);
        when(finalSelection.isEnabled(any(SearchContext.class))).thenReturn(true);
        when(finalSelection.process(anyList(), anyList(), any(SearchContext.class)))
                .thenReturn(List.of(keywordChunk, vectorChunk, supplementChunk));

        KnowledgeRetrievalResult result = engine(List.of(vector, keyword), List.of(finalSelection),
                directedScope(List.of(intent("A", "kb-a"), intent("B", "kb-b")),
                        List.of("kb-a", "kb-b"), List.of("kb-c")))
                .retrieveKnowledgeChannels(new SubQuestionIntent("问题", List.of()), RetrievalBudget.uniform(10));
        Map<String, List<RetrievedChunk>> grouped = result.groupByIntent("multi_channel");

        assertEquals(List.of(keywordChunk, vectorChunk, supplementChunk), result.chunks(), "不得改变最终后处理顺序");
        assertEquals(List.of(vectorChunk), grouped.get("A"));
        assertEquals(List.of(keywordChunk), grouped.get("B"), "关键词证据按库获得归属");
        assertEquals(List.of(supplementChunk), grouped.get("multi_channel"));
        assertEquals(Set.of("A", "B"), result.retrievedIntentIds());
        assertEquals(Set.of("A", "B"), result.directedIntentIds());
        assertFalse(result.intentIdsByChunkKey().containsKey(RetrievedChunkKey.of(discarded)));
    }

    @Test
    void sharedCollectionAttributesAllBoundIntents() {
        // 多绑定语义定案：同一个库被多个意图绑定 ⇒ 确定性多归属，不挑一个
        RetrievedChunk sharedChunk = chunk("s1", "共享库资料", "kb-shared", 0.9F);
        SearchChannel vector = channel("vector", SearchChannelType.VECTOR,
                channelResult(SearchChannelType.VECTOR, "vector", sharedChunk));

        KnowledgeRetrievalResult result = engine(List.of(vector), List.of(),
                directedScope(List.of(intent("报销", "kb-shared"), intent("发票", "kb-shared")),
                        List.of("kb-shared"), List.of()))
                .retrieveKnowledgeChannels(new SubQuestionIntent("问题", List.of()), RetrievalBudget.uniform(10));

        assertEquals(Set.of("报销", "发票"),
                result.intentIdsByChunkKey().get(RetrievedChunkKey.of(sharedChunk)));
        assertEquals(Set.of("报销", "发票"), result.directedIntentIds());
    }

    @Test
    void globalScopeYieldsNoAttribution() {
        RetrievedChunk globalChunk = chunk("g1", "全局资料", "kb-a", 0.9F);
        SearchChannel vector = channel("vector", SearchChannelType.VECTOR,
                channelResult(SearchChannelType.VECTOR, "vector", globalChunk));

        KnowledgeRetrievalResult result = engine(List.of(vector), List.of(),
                RetrievalScope.global(0.3, List.of("kb-a")))
                .retrieveKnowledgeChannels(new SubQuestionIntent("问题", List.of()), RetrievalBudget.uniform(10));

        assertTrue(result.intentIdsByChunkKey().isEmpty());
        assertTrue(result.directedIntentIds().isEmpty());
        assertEquals(List.of(globalChunk), result.groupByIntent("multi_channel").get("multi_channel"));
    }

    @Test
    void directedTimeoutKeepsScopeAndDegradesToMiss() {
        // 慢通道模拟卡死的后端：超时只丢它自己的结果，不钳制同一子问题里其余通道
        RetrievedChunk fastChunk = chunk("fast", "补充库资料", "kb-supplement", 0.9F);
        SearchChannel fast = channel(
                "vector",
                SearchChannelType.VECTOR,
                SearchChannelResult.builder()
                        .channelType(SearchChannelType.VECTOR)
                        .channelName("vector")
                        .chunks(List.of(fastChunk))
                        .build()
        );
        SearchChannel slow = mock(SearchChannel.class);
        when(slow.getName()).thenReturn("graph");
        when(slow.getType()).thenReturn(SearchChannelType.GRAPH);
        when(slow.isEnabled(any(SearchContext.class))).thenReturn(true);
        // Mockito 对接口 default 方法默认桩为 null，会被引擎的 nonNull 过滤悄悄吞掉——
        // 那样本测试只证明快通道无恙，降级出口本身反而没被测到，必须真调 default 实现
        when(slow.emptyResult(anyLong())).thenCallRealMethod();
        when(slow.search(any(SearchContext.class))).thenAnswer(invocation -> {
            Thread.sleep(1_000);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.GRAPH)
                    .channelName("graph")
                    .chunks(List.of(chunk("slow", "慢通道资料", 0.8F)))
                    .build();
        });

        SearchChannelProperties properties = new SearchChannelProperties();
        properties.getChannels().setTimeoutMs(200);
        RetrievalScopeResolver resolver = mock(RetrievalScopeResolver.class);
        NodeScore candidate = intent("A", "kb-a");
        when(resolver.resolve(anyList())).thenReturn(directedScope(
                List.of(candidate), List.of("kb-a"), List.of("kb-supplement")));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            long start = System.nanoTime();
            KnowledgeRetrievalResult result = new MultiChannelRetrievalEngine(
                    List.of(fast, slow),
                    List.of(),
                    resolver,
                    pool,
                    properties)
                    .retrieveKnowledgeChannels(
                            new SubQuestionIntent("问题", List.of()),
                            RetrievalBudget.uniform(10)
                    );
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertEquals(List.of("fast"), result.chunks().stream().map(RetrievedChunk::getId).toList(),
                    "慢通道超时按空结果降级，快通道证据保留");
            assertEquals(Set.of("A"), result.directedIntentIds());
            assertTrue(result.retrievedIntentIds().isEmpty());
            assertTrue(result.eligibleIntentIds(List.of(candidate)).isEmpty());
            assertTrue(elapsedMs < 800, "慢通道不得钳制整次检索，实际耗时 " + elapsedMs + "ms");
            verify(slow).emptyResult(0L);
        } finally {
            pool.shutdownNow();
        }
    }

    private MultiChannelRetrievalEngine engine(List<SearchChannel> channels,
                                               List<SearchResultPostProcessor> processors,
                                               RetrievalScope scope) {
        RetrievalScopeResolver resolver = mock(RetrievalScopeResolver.class);
        when(resolver.resolve(anyList())).thenReturn(scope);
        return new MultiChannelRetrievalEngine(
                channels, processors, resolver, Runnable::run, new SearchChannelProperties());
    }

    private static RetrievalScope directedScope(List<NodeScore> intents,
                                                List<String> targets, List<String> supplement) {
        return new RetrievalScope(true, 0.9, intents, targets, supplement);
    }

    private static NodeScore intent(String id, String collection) {
        return NodeScore.builder()
                .node(IntentNode.builder().id(id).name(id).collectionNames(List.of(collection)).build())
                .score(0.9)
                .build();
    }

    private static SearchChannelResult channelResult(SearchChannelType type, String name, RetrievedChunk... chunks) {
        return SearchChannelResult.builder()
                .channelType(type)
                .channelName(name)
                .chunks(List.of(chunks))
                .build();
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

    private RetrievedChunk chunk(String id, String text, String collectionName, float score) {
        return RetrievedChunk.builder().id(id).text(text).collectionName(collectionName).score(score).build();
    }
}
