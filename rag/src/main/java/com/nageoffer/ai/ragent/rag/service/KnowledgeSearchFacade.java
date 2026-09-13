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
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScopeResolver;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.core.source.CitationContextEnricher;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识检索门面：Agent 模式下 rag 对外的唯一检索窄口
 * 改写 -> 意图解析（内置 KB-only 过滤）-> 歧义引导 -> 多通道检索 -> KB_ANSWER 合成，返回可直接引用的答案文本
 * 引用/来源装配定死不走，与 rag.citation.enabled 无关
 * 近期轮次只喂给改写做指代消解，合成阶段不带历史：工具结论只依据本次证据
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeSearchFacade {

    public static final String EMPTY_RESULT = "未在知识库中检索到与该问题相关的内容。";

    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final IntentGuidanceService guidanceService;
    private final RetrievalEngine retrievalEngine;
    private final CitationContextEnricher citationContextEnricher;
    private final RAGPromptService promptService;
    private final LLMService llmService;

    /**
     * 检索并合成答案，供主 Agent 的 search_knowledge 工具调用
     *
     * @param recentHistory 主 Agent 会话的近期 user/assistant 轮次，仅用于改写阶段的指代消解
     */
    public String search(String query, List<ChatMessage> recentHistory) {
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(query, recentHistory);
        List<SubQuestionIntent> subIntents = filterKbOnly(intentResolver.resolve(rewriteResult));

        GuidanceDecision guidance = guidanceService.detectAmbiguity(
                rewriteResult.rewrittenQuestion(), subIntents);
        if (guidance.isPrompt()) {
            log.info("Agent 知识库检索命中歧义引导，跳过检索与答案合成, question={}",
                    rewriteResult.rewrittenQuestion());
            return guidance.getPrompt();
        }

        RetrievalContext retrievalCtx = retrievalEngine.retrieve(subIntents);
        if (!retrievalCtx.hasKb()) {
            return EMPTY_RESULT;
        }

        // 工具不渲染角标，但内部 docId 一定要抹掉，否则会随工具结果漏进主 Agent 的可见文本
        String kbContext = citationContextEnricher.stripDocIdAnchors(retrievalCtx.getKbContext());

        IntentGroup mergedGroup = intentResolver.mergeIntentGroup(subIntents);
        //promptContext
        PromptContext promptContext = PromptContext.builder()
                .question(rewriteResult.rewrittenQuestion())
                .kbContext(kbContext)
                .kbIntents(mergedGroup.kbIntents())
                .eligibleIntentIds(retrievalCtx.getEligibleIntentIds())
                .build();
        List<ChatMessage> messages = promptService.buildStructuredMessages(
                promptContext, List.of(), rewriteResult.rewrittenQuestion(), rewriteResult.subQuestions(), false);

        return llmService.chat(ChatRequest.builder()
                .messages(messages)
                .temperature(0D)
                .topP(1D)
                .thinking(false)
                .build());
    }

    /**
     * 只保留 KB 意图：MCP 走原生工具，SYSTEM 由主 Agent 人设直接承担
     * <p>
     * 剩零意图的子问题照样往下走，不在此拦截：作用域判定只有 {@link RetrievalScopeResolver}
     * 一份，零意图在那里回落全局检索。拦在这里等于把工具调用否决两次——主 Agent 已判过一次「该查知识库」
     */
    private List<SubQuestionIntent> filterKbOnly(List<SubQuestionIntent> subIntents) {
        return subIntents.stream()
                .map(si -> new SubQuestionIntent(si.subQuestion(), NodeScoreFilters.kb(si.nodeScores())))
                .toList();
    }
}
