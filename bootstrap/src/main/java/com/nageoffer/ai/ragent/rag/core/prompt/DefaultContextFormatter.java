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

package com.nageoffer.ai.ragent.rag.core.prompt;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DefaultContextFormatter implements ContextFormatter {

    @Override
    public String formatKbContext(List<NodeScore> kbIntents, Map<String, List<RetrievedChunk>> rerankedByIntent, int topK) {
        if (rerankedByIntent == null || rerankedByIntent.isEmpty()) {
            return "";
        }
        if (CollUtil.isEmpty(kbIntents)) {
            return formatChunksWithoutIntent(rerankedByIntent, topK);
        }

        // 多意图场景：合并所有规则和文档
        if (kbIntents.size() > 1) {
            return formatMultiIntentContext(kbIntents, rerankedByIntent, topK);
        }

        // 单意图场景：保持原有逻辑
        return formatSingleIntentContext(kbIntents.get(0), rerankedByIntent, topK);
    }

    /**
     * 格式化单意图上下文
     */
    private String formatSingleIntentContext(NodeScore nodeScore, Map<String, List<RetrievedChunk>> rerankedByIntent, int topK) {
        List<RetrievedChunk> chunks = rerankedByIntent.get(nodeScore.getNode().getId());
        if (CollUtil.isEmpty(chunks)) {
            return "";
        }
        String snippet = StrUtil.emptyIfNull(nodeScore.getNode().getPromptSnippet()).trim();
        String body = chunks.stream()
                .limit(topK)
                .map(RetrievedChunk::getText)
                .collect(Collectors.joining("\n"));
        StringBuilder block = new StringBuilder();
        if (StrUtil.isNotBlank(snippet)) {
            block.append("#### 回答规则\n").append(snippet).append("\n\n");
        }
        block.append("#### 知识库片段\n````text\n").append(body).append("\n````");
        return block.toString();
    }

    /**
     * 格式化多意图上下文
     */
    private String formatMultiIntentContext(List<NodeScore> kbIntents, Map<String, List<RetrievedChunk>> rerankedByIntent, int topK) {
        StringBuilder result = new StringBuilder();

        // 1. 合并所有意图的回答规则
        List<String> snippets = kbIntents.stream()
                .map(ns -> ns.getNode().getPromptSnippet())
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();

        if (!snippets.isEmpty()) {
            result.append("#### 回答规则\n");
            for (int i = 0; i < snippets.size(); i++) {
                result.append(i + 1).append(". ").append(snippets.get(i)).append("\n");
            }
            result.append("\n");
        }

        // 2. 合并所有意图的文档片段（去重）
        List<RetrievedChunk> allChunks = rerankedByIntent.values().stream()
                .flatMap(List::stream)
                .distinct()
                .limit(topK)
                .toList();

        if (!allChunks.isEmpty()) {
            String body = allChunks.stream()
                    .map(RetrievedChunk::getText)
                    .collect(Collectors.joining("\n"));
            result.append("#### 知识库片段\n````text\n").append(body).append("\n````");
        }

        return result.toString();
    }

    private String formatChunksWithoutIntent(Map<String, List<RetrievedChunk>> rerankedByIntent, int topK) {
        int limit = topK > 0 ? topK : Integer.MAX_VALUE;
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (List<RetrievedChunk> list : rerankedByIntent.values()) {
            if (CollUtil.isEmpty(list)) {
                continue;
            }
            for (RetrievedChunk chunk : list) {
                chunks.add(chunk);
                if (chunks.size() >= limit) {
                    break;
                }
            }
            if (chunks.size() >= limit) {
                break;
            }
        }
        if (chunks.isEmpty()) {
            return "";
        }

        String body = chunks.stream()
                .map(RetrievedChunk::getText)
                .collect(Collectors.joining("\n"));
        return "#### 知识库片段\n````text\n" + body + "\n````";
    }

    @Override
    public String formatMcpContext(List<MCPResponse> responses, List<NodeScore> mcpIntents) {
        if (CollUtil.isEmpty(responses) || responses.stream().noneMatch(MCPResponse::isSuccess)) {
            return "";
        }
        if (CollUtil.isEmpty(mcpIntents)) {
            return mergeResponsesToText(responses);
        }

        Map<String, IntentNode> toolToIntent = new LinkedHashMap<>();
        for (NodeScore ns : mcpIntents) {
            IntentNode node = ns.getNode();
            if (node == null || StrUtil.isBlank(node.getMcpToolId())) {
                continue;
            }
            toolToIntent.putIfAbsent(node.getMcpToolId(), node);
        }

        Map<String, List<MCPResponse>> grouped = responses.stream()
                .filter(MCPResponse::isSuccess)
                .filter(r -> StrUtil.isNotBlank(r.getToolId()))
                .collect(Collectors.groupingBy(MCPResponse::getToolId));

        return toolToIntent.entrySet().stream()
                .map(entry -> {
                    List<MCPResponse> toolResponses = grouped.get(entry.getKey());
                    if (CollUtil.isEmpty(toolResponses)) {
                        return "";
                    }
                    IntentNode node = entry.getValue();
                    String snippet = StrUtil.emptyIfNull(node.getPromptSnippet()).trim();
                    String body = mergeResponsesToText(toolResponses);
                    if (StrUtil.isBlank(body)) {
                        return "";
                    }
                    StringBuilder block = new StringBuilder();
                    if (StrUtil.isNotBlank(snippet)) {
                        block.append("#### 意图规则\n").append(snippet).append("\n");
                    }
                    block.append("#### 动态数据片段\n").append(body);
                    return block.toString();
                })
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 将多个 MCP 响应合并为文本（用于拼接到 Prompt）
     */
    private String mergeResponsesToText(List<MCPResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return "";
        }

        List<String> successResults = new ArrayList<>();
        List<String> errorResults = new ArrayList<>();

        for (MCPResponse response : responses) {
            if (response.isSuccess() && response.getTextResult() != null) {
                successResults.add(response.getTextResult());
            } else if (!response.isSuccess()) {
                errorResults.add(String.format("工具 %s 调用失败: %s",
                        response.getToolId(), response.getErrorMessage()));
            }
        }

        StringBuilder sb = new StringBuilder();

        if (!successResults.isEmpty()) {
            for (String result : successResults) {
                sb.append(result).append("\n\n");
            }
        }

        if (!errorResults.isEmpty()) {
            sb.append("【部分查询失败】\n");
            for (String error : errorResults) {
                sb.append("- ").append(error).append("\n");
            }
        }

        return sb.toString().trim();
    }
}
