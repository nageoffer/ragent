package com.nageoffer.ai.ragent.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.convention.ChatRequest;
import com.nageoffer.ai.ragent.enums.IntentKind;
import com.nageoffer.ai.ragent.rag.chat.LLMService;
import com.nageoffer.ai.ragent.rag.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.intent.LLMTreeIntentClassifier;
import com.nageoffer.ai.ragent.rag.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.mcp.MCPParameterExtractor;
import com.nageoffer.ai.ragent.rag.mcp.MCPRequest;
import com.nageoffer.ai.ragent.rag.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.mcp.MCPService;
import com.nageoffer.ai.ragent.rag.mcp.MCPTool;
import com.nageoffer.ai.ragent.rag.mcp.MCPToolExecutor;
import com.nageoffer.ai.ragent.rag.mcp.MCPToolRegistry;
import com.nageoffer.ai.ragent.rag.prompt.MCPPromptService;
import com.nageoffer.ai.ragent.rag.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.retrieve.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.service.RAGService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
@Service("ragEnterpriseService")
@RequiredArgsConstructor
public class RAGEnterpriseService implements RAGService {

    private final RetrieverService retrieverService;
    private final LLMService llmService;
    private final RerankService rerankService;
    private final LLMTreeIntentClassifier llmTreeIntentClassifier;
    private final QueryRewriteService queryRewriteService;
    private final RAGPromptService ragPromptService;
    private final MCPPromptService mcpPromptService;
    private final MCPService mcpService;
    private final MCPParameterExtractor mcpParameterExtractor;
    private final MCPToolRegistry mcpToolRegistry;

    // ==================== 主入口 ====================

    @Override
    public void streamAnswer(String question, int topK, StreamCallback callback) {
        String rewriteQuestion = queryRewriteService.rewrite(question);
        List<NodeScore> nodeScores = llmTreeIntentClassifier.classifyTargets(rewriteQuestion);

        // SYSTEM 意图单独处理
        if (isSystemOnly(nodeScores)) {
            streamSystemResponse(rewriteQuestion, callback);
            return;
        }

        // 分离意图
        IntentGroup intentGroup = separateIntents(nodeScores);
        log.info("意图分离 - MCP: {}, KB: {}", intentGroup.mcpIntents.size(), intentGroup.kbIntents.size());

        // 统一处理：获取上下文 → 构建 Prompt → 流式输出
        RetrievalContext ctx = buildRetrievalContext(rewriteQuestion, intentGroup, topK);

        if (ctx.isEmpty()) {
            callback.onContent("未检索到与问题相关的文档内容。");
            return;
        }

        streamLLMResponse(rewriteQuestion, ctx, intentGroup, callback);
    }

    // ==================== 意图分离 ====================

    private boolean isSystemOnly(List<NodeScore> nodeScores) {
        return nodeScores.size() == 1 && Objects.equals(nodeScores.get(0).getNode().getKind(), SYSTEM);
    }

    private IntentGroup separateIntents(List<NodeScore> nodeScores) {
        List<NodeScore> mcpIntents = nodeScores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .filter(ns -> ns.getNode().getKind() == IntentKind.MCP)
                .filter(ns -> StrUtil.isNotBlank(ns.getNode().getMcpToolId()))
                .limit(MAX_INTENT_COUNT)
                .toList();

        List<NodeScore> kbIntents = nodeScores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .filter(ns -> {
                    IntentNode node = ns.getNode();
                    return node.getKind() == null || node.getKind() == IntentKind.KB;
                })
                .limit(MAX_INTENT_COUNT)
                .toList();

        return new IntentGroup(mcpIntents, kbIntents);
    }

    // ==================== 上下文构建（核心重构点）====================

