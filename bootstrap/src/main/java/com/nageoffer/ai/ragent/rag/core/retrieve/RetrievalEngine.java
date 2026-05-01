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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import com.nageoffer.ai.ragent.rag.core.mcp.McpParameterExtractor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.dto.KbResult;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

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
    private final ContextFormatter contextFormatter;
    private final McpParameterExtractor mcpParameterExtractor;
    private final McpToolRegistry mcpToolRegistry;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    @Qualifier("ragContextThreadPoolExecutor")
    private final Executor ragContextExecutor;
    @Qualifier("mcpBatchThreadPoolExecutor")
    private final Executor mcpBatchExecutor;

    /**
     * 检索方法：根据子问题意图列表执行检索，整合知识库和MCP工具的结果
     */
    @RagTraceNode(name = "retrieval-engine", type = "RETRIEVE")
    public RetrievalContext retrieve(List<SubQuestionIntent> subIntents, int topK) {
        if (CollUtil.isEmpty(subIntents)) {
            return RetrievalContext.builder()
                    .intentChunks(Map.of())
                    .build();
        }

        int finalTopK = topK > 0 ? topK : searchProperties.getDefaultTopK();
        List<CompletableFuture<SubQuestionContext>> tasks = subIntents.stream()
                .map(si -> CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return buildSubQuestionContext(
                                        si,
                                        resolveSubQuestionTopK(si, finalTopK)
                                );
                            } catch (Exception e) {
                                log.error("子问题上下文构建失败，降级为空上下文，question：{}", si.subQuestion(), e);
                                return new SubQuestionContext(si.subQuestion(), "", "", Map.of());
                            }
                        },
                        ragContextExecutor
                ))
                .toList();
        List<SubQuestionContext> contexts = tasks.stream()
                .map(CompletableFuture::join)
                .toList();

        StringBuilder kbBuilder = new StringBuilder();
        StringBuilder mcpBuilder = new StringBuilder();
        Map<String, List<RetrievedChunk>> mergedIntentChunks = new HashMap<>();

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
        List<NodeScore> kbIntents = NodeScoreFilters.kb(intent.nodeScores());
        List<NodeScore> mcpIntents = NodeScoreFilters.mcp(intent.nodeScores());

        KbResult kbResult = retrieveAndRerank(intent, kbIntents, topK);

        String mcpContext = CollUtil.isNotEmpty(mcpIntents)
                ? executeMcpAndMerge(intent.subQuestion(), mcpIntents)
                : "";

        return new SubQuestionContext(intent.subQuestion(), kbResult.groupedContext(), mcpContext, kbResult.intentChunks());
    }

    /**
     * 子问题实际 TopK 计算规则
     */
    private int resolveSubQuestionTopK(SubQuestionIntent intent, int fallbackTopK) {
        return NodeScoreFilters.kb(intent.nodeScores()).stream()
                .map(NodeScore::getNode)
                .filter(Objects::nonNull)
                .map(IntentNode::getTopK)
                .filter(Objects::nonNull)
                .filter(topK -> topK > 0)
                .max(Integer::compareTo)
                .orElse(fallbackTopK);
    }

    private void appendSection(StringBuilder builder, String question, String context) {
        builder.append("---\n")
                .append("**子问题**：").append(question).append("\n\n")
                .append("**相关文档**：\n")
                .append(context).append("\n\n");
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

    private KbResult retrieveAndRerank(SubQuestionIntent intent, List<NodeScore> kbIntents, int topK) {
        // 使用多通道检索引擎（是否启用全局检索由置信度阈值决定）
        List<SubQuestionIntent> subIntents = List.of(intent);
        List<RetrievedChunk> chunks = multiChannelRetrievalEngine.retrieveKnowledgeChannels(subIntents, topK);

        if (CollUtil.isEmpty(chunks)) {
            return KbResult.empty();
        }

        // 按意图节点分组（用于格式化上下文）
        Map<String, List<RetrievedChunk>> intentChunks = new HashMap<>();

        // 如果有意图识别结果，按意图节点 ID 分组
        if (CollUtil.isNotEmpty(kbIntents)) {
            // 将所有 chunks 按意图节点 ID 分配
            // 注意：多通道检索返回的 chunks 无法精确对应到某个意图节点
            // 所以我们将所有 chunks 分配给每个意图节点
            for (NodeScore ns : kbIntents) {
                intentChunks.put(ns.getNode().getId(), chunks);
            }
        } else {
            // 如果没有意图识别结果，使用特殊 key
            intentChunks.put(MULTI_CHANNEL_KEY, chunks);
        }

        String groupedContext = contextFormatter.formatKbContext(kbIntents, intentChunks, topK);
        return new KbResult(groupedContext, intentChunks);
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
        Map<String, Object> params = mcpParameterExtractor.extractParameters(question, tool, customParamPrompt);

        return executor.execute(params != null ? params : new HashMap<>());
    }

    private record ToolOutput(String toolId, CallToolResult result) {
    }

    private record SubQuestionContext(String question,
                                      String kbContext,
                                      String mcpContext,
                                      Map<String, List<RetrievedChunk>> intentChunks) {
    }
}
