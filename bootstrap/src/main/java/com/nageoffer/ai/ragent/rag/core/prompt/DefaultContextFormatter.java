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
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
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
    public String formatMcpContext(Map<String, List<CallToolResult>> toolResults,
                                   List<NodeScore> mcpIntents) {
        if (CollUtil.isEmpty(toolResults)) {
            return "";
        }
        if (CollUtil.isEmpty(mcpIntents)) {
            return mergeAllResultsToText(toolResults);
        }

        Map<String, IntentNode> toolToIntent = new LinkedHashMap<>();
        for (NodeScore ns : mcpIntents) {
            IntentNode node = ns.getNode();
            if (node == null || StrUtil.isBlank(node.getMcpToolId())) {
                continue;
            }
            toolToIntent.putIfAbsent(node.getMcpToolId(), node);
        }

        return toolToIntent.entrySet().stream()
                .map(entry -> {
                    List<CallToolResult> results = toolResults.get(entry.getKey());
                    if (CollUtil.isEmpty(results)) {
                        return "";
                    }
                    IntentNode node = entry.getValue();
                    String snippet = StrUtil.emptyIfNull(node.getPromptSnippet()).trim();
                    String body = mergeResultsToText(results);
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

    private String mergeAllResultsToText(Map<String, List<CallToolResult>> toolResults) {
        List<CallToolResult> allResults = toolResults.values().stream()
                .flatMap(List::stream)
                .toList();
        return mergeResultsToText(allResults);
    }

    /**
     * 将多个 CallToolResult 合并为文本
     */
    private String mergeResultsToText(List<CallToolResult> results) {
        if (CollUtil.isEmpty(results)) {
            return "";
        }

        List<String> successTexts = new ArrayList<>();
        List<String> errorTexts = new ArrayList<>();

        for (CallToolResult result : results) {
            boolean isError = result.isError() != null && result.isError();
            String text = extractTextContent(result);
            if (!isError && text != null) {
                successTexts.add(text);
            } else if (isError && text != null) {
                errorTexts.add("工具调用失败: " + text);
            }
        }

        StringBuilder sb = new StringBuilder();

        for (String text : successTexts) {
            sb.append(text).append("\n\n");
        }

        if (CollUtil.isNotEmpty(errorTexts)) {
            sb.append("【部分查询失败】\n");
            for (String error : errorTexts) {
                sb.append("- ").append(error).append("\n");
            }
        }

        return sb.toString().trim();
    }

    private String extractTextContent(CallToolResult result) {
        if (result == null || result.content() == null) {
            return null;
        }
        List<String> texts = result.content().stream()
                .filter(c -> c instanceof TextContent)
                .map(c -> ((TextContent) c).text())
                .toList();
        return texts.isEmpty() ? null : String.join("\n", texts);
    }
}
