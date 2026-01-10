package com.nageoffer.ai.ragent.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.constant.RAGConstant;
import com.nageoffer.ai.ragent.convention.ChatMessage;
import com.nageoffer.ai.ragent.convention.ChatRequest;
import com.nageoffer.ai.ragent.dto.MessageDelta;
import com.nageoffer.ai.ragent.dto.MetaPayload;
import com.nageoffer.ai.ragent.enums.IntentKind;
import com.nageoffer.ai.ragent.enums.SSEEventType;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.rag.chat.LLMService;
import com.nageoffer.ai.ragent.rag.chat.StreamCallback;
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
import com.nageoffer.ai.ragent.rag.prompt.RAGEnterprisePromptService;
import com.nageoffer.ai.ragent.rag.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.retrieve.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.service.RAGEnterpriseService;
import lombok.Builder;
import lombok.Data;
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

import static com.nageoffer.ai.ragent.constant.RAGConstant.CHAT_SYSTEM_PROMPT;
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
    private final ConversationMemoryService memoryService;
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
            RAGEnterprisePromptService promptBuilder,
            ContextFormatter contextFormatter,
            ConversationMemoryService memoryService,
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
        this.memoryService = memoryService;
        this.intentClassifier = intentClassifier;
        this.queryRewriteService = queryRewriteService;
        this.intentClassifyExecutor = intentClassifyExecutor;
        this.ragContextExecutor = ragContextExecutor;
        this.ragRetrievalExecutor = ragRetrievalExecutor;
    }

    @Override
    public void streamChat(String question, String conversationId, SseEmitter emitter) {
        String actualConversationId = StrUtil.isEmpty(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        SseEmitterSender sender = new SseEmitterSender(emitter);
        sender.sendEvent(SSEEventType.META.value(),
                new MetaPayload(actualConversationId, IdUtil.getSnowflakeNextIdStr()));

        StreamCallback callback = new StreamCallback() {
            @Override
            public void onContent(String chunk) {
                if (StrUtil.isBlank(chunk)) {
                    return;
                }
                int[] codePoints = chunk.codePoints().toArray();
                for (int codePoint : codePoints) {
                    String character = new String(new int[]{codePoint}, 0, 1);
                    sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(character));
                }
            }

            @Override
            public void onComplete() {
                sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
                sender.complete();
            }

            @Override
            public void onError(Throwable t) {
                sender.fail(t);
            }
        };

        List<ChatMessage> history = memoryService.load(conversationId, UserContext.getUserId());
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(question, history);

        ChatMessage userMessage = ChatMessage.user(question);
        if (StrUtil.isNotBlank(conversationId)) {
            memoryService.append(conversationId, UserContext.getUserId(), userMessage);
        }

        List<SubQuestionIntent> subIntents = buildSubQuestionIntents(rewriteResult);

        boolean allSystemOnly = subIntents.stream()
                .allMatch(si -> isSystemOnly(si.nodeScores));
        if (allSystemOnly) {
            StreamCallback wrapped = wrapWithMemory(conversationId, callback);
            streamSystemResponse(rewriteResult.rewrittenQuestion(), wrapped);
            return;
        }

        RetrievalContext ctx = buildPerQuestionContext(subIntents, DEFAULT_TOP_K);
        if (ctx.isEmpty()) {
            String emptyReply = "未检索到与问题相关的文档内容。";
            if (StrUtil.isNotBlank(conversationId)) {
                memoryService.append(conversationId, UserContext.getUserId(), ChatMessage.assistant(emptyReply));
            }
            callback.onContent(emptyReply);
            return;
        }

        // 聚合所有意图用于 prompt 规划
        IntentGroup mergedGroup = mergeIntentGroup(subIntents);

        StreamCallback wrapped = wrapWithMemory(conversationId, callback);
        streamLLMResponse(rewriteResult, ctx, mergedGroup, history, wrapped);
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
                .mapToInt(si -> si.nodeScores.size())
                .sum();
        if (total <= RAGConstant.MAX_INTENT_COUNT) {
            return subIntents;
        }

        List<IntentCandidate> candidates = new ArrayList<>();
        Map<Integer, List<NodeScore>> retainedByIndex = new ConcurrentHashMap<>();
        for (int i = 0; i < subIntents.size(); i++) {
            SubQuestionIntent si = subIntents.get(i);
            if (CollUtil.isEmpty(si.nodeScores)) {
                continue;
            }
            List<NodeScore> sorted = si.nodeScores.stream()
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
            candidates.sort((a, b) -> Double.compare(b.nodeScore.getScore(), a.nodeScore.getScore()));
            for (int i = 0; i < Math.min(remaining, candidates.size()); i++) {
                IntentCandidate kept = candidates.get(i);
                retainedByIndex.computeIfAbsent(kept.subQuestionIndex, k -> new ArrayList<>())
                        .add(kept.nodeScore);
            }
        }

        List<SubQuestionIntent> capped = new ArrayList<>();
        for (int i = 0; i < subIntents.size(); i++) {
            SubQuestionIntent si = subIntents.get(i);
            List<NodeScore> retained = retainedByIndex.getOrDefault(i, List.of());
            capped.add(new SubQuestionIntent(si.subQuestion, retained));
        }
        return capped;
    }

    private IntentGroup mergeIntentGroup(List<SubQuestionIntent> subIntents) {
        List<NodeScore> mcpIntents = new ArrayList<>();
        List<NodeScore> kbIntents = new ArrayList<>();
        for (SubQuestionIntent si : subIntents) {
            mcpIntents.addAll(filterMcpIntents(si.nodeScores));
            kbIntents.addAll(filterKbIntents(si.nodeScores));
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
                    List<NodeScore> kbIntents = filterKbIntents(si.nodeScores);
                    List<NodeScore> mcpIntents = filterMcpIntents(si.nodeScores);

                    if (CollUtil.isNotEmpty(kbIntents)) {
                        KbResult kbResult = retrieveAndRerank(si.subQuestion, kbIntents, finalTopK);
                        if (StrUtil.isNotBlank(kbResult.groupedContext)) {
                            synchronized (kbBuilder) {
                                kbBuilder.append("### 子问题：").append(si.subQuestion).append("\n")
                                        .append(kbResult.groupedContext).append("\n\n");
                            }
                            mergedIntentChunks.putAll(kbResult.intentChunks);
                        }
                    }

                    if (CollUtil.isNotEmpty(mcpIntents)) {
                        String mcpContext = executeMcpAndMerge(si.subQuestion, mcpIntents);
                        if (StrUtil.isNotBlank(mcpContext)) {
                            synchronized (mcpBuilder) {
                                mcpBuilder.append("### 子问题：").append(si.subQuestion).append("\n")
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

    private void streamSystemResponse(String question, StreamCallback callback) {
        String prompt = CHAT_SYSTEM_PROMPT.formatted(question);
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .temperature(0.7D)
                .topP(0.8D)
                .thinking(false)
                .build();
        llmService.streamChat(req, callback);
    }

    private void streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                   IntentGroup intentGroup, List<ChatMessage> history, StreamCallback callback) {
        PromptContext promptContext = PromptContext.builder()
                .question(rewriteResult.joinSubQuestions())
                .mcpContext(ctx.mcpContext)
                .kbContext(ctx.kbContext)
                .mcpIntents(intentGroup.mcpIntents)
                .kbIntents(intentGroup.kbIntents)
                .intentChunks(ctx.intentChunks)
                .build();

        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                history,
                rewriteResult.joinSubQuestions()
        );
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(false)
                .temperature(ctx.hasMcp() ? 0.3D : 0D)  // MCP 场景稍微放宽温度
                .topP(0.7D)
                .build();

        llmService.streamChat(chatRequest, callback);
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

    private StreamCallback wrapWithMemory(String conversationId, StreamCallback delegate) {
        if (StrUtil.isBlank(conversationId)) {
            return delegate;
        }
        StringBuilder answer = new StringBuilder();
        return new StreamCallback() {
            @Override
            public void onContent(String chunk) {
                if (StrUtil.isNotBlank(chunk)) {
                    answer.append(chunk);
                }
                delegate.onContent(chunk);
            }

            @Override
            public void onComplete() {
                if (!answer.isEmpty()) {
                    memoryService.append(conversationId, UserContext.getUserId(), ChatMessage.assistant(answer.toString()));
                }
                delegate.onComplete();
            }

            @Override
            public void onError(Throwable t) {
                delegate.onError(t);
            }
        };
    }

    // ==================== 内部数据结构 ====================

    /**
     * 意图分组
     */
    private record IntentGroup(List<NodeScore> mcpIntents, List<NodeScore> kbIntents) {
    }

    private record SubQuestionIntent(String subQuestion, List<NodeScore> nodeScores) {
    }

    private record IntentCandidate(int subQuestionIndex, NodeScore nodeScore) {
    }

    /**
     * KB 检索结果
     */
    private record KbResult(String groupedContext, Map<String, List<RetrievedChunk>> intentChunks) {
        static KbResult empty() {
            return new KbResult("", Map.of());
        }
    }

    /**
     * 检索上下文（MCP + KB 结果的统一承载）
     */
    @Data
    @Builder
    private static class RetrievalContext {
        private String mcpContext;
        private String kbContext;
        private Map<String, List<RetrievedChunk>> intentChunks;

        boolean hasMcp() {
            return StrUtil.isNotBlank(mcpContext);
        }

        boolean hasKb() {
            return StrUtil.isNotBlank(kbContext);
        }

        boolean isEmpty() {
            return !hasMcp() && !hasKb();
        }
    }
}
