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
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
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
    private final SearchChannelProperties searchChannelProperties;

    @Override
    public String formatKbContext(List<NodeScore> kbIntents,
                                  Set<String> eligibleIntentIds,
                                  List<RetrievedChunk> contextChunks) {
        if (CollUtil.isEmpty(contextChunks)) {
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
            return renderKbSection("", renderChunksGroupedByDoc(contextChunks));
        }
        if (eligibleIntents.size() > 1) {
            return formatMultiIntentContext(eligibleIntents, contextChunks);
        }
        return formatSingleIntentContext(eligibleIntents.get(0), contextChunks);
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
    private String formatSingleIntentContext(NodeScore nodeScore, List<RetrievedChunk> contextChunks) {
        String snippet = StrUtil.emptyIfNull(nodeScore.getNode().getPromptSnippet()).trim();
        return renderKbSection(renderSnippetRules(snippet), renderChunksGroupedByDoc(contextChunks));
    }

    /**
     * 格式化多意图上下文
     */
    private String formatMultiIntentContext(List<NodeScore> kbIntents, List<RetrievedChunk> contextChunks) {
        // 合并所有意图的回答规则
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

        return renderKbSection(snippetSection, renderChunksGroupedByDoc(contextChunks));
    }

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
    private String renderChunksGroupedByDoc(List<RetrievedChunk> chunks) {
        // 按 docId 分组：LinkedHashMap 保持首次出现顺序 = 文档间的相关性排序；docId 为空的块各自单独成组
        LinkedHashMap<String, List<RetrievedChunk>> groups = new LinkedHashMap<>();
        int anonymousSeq = 0;
        for (RetrievedChunk chunk : chunks) {
            String key = StrUtil.isNotBlank(chunk.getDocId()) ? chunk.getDocId() : "__nodoc__" + (anonymousSeq++);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(chunk);
        }

        return groups.values().stream()
                .map(this::renderDocBlock)
                .collect(Collectors.joining("\n"));
    }

    /**
     * 渲染单个文档块：组内按序号排序后拼接，带内部 docId 作为锚点，开关开启时首行放一次文档名
     * <p>
     * 文档名原样使用、不剥扩展名，想让模型看到干净的名字就在后台改名；它会诱导模型写"出自《XX》"，
     * 压住它的只有 KB_ANSWER 里不报文档名的规则，换模型或改提示词后要抽查回答
     */
    private String renderDocBlock(List<RetrievedChunk> group) {
        List<RetrievedChunk> ordered = group.stream()
                .sorted(Comparator.comparing(RetrievedChunk::getChunkIndex,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        String chunks = withDocName(group, joinDocBody(ordered));
        String docId = sanitize(resolveDocId(group));
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

    private String withDocName(List<RetrievedChunk> group, String body) {
        if (!searchChannelProperties.isIncludeDocName()) {
            return body;
        }
        String docName = sanitize(resolveDocName(group));
        if (StrUtil.isBlank(docName)) {
            return body;
        }
        return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "kb-doc-name", Map.of("doc_name", docName))
                + "\n" + body;
    }

    /**
     * 清洗会破坏伪标签结构的字符（引号、尖括号）
     */
    private String sanitize(String value) {
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

    private String resolveDocName(List<RetrievedChunk> group) {
        return group.stream()
                .map(RetrievedChunk::getDocName)
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse("");
    }

}
