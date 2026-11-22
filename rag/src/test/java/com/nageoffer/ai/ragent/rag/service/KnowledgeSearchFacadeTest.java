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
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.prompt.KnowledgeEvidenceRenderer;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 门面契约：交回按文档归组的资料原文，内部 docId 不外泄，改写不带历史，歧义与零命中各自提前返回
 */
class KnowledgeSearchFacadeTest {

    private static final String QUESTION = "报销标准是多少";

    private final QueryRewriteService queryRewriteService = mock(QueryRewriteService.class);
    private final IntentResolver intentResolver = mock(IntentResolver.class);
    private final IntentGuidanceService guidanceService = mock(IntentGuidanceService.class);
    private final RetrievalEngine retrievalEngine = mock(RetrievalEngine.class);

    @Test
    void returnsEvidenceGroupedByDocumentWithoutInternalIds() {
        stubRetrievalHit();

        String result = facade().search(QUESTION);

        assertEquals("""
                <retrieval>
                <document>
                <doc_name>差旅制度.md</doc_name>
                差旅报销上限 800 元
                </document>
                </retrieval>""", result);
        assertFalse(result.contains("doc-a"), "内部文档 ID 不得进入模型可见文本");
    }

    /**
     * 主 Agent 传进来的已是消解过、且被它有意收窄的查询，再喂历史会让改写拿上一轮的
     * 实体把它反向补全，产出看着合理却检索错的问题
     */
    @Test
    void passesNoHistoryToRewrite() {
        stubRetrievalHit();

        facade().search("它的上限是多少");

        ArgumentCaptor<List<ChatMessage>> rewriteHistory = ArgumentCaptor.forClass(List.class);
        verify(queryRewriteService).rewriteWithSplit(anyString(), rewriteHistory.capture());
        assertTrue(rewriteHistory.getValue().isEmpty(), "改写阶段不得拿到会话历史");
    }

    /**
     * 零 KB 意图不是「没得查」而是「不知道查哪」：子问题须原样下传，由作用域解析器回落全局检索
     */
    @Test
    void keepsIntentlessSubQuestionsSoScopeResolverCanFallBackToGlobal() {
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
        when(retrievalEngine.retrieve(anyList())).thenReturn(retrievalHit(Set.of("kb-1")));

        facade().search(QUESTION);

        ArgumentCaptor<List<SubQuestionIntent>> retrieved = ArgumentCaptor.forClass(List.class);
        verify(retrievalEngine).retrieve(retrieved.capture());
        assertEquals(List.of("报销标准是多少", "公司福利有哪些"),
                retrieved.getValue().stream().map(SubQuestionIntent::subQuestion).toList(),
                "零意图子问题不得在门面被丢弃");
    }

    @Test
    void returnsGuidanceWithoutRetrievalWhenQuestionIsAmbiguous() {
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
        KnowledgeSearchFacade facade = facade();
        when(guidanceService.detectAmbiguity(QUESTION, subIntents)).thenReturn(GuidanceDecision.prompt(prompt));

        assertEquals(prompt, facade.search(QUESTION));
        verify(retrievalEngine, never()).retrieve(anyList());
    }

    @Test
    void returnsEmptyNoticeWhenNothingRetrieved() {
        stubRetrievalHit();
        when(retrievalEngine.retrieve(anyList())).thenReturn(RetrievalContext.builder().build());

        assertEquals("未在知识库中检索到与该问题相关的内容。", facade().search(QUESTION));
    }

    /**
     * 两个叶子同名、各配一条规则：用全路径区分；没留下资料的那一题，意图虽仍 eligible，也不输出规则
     */
    @Test
    void labelsRulesByIntentPathAndSkipsIntentWithoutEvidence() {
        stubRetrievalHit();
        when(intentResolver.mergeKbIntents(anyList())).thenReturn(List.of(
                rule("kb-1", "数据安全", "手机服务 > 数据安全", "先说要不要备份"),
                rule("kb-2", "数据安全", "账户服务 > 数据安全", "先确认账号归属"),
                rule("kb-3", "以旧换新", "售后服务 > 以旧换新", "先说能不能折抵")));
        when(retrievalEngine.retrieve(anyList())).thenReturn(RetrievalContext.builder()
                .contextChunks(List.of(chunk()))
                .eligibleIntentIds(Set.of("kb-1", "kb-2", "kb-3"))
                .evidencedIntentIds(Set.of("kb-1", "kb-2"))
                .build());

        String result = facade().search(QUESTION);

        assertTrue(result.contains("<rules>\n[手机服务 > 数据安全] 先说要不要备份\n[账户服务 > 数据安全] 先确认账号归属\n</rules>"),
                result);
        assertFalse(result.contains("以旧换新"), result);
    }

    private KnowledgeSearchFacade facade() {
        when(guidanceService.detectAmbiguity(anyString(), anyList())).thenReturn(GuidanceDecision.none());
        return new KnowledgeSearchFacade(queryRewriteService, intentResolver, guidanceService, retrievalEngine,
                new KnowledgeEvidenceRenderer(new SearchChannelProperties()));
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
        when(retrievalEngine.retrieve(anyList())).thenReturn(retrievalHit(Set.of("kb-1")));
    }

    private static RetrievalContext retrievalHit(Set<String> intentIds) {
        return RetrievalContext.builder()
                .contextChunks(List.of(chunk()))
                .eligibleIntentIds(intentIds)
                .evidencedIntentIds(intentIds)
                .build();
    }

    private static RetrievedChunk chunk() {
        return RetrievedChunk.builder()
                .id("chunk-1").docId("doc-a").docName("差旅制度.md").chunkIndex(0).score(0.9F)
                .text("差旅报销上限 800 元").build();
    }

    private static NodeScore rule(String id, String name, String fullPath, String snippet) {
        return NodeScore.builder()
                .node(IntentNode.builder().id(id).name(name).fullPath(fullPath).promptSnippet(snippet).build())
                .score(1.0D)
                .build();
    }
}
