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
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultContextFormatter implements ContextFormatter {

    private final PromptTemplateLoader templateLoader;

    @Override
    public String formatKbContext(List<NodeScore> kbIntents,
                                  Set<String> eligibleIntentIds,
                                  List<RetrievedChunk> rerankedChunks,
                                  int contextTopK) {
        if (CollUtil.isEmpty(rerankedChunks)) {
            return "";
        }

        List<NodeScore> eligibleIntents = eligibleIntents(kbIntents, eligibleIntentIds);
        String branch = switch (eligibleIntents.size()) {
            case 0 -> "无可用意图";
            case 1 -> "单意图";
            default -> "多意图";
        };
        log.info("检索归因 - 提示词分支: {}, 可用意图: {}",
                branch,
                eligibleIntents.stream().map(ns -> ns.getNode().getName()).toList());
        if (eligibleIntents.isEmpty()) {
            return formatChunksWithoutIntent(rerankedChunks, contextTopK);
        }
        if (eligibleIntents.size() > 1) {
            return formatMultiIntentContext(eligibleIntents, rerankedChunks, contextTopK);
        }
        return formatSingleIntentContext(eligibleIntents.get(0), rerankedChunks, contextTopK);
    }

    private List<NodeScore> eligibleIntents(List<NodeScore> kbIntents,
                                            Set<String> eligibleIntentIds) {
        if (CollUtil.isEmpty(kbIntents) || CollUtil.isEmpty(eligibleIntentIds)) {
            return List.of();
        }
        return kbIntents.stream()
                .filter(nodeScore -> nodeScore != null && nodeScore.getNode() != null)
                .filter(nodeScore -> eligibleIntentIds.contains(nodeScore.getNode().getId()))
                .toList();
    }

    /**
     * 格式化单意图上下文
     */
    private String formatSingleIntentContext(NodeScore nodeScore, List<RetrievedChunk> rerankedChunks, int topK) {
        String snippet = StrUtil.emptyIfNull(nodeScore.getNode().getPromptSnippet()).trim();
        String docBlocks = renderChunksGroupedByDoc(distinctChunks(rerankedChunks), topK);
        return renderKbSection(renderSnippetRules(snippet), docBlocks);
    }

    /**
     * 格式化多意图上下文
     */
    private String formatMultiIntentContext(List<NodeScore> kbIntents,
                                            List<RetrievedChunk> rerankedChunks,
                                            int topK) {
        // 1. 合并所有意图的回答规则
        List<String> snippets = kbIntents.stream()
                .map(ns -> ns.getNode().getPromptSnippet())
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();

        String snippetSection = "";
        if (!snippets.isEmpty()) {
            String numberedRules = IntStream.range(0, snippets.size())
                    .mapToObj(i -> (i + 1) + ". " + snippets.get(i))
                    .collect(Collectors.joining("\n"));
            snippetSection = renderSnippetRules(numberedRules);
        }

        // 2. 合并所有意图的文档片段（按 chunk id 去重，保持相关性顺序）
        List<RetrievedChunk> allChunks = distinctChunks(rerankedChunks);

        if (allChunks.isEmpty()) {
            return snippetSection;
        }

        String docBlocks = renderChunksGroupedByDoc(allChunks, topK);
        return renderKbSection(snippetSection, docBlocks);
    }

    private String formatChunksWithoutIntent(List<RetrievedChunk> rerankedChunks, int topK) {
        List<RetrievedChunk> chunks = distinctChunks(rerankedChunks);
        if (chunks.isEmpty()) {
            return "";
        }

        String docBlocks = renderChunksGroupedByDoc(chunks, topK);
        return renderKbSection("", docBlocks);
    }

    private List<RetrievedChunk> distinctChunks(List<RetrievedChunk> chunks) {
        Map<String, RetrievedChunk> distinct = new LinkedHashMap<>();
        chunks.forEach(chunk -> distinct.putIfAbsent(RetrievedChunkKey.of(chunk), chunk));
        return new ArrayList<>(distinct.values());
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
                    String snippetSection = StrUtil.isNotBlank(snippet)
                            ? templateLoader.renderSection(CONTEXT_FORMAT_PATH, "mcp-intent-rules", Map.of("rules", snippet))
                            : "";
                    return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "mcp-section", Map.of(
                            "snippet_section", snippetSection,
                            "body", body
                    ));
                })
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining("\n\n"));
    }

    // ==================== 工具方法 ====================

    private String renderKbSection(String snippetSection, String docBlocks) {
        return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "kb-section", Map.of(
                "snippet_section", snippetSection,
                "doc_blocks", docBlocks
        ));
    }

    private String renderSnippetRules(String snippet) {
        if (StrUtil.isBlank(snippet)) {
            return "";
        }
        return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "snippet-rules", Map.of("rules", snippet));
    }

    /**
     * 按文档聚合渲染 chunk 列表
     * <p>
     * 文档之间按相关性排序（各文档首个命中块在原列表中的顺序，即该文档最佳块的排名），
     * 文档内部按 {@code chunkIndex} 升序还原原文顺序；docId 缺失的块各自单独成组、留在原位
     */
    private String renderChunksGroupedByDoc(List<RetrievedChunk> chunks, int topK) {
        long limit = topK > 0 ? topK : Long.MAX_VALUE;
        List<RetrievedChunk> limited = chunks.stream().limit(limit).toList();
        if (limited.isEmpty()) {
            return "";
        }

        // 按 docId 分组：LinkedHashMap 保持首次出现顺序 = 文档间的相关性排序；docId 为空的块各自单独成组
        LinkedHashMap<String, List<RetrievedChunk>> groups = new LinkedHashMap<>();
        int anonymousSeq = 0;
        for (RetrievedChunk chunk : limited) {
            String key = StrUtil.isNotBlank(chunk.getDocId()) ? chunk.getDocId() : "__nodoc__" + (anonymousSeq++);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(chunk);
        }

        return groups.values().stream()
                .map(this::renderDocBlock)
                .collect(Collectors.joining("\n"));
    }

    /**
     * 渲染单个文档块：组内按序号排序后拼接，只带内部 docId 作为锚点
     * <p>
     * 刻意不注入文档标题：标题一旦进入上下文，模型就会写出"出自《XX》"之类的归因表述，
     * 而提示词层面的禁令压不住。资料之间的区分交给 {@code ref} 编号，文档名只在前端来源列表展示
     */
    private String renderDocBlock(List<RetrievedChunk> group) {
        List<RetrievedChunk> ordered = group.stream()
                .sorted(Comparator.comparing(RetrievedChunk::getChunkIndex,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        String chunks = joinDocBody(ordered);
        String docId = sanitizeAttribute(resolveDocId(group));
        if (StrUtil.isNotBlank(docId)) {
            return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "kb-doc-block", Map.of(
                    "doc_id", docId,
                    "chunks", chunks
            ));
        }
        return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "kb-doc-block-anonymous", Map.of(
                "chunks", chunks
        ));
    }

    /**
     * 组内拼接文本：同文档的块按 index 排好后用换行顺次拼接
     */
    private String joinDocBody(List<RetrievedChunk> ordered) {
        return ordered.stream()
                .map(RetrievedChunk::getText)
                .map(StrUtil::emptyIfNull)
                .filter(text -> !text.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 清洗属性值里会破坏伪标签结构的字符（引号、尖括号）
     */
    private String sanitizeAttribute(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        return value.replaceAll("[\"<>]", "").trim();
    }

    private String resolveDocId(List<RetrievedChunk> group) {
        return group.stream()
                .map(RetrievedChunk::getDocId)
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse("");
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
                errorTexts.add("- 工具调用失败: " + text);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String text : successTexts) {
            sb.append(text).append("\n\n");
        }

        if (CollUtil.isNotEmpty(errorTexts)) {
            String errorList = String.join("\n", errorTexts);
            sb.append(templateLoader.renderSection(CONTEXT_FORMAT_PATH, "mcp-error", Map.of("error_list", errorList)));
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
