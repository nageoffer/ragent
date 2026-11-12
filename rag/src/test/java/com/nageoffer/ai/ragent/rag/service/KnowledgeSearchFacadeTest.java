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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.core.source.CitationContextEnricher;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 门面契约：内部 docId 不得随工具结果外泄，近期轮次要喂到改写
 */
class KnowledgeSearchFacadeTest {

    private static final String QUESTION = "报销标准是多少";
    private static final String KB_CONTEXT = """
            <content data-ragent-doc-id="doc-a">
            差旅报销上限 800 元
            </content>
            """;

    private final QueryRewriteService queryRewriteService = mock(QueryRewriteService.class);
    private final IntentResolver intentResolver = mock(IntentResolver.class);
    private final IntentGuidanceService guidanceService = mock(IntentGuidanceService.class);
    private final RetrievalEngine retrievalEngine = mock(RetrievalEngine.class);
    private final RAGPromptService promptService = mock(RAGPromptService.class);
    private final LLMService llmService = mock(LLMService.class);

    /**
     * 引用开关开着也不能漏：门面这条路不装配来源，锚点只能被抹掉不能被翻译成编号
     */
    @Test
    void removesInternalDocumentIdsBeforeHandingContextToModel() {
        KnowledgeSearchFacade facade = facade(true);
        stubRetrievalHit();

        facade.search(QUESTION);

        String kbContext = capturePromptContext().getKbContext();
        assertFalse(kbContext.contains("data-ragent-doc-id"), "内部文档 ID 不得进入模型可见文本");
        assertFalse(kbContext.contains("ref="), "门面无来源编号，不得注入角标");
        assertTrue(kbContext.contains("差旅报销上限 800 元"), "正文不应被改写规则误伤");
    }

    /**
     * 改写与合成两端都不带历史
     * <p>
     * 主 Agent 传进来的已是消解过、且被它有意收窄的查询，再喂历史会让改写拿上一轮的
     * 实体把它反向补全，产出看着合理却检索错的问题
     */
    @Test
    void passesNoHistoryToRewriteNorSynthesis() {
        KnowledgeSearchFacade facade = facade(false);
        stubRetrievalHit();

        facade.search("它的上限是多少");

        ArgumentCaptor<List<ChatMessage>> rewriteHistory = ArgumentCaptor.forClass(List.class);
        verify(queryRewriteService).rewriteWithSplit(anyString(), rewriteHistory.capture());
        assertTrue(rewriteHistory.getValue().isEmpty(), "改写阶段不得拿到会话历史");

        ArgumentCaptor<List<ChatMessage>> promptHistory = ArgumentCaptor.forClass(List.class);
        verify(promptService).buildStructuredMessages(
                any(PromptContext.class), promptHistory.capture(), anyString(), anyList(), anyBoolean());
        assertTrue(promptHistory.getValue().isEmpty(), "合成阶段只依据本次证据");
    }

    /**
     * 零 KB 意图不是「没得查」而是「不知道查哪」：子问题须原样下传，由作用域解析器回落全局检索
     * <p>
     * 逐子问题各判各的，与 v1 管线同构：一个子问题蒙中意图，不该让其余子问题连库都进不去
     */
    @Test
    void keepsIntentlessSubQuestionsSoScopeResolverCanFallBackToGlobal() {
        KnowledgeSearchFacade facade = facade(false);
        NodeScore kbNode = NodeScore.builder()
                .node(IntentNode.builder().id("kb-1").build())
                .score(1.0D)
                .build();
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult(QUESTION, List.of("报销标准是多少", "公司福利有哪些")));
        when(intentResolver.resolve(any(RewriteResult.class))).thenReturn(List.of(
                new SubQuestionIntent("报销标准是多少", List.of(kbNode)),
                new SubQuestionIntent("公司福利有哪些", List.of())
        ));
        when(intentResolver.mergeKbIntents(anyList())).thenReturn(List.of(kbNode));
        when(retrievalEngine.retrieve(anyList()))
                .thenReturn(RetrievalContext.builder().kbContext(KB_CONTEXT).build());
        when(promptService.buildStructuredMessages(
                any(PromptContext.class), anyList(), anyString(), anyList(), anyBoolean()))
                .thenReturn(List.of());
        when(llmService.chat(any())).thenReturn("答案");

