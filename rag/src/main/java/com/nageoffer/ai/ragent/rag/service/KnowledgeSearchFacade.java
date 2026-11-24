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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.prompt.KnowledgeEvidenceRenderer;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.trace.KnowledgeSearchSpans;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 知识检索门面：Agent 模式下 rag 对外的唯一检索窄口
 * 改写 -> KB 意图解析 -> 歧义引导 -> 多通道检索 -> 按文档归组的资料原文，由主 Agent 读后作答
 * 引用/来源装配定死不走，与 rag.citation.enabled 无关
 * 不带任何会话历史：主 Agent 已消解过指代，传进来的是被它有意收窄的查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeSearchFacade {

    private static final String EMPTY_RESULT = "未在知识库中检索到与该问题相关的内容。";

    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final IntentGuidanceService guidanceService;
    private final RetrievalEngine retrievalEngine;
    private final KnowledgeEvidenceRenderer evidenceRenderer;

    /**
     * 检索并交回资料原文，供主 Agent 的 search_knowledge 工具调用；歧义与零命中各自提前返回一句话
     */
    public String search(String query) {
        // 不喂历史：主 Agent 手握完整对话，传进来的已是消解过、且被它有意收窄的查询
        RewriteResult rewriteResult = KnowledgeSearchSpans.trace("query_rewrite", () -> {
            RewriteResult result = queryRewriteService.rewriteWithSplit(query, List.of());
            KnowledgeSearchSpans.count(KnowledgeSearchSpans.SUB_QUESTION_COUNT, CollUtil.size(result.subQuestions()));
            return result;
        });
        List<SubQuestionIntent> subIntents = KnowledgeSearchSpans.trace("intent_resolve", () -> {
            List<SubQuestionIntent> resolved = intentResolver.resolve(rewriteResult);
            KnowledgeSearchSpans.count(KnowledgeSearchSpans.INTENT_COUNT, intentResolver.mergeKbIntents(resolved).size());
            return resolved;
        });

        GuidanceDecision guidance = KnowledgeSearchSpans.trace("guidance",
                () -> guidanceService.detectAmbiguity(rewriteResult.rewrittenQuestion(), subIntents));
        if (guidance.isPrompt()) {
            log.info("Agent 知识库检索命中歧义引导，跳过检索, question={}",
                    rewriteResult.rewrittenQuestion());
            return guidance.getPrompt();
        }

        RetrievalContext retrievalCtx = retrievalEngine.retrieve(subIntents);
        if (retrievalCtx.getContextChunks().isEmpty()) {
            return EMPTY_RESULT;
        }
        return evidenceRenderer.render(retrievalCtx.getContextChunks(), answerRules(subIntents, retrievalCtx));
    }

    /**
     * 留下了资料的子问题所命中意图的答题规则，带意图全路径：叶子可能同名，正文又已跨子问题合并，只写名字分不清规则管谁
     */
    private List<String> answerRules(List<SubQuestionIntent> subIntents, RetrievalContext retrievalCtx) {
        Set<String> evidenced = retrievalCtx.getEvidencedIntentIds();
        return intentResolver.mergeKbIntents(subIntents).stream()
                .map(NodeScore::getNode)
                .filter(node -> node != null && evidenced.contains(node.getId()))
                .filter(node -> StrUtil.isNotBlank(node.getPromptSnippet()))
                .map(node -> "[" + StrUtil.blankToDefault(node.getFullPath(), node.getName()) + "] "
                        + node.getPromptSnippet().trim())
                .distinct()
                .toList();
    }
}
