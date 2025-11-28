package com.nageoffer.ai.ragent.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.convention.ChatRequest;
import com.nageoffer.ai.ragent.enums.IntentKind;
import com.nageoffer.ai.ragent.service.RAGService;
import com.nageoffer.ai.ragent.service.rag.chat.LLMService;
import com.nageoffer.ai.ragent.service.rag.chat.StreamCallback;
import com.nageoffer.ai.ragent.service.rag.intent.IntentNode;
import com.nageoffer.ai.ragent.service.rag.intent.LLMTreeIntentClassifier;
import com.nageoffer.ai.ragent.service.rag.intent.NodeScore;
import com.nageoffer.ai.ragent.service.rag.rerank.RerankService;
import com.nageoffer.ai.ragent.service.rag.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.service.rag.retrieve.RetrievedChunk;
import com.nageoffer.ai.ragent.service.rag.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.service.rag.rewrite.QueryRewriteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.constant.RAGConstant.CHAT_SYSTEM_PROMPT;
import static com.nageoffer.ai.ragent.constant.RAGConstant.INTENT_MIN_SCORE;
import static com.nageoffer.ai.ragent.constant.RAGConstant.MAX_INTENT_COUNT;
import static com.nageoffer.ai.ragent.constant.RAGConstant.RAG_DEFAULT_PROMPT;
import static com.nageoffer.ai.ragent.enums.IntentKind.SYSTEM;

@Slf4j
@Service
@RequiredArgsConstructor
public class RAGServiceImpl implements RAGService {

    private final RetrieverService retrieverService;
    private final LLMService llmService;
    private final RerankService rerankService;
    private final LLMTreeIntentClassifier llmTreeIntentClassifier;
    private final QueryRewriteService queryRewriteService;

    @Override
    public String answer(String question, int topK) {
        String rewriteQuestion = queryRewriteService.rewrite(question);
        List<NodeScore> nodeScores = llmTreeIntentClassifier.classifyTargets(rewriteQuestion);

        if (nodeScores.size() == 1) {
            if (Objects.equals(nodeScores.get(0).getNode().getKind(), SYSTEM)) {
                String prompt = CHAT_SYSTEM_PROMPT.formatted(rewriteQuestion);

                ChatRequest req = ChatRequest.builder()
                        .prompt(prompt)
                        .temperature(0.7D) // 打招呼可以稍微活泼一点
                        .topP(0.8D)
                        .thinking(false)
                        .build();

                return llmService.chat(req);
            }
        }

        List<NodeScore> ragIntentScores = nodeScores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .filter(ns -> {
                    IntentNode node = ns.getNode();
                    return node.getKind() == null || node.getKind() == IntentKind.KB;
                })
                .limit(MAX_INTENT_COUNT)
                .toList();

        int finalTopK = topK > 0 ? topK : 5;
        int searchTopK = Math.max(finalTopK * 3, 20); // 至少 20，避免问题很小时候召回太少

        List<RetrievedChunk> allChunks = Collections.synchronizedList(new ArrayList<>());
        ragIntentScores.parallelStream().forEach(nodeScore -> {
            RetrieveRequest request = RetrieveRequest.builder()
                    .collectionName(nodeScore.getNode().getCollectionName())
                    .query(rewriteQuestion)
                    .topK(searchTopK)
                    .build();

            List<RetrievedChunk> nodeRetrieveChunks = retrieverService.retrieve(request);
            if (CollUtil.isNotEmpty(nodeRetrieveChunks)) {
                allChunks.addAll(nodeRetrieveChunks);
            }
        });

        // 如果没有检索到内容，直接 fallback
        if (allChunks.isEmpty()) {
            return "未检索到与问题相关的文档内容，请尝试换一个问法。";
        }

        int rerankLimit = finalTopK * 2;
        List<RetrievedChunk> reranked = rerankService.rerank(rewriteQuestion, allChunks, rerankLimit);

        // List<RetrievedChunk> elbowSelected = selectByElbow(reranked);
        // 暂时忽略分数排序，因为不同知识库类型召回数据不同，容易误判
        List<RetrievedChunk> elbowSelected = reranked;

        double MIN_SCORE = 0.4;
        double MARGIN_RATIO = 0.75;

        float bestScore = elbowSelected.get(0).getScore();
        List<RetrievedChunk> filteredByScore = elbowSelected.stream()
                .filter(c -> {
                    Float s = c.getScore();
                    if (s == null) {
                        return true;
                    }
                    return s >= MIN_SCORE && s >= bestScore * MARGIN_RATIO;
                })
                .toList();

        // 如果筛完太少，就退回到 reranked 前 topK 几条
        if (filteredByScore.isEmpty()) {
            filteredByScore = reranked.subList(0, Math.min(finalTopK, reranked.size()));
        }

        // 拼接上下文
        String context = filteredByScore.stream()
                .map(h -> "- " + h.getText())
                .collect(Collectors.joining("\n"));

        String promptTemplate;
        if (ragIntentScores.size() == 1 &&
                StrUtil.isNotBlank(ragIntentScores.get(0).getNode().getPromptTemplate())) {
            promptTemplate = ragIntentScores.get(0).getNode().getPromptTemplate();
        } else {
            promptTemplate = RAG_DEFAULT_PROMPT;
        }

        String prompt = promptTemplate.formatted(context, rewriteQuestion);

        // 调 LLM
        ChatRequest req = ChatRequest.builder()
                .prompt(prompt)
                .thinking(false)
                .temperature(0D)
                .topP(0.7D)
                .build();

        return llmService.chat(req);
    }