        facade.search(QUESTION);

        ArgumentCaptor<List<SubQuestionIntent>> retrieved = ArgumentCaptor.forClass(List.class);
        verify(retrievalEngine).retrieve(retrieved.capture());
        assertEquals(List.of("报销标准是多少", "公司福利有哪些"),
                retrieved.getValue().stream().map(SubQuestionIntent::subQuestion).toList(),
                "零意图子问题不得在门面被丢弃");
    }

    @Test
    void returnsGuidanceWithoutRetrievalOrAnswerSynthesisWhenQuestionIsAmbiguous() {
        KnowledgeSearchFacade facade = facade(false);
        NodeScore oaSecurity = NodeScore.builder()
                .node(IntentNode.builder().id("oa-security").name("数据安全").build())
                .score(0.62D)
                .build();
        NodeScore insuranceSecurity = NodeScore.builder()
                .node(IntentNode.builder().id("insurance-security").name("数据安全").build())
                .score(0.60D)
                .build();
        List<SubQuestionIntent> subIntents = List.of(
                new SubQuestionIntent(QUESTION, List.of(oaSecurity, insuranceSecurity)));
        String prompt = "关于数据安全，请选择 OA 系统或保险系统";
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult(QUESTION, List.of(QUESTION)));
        when(intentResolver.resolve(any(RewriteResult.class))).thenReturn(subIntents);
        when(guidanceService.detectAmbiguity(QUESTION, subIntents))
                .thenReturn(GuidanceDecision.prompt(prompt));

        String result = facade.search(QUESTION);

        assertEquals(prompt, result);
        verify(retrievalEngine, never()).retrieve(anyList());
        verify(promptService, never()).buildStructuredMessages(
                any(PromptContext.class), anyList(), anyString(), anyList(), anyBoolean());
        verify(llmService, never()).chat(any());
    }

    private KnowledgeSearchFacade facade(boolean citationEnabled) {
        RAGConfigProperties properties = new RAGConfigProperties();
        properties.setCitationEnabled(citationEnabled);
        when(guidanceService.detectAmbiguity(anyString(), anyList())).thenReturn(GuidanceDecision.none());
        return new KnowledgeSearchFacade(queryRewriteService, intentResolver, guidanceService, retrievalEngine,
                new CitationContextEnricher(properties), promptService, llmService);
    }

    private void stubRetrievalHit() {
        NodeScore kbNode = NodeScore.builder()
                .node(IntentNode.builder().id("kb-1").build())
                .score(1.0D)
                .build();
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult(QUESTION, List.of(QUESTION)));
        when(intentResolver.resolve(any(RewriteResult.class)))
                .thenReturn(List.of(new SubQuestionIntent(QUESTION, List.of(kbNode))));
        when(intentResolver.mergeKbIntents(anyList())).thenReturn(List.of(kbNode));
        when(retrievalEngine.retrieve(anyList()))
                .thenReturn(RetrievalContext.builder().kbContext(KB_CONTEXT).build());
        when(promptService.buildStructuredMessages(
                any(PromptContext.class), anyList(), anyString(), anyList(), anyBoolean()))
                .thenReturn(List.of());
        when(llmService.chat(any())).thenReturn("差旅报销上限 800 元");
    }

    private PromptContext capturePromptContext() {
        ArgumentCaptor<PromptContext> captor = ArgumentCaptor.forClass(PromptContext.class);
        verify(promptService).buildStructuredMessages(
                captor.capture(), anyList(), anyString(), anyList(), anyBoolean());
        return captor.getValue();
    }
}
