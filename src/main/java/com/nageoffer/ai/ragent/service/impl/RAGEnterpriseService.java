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
import com.nageoffer.ai.ragent.rag.mcp.*;
import com.nageoffer.ai.ragent.rag.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.retrieve.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.service.RAGService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.constant.RAGConstant.*;
import static com.nageoffer.ai.ragent.enums.IntentKind.SYSTEM;

/**
 * RAG 企业版服务（V3）
 * <p>
 * 支持三种意图类型：
 * - SYSTEM：系统交互（打招呼、自我介绍等）
 * - KB：知识库检索
 * - MCP：实时数据工具调用
 * <p>
 * 支持 KB + MCP 混合场景
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

    // MCP 相关依赖（V3 Enterprise 专用）
    private final MCPService mcpService;
    private final MCPParameterExtractor mcpParameterExtractor;
    private final MCPToolRegistry mcpToolRegistry;

    @Override
    public void streamAnswer(String question, int topK, StreamCallback callback) {
        String rewriteQuestion = queryRewriteService.rewrite(question);

        List<NodeScore> nodeScores = llmTreeIntentClassifier.classifyTargets(rewriteQuestion);

        // 如果只有一个 SYSTEM 意图，走系统打招呼的流式输出
        if (nodeScores.size() == 1 && Objects.equals(nodeScores.get(0).getNode().getKind(), SYSTEM)) {
            handleSystemIntent(rewriteQuestion, callback);
            return;
        }

        // 分离 MCP 意图和 KB 意图
        List<NodeScore> mcpIntentScores = nodeScores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .filter(ns -> ns.getNode().getKind() == IntentKind.MCP)
                .filter(ns -> StrUtil.isNotBlank(ns.getNode().getMcpToolId()))
                .limit(MAX_INTENT_COUNT)
                .toList();

        List<NodeScore> kbIntentScores = nodeScores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .filter(ns -> {
                    IntentNode node = ns.getNode();
                    return node.getKind() == null || node.getKind() == IntentKind.KB;
                })
                .limit(MAX_INTENT_COUNT)
                .toList();

        log.info("意图识别结果 - MCP意图: {}, KB意图: {}", mcpIntentScores.size(), kbIntentScores.size());

        // 根据意图类型分流处理
        boolean hasMcpIntent = CollUtil.isNotEmpty(mcpIntentScores);
        boolean hasKbIntent = CollUtil.isNotEmpty(kbIntentScores);

        if (hasMcpIntent && !hasKbIntent) {
            // 纯 MCP 场景
            handleMcpOnlyIntent(rewriteQuestion, mcpIntentScores, callback);
        } else if (!hasMcpIntent && hasKbIntent) {
            // 纯 KB 场景
            handleKbOnlyIntent(rewriteQuestion, kbIntentScores, topK, callback);
        } else if (hasMcpIntent && hasKbIntent) {
            // 混合场景：MCP + KB
            handleMixedIntent(rewriteQuestion, mcpIntentScores, kbIntentScores, topK, callback);
        } else {
            // 无匹配意图
            callback.onContent("未检索到与问题相关的文档内容。");
        }
    }

    /**
     * 处理系统意图（打招呼、自我介绍等）
     */
    private void handleSystemIntent(String question, StreamCallback callback) {
        String prompt = CHAT_SYSTEM_PROMPT.formatted(question);
        ChatRequest req = ChatRequest.builder()
                .prompt(prompt)
                .temperature(0.7D)
                .topP(0.8D)
                .thinking(false)
                .build();
        llmService.streamChat(req, callback);
    }

    /**
     * 处理纯 MCP 意图
     */
    private void handleMcpOnlyIntent(String question, List<NodeScore> mcpIntentScores, StreamCallback callback) {
        log.info("进入纯 MCP 处理流程, 意图数: {}", mcpIntentScores.size());

        // 执行 MCP 工具调用
        List<MCPResponse> mcpResponses = executeMcpTools(question, mcpIntentScores);

        if (mcpResponses.isEmpty() || mcpResponses.stream().noneMatch(MCPResponse::isSuccess)) {
            callback.onContent("实时数据查询暂时不可用，请稍后重试。");
            return;
        }

        // 合并 MCP 结果
        String mcpResultText = mcpService.mergeResponsesToText(mcpResponses);

        // 使用 MCP 专用 Prompt 生成回答
        String prompt = MCP_ONLY_PROMPT.formatted(mcpResultText, question);

        ChatRequest chatRequest = ChatRequest.builder()
                .prompt(prompt)
                .thinking(false)
                .temperature(0.3D)
                .topP(0.8D)
                .build();

        llmService.streamChat(chatRequest, callback);
    }

    /**
     * 处理纯 KB 意图（原有逻辑）
     */
    private void handleKbOnlyIntent(String question, List<NodeScore> kbIntentScores, int topK, StreamCallback callback) {
        log.info("进入纯 KB 处理流程, 意图数: {}", kbIntentScores.size());

        int finalTopK = topK > 0 ? topK : DEFAULT_TOP_K;
        int searchTopK = Math.max(finalTopK * SEARCH_TOP_K_MULTIPLIER, MIN_SEARCH_TOP_K);

        // 按意图拆分检索结果
        Map<String, List<RetrievedChunk>> intentChunks = new java.util.concurrent.ConcurrentHashMap<>();
        kbIntentScores.parallelStream().forEach(ns -> {
            IntentNode node = ns.getNode();
            RetrieveRequest request = RetrieveRequest.builder()
                    .collectionName(node.getCollectionName())
                    .query(question)
                    .topK(searchTopK)
                    .build();
            List<RetrievedChunk> chunks = retrieverService.retrieve(request);
            if (CollUtil.isNotEmpty(chunks)) {
                intentChunks.put(node.getId(), chunks);
            }
        });

        // 只保留有检索结果的意图
        List<NodeScore> retainedIntents = kbIntentScores.stream()
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
        List<RetrievedChunk> reranked = rerankService.rerank(question, allChunks, rerankLimit);

        // 拼接上下文
        String context = reranked.stream()
                .map(h -> "- " + h.getText())
                .collect(Collectors.joining("\n"));

        // 使用 ragPromptService 统一构建 Prompt
        String prompt = ragPromptService.buildPrompt(context, question, kbIntentScores, intentChunks);

        // 调 LLM 流式输出
        ChatRequest chatRequest = ChatRequest.builder()
                .prompt(prompt)
                .thinking(false)
                .temperature(0D)
                .topP(0.7D)
                .build();

        llmService.streamChat(chatRequest, callback);
    }

    /**
     * 处理混合意图（MCP + KB）
     */
    private void handleMixedIntent(String question, List<NodeScore> mcpIntentScores,
                                   List<NodeScore> kbIntentScores, int topK, StreamCallback callback) {
        log.info("进入混合处理流程 - MCP意图: {}, KB意图: {}", mcpIntentScores.size(), kbIntentScores.size());

        int finalTopK = topK > 0 ? topK : DEFAULT_TOP_K;
        int searchTopK = Math.max(finalTopK * SEARCH_TOP_K_MULTIPLIER, MIN_SEARCH_TOP_K);

        // 并行执行 MCP 和 KB 检索
        List<MCPResponse> mcpResponses = executeMcpTools(question, mcpIntentScores);

        Map<String, List<RetrievedChunk>> intentChunks = new java.util.concurrent.ConcurrentHashMap<>();
        kbIntentScores.parallelStream().forEach(ns -> {
            IntentNode node = ns.getNode();
            RetrieveRequest request = RetrieveRequest.builder()
                    .collectionName(node.getCollectionName())
                    .query(question)
                    .topK(searchTopK)
                    .build();
            List<RetrievedChunk> chunks = retrieverService.retrieve(request);
            if (CollUtil.isNotEmpty(chunks)) {
                intentChunks.put(node.getId(), chunks);
            }
        });

        // 构建 MCP 结果文本
        String mcpResultText = "";
        if (CollUtil.isNotEmpty(mcpResponses) && mcpResponses.stream().anyMatch(MCPResponse::isSuccess)) {
            mcpResultText = mcpService.mergeResponsesToText(mcpResponses);
        }

        // 构建 KB 结果
        List<NodeScore> retainedIntents = kbIntentScores.stream()
                .filter(ns -> CollUtil.isNotEmpty(intentChunks.get(ns.getNode().getId())))
                .toList();

        List<RetrievedChunk> allChunks = retainedIntents.stream()
                .flatMap(ns -> intentChunks.get(ns.getNode().getId()).stream())
                .collect(Collectors.toList());

        String kbContext = "";
        if (CollUtil.isNotEmpty(allChunks)) {
            int rerankLimit = finalTopK * RERANK_LIMIT_MULTIPLIER;
            List<RetrievedChunk> reranked = rerankService.rerank(question, allChunks, rerankLimit);
            kbContext = reranked.stream()
                    .map(h -> "- " + h.getText())
                    .collect(Collectors.joining("\n"));
        }

        // 根据结果情况选择处理方式
        if (StrUtil.isBlank(mcpResultText) && StrUtil.isBlank(kbContext)) {
            callback.onContent("未检索到与问题相关的文档内容。");
            return;
        }

        // 合并上下文
        String combinedContext;
        if (StrUtil.isNotBlank(mcpResultText) && StrUtil.isNotBlank(kbContext)) {
            combinedContext = MCP_KB_MIXED_CONTEXT_TEMPLATE.formatted(mcpResultText, kbContext);
        } else if (StrUtil.isNotBlank(mcpResultText)) {
            combinedContext = MCP_CONTEXT_TEMPLATE.formatted(mcpResultText);
        } else {
            combinedContext = kbContext;
        }

        // 使用 RAG 默认 Prompt 模板
        String prompt = ragPromptService.buildPrompt(combinedContext, question, kbIntentScores, intentChunks);

        ChatRequest chatRequest = ChatRequest.builder()
                .prompt(prompt)
                .thinking(false)
                .temperature(0D)
                .topP(0.7D)
                .build();

        llmService.streamChat(chatRequest, callback);
    }

    /**
     * 执行 MCP 工具调用
     */
    private List<MCPResponse> executeMcpTools(String question, List<NodeScore> mcpIntentScores) {
        List<MCPRequest> mcpRequests = new ArrayList<>();

        for (NodeScore ns : mcpIntentScores) {
            IntentNode node = ns.getNode();
            String toolId = node.getMcpToolId();

            // 获取工具定义
            Optional<MCPToolExecutor> executorOpt = mcpToolRegistry.getExecutor(toolId);
            if (executorOpt.isEmpty()) {
                log.warn("MCP 工具不存在, toolId: {}", toolId);
                continue;
            }

            MCPTool tool = executorOpt.get().getToolDefinition();

            // 提取参数
            Map<String, Object> params = mcpParameterExtractor.extractParameters(question, tool);
            log.info("MCP 参数提取完成, toolId: {}, 参数: {}", toolId, params);

            // 构建请求
            MCPRequest request = MCPRequest.builder()
                    .toolId(toolId)
                    .userQuestion(question)
                    .parameters(params)
                    .build();

            mcpRequests.add(request);
        }

        if (mcpRequests.isEmpty()) {
            return List.of();
        }

        // 批量执行
        return mcpService.executeBatch(mcpRequests);
    }

    /**
     * 根据分数过滤检索结果
     */
    private List<RetrievedChunk> filterByScore(List<RetrievedChunk> reranked, int finalTopK) {
        if (CollUtil.isEmpty(reranked)) {
            return reranked;
        }

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

        if (filtered.isEmpty()) {
            return reranked.subList(0, Math.min(finalTopK, reranked.size()));
        }

        return filtered;
    }
}
