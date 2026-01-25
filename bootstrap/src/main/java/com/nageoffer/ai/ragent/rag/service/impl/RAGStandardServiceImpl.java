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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.intent.IntentClassifier;
import com.nageoffer.ai.ragent.rag.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.prompt.RAGStandardPromptService;
import com.nageoffer.ai.ragent.rag.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.service.RAGService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.DEFAULT_TOP_K;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.INTENT_MIN_SCORE;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MAX_INTENT_COUNT;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MIN_SEARCH_TOP_K;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.RERANK_LIMIT_MULTIPLIER;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.SCORE_MARGIN_RATIO;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.SEARCH_TOP_K_MULTIPLIER;
import static com.nageoffer.ai.ragent.rag.enums.IntentKind.SYSTEM;

@Slf4j
@Service("ragStandardService")
@RequiredArgsConstructor
public class RAGStandardServiceImpl implements RAGService {

    private final RetrieverService retrieverService;
    private final LLMService llmService;
    private final RerankService rerankService;
    private final IntentClassifier defaultIntentClassifier;
    private final QueryRewriteService defaultQueryRewriteService;
    private final RAGStandardPromptService ragStandardPromptService;
    private final PromptTemplateLoader promptTemplateLoader;

    @Override
    public void streamChat(String question, int topK, StreamCallback callback) {
        String rewriteQuestion = defaultQueryRewriteService.rewrite(question);

        List<NodeScore> nodeScores = defaultIntentClassifier.classifyTargets(rewriteQuestion);

        // 如果只有一个 SYSTEM 意图，走系统打招呼的流式输出
        if (nodeScores.size() == 1 && Objects.equals(nodeScores.get(0).getNode().getKind(), SYSTEM)) {
            String prompt = promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);
            ChatRequest req = ChatRequest.builder()
                    .messages(List.of(
                            ChatMessage.system(prompt),
                            ChatMessage.user(rewriteQuestion))
                    )
                    .temperature(0.7D)
                    .topP(0.8D)
                    .thinking(false)
                    .build();

            llmService.streamChat(req, callback);
            return;
        }

        // 过滤出 RAG 意图（KB 类型）
        List<NodeScore> ragIntentScores = nodeScores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .filter(ns -> {
                    IntentNode node = ns.getNode();
                    return node.getKind() == null || node.getKind() == IntentKind.KB;
                })
                .limit(MAX_INTENT_COUNT)
                .toList();

        int finalTopK = topK > 0 ? topK : DEFAULT_TOP_K;
        int searchTopK = Math.max(finalTopK * SEARCH_TOP_K_MULTIPLIER, MIN_SEARCH_TOP_K);

        // 跟同步版保持一致：按意图拆分检索结果
        Map<String, List<RetrievedChunk>> intentChunks = new java.util.concurrent.ConcurrentHashMap<>();
        ragIntentScores.parallelStream().forEach(ns -> {
            IntentNode node = ns.getNode();
            RetrieveRequest request = RetrieveRequest.builder()
                    .collectionName(node.getCollectionName())
                    .query(rewriteQuestion)
                    .topK(searchTopK)
                    .build();
            List<RetrievedChunk> chunks = retrieverService.retrieve(request);
            if (CollUtil.isNotEmpty(chunks)) {
                intentChunks.put(node.getId(), chunks);
            }
        });

        // 只保留有检索结果的意图
        List<NodeScore> retainedIntents = ragIntentScores.stream()
                .filter(ns -> CollUtil.isNotEmpty(intentChunks.get(ns.getNode().getId())))
                .toList();

        // 聚合所有 chunk 做 Rerank
        List<RetrievedChunk> allChunks = retainedIntents.stream()
                .flatMap(ns -> intentChunks.get(ns.getNode().getId()).stream())
                .collect(Collectors.toList());

        // 如果没有检索到内容，直接 fallback
        if (allChunks.isEmpty()) {
            callback.onContent("未检索到与问题相关的文档内容。");
            return;
        }

        int rerankLimit = finalTopK * RERANK_LIMIT_MULTIPLIER;
        List<RetrievedChunk> reranked = rerankService.rerank(rewriteQuestion, allChunks, rerankLimit);

        // 暂时忽略肘部算法，直接采用 Rerank 排序链表
        List<RetrievedChunk> filteredByScore = reranked;

        // 拼接上下文
        String context = filteredByScore.stream()
                .map(h -> "- " + h.getText())
                .collect(Collectors.joining("\n"));

        // 使用 ragPromptService 统一构建 Prompt（与同步方法保持一致）
        String prompt = ragStandardPromptService.buildPrompt(
                context,
                rewriteQuestion,
                ragIntentScores,
                intentChunks
        );

        // 调 LLM 流式输出
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .thinking(false)
                .temperature(0D)
                .topP(0.7D)
                .build();

        llmService.streamChat(chatRequest, callback);
    }

    /**
     * 根据分数过滤检索结果
     *
     * @param reranked  已经 rerank 后的结果列表
     * @param finalTopK 最终需要的 TopK 数量
     * @return 过滤后的结果列表
     */
    private List<RetrievedChunk> filterByScore(List<RetrievedChunk> reranked, int finalTopK) {
        if (CollUtil.isEmpty(reranked)) {
            return reranked;
        }

        // 选一个非空的最高分作对比基准（为空则跳过阈值过滤）
        Float bestScore = reranked.stream()
                .map(RetrievedChunk::getScore)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        List<RetrievedChunk> filtered = reranked.stream()
                .filter(c -> {
                    Float s = c.getScore();
                    if (s == null || bestScore == null) {
                        return true;
                    }
                    return s >= INTENT_MIN_SCORE && s >= bestScore * SCORE_MARGIN_RATIO;
                })
                .toList();

        // 如果筛完太少，就退回到 reranked 前 topK 几条
        if (filtered.isEmpty()) {
            return reranked.subList(0, Math.min(finalTopK, reranked.size()));
        }

        return filtered;
    }
}
