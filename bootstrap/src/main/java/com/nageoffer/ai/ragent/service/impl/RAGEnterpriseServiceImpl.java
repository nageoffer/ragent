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

package com.nageoffer.ai.ragent.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.constant.RAGConstant;
import com.nageoffer.ai.ragent.dto.IntentCandidate;
import com.nageoffer.ai.ragent.dto.IntentGroup;
import com.nageoffer.ai.ragent.dto.KbResult;
import com.nageoffer.ai.ragent.dto.RetrievalContext;
import com.nageoffer.ai.ragent.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.enums.IntentKind;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.intent.IntentClassifier;
import com.nageoffer.ai.ragent.rag.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.mcp.MCPParameterExtractor;
import com.nageoffer.ai.ragent.rag.mcp.MCPRequest;
import com.nageoffer.ai.ragent.rag.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.mcp.MCPService;
import com.nageoffer.ai.ragent.rag.mcp.MCPTool;
import com.nageoffer.ai.ragent.rag.mcp.MCPToolExecutor;
import com.nageoffer.ai.ragent.rag.mcp.MCPToolRegistry;
import com.nageoffer.ai.ragent.rag.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.prompt.RAGEnterprisePromptService;
import com.nageoffer.ai.ragent.rag.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.service.ConversationGroupService;
import com.nageoffer.ai.ragent.service.RAGEnterpriseService;
import com.nageoffer.ai.ragent.service.handler.ChatRateLimit;
import com.nageoffer.ai.ragent.service.handler.StreamChatEventHandler;
import com.nageoffer.ai.ragent.service.handler.StreamTaskManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static com.nageoffer.ai.ragent.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;
import static com.nageoffer.ai.ragent.constant.RAGConstant.DEFAULT_TOP_K;
import static com.nageoffer.ai.ragent.constant.RAGConstant.INTENT_MIN_SCORE;
import static com.nageoffer.ai.ragent.constant.RAGConstant.MAX_INTENT_COUNT;
import static com.nageoffer.ai.ragent.constant.RAGConstant.MIN_SEARCH_TOP_K;
import static com.nageoffer.ai.ragent.constant.RAGConstant.RERANK_LIMIT_MULTIPLIER;
import static com.nageoffer.ai.ragent.constant.RAGConstant.SEARCH_TOP_K_MULTIPLIER;
import static com.nageoffer.ai.ragent.enums.IntentKind.SYSTEM;

/**
 * RAG 企业版服务（V3）
 * <p>
 * 支持三种意图类型：SYSTEM / KB / MCP，以及 KB + MCP 混合场景
 */
@Slf4j
@Service
public class RAGEnterpriseServiceImpl implements RAGEnterpriseService {

    private final RetrieverService retrieverService;
    private final LLMService llmService;
    private final RerankService rerankService;
    private final IntentClassifier intentClassifier;
    private final QueryRewriteService queryRewriteService;
    private final RAGEnterprisePromptService promptBuilder;
    private final ContextFormatter contextFormatter;
    private final MCPService mcpService;
    private final MCPParameterExtractor mcpParameterExtractor;
    private final MCPToolRegistry mcpToolRegistry;
    private final PromptTemplateLoader promptTemplateLoader;
    private final ConversationMemoryService memoryService;
    private final StreamTaskManager taskManager;
    private final AIModelProperties modelProperties;
    private final ConversationGroupService conversationGroupService;
    private final Executor intentClassifyExecutor;
    private final Executor ragContextExecutor;
    private final Executor ragRetrievalExecutor;

    public RAGEnterpriseServiceImpl(
            RetrieverService retrieverService,
            LLMService llmService,
            RerankService rerankService,
            MCPService mcpService,
            MCPParameterExtractor mcpParameterExtractor,
            MCPToolRegistry mcpToolRegistry,
            PromptTemplateLoader promptTemplateLoader,
            RAGEnterprisePromptService promptBuilder,
            ContextFormatter contextFormatter,
            ConversationMemoryService memoryService,
            StreamTaskManager taskManager,
            AIModelProperties modelProperties,
            ConversationGroupService conversationGroupService,
            @Qualifier("defaultIntentClassifier") IntentClassifier intentClassifier,
            @Qualifier("multiQuestionRewriteService") QueryRewriteService queryRewriteService,
            @Qualifier("intentClassifyThreadPoolExecutor") Executor intentClassifyExecutor,
            @Qualifier("ragContextThreadPoolExecutor") Executor ragContextExecutor,
            @Qualifier("ragRetrievalThreadPoolExecutor") Executor ragRetrievalExecutor) {
        this.retrieverService = retrieverService;
        this.llmService = llmService;
        this.rerankService = rerankService;
        this.promptBuilder = promptBuilder;
        this.contextFormatter = contextFormatter;
        this.mcpService = mcpService;
        this.mcpParameterExtractor = mcpParameterExtractor;
        this.mcpToolRegistry = mcpToolRegistry;
        this.promptTemplateLoader = promptTemplateLoader;
        this.memoryService = memoryService;
        this.taskManager = taskManager;
        this.modelProperties = modelProperties;
        this.conversationGroupService = conversationGroupService;
        this.intentClassifier = intentClassifier;
        this.queryRewriteService = queryRewriteService;
        this.intentClassifyExecutor = intentClassifyExecutor;
        this.ragContextExecutor = ragContextExecutor;
        this.ragRetrievalExecutor = ragRetrievalExecutor;
    }

