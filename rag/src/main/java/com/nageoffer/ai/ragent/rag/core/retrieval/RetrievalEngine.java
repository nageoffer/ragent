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

package com.nageoffer.ai.ragent.rag.core.retrieval;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.config.OrchestrationMode;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import com.nageoffer.ai.ragent.rag.core.mcp.McpCallMeta;
import com.nageoffer.ai.ragent.rag.core.mcp.McpExtractionResult;
import com.nageoffer.ai.ragent.rag.core.mcp.McpParameterExtractor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dto.KbResult;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MULTI_CHANNEL_KEY;

/**
 * 检索引擎
 * 负责协调多通道检索（知识库）和 MCP（模型控制协议）工具的调用，并对检索结果进行重排序和格式化，最终生成用于 LLM 的上下文
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalEngine {

    private final SearchChannelProperties searchProperties;
    private final OrchestrationProperties orchestrationProperties;
    private final ContextFormatter contextFormatter;
    private final PromptTemplateLoader templateLoader;
    private final McpParameterExtractor mcpParameterExtractor;
    private final McpToolRegistry mcpToolRegistry;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    private final Executor ragContextExecutor;
    private final Executor mcpBatchExecutor;

    /**
     * 检索方法：根据子问题意图列表执行检索，整合知识库和MCP工具的结果
     */
    @RagTraceNode(name = "retrieval-engine", type = "RETRIEVE")
    public RetrievalContext retrieve(List<SubQuestionIntent> subIntents) {
        if (CollUtil.isEmpty(subIntents)) {
            return RetrievalContext.builder()
                    .intentChunks(Map.of())
                    .build();
        }

        // 一次算好检索预算：全 subquestion 共用。最终条数即配置的 default-top-k（启动已校验 >0），是 contextTopK 段唯一真源，
        // 不再被 max(意图节点 topK) 抬高（node.topK 只覆盖向量定向路的召回深度，见 VectorSearchChannel.resolveDirectedBudget）
        int contextTopK = searchProperties.getDefaultTopK();
        RetrievalBudget budget = new RetrievalBudget(
                searchProperties.resolveRecallBudget(contextTopK),
                searchProperties.getFusion().getRerankCandidateLimit(),
                contextTopK
        );
        List<CompletableFuture<SubQuestionContext>> tasks = subIntents.stream()
                .map(si -> CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return buildSubQuestionContext(si, budget);
                            } catch (Exception e) {
                                log.error("子问题上下文构建失败，降级为空上下文，question：{}", si.subQuestion(), e);
                                return new SubQuestionContext(
                                        si.subQuestion(), "", "", Map.of(),
                                        KnowledgeRetrievalResult.empty().eligibleIntentIds(
                                                NodeScoreFilters.kb(si.nodeScores())));
                            }
                        },
                        ragContextExecutor
                ))
                .toList();
        List<SubQuestionContext> contexts = tasks.stream()
                .map(CompletableFuture::join)
                .toList();

        Map<String, List<RetrievedChunk>> mergedIntentChunks = new LinkedHashMap<>();
        Set<String> eligibleIntentIds = new LinkedHashSet<>();
        for (SubQuestionContext context : contexts) {
            eligibleIntentIds.addAll(context.eligibleIntentIds());
            if (CollUtil.isNotEmpty(context.intentChunks())) {
                context.intentChunks().forEach((intentId, chunks) -> {
                    if (CollUtil.isEmpty(chunks)) {
                        return;
                    }
                    mergedIntentChunks
                            .computeIfAbsent(intentId, ignored -> new ArrayList<>())
                            .addAll(chunks);
                });
            }
        }

        boolean singleQuestion = contexts.size() == 1;
        String kbContext;
        String mcpContext;

        if (singleQuestion) {
            SubQuestionContext only = contexts.get(0);
            kbContext = StrUtil.emptyIfNull(only.kbContext()).trim();
            mcpContext = StrUtil.emptyIfNull(only.mcpContext()).trim();
        } else {
            StringBuilder kbBuilder = new StringBuilder();
            StringBuilder mcpBuilder = new StringBuilder();
            int globalIndex = 0;
            for (SubQuestionContext context : contexts) {
                boolean hasKb = StrUtil.isNotBlank(context.kbContext());
                boolean hasMcp = StrUtil.isNotBlank(context.mcpContext());
                if (hasKb || hasMcp) {
                    globalIndex++;
                }
                if (hasKb) {
                    appendSection(kbBuilder, "sub-question-kb-wrapper", globalIndex, context.question(), context.kbContext());
                }
                if (hasMcp) {
                    appendSection(mcpBuilder, "sub-question-mcp-wrapper", globalIndex, context.question(), context.mcpContext());
                }
            }
            kbContext = kbBuilder.toString().trim();
            mcpContext = mcpBuilder.toString().trim();
        }

        return RetrievalContext.builder()
                .mcpContext(mcpContext)
                .kbContext(kbContext)
                .intentChunks(mergedIntentChunks)
                .eligibleIntentIds(Set.copyOf(eligibleIntentIds))
                .build();
    }

    private SubQuestionContext buildSubQuestionContext(SubQuestionIntent intent, RetrievalBudget budget) {
        List<NodeScore> kbIntents = NodeScoreFilters.kb(intent.nodeScores());
        List<NodeScore> mcpIntents = NodeScoreFilters.mcp(intent.nodeScores());

        KbResult kbResult = retrieveAndRerank(intent, kbIntents, budget);

        String mcpContext = "";
        if (CollUtil.isNotEmpty(mcpIntents)) {
            // AGENT 档下 MCP 工具的正当入口只有 Agent 自己的工具清单，那条路上有确认卡、技能遮蔽、readOnlyHint
            if (orchestrationProperties.getMode() == OrchestrationMode.WORKFLOW) {
                mcpContext = executeMcpAndMerge(intent.subQuestion(), mcpIntents);
            }
        }

        return new SubQuestionContext(intent.subQuestion(), kbResult.groupedContext(), mcpContext,
                kbResult.intentChunks(), kbResult.eligibleIntentIds());
    }

    private void appendSection(StringBuilder builder, String section, int index, String question, String context) {
        if (!builder.isEmpty()) {
            builder.append("\n");
        }
        builder.append(templateLoader.renderSection(CONTEXT_FORMAT_PATH, section, Map.of(
                "index", String.valueOf(index),
                "question", question,
                "context", context
        )));
    }

    private String executeMcpAndMerge(String question, List<NodeScore> mcpIntents) {
        if (CollUtil.isEmpty(mcpIntents)) {
            return "";
        }

        Map<String, List<CallToolResult>> toolResults = executeMcpTools(question, mcpIntents);
        if (toolResults.isEmpty()) {
            return "";
        }

        return contextFormatter.formatMcpContext(toolResults, mcpIntents);
    }

    private KbResult retrieveAndRerank(SubQuestionIntent intent, List<NodeScore> kbIntents, RetrievalBudget budget) {
        // 使用多通道检索引擎（是否启用全局检索由置信度阈值决定）
        KnowledgeRetrievalResult retrievalResult =
                multiChannelRetrievalEngine.retrieveKnowledgeChannels(intent, budget);
        Set<String> eligibleIntentIds = retrievalResult.eligibleIntentIds(kbIntents);

        if (CollUtil.isEmpty(retrievalResult.chunks())) {
            return new KbResult("", Map.of(), eligibleIntentIds);
        }

        // 关闭 Rerank 时上游不会截到 contextTopK，这里统一截断，保证来源面板与提示词证据是同一批 Chunk
        List<RetrievedChunk> chunks = retrievalResult.chunks().stream()
                .limit(budget.contextTopK())
                .toList();
        Map<String, List<RetrievedChunk>> intentChunks = new KnowledgeRetrievalResult(
                chunks, retrievalResult.intentIdsByChunkKey(), retrievalResult.directedIntentIds())
                .groupByIntent(MULTI_CHANNEL_KEY);

        String groupedContext = contextFormatter.formatKbContext(
                kbIntents, eligibleIntentIds, chunks, budget.contextTopK());
        return new KbResult(groupedContext, intentChunks, eligibleIntentIds);
    }

    /**
     * 执行 MCP 工具调用，返回按 toolId 分组的结果
     */
    private Map<String, List<CallToolResult>> executeMcpTools(String question,
                                                              List<NodeScore> mcpIntentScores) {
        if (CollUtil.isEmpty(mcpIntentScores)) {
            return Map.of();
        }

        List<CompletableFuture<ToolOutput>> futures = mcpIntentScores.stream()
                .map(ns -> CompletableFuture.supplyAsync(
                        () -> {
                            String toolId = ns.getNode().getMcpToolId();
                            try {
                                CallToolResult result = executeSingleMcpTool(question, ns.getNode());
                                return result == null ? null : new ToolOutput(toolId, result);
                            } catch (Exception e) {
                                log.error("MCP 工具调用异常, toolId: {}", toolId, e);
                                return new ToolOutput(toolId, CallToolResult.builder()
                                        .content(List.of(new TextContent("工具调用异常: " + e.getMessage())))
                                        .isError(true)
                                        .build());
                            }
                        },
                        mcpBatchExecutor
                ))
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(
                        ToolOutput::toolId,
                        Collectors.mapping(ToolOutput::result, Collectors.toList())
                ));
    }

    private CallToolResult executeSingleMcpTool(String question, IntentNode intentNode) {
        String toolId = intentNode.getMcpToolId();
        Optional<McpToolExecutor> executorOpt = mcpToolRegistry.getExecutor(toolId);
        if (executorOpt.isEmpty()) {
            log.warn("MCP 工具不存在: {}", toolId);
            return null;
        }

        McpToolExecutor executor = executorOpt.get();
        Tool tool = executor.getToolDefinition();

        String customParamPrompt = intentNode.getParamPromptTemplate();
        McpExtractionResult extraction = mcpParameterExtractor.extractParameters(question, tool, customParamPrompt);

        // 按提参结局分流：仅 SUCCESS 才真正调用远端工具，缺必填参 / 提取失败均不调用、改注入提示进上下文
        return switch (extraction.status()) {
            // 身份从 UserContext 取：mcpBatchExecutor 与 ragContextExecutor 都被 TtlExecutors 包过，
            // UserContext 又是 TransmittableThreadLocal，这两跳异步之后仍取得到登录态
            case SUCCESS -> executor.execute(
                    extraction.params() != null ? extraction.params() : new HashMap<>(),
                    McpCallMeta.ofUser(UserContext.getUserId()));
            case NEED_CLARIFICATION -> clarificationResult(toolId, extraction.missingRequired());
            case FAILED -> extractionFailedResult(toolId);
        };
    }

    /**
     * 缺必填参数（用户未提供）：不调用工具，注入结构化提示让 LLM 在回答中主动向用户追问
     * <p>
     * isError=false 使其作为正文进入上下文（而非「工具调用失败」段），便于 LLM 直接据此追问
     */
    private CallToolResult clarificationResult(String toolId, List<String> missingRequired) {
        String missing = CollUtil.isNotEmpty(missingRequired) ? String.join("、", missingRequired) : "必要信息";
        log.info("MCP 缺少必填参数，跳过工具调用并注入澄清提示, toolId: {}, missing: {}", toolId, missingRequired);
        String note = String.format(
                "调用工具【%s】需要参数：%s，但用户问题中未提供。请在回答中主动向用户询问这些信息，不要编造。",
                toolId, missing);
        return CallToolResult.builder()
                .content(List.of(new TextContent(note)))
                .isError(false)
                .build();
    }

    /**
     * 提取失败（协议畸形 / 值非法）：不调用工具，注入失败提示（isError=true 进「工具调用失败」段）
     */
    private CallToolResult extractionFailedResult(String toolId) {
        log.warn("MCP 参数提取失败，跳过工具调用, toolId: {}", toolId);
        return CallToolResult.builder()
                .content(List.of(new TextContent("未能为工具【" + toolId + "】提取到有效参数，已跳过调用。")))
                .isError(true)
                .build();
    }

    private record ToolOutput(String toolId, CallToolResult result) {
    }

    private record SubQuestionContext(String question,
                                      String kbContext,
                                      String mcpContext,
                                      Map<String, List<RetrievedChunk>> intentChunks,
                                      Set<String> eligibleIntentIds) {
    }
}
