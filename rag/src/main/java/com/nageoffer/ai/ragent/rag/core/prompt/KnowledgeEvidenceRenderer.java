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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把检索证据渲染成 search_knowledge 交给主 Agent 的资料原文
 * 与 WorkFlow 的 KB_ANSWER 输入分开：那份要按子问题分段并带引用锚点，这份按文档归组、跨子问题去重；不读分数，各子问题的分数不可比
 */
@Component
@RequiredArgsConstructor
public class KnowledgeEvidenceRenderer {

    /**
     * 同一文档内两段不相邻时插在中间，告诉模型这里省略了原文
     */
    static final String GAP = "……";

    private final SearchChannelProperties searchChannelProperties;

    /**
     * @param chunks 各子问题进入上下文的分片，按子问题先后、各自名次排列，可能重复
     * @param rules  命中意图配置的答题规则，每条形如「[意图路径] 规则」
     */
    public String render(List<RetrievedChunk> chunks, List<String> rules) {
        // 同一段只出一次，不丢任何一段；留哪一份都一样，下面不读分数
        Map<String, RetrievedChunk> distinct = new LinkedHashMap<>();
        chunks.forEach(chunk -> distinct.putIfAbsent(evidenceKey(chunk), chunk));

        StringBuilder out = new StringBuilder("<retrieval>\n");
        if (!rules.isEmpty()) {
            out.append("<rules>\n").append(String.join("\n", rules)).append("\n</rules>\n");
        }
        groupByDocument(distinct.values()).forEach(group -> out.append(renderDocument(group)));
        return out.append("</retrieval>").toString();
    }

    /**
     * 文档按首次出现的先后排列：先是第一个子问题按其名次命中的文档，再接后续子问题新出现的；没有 docId 的分片各自成组
     */
    private List<List<RetrievedChunk>> groupByDocument(Collection<RetrievedChunk> chunks) {
        Map<String, List<RetrievedChunk>> groups = new LinkedHashMap<>();
        int anonymous = 0;
        for (RetrievedChunk chunk : chunks) {
            String key = StrUtil.isNotBlank(chunk.getDocId()) ? chunk.getDocId() : "__nodoc__" + anonymous++;
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(chunk);
        }
        return new ArrayList<>(groups.values());
    }

    private String renderDocument(List<RetrievedChunk> group) {
        List<RetrievedChunk> ordered = group.stream()
                .sorted(Comparator.comparing(RetrievedChunk::getChunkIndex, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        StringBuilder out = new StringBuilder("<document>\n");
        String docName = sanitize(group.stream().map(RetrievedChunk::getDocName)
                .filter(StrUtil::isNotBlank).findFirst().orElse(null));
        if (searchChannelProperties.isIncludeDocName() && StrUtil.isNotBlank(docName)) {
            out.append("<doc_name>").append(docName).append("</doc_name>\n");
        }
        Integer previous = null;
        for (int i = 0; i < ordered.size(); i++) {
            RetrievedChunk chunk = ordered.get(i);
            if (i > 0 && !adjacent(previous, chunk.getChunkIndex())) {
                out.append(GAP).append('\n');
            }
            out.append(StrUtil.emptyIfNull(chunk.getText()).strip()).append('\n');
            previous = chunk.getChunkIndex();
        }
        return out.append("</document>\n").toString();
    }

    /**
     * 来源、位置、正文都相同才算同一段：图谱引用号只在单次查询内唯一，网页 id 是 URL，不同子问题会拿到同一页的不同摘录
     */
    private static String evidenceKey(RetrievedChunk chunk) {
        String source = StrUtil.isNotBlank(chunk.getDocId()) ? chunk.getDocId() : StrUtil.emptyIfNull(chunk.getId());
        return source + '\n' + chunk.getChunkIndex() + '\n' + StrUtil.emptyIfNull(chunk.getText());
    }

    private static boolean adjacent(Integer previous, Integer current) {
        return previous != null && current != null && current == previous + 1;
    }

    /**
     * 文档名里的尖括号会破坏标签结构
     */
    private static String sanitize(String value) {
        return StrUtil.isBlank(value) ? "" : value.replaceAll("[<>]", "").trim();
    }
}