    @Override
    @ChatRateLimit
    public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        String taskId = IdUtil.getSnowflakeNextIdStr();
        log.info("打印会话消息参数，会话ID：{}，单次消息ID：{}", conversationId, taskId);
        StreamCallback callback = new StreamChatEventHandler(
                emitter,
                actualConversationId,
                taskId,
                modelProperties,
                memoryService,
                conversationGroupService,
                taskManager
        );

        List<ChatMessage> history = memoryService.load(actualConversationId, UserContext.getUserId());
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(question, history);

        memoryService.append(actualConversationId, UserContext.getUserId(), ChatMessage.user(question));

        List<SubQuestionIntent> subIntents = buildSubQuestionIntents(rewriteResult);
        boolean allSystemOnly = subIntents.stream()
                .allMatch(si -> isSystemOnly(si.nodeScores()));
        if (allSystemOnly) {
            StreamCancellationHandle handle = streamSystemResponse(rewriteResult.rewrittenQuestion(), callback);
            taskManager.bindHandle(taskId, handle);
            return;
        }

        RetrievalContext ctx = buildPerQuestionContext(subIntents, DEFAULT_TOP_K);
        if (ctx.isEmpty()) {
            String emptyReply = "未检索到与问题相关的文档内容。";
            callback.onContent(emptyReply);
            callback.onComplete();
            taskManager.unregister(taskId);
            return;
        }

        // 聚合所有意图用于 prompt 规划
        IntentGroup mergedGroup = mergeIntentGroup(subIntents);

