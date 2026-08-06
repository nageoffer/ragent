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
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MULTI_CHANNEL_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
                        Map.of(RetrievedChunkKey.of(chunkA), Set.of("A"))
                ));

        RetrievalContext result = engine(multiChannel, contextFormatter).retrieve(List.of(
                new SubQuestionIntent("问题", List.of(intent("A"), intent("B")))
        ));

        assertEquals(Map.of(
                "A", List.of(chunkA),
                MULTI_CHANNEL_KEY, List.of(globalChunk)
        ), result.getIntentChunks());
        assertEquals(Set.of("A"), result.getRetrievedIntentIds());
        verify(contextFormatter).formatKbContext(anyList(), eq(Set.of("A")), eq(List.of(chunkA, globalChunk)), anyInt());
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

    private RetrievedChunk chunk(String id, String text) {
        return RetrievedChunk.builder().id(id).text(text).build();
    }
}
