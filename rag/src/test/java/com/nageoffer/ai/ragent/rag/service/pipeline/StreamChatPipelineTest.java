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

package com.nageoffer.ai.ragent.rag.service.pipeline;

import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.core.source.CitationContextEnricher;
import com.nageoffer.ai.ragent.rag.core.source.GroundingChunksAssembler;
import com.nageoffer.ai.ragent.rag.core.source.SourcesAssembler;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.NO_EVIDENCE_RESPONSE_PROMPT_PATH;

@ExtendWith(MockitoExtension.class)
class StreamChatPipelineTest {

    @Mock
    private ConversationMemoryService memoryService;
    @Mock
    private QueryRewriteService queryRewriteService;
    @Mock
    private IntentResolver intentResolver;
    @Mock
    private IntentGuidanceService guidanceService;
    @Mock
    private RetrievalEngine retrievalEngine;
    @Mock
    private LLMService llmService;
    @Mock
    private RAGPromptService promptBuilder;
    @Mock
    private AgentPromptResolver agentPromptResolver;
    @Mock
    private PromptTemplateLoader promptTemplateLoader;
    @Mock
    private StreamTaskManager taskManager;
    @Mock
    private SourcesAssembler sourcesAssembler;
    @Mock
    private GroundingChunksAssembler groundingChunksAssembler;
    @Mock
    private CitationContextEnricher citationContextEnricher;

    @InjectMocks
    private StreamChatPipeline pipeline;

    @Test
    void passesEligibleIntentIdsToPromptContext() {
        StreamCallback callback = org.mockito.Mockito.mock(StreamCallback.class);
        RewriteResult rewriteResult = new RewriteResult("改写问题", List.of("改写问题"));
        List<SubQuestionIntent> subIntents = List.of(new SubQuestionIntent("改写问题", List.of()));
        Set<String> eligibleIntentIds = Set.of("intent-1");
        RetrievalContext retrievalContext = RetrievalContext.builder()
                .kbContext("<content>资料</content>")
                .intentChunks(Map.of())
                .eligibleIntentIds(eligibleIntentIds)
                .build();

        when(memoryService.load("conversation-1", "user-1")).thenReturn(List.of());
        when(memoryService.append(any(), any(), any())).thenReturn("message-1");
        when(queryRewriteService.rewriteWithSplit("原问题", List.of())).thenReturn(rewriteResult);
        when(intentResolver.resolve(rewriteResult)).thenReturn(subIntents);
        when(guidanceService.detectAmbiguity("改写问题", subIntents)).thenReturn(GuidanceDecision.none());
        when(intentResolver.isSystemOnly(anyList())).thenReturn(false);
        when(retrievalEngine.retrieve(subIntents)).thenReturn(retrievalContext);
        when(intentResolver.mergeKbIntents(subIntents)).thenReturn(List.of());
        when(citationContextEnricher.enrich("<content>资料</content>", List.of()))
                .thenReturn("<content>资料</content>");
        when(promptBuilder.buildStructuredMessages(any(), anyList(), any(), anyList())).thenReturn(List.of());

        pipeline.execute(StreamChatContext.builder()
                .question("原问题")
                .conversationId("conversation-1")
                .taskId("task-1")
                .userId("user-1")
                .callback(callback)
                .build());

        ArgumentCaptor<PromptContext> promptContext = ArgumentCaptor.forClass(PromptContext.class);
        verify(promptBuilder).buildStructuredMessages(promptContext.capture(), anyList(), any(), anyList());
        assertEquals(eligibleIntentIds, promptContext.getValue().getEligibleIntentIds());
    }

    @Test
    void answersWithNoEvidencePromptWhenRetrievalReturnsNothing() {
        StreamCallback callback = org.mockito.Mockito.mock(StreamCallback.class);
        RewriteResult rewriteResult = new RewriteResult("今天天气怎么样", List.of("今天天气怎么样"));
        List<SubQuestionIntent> subIntents = List.of(new SubQuestionIntent("今天天气怎么样", List.of()));
        RetrievalContext emptyRetrieval = RetrievalContext.builder()
                .kbContext("")
                .intentChunks(Map.of())
                .build();

        when(memoryService.load("conversation-1", "user-1")).thenReturn(List.of());
        when(memoryService.append(any(), any(), any())).thenReturn("message-1");
        when(queryRewriteService.rewriteWithSplit("原问题", List.of())).thenReturn(rewriteResult);
        when(intentResolver.resolve(rewriteResult)).thenReturn(subIntents);
        when(guidanceService.detectAmbiguity("今天天气怎么样", subIntents)).thenReturn(GuidanceDecision.none());
        when(intentResolver.isSystemOnly(anyList())).thenReturn(false);
        when(retrievalEngine.retrieve(subIntents)).thenReturn(emptyRetrieval);
        when(promptTemplateLoader.load(NO_EVIDENCE_RESPONSE_PROMPT_PATH)).thenReturn("兜底提示词");

        pipeline.execute(StreamChatContext.builder()
                .question("原问题")
                .conversationId("conversation-1")
                .taskId("task-1")
                .userId("user-1")
                .callback(callback)
                .build());

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmService).streamChat(request.capture(), eq(callback));
        assertEquals("兜底提示词", request.getValue().getMessages().get(0).getContent());
        verify(callback, never()).onContent(anyString());
    }
}