    @Override
    public void streamAnswer(String question, int topK, StreamCallback callback) {
        String rewriteQuestion = queryRewriteService.rewrite(question);

        List<NodeScore> nodeScores = llmTreeIntentClassifier.classifyTargets(rewriteQuestion);

        // 如果只有一个 SYSTEM 意图，走系统打招呼的流式输出
        if (nodeScores.size() == 1 && Objects.equals(nodeScores.get(0).getNode().getKind(), SYSTEM)) {
            String prompt = CHAT_SYSTEM_PROMPT.formatted(rewriteQuestion);
            ChatRequest req = ChatRequest.builder()
                    .prompt(prompt)
                    .temperature(0.7D)
                    .topP(0.8D)
                    .thinking(false)
                    .build();

            llmService.streamChat(req, callback);
            return;
        }

        List<NodeScore> ragIntentScores = nodeScores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .filter(ns -> {
                    IntentNode node = ns.getNode();
                    return node.getKind() == null || node.getKind() == IntentKind.KB;
                })
                .limit(MAX_INTENT_COUNT)
                .toList();

        int finalTopK = topK > 0 ? topK : 5;
        int searchTopK = Math.max(finalTopK * 3, 20); // 至少 20，避免召回过少

        List<RetrievedChunk> allChunks = Collections.synchronizedList(new ArrayList<>());
        ragIntentScores.parallelStream().forEach(nodeScore -> {
            RetrieveRequest request = RetrieveRequest.builder()
                    .collectionName(nodeScore.getNode().getCollectionName())
                    .query(rewriteQuestion)
                    .topK(searchTopK)
                    .build();
            List<RetrievedChunk> nodeRetrieveChunks = retrieverService.retrieve(request);
            if (CollUtil.isNotEmpty(nodeRetrieveChunks)) {
                allChunks.addAll(nodeRetrieveChunks);
            }
        });

        if (allChunks.isEmpty()) {
            callback.onContent("未检索到与问题相关的文档内容，请尝试换一个问法。");
            return;
        }

        int rerankLimit = finalTopK * 2;
        List<RetrievedChunk> reranked = rerankService.rerank(rewriteQuestion, allChunks, rerankLimit);

        // 暂时忽略肘部算法，直接采用 Rerank 排序链表
        List<RetrievedChunk> elbowSelected = reranked;

        final double MIN_SCORE = 0.4;
        final double MARGIN_RATIO = 0.75;

        float bestScore = elbowSelected.get(0).getScore();
        List<RetrievedChunk> filteredByScore = elbowSelected.stream()
                .filter(c -> {
                    Float s = c.getScore();
                    if (s == null) return true;
                    return s >= MIN_SCORE && s >= bestScore * MARGIN_RATIO;
                })
                .toList();

        if (filteredByScore.isEmpty()) {
            filteredByScore = reranked.subList(0, Math.min(finalTopK, reranked.size()));
        }

        String context = filteredByScore.stream()
                .map(h -> "- " + h.getText())
                .collect(Collectors.joining("\n"));

        String promptTemplate;
        if (ragIntentScores.size() == 1 &&
                StrUtil.isNotBlank(ragIntentScores.get(0).getNode().getPromptTemplate())) {
            promptTemplate = ragIntentScores.get(0).getNode().getPromptTemplate();
        } else {
            promptTemplate = RAG_DEFAULT_PROMPT;
        }
        String prompt = promptTemplate.formatted(context, rewriteQuestion);

        ChatRequest chatRequest = ChatRequest.builder()
                .prompt(prompt)
                .thinking(false)
                .temperature(0D)
                .topP(0.7D)
                .build();
        llmService.streamChat(chatRequest, callback);
    }

    private List<RetrievedChunk> selectByElbow(List<RetrievedChunk> reranked) {
        if (reranked.size() <= 1) {
            return reranked;
        }

        // 至少保留 1 条
        int cutIndex = reranked.size();

        // 简单策略：ratio < 0.9 认为是一个“断崖”
        double DROP_RATIO = 0.9;

        for (int i = 0; i < reranked.size() - 1; i++) {
            float cur = reranked.get(i).getScore();
            float next = reranked.get(i + 1).getScore();

            if (cur <= 0) {
                break;
            }

            double ratio = next / cur;
            if (ratio < DROP_RATIO) {
                // 截到 i（包含 i），后面的认为是另一个“段”
                cutIndex = i + 1;
                break;
            }
        }

        return reranked.subList(0, cutIndex);
    }
}
