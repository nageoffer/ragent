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
import com.nageoffer.ai.ragent.rag.core.mcp.McpParameterExtractor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.prompt.DefaultContextFormatter;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MULTI_CHANNEL_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalEngineTest {

    @Test
    void returnsOnlyActualIntentMatchesAndIndependentGlobalEvidence() {
        RetrievedChunk chunkA = chunk("a", "A资料");
        RetrievedChunk globalChunk = chunk("global", "全局资料");
        MultiChannelRetrievalEngine multiChannel = mock(MultiChannelRetrievalEngine.class);
        ContextFormatter contextFormatter = mock(ContextFormatter.class);
        when(multiChannel.retrieveKnowledgeChannels(
                any(SubQuestionIntent.class), any(RetrievalBudget.class)))
                .thenReturn(new KnowledgeRetrievalResult(
                        List.of(chunkA, globalChunk),
                        Map.of(RetrievedChunkKey.of(chunkA), Set.of("A")),
                        Set.of("A", "B")
                ));

        RetrievalContext result = engine(multiChannel, contextFormatter).retrieve(List.of(
                new SubQuestionIntent("问题", List.of(intent("A"), intent("B")))
        ));

        assertEquals(Map.of(
                "A", List.of(chunkA),
                MULTI_CHANNEL_KEY, List.of(globalChunk)
        ), result.getIntentChunks());
        assertEquals(Set.of("A"), result.getEligibleIntentIds());
        verify(contextFormatter).formatKbContext(anyList(), eq(Set.of("A")), eq(List.of(chunkA, globalChunk)), anyInt());
    }

    @Test
    void globalEvidenceKeepsCandidatesEligible() {
        RetrievedChunk globalChunk = chunk("global", "全局资料");
        MultiChannelRetrievalEngine multiChannel = mock(MultiChannelRetrievalEngine.class);
        ContextFormatter contextFormatter = mock(ContextFormatter.class);
        when(multiChannel.retrieveKnowledgeChannels(
                any(SubQuestionIntent.class), any(RetrievalBudget.class)))
                .thenReturn(new KnowledgeRetrievalResult(List.of(globalChunk), Map.of(), Set.of()));

        RetrievalContext result = engine(multiChannel, contextFormatter).retrieve(List.of(
                new SubQuestionIntent("问题", List.of(intent("A"), intent("B")))
        ));

        assertEquals(Set.of("A", "B"), result.getEligibleIntentIds());
        verify(contextFormatter).formatKbContext(
                anyList(), eq(Set.of("A", "B")), eq(List.of(globalChunk)), anyInt());
    }

    @Test
    void globalFallbackInjectsBothLowConfidenceCandidateSnippets() {
        RetrievedChunk globalChunk = chunk("global", "全局资料");
        MultiChannelRetrievalEngine multiChannel = mock(MultiChannelRetrievalEngine.class);
        when(multiChannel.retrieveKnowledgeChannels(
                any(SubQuestionIntent.class), any(RetrievalBudget.class)))
                .thenReturn(new KnowledgeRetrievalResult(List.of(globalChunk), Map.of(), Set.of()));
        ContextFormatter contextFormatter = new DefaultContextFormatter(
                new PromptTemplateLoader(new DefaultResourceLoader()));

        RetrievalContext result = engine(multiChannel, contextFormatter).retrieve(List.of(
                new SubQuestionIntent("问题", List.of(
                        lowConfidenceIntentWithSnippet("A", "SNIPPET_A"),
                        lowConfidenceIntentWithSnippet("B", "SNIPPET_B")))
        ));

        assertEquals(Set.of("A", "B"), result.getEligibleIntentIds());
        assertTrue(result.getKbContext().contains("SNIPPET_A"));
        assertTrue(result.getKbContext().contains("SNIPPET_B"));
        assertTrue(result.getKbContext().contains("全局资料"));
    }

    @Test
    void directedMissKeepsEvidenceWithoutEligibleIntent() {
        RetrievedChunk supplement = chunk("supplement", "补充资料");
        MultiChannelRetrievalEngine multiChannel = mock(MultiChannelRetrievalEngine.class);
        ContextFormatter contextFormatter = mock(ContextFormatter.class);
        when(multiChannel.retrieveKnowledgeChannels(
                any(SubQuestionIntent.class), any(RetrievalBudget.class)))
                .thenReturn(new KnowledgeRetrievalResult(List.of(supplement), Map.of(), Set.of("A")));
        when(contextFormatter.formatKbContext(anyList(), any(), anyList(), anyInt()))
                .thenReturn("补充资料上下文");

        RetrievalContext result = engine(multiChannel, contextFormatter).retrieve(List.of(
                new SubQuestionIntent("问题", List.of(intent("A")))
        ));

        assertTrue(result.getEligibleIntentIds().isEmpty());
        assertEquals("补充资料上下文", result.getKbContext());
        assertEquals(List.of(supplement), result.getIntentChunks().get(MULTI_CHANNEL_KEY));
        verify(contextFormatter).formatKbContext(anyList(), eq(Set.of()), eq(List.of(supplement)), anyInt());
    }

    @Test
    void multiQuestionEligibilityUsesEachQuestionOutcome() {
        RetrievedChunk hitChunk = chunk("hit", "A资料");
        KnowledgeRetrievalResult unknown = KnowledgeRetrievalResult.empty();
        KnowledgeRetrievalResult hit = new KnowledgeRetrievalResult(
                List.of(hitChunk), Map.of(RetrievedChunkKey.of(hitChunk), Set.of("A")), Set.of("A"));
        KnowledgeRetrievalResult miss = new KnowledgeRetrievalResult(List.of(), Map.of(), Set.of("A"));

        assertEquals(Set.of("A"), eligibleAfterTwoQuestions(unknown, miss));
        assertEquals(Set.of("A"), eligibleAfterTwoQuestions(hit, miss));
        assertTrue(eligibleAfterTwoQuestions(miss, miss).isEmpty());
    }

    @Test
    void failedSubQuestionKeepsCandidateUnevaluated() {
        MultiChannelRetrievalEngine multiChannel = mock(MultiChannelRetrievalEngine.class);
        ContextFormatter contextFormatter = mock(ContextFormatter.class);
        KnowledgeRetrievalResult miss = new KnowledgeRetrievalResult(List.of(), Map.of(), Set.of("A"));
        when(multiChannel.retrieveKnowledgeChannels(
                any(SubQuestionIntent.class), any(RetrievalBudget.class)))
                .thenThrow(new IllegalStateException("retrieval unavailable"))
                .thenReturn(miss);

        RetrievalContext result = engine(multiChannel, contextFormatter).retrieve(List.of(
                new SubQuestionIntent("问题一", List.of(intent("A"))),
                new SubQuestionIntent("问题二", List.of(intent("A")))
        ));

        assertEquals(Set.of("A"), result.getEligibleIntentIds());
    }

    private Set<String> eligibleAfterTwoQuestions(KnowledgeRetrievalResult first,
                                                   KnowledgeRetrievalResult second) {
        MultiChannelRetrievalEngine multiChannel = mock(MultiChannelRetrievalEngine.class);
        ContextFormatter contextFormatter = mock(ContextFormatter.class);
        when(multiChannel.retrieveKnowledgeChannels(
                any(SubQuestionIntent.class), any(RetrievalBudget.class)))
                .thenReturn(first, second);

        return engine(multiChannel, contextFormatter).retrieve(List.of(
                new SubQuestionIntent("问题一", List.of(intent("A"))),
                new SubQuestionIntent("问题二", List.of(intent("A")))
        )).getEligibleIntentIds();
    }

    private RetrievalEngine engine(MultiChannelRetrievalEngine multiChannel, ContextFormatter contextFormatter) {
        return new RetrievalEngine(
                new SearchChannelProperties(),
                contextFormatter,
                mock(PromptTemplateLoader.class),
                mock(McpParameterExtractor.class),
                mock(McpToolRegistry.class),
                multiChannel,
                Runnable::run,
                Runnable::run
        );
    }

    private NodeScore intent(String id) {
        return NodeScore.builder().node(IntentNode.builder().id(id).build()).score(0.9).build();
    }

    private NodeScore lowConfidenceIntentWithSnippet(String id, String snippet) {
        return NodeScore.builder()
                .node(IntentNode.builder().id(id).promptSnippet(snippet).build())
                .score(0.5)
                .build();
    }

    private RetrievedChunk chunk(String id, String text) {
        return RetrievedChunk.builder().id(id).text(text).build();
    }
}
