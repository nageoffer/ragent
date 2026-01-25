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

package com.nageoffer.ai.ragent.rag.retrieve;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.dto.KbResult;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.mcp.MCPParameterExtractor;
import com.nageoffer.ai.ragent.rag.mcp.MCPRequest;
import com.nageoffer.ai.ragent.rag.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.mcp.MCPService;
import com.nageoffer.ai.ragent.rag.mcp.MCPTool;
import com.nageoffer.ai.ragent.rag.mcp.MCPToolExecutor;
import com.nageoffer.ai.ragent.rag.mcp.MCPToolRegistry;
import com.nageoffer.ai.ragent.rag.prompt.ContextFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.DEFAULT_TOP_K;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.INTENT_MIN_SCORE;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MIN_SEARCH_TOP_K;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.RERANK_LIMIT_MULTIPLIER;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.SEARCH_TOP_K_MULTIPLIER;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalEngine {

    private final RetrieverService retrieverService;
    private final RerankService rerankService;
    private final ContextFormatter contextFormatter;
    private final MCPService mcpService;
    private final MCPParameterExtractor mcpParameterExtractor;
    private final MCPToolRegistry mcpToolRegistry;
    @Qualifier("ragContextThreadPoolExecutor")
    private final Executor ragContextExecutor;
    @Qualifier("ragRetrievalThreadPoolExecutor")
    private final Executor ragRetrievalExecutor;

    public RetrievalContext retrieve(List<SubQuestionIntent> subIntents, int topK) {
        if (CollUtil.isEmpty(subIntents)) {
            return RetrievalContext.builder()
                    .mcpContext("")
                    .kbContext("")
                    .intentChunks(Map.of())
                    .build();
        }

        int finalTopK = topK > 0 ? topK : DEFAULT_TOP_K;
        List<CompletableFuture<SubQuestionContext>> tasks = subIntents.stream()
                .map(si -> CompletableFuture.supplyAsync(
                        () -> buildSubQuestionContext(si, finalTopK),
                        ragContextExecutor
                ))
                .toList();
        List<SubQuestionContext> contexts = tasks.stream()
                .map(CompletableFuture::join)
                .toList();

        StringBuilder kbBuilder = new StringBuilder();
        StringBuilder mcpBuilder = new StringBuilder();
        Map<String, List<RetrievedChunk>> mergedIntentChunks = new ConcurrentHashMap<>();

        for (SubQuestionContext context : contexts) {
            if (StrUtil.isNotBlank(context.kbContext())) {
                appendSection(kbBuilder, context.question(), context.kbContext());
            }
            if (StrUtil.isNotBlank(context.mcpContext())) {
                appendSection(mcpBuilder, context.question(), context.mcpContext());
            }
            if (CollUtil.isNotEmpty(context.intentChunks())) {
                mergedIntentChunks.putAll(context.intentChunks());
            }
        }

        return RetrievalContext.builder()
                .mcpContext(mcpBuilder.toString().trim())
                .kbContext(kbBuilder.toString().trim())
                .intentChunks(mergedIntentChunks)
                .build();
    }

    private SubQuestionContext buildSubQuestionContext(SubQuestionIntent intent, int topK) {
        List<NodeScore> kbIntents = filterKbIntents(intent.nodeScores());
        List<NodeScore> mcpIntents = filterMcpIntents(intent.nodeScores());

        KbResult kbResult = CollUtil.isNotEmpty(kbIntents)
                ? retrieveAndRerank(intent.subQuestion(), kbIntents, topK)
                : KbResult.empty();
        String mcpContext = CollUtil.isNotEmpty(mcpIntents)
                ? executeMcpAndMerge(intent.subQuestion(), mcpIntents)
                : "";

        return new SubQuestionContext(intent.subQuestion(), kbResult.groupedContext(), mcpContext, kbResult.intentChunks());
    }

    private void appendSection(StringBuilder builder, String question, String context) {
        builder.append("### 子问题：").append(question).append("\n")
                .append(context).append("\n\n");
    }

    private List<NodeScore> filterMcpIntents(List<NodeScore> nodeScores) {
        return nodeScores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .filter(ns -> ns.getNode() != null && ns.getNode().getKind() == IntentKind.MCP)
                .filter(ns -> StrUtil.isNotBlank(ns.getNode().getMcpToolId()))
                .toList();
    }

    private List<NodeScore> filterKbIntents(List<NodeScore> nodeScores) {
        return nodeScores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .filter(ns -> {
                    IntentNode node = ns.getNode();
                    if (node == null) {
                        return false;
                    }
                    return node.getKind() == null || node.getKind() == IntentKind.KB;
                })
                .toList();
    }

    private String executeMcpAndMerge(String question, List<NodeScore> mcpIntents) {
        if (CollUtil.isEmpty(mcpIntents)) {
            return "";
        }

        List<MCPResponse> responses = executeMcpTools(question, mcpIntents);
        if (responses.isEmpty() || responses.stream().noneMatch(MCPResponse::isSuccess)) {
            return "";
        }

        return contextFormatter.formatMcpContext(responses, mcpIntents);
    }

    private KbResult retrieveAndRerank(String question, List<NodeScore> kbIntents, int topK) {
        if (CollUtil.isEmpty(kbIntents)) {
            return KbResult.empty();
        }

        int searchTopK = Math.max(topK * SEARCH_TOP_K_MULTIPLIER, MIN_SEARCH_TOP_K);
        Map<String, List<RetrievedChunk>> intentChunks = new ConcurrentHashMap<>();

        List<CompletableFuture<Void>> tasks = kbIntents.stream()
                .map(ns -> CompletableFuture.supplyAsync(() -> {
                            IntentNode node = ns.getNode();
                            return retrieverService.retrieve(
                                    RetrieveRequest.builder()
                                            .collectionName(node.getCollectionName())
                                            .query(question)
                                            .topK(searchTopK)
                                            .build()
                            );
                        }, ragRetrievalExecutor)
                        .thenApply(chunks -> {
                            if (CollUtil.isEmpty(chunks)) {
                                return List.<RetrievedChunk>of();
                            }
                            int rerankLimit = topK * RERANK_LIMIT_MULTIPLIER;
                            return rerankService.rerank(question, chunks, rerankLimit);
                        })
                        .thenAccept(perIntent -> {
                            if (CollUtil.isNotEmpty(perIntent)) {
                                intentChunks.put(ns.getNode().getId(), perIntent);
                            }
                        }))
                .toList();

        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();

        if (intentChunks.isEmpty()) {
            return KbResult.empty();
        }

        String groupedContext = contextFormatter.formatKbContext(kbIntents, intentChunks, topK);
        return new KbResult(groupedContext, intentChunks);
    }

    private List<MCPResponse> executeMcpTools(String question, List<NodeScore> mcpIntentScores) {
        List<MCPRequest> requests = mcpIntentScores.stream()
                .map(ns -> buildMcpRequest(question, ns.getNode()))
                .filter(Objects::nonNull)
                .toList();

        return requests.isEmpty() ? List.of() : mcpService.executeBatch(requests);
    }

    private MCPRequest buildMcpRequest(String question, IntentNode intentNode) {
        String toolId = intentNode.getMcpToolId();
        Optional<MCPToolExecutor> executorOpt = mcpToolRegistry.getExecutor(toolId);
        if (executorOpt.isEmpty()) {
            log.warn("MCP 工具不存在: {}", toolId);
            return null;
        }

        MCPTool tool = executorOpt.get().getToolDefinition();

        String customParamPrompt = intentNode.getParamPromptTemplate();
        Map<String, Object> params = mcpParameterExtractor.extractParameters(question, tool, customParamPrompt);
        log.info("MCP 参数提取 - toolId: {}, 使用自定义提示词: {}, params: {}",
                toolId, StrUtil.isNotBlank(customParamPrompt), params);

        return MCPRequest.builder()
                .toolId(toolId)
                .userQuestion(question)
                .parameters(params)
                .build();
    }

    private record SubQuestionContext(String question,
                                      String kbContext,
                                      String mcpContext,
                                      Map<String, List<RetrievedChunk>> intentChunks) {
    }
}