    /**
     * 统一构建检索上下文（MCP + KB 并行执行）
     */
    private RetrievalContext buildRetrievalContext(String question, IntentGroup intentGroup, int topK) {
        int finalTopK = topK > 0 ? topK : DEFAULT_TOP_K;

        // 并行执行 MCP 和 KB
        CompletableFuture<String> mcpFuture = CompletableFuture.supplyAsync(() ->
                executeMcpAndMerge(question, intentGroup.mcpIntents));

        CompletableFuture<KbResult> kbFuture = CompletableFuture.supplyAsync(() ->
                retrieveAndRerank(question, intentGroup.kbIntents, finalTopK));

        // 等待结果
        String mcpContext = mcpFuture.join();
        KbResult kbResult = kbFuture.join();

        return RetrievalContext.builder()
                .mcpContext(mcpContext)
                .kbContext(kbResult.context)
                .intentChunks(kbResult.intentChunks)
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

        return mcpService.mergeResponsesToText(responses);
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

        // 并行检索
        kbIntents.parallelStream().forEach(ns -> {
            IntentNode node = ns.getNode();
            List<RetrievedChunk> chunks = retrieverService.retrieve(
                    RetrieveRequest.builder()
                            .collectionName(node.getCollectionName())
                            .query(question)
                            .topK(searchTopK)
                            .build()
            );
            if (CollUtil.isNotEmpty(chunks)) {
                intentChunks.put(node.getId(), chunks);
            }
        });

        // 聚合 + Rerank
        List<RetrievedChunk> allChunks = kbIntents.stream()
                .filter(ns -> CollUtil.isNotEmpty(intentChunks.get(ns.getNode().getId())))
                .flatMap(ns -> intentChunks.get(ns.getNode().getId()).stream())
                .collect(Collectors.toList());

        if (allChunks.isEmpty()) {
            return KbResult.empty();
        }

        int rerankLimit = topK * RERANK_LIMIT_MULTIPLIER;
        List<RetrievedChunk> reranked = rerankService.rerank(question, allChunks, rerankLimit);

        String context = reranked.stream()
                .map(h -> "- " + h.getText())
                .collect(Collectors.joining("\n"));

        return new KbResult(context, intentChunks);
    }

    // ==================== LLM 响应 ====================

    private void streamSystemResponse(String question, StreamCallback callback) {
        ChatRequest req = ChatRequest.builder()
                .prompt(CHAT_SYSTEM_PROMPT.formatted(question))
                .temperature(0.7D)
                .topP(0.8D)
                .thinking(false)
                .build();
        llmService.streamChat(req, callback);
    }

    private void streamLLMResponse(String question, RetrievalContext ctx,
                                   IntentGroup intentGroup, StreamCallback callback) {
        String prompt = buildPrompt(question, ctx, intentGroup);

        ChatRequest chatRequest = ChatRequest.builder()
                .prompt(prompt)
                .thinking(false)
                .temperature(ctx.hasMcp() ? 0.3D : 0D)  // MCP 场景稍微放宽温度
                .topP(0.7D)
                .build();

        llmService.streamChat(chatRequest, callback);
    }

    /**
     * 统一 Prompt 构建（实现完整的5种场景）
     * <p>
     * 场景1: 单问题命中KB，使用 promptTemplate 或默认 RAG 提示词
     * 场景2: 多问题多意图全命中KB，使用默认 RAG 提示词 + promptSnippet
     * 场景3: 单问题命中MCP，使用 promptTemplate 或默认 MCP 提示词
     * 场景4: 多问题多意图全命中MCP，使用默认 MCP 提示词 + promptSnippet
     * 场景5: 混合命中 MCP 和 KB，使用混合提示词 + promptSnippet
     */
    private String buildPrompt(String question, RetrievalContext ctx, IntentGroup intentGroup) {
        // 场景3/4: 只有 MCP
        if (ctx.hasMcp() && !ctx.hasKb()) {
            return mcpPromptService.buildMcpOnlyPrompt(ctx.mcpContext, question, intentGroup.mcpIntents);
        }

        // 场景1/2: 只有 KB
        if (!ctx.hasMcp() && ctx.hasKb()) {
            return ragPromptService.buildPrompt(ctx.kbContext, question, intentGroup.kbIntents, ctx.intentChunks);
        }

        // 场景5: MCP + KB 混合
        List<NodeScore> allIntents = new ArrayList<>();
        allIntents.addAll(intentGroup.mcpIntents);
        allIntents.addAll(intentGroup.kbIntents);
        return mcpPromptService.buildMixedPrompt(ctx.mcpContext, ctx.kbContext, question, allIntents);
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

    // ==================== 内部数据结构 ====================

    /**
     * 意图分组
     */
    private record IntentGroup(List<NodeScore> mcpIntents, List<NodeScore> kbIntents) {
    }

    /**
     * KB 检索结果
     */
    private record KbResult(String context, Map<String, List<RetrievedChunk>> intentChunks) {
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
