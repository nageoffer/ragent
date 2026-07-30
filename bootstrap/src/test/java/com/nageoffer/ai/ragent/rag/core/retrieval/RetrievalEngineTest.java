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
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.mcp.McpParameterExtractor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MULTI_CHANNEL_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalEngineTest {

    @Test
    void returnsOnlyActualIntentMatchesAndIndependentGlobalEvidence() {
        RetrievedChunk chunkA = chunk("a", "A资料");
        RetrievedChunk globalChunk = chunk("global", "全局资料");
        MultiChannelRetrievalEngine multiChannel = mock(MultiChannelRetrievalEngine.class);
        when(multiChannel.retrieveKnowledgeChannels(anyList(), any(RetrievalBudget.class)))
                .thenReturn(new KnowledgeRetrievalResult(
                        List.of(chunkA, globalChunk),
                        IntentChunkAttribution.fromIntentChunks(Map.of("A", List.of(chunkA)))
                ));

        RetrievalContext result = engine(multiChannel).retrieve(List.of(
                new SubQuestionIntent("问题", List.of(intent("A"), intent("B")))
        ));

        assertEquals(List.of(chunkA), result.getIntentChunks().get("A"));
        assertFalse(result.getIntentChunks().containsKey("B"));
        assertEquals(List.of(globalChunk), result.getIntentChunks().get(MULTI_CHANNEL_KEY));
    }

    @Test
    void mergesSubQuestionsWithoutCrossingIntentOrGlobalKeys() {
        RetrievedChunk chunkA1 = chunk("a1", "A资料1");
        RetrievedChunk chunkA2 = chunk("a2", "A资料2");
        RetrievedChunk chunkB = chunk("b", "B资料");
        RetrievedChunk globalChunk = chunk("global", "全局资料");
        MultiChannelRetrievalEngine multiChannel = mock(MultiChannelRetrievalEngine.class);
        when(multiChannel.retrieveKnowledgeChannels(anyList(), any(RetrievalBudget.class)))
                .thenAnswer(invocation -> {
                    List<SubQuestionIntent> request = invocation.getArgument(0);
                    if ("问题1".equals(request.get(0).subQuestion())) {
                        return new KnowledgeRetrievalResult(
                                List.of(chunkA1),
                                IntentChunkAttribution.fromIntentChunks(Map.of("A", List.of(chunkA1)))
                        );
                    }
                    Map<String, List<RetrievedChunk>> byIntent = new LinkedHashMap<>();
                    byIntent.put("A", List.of(chunkA2));
                    byIntent.put("B", List.of(chunkB));
                    return new KnowledgeRetrievalResult(
                            List.of(chunkA2, globalChunk, chunkB),
                            IntentChunkAttribution.fromIntentChunks(byIntent)
                    );
                });

        RetrievalContext result = engine(multiChannel).retrieve(List.of(
                new SubQuestionIntent("问题1", List.of(intent("A"))),
                new SubQuestionIntent("问题2", List.of(intent("A"), intent("B")))
        ));

        assertEquals(List.of(chunkA1, chunkA2), result.getIntentChunks().get("A"));
        assertEquals(List.of(chunkB), result.getIntentChunks().get("B"));
        assertEquals(List.of(globalChunk), result.getIntentChunks().get(MULTI_CHANNEL_KEY));
    }

    private RetrievalEngine engine(MultiChannelRetrievalEngine multiChannel) {
        ContextFormatter formatter = mock(ContextFormatter.class);
        when(formatter.formatKbContext(anyList(), anyMap(), anyList(), anyInt())).thenReturn("KB_CONTEXT");
        PromptTemplateLoader templateLoader = mock(PromptTemplateLoader.class);
        when(templateLoader.renderSection(anyString(), anyString(), anyMap()))
                .thenAnswer(invocation -> String.valueOf(((Map<?, ?>) invocation.getArgument(2)).get("context")));
        return new RetrievalEngine(
                new SearchChannelProperties(),
                formatter,
                templateLoader,
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

    private RetrievedChunk chunk(String id, String text) {
        return RetrievedChunk.builder().id(id).text(text).build();
    }
}
