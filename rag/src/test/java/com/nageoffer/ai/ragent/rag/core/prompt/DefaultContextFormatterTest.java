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

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KB 上下文组装测试
 * <p>
 * 覆盖上下文优化的核心行为：
 * 1. 按文档聚合：文档之间按相关性（各文档最佳块排名）排序，文档内部按 chunkIndex 还原原文顺序
 * 2. 只注入内部 docId 作为锚点，文档名（含文件名）绝不进上下文；docId 缺失的块单独成组、无任何属性
 * 3. 同文档的块按 index 排好后用单换行顺次拼接
 */
class DefaultContextFormatterTest {

    private DefaultContextFormatter formatter() {
        return new DefaultContextFormatter(new PromptTemplateLoader(new DefaultResourceLoader()));
    }

    private RetrievedChunk chunk(String id, String text, String docId, String docName, Integer index, float score) {
        return RetrievedChunk.builder()
                .id(id).text(text).score(score)
                .docId(docId).docName(docName).chunkIndex(index)
                .build();
    }

    @Test
    void groupsByDocumentAndOrdersWithinDocByIndex() {
        // 相关性顺序：A的idx3(rank1)、B的idx0(rank2)、A的idx1(rank3)、无归属孤块(rank4)
        List<RetrievedChunk> chunks = List.of(
                chunk("a3", "A-idx3正文", "docA", "员工手册.pdf", 3, 0.9f),
                chunk("b0", "B-idx0正文", "docB", "报销政策.md", 0, 0.8f),
                chunk("a1", "A-idx1正文", "docA", "员工手册.pdf", 1, 0.7f),
                chunk("x0", "孤块正文", null, null, null, 0.6f));

        String result = formatter().formatKbContext(List.of(), Set.of(), chunks, 100);

        // 文档 A 整体在文档 B 之前（A 的最佳块排名更高），孤块最后
        assertTrue(result.indexOf("A-idx1正文") < result.indexOf("A-idx3正文"), "同文档内应按 chunkIndex 升序");
        assertTrue(result.indexOf("A-idx3正文") < result.indexOf("B-idx0正文"), "文档 A 整块应在文档 B 之前");
        assertTrue(result.indexOf("B-idx0正文") < result.indexOf("孤块正文"), "无归属孤块应排在最后");

        // 只注入内部 docId，文档名一律不进上下文（模型拿不到名字才不会写"出自《XX》"）
        assertTrue(result.contains("data-ragent-doc-id=\"docA\""), "应携带内部 docId 供后续注入引用编号");
        assertTrue(result.contains("data-ragent-doc-id=\"docB\""));
        assertFalse(result.contains("source="), "不应再注入 source 属性");
        assertFalse(result.contains("员工手册"), "文档名不得进入上下文");
        assertFalse(result.contains("报销政策"));

        // 孤块单独成组、无任何属性
        assertTrue(result.replace("\r\n", "\n").contains("<content>\n孤块正文\n</content>"),
                "docId 缺失应渲染为无属性的独立块");
    }

    @Test
    void sameDocChunksJoinedByNewline() {
        // 同文档的块按 index 排好后用单换行顺次拼接（原文照拼，不做任何去重/加工）
        List<RetrievedChunk> chunks = List.of(
                chunk("c1", "第一块正文", "docC", "说明.txt", 1, 0.9f),
                chunk("c2", "第二块正文", "docC", "说明.txt", 2, 0.8f));

        String result = formatter().formatKbContext(List.of(), Set.of(), chunks, 100);

        assertTrue(result.contains("第一块正文\n第二块正文"), "同文档块之间用单换行拼接");
    }

    @Test
    void keepsInternalDocumentIdAsOnlyAttribute() {
        List<RetrievedChunk> chunks = List.of(
                chunk("c1", "无标题正文", "docC", null, 0, 0.9f));

        String result = formatter().formatKbContext(List.of(), Set.of(), chunks, 100);

        assertTrue(result.contains("<content data-ragent-doc-id=\"docC\">"));
    }

    @Test
    void keepsDifferentChunksWhenIdsAreBlank() {
        List<RetrievedChunk> chunks = List.of(
                chunk("", "第一块正文", "docC", null, 0, 0.9f),
                chunk("", "第二块正文", "docC", null, 1, 0.8f));

        String result = formatter().formatKbContext(List.of(), Set.of(), chunks, 100);

        assertTrue(result.contains("第一块正文"));
        assertTrue(result.contains("第二块正文"));
    }

    @Test
    void onlyEligibleIntentContributesSnippet() {
        NodeScore intentA = intent("A", "SNIPPET_A");
        NodeScore intentB = intent("B", "SNIPPET_B");
        RetrievedChunk chunkA = chunk("chunk-a", "A的资料", "docA", null, 0, 0.9f);

        String result = formatter().formatKbContext(
                List.of(intentA, intentB),
                Set.of("A"),
                List.of(chunkA),
                100
        );

        assertTrue(result.contains("SNIPPET_A"));
        assertFalse(result.contains("SNIPPET_B"), "未进入提示词规划的意图不应注入回答规则");
        assertTrue(result.contains("A的资料"));
    }

    @Test
    void directedMissKeepsEvidenceWithoutCandidateSnippet() {
        NodeScore intentA = intent("A", "SNIPPET_A");
        RetrievedChunk supplement = chunk("supplement", "补充资料", null, null, 0, 0.9f);

        String result = formatter().formatKbContext(
                List.of(intentA),
                Set.of(),
                List.of(supplement),
                100
        );

        assertFalse(result.contains("SNIPPET_A"));
        assertTrue(result.contains("补充资料"));
    }

    @Test
    void globalEvidenceKeepsUnevaluatedCandidateSnippet() {
        NodeScore intentA = intent("A", "SNIPPET_A");
        RetrievedChunk globalChunk = chunk("global", "全局资料", null, null, 0, 0.9f);

        String result = formatter().formatKbContext(
                List.of(intentA),
                Set.of("A"),
                List.of(globalChunk),
                100
        );

        assertTrue(result.contains("SNIPPET_A"));
        assertTrue(result.contains("全局资料"));
    }

    private NodeScore intent(String id, String snippet) {
        return NodeScore.builder()
                .node(IntentNode.builder().id(id).promptSnippet(snippet).build())
                .score(0.9)
                .build();
    }
}