        StreamCancellationHandle handle = streamLLMResponse(rewriteResult, ctx, mergedGroup, history, deepThinking, callback);
        taskManager.bindHandle(taskId, handle);
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }

    // ==================== 意图分离 ====================

    /**
     * 单个子问题意图分类
     */
    private List<NodeScore> classifyIntents(String question) {
        List<NodeScore> scores = intentClassifier.classifyTargets(question);
        return scores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .limit(MAX_INTENT_COUNT)
                .toList();
    }

    private boolean isSystemOnly(List<NodeScore> nodeScores) {
        return nodeScores.size() == 1 && Objects.equals(nodeScores.get(0).getNode().getKind(), SYSTEM);
    }

    private List<SubQuestionIntent> buildSubQuestionIntents(RewriteResult rewriteResult) {
        List<String> subQuestions = CollUtil.isNotEmpty(rewriteResult.subQuestions())
                ? rewriteResult.subQuestions()
                : List.of(rewriteResult.rewrittenQuestion());
        List<CompletableFuture<SubQuestionIntent>> tasks = subQuestions.stream()
                .map(q -> CompletableFuture.supplyAsync(
                        () -> new SubQuestionIntent(q, classifyIntents(q)),
                        intentClassifyExecutor
                ))
                .toList();
        List<SubQuestionIntent> subIntents = tasks.stream()
                .map(CompletableFuture::join)
                .toList();
        return capTotalIntents(subIntents);
    }

    private List<SubQuestionIntent> capTotalIntents(List<SubQuestionIntent> subIntents) {
        int total = subIntents.stream()
                .mapToInt(si -> si.nodeScores().size())
                .sum();
        if (total <= RAGConstant.MAX_INTENT_COUNT) {
            return subIntents;
        }

        List<IntentCandidate> candidates = new ArrayList<>();
        Map<Integer, List<NodeScore>> retainedByIndex = new ConcurrentHashMap<>();
        for (int i = 0; i < subIntents.size(); i++) {
            SubQuestionIntent si = subIntents.get(i);
            if (CollUtil.isEmpty(si.nodeScores())) {
                continue;
            }
            List<NodeScore> sorted = si.nodeScores().stream()
                    .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                    .toList();
            retainedByIndex.computeIfAbsent(i, k -> new ArrayList<>())
                    .add(sorted.get(0));
            for (int j = 1; j < sorted.size(); j++) {
                candidates.add(new IntentCandidate(i, sorted.get(j)));
            }
        }

        int remaining = Math.max(0, RAGConstant.MAX_INTENT_COUNT
                - retainedByIndex.values().stream().mapToInt(List::size).sum());
        if (remaining > 0 && CollUtil.isNotEmpty(candidates)) {
            candidates.sort((a, b) -> Double.compare(b.nodeScore().getScore(), a.nodeScore().getScore()));
            for (int i = 0; i < Math.min(remaining, candidates.size()); i++) {
                IntentCandidate kept = candidates.get(i);
                retainedByIndex.computeIfAbsent(kept.subQuestionIndex(), k -> new ArrayList<>())
                        .add(kept.nodeScore());
            }
        }

        List<SubQuestionIntent> capped = new ArrayList<>();
        for (int i = 0; i < subIntents.size(); i++) {
            SubQuestionIntent si = subIntents.get(i);
            List<NodeScore> retained = retainedByIndex.getOrDefault(i, List.of());
            capped.add(new SubQuestionIntent(si.subQuestion(), retained));
        }
        return capped;
    }

    private IntentGroup mergeIntentGroup(List<SubQuestionIntent> subIntents) {
        List<NodeScore> mcpIntents = new ArrayList<>();
        List<NodeScore> kbIntents = new ArrayList<>();
        for (SubQuestionIntent si : subIntents) {
            mcpIntents.addAll(filterMcpIntents(si.nodeScores()));
            kbIntents.addAll(filterKbIntents(si.nodeScores()));
        }
        return new IntentGroup(mcpIntents, kbIntents);
    }

    private List<NodeScore> filterMcpIntents(List<NodeScore> nodeScores) {
        return nodeScores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .filter(ns -> ns.getNode().getKind() == IntentKind.MCP)
                .filter(ns -> StrUtil.isNotBlank(ns.getNode().getMcpToolId()))
                .toList();
    }

    private List<NodeScore> filterKbIntents(List<NodeScore> nodeScores) {
        return nodeScores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .filter(ns -> {
                    IntentNode node = ns.getNode();
                    return node.getKind() == null || node.getKind() == IntentKind.KB;
                })
                .toList();
    }

    // ==================== 上下文构建（核心重构点）====================

    /**
     * 统一构建检索上下文（MCP + KB 并行执行）
     */
    private RetrievalContext buildPerQuestionContext(List<SubQuestionIntent> subIntents, int topK) {
        int finalTopK = topK > 0 ? topK : DEFAULT_TOP_K;
        Map<String, List<RetrievedChunk>> mergedIntentChunks = new ConcurrentHashMap<>();
        StringBuilder kbBuilder = new StringBuilder();
        StringBuilder mcpBuilder = new StringBuilder();

        List<CompletableFuture<Void>> tasks = subIntents.stream()
                .map(si -> CompletableFuture.runAsync(() -> {
                    List<NodeScore> kbIntents = filterKbIntents(si.nodeScores());
                    List<NodeScore> mcpIntents = filterMcpIntents(si.nodeScores());

                    if (CollUtil.isNotEmpty(kbIntents)) {
                        KbResult kbResult = retrieveAndRerank(si.subQuestion(), kbIntents, finalTopK);
                        if (StrUtil.isNotBlank(kbResult.groupedContext())) {
                            synchronized (kbBuilder) {
                                kbBuilder.append("### 子问题：").append(si.subQuestion()).append("\n")
                                        .append(kbResult.groupedContext()).append("\n\n");
                            }
                            mergedIntentChunks.putAll(kbResult.intentChunks());
                        }
                    }

                    if (CollUtil.isNotEmpty(mcpIntents)) {
                        String mcpContext = executeMcpAndMerge(si.subQuestion(), mcpIntents);
                        if (StrUtil.isNotBlank(mcpContext)) {
                            synchronized (mcpBuilder) {
                                mcpBuilder.append("### 子问题：").append(si.subQuestion()).append("\n")
                                        .append(mcpContext).append("\n\n");
                            }
                        }
                    }
                }, ragContextExecutor))
                .toList();

        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();

        return RetrievalContext.builder()
                .mcpContext(mcpBuilder.toString().trim())
                .kbContext(kbBuilder.toString().trim())
                .intentChunks(mergedIntentChunks)
                .build();
    }

    /**
     * 执行 MCP 调用并合并结果
     */
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

    /**
     * KB 检索 + Rerank
     */
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

    // ==================== LLM 响应 ====================

    private StreamCancellationHandle streamSystemResponse(String question, StreamCallback callback) {
        String systemPrompt = promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system(systemPrompt),
                        ChatMessage.user(question)
                ))
                .temperature(0.7D)
                .topP(0.8D)
                .thinking(false)
                .build();
        return llmService.streamChat(req, callback);
    }

    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       IntentGroup intentGroup, List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback) {
        PromptContext promptContext = PromptContext.builder()
                .question(rewriteResult.joinSubQuestions())
                .mcpContext(ctx.getMcpContext())
                .kbContext(ctx.getKbContext())
                .mcpIntents(intentGroup.mcpIntents())
                .kbIntents(intentGroup.kbIntents())
                .intentChunks(ctx.getIntentChunks())
                .build();

        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                history,
                rewriteResult.joinSubQuestions()
        );
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)
                .temperature(ctx.hasMcp() ? 0.3D : 0D)  // MCP 场景稍微放宽温度
                .topP(ctx.hasMcp() ? 0.8D : 1D)
                .build();

        return llmService.streamChat(chatRequest, callback);
    }

    // ==================== MCP 工具执行 ====================

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

        // 使用意图节点配置的自定义参数提取提示词（如果配置了）
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
}
