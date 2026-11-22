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
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 交给主 Agent 的资料：按文档归组、跨子问题去重、断开处标省略，任何一段都不因分数或子问题先后被丢掉
 */
class KnowledgeEvidenceRendererTest {

    private final KnowledgeEvidenceRenderer renderer = new KnowledgeEvidenceRenderer(new SearchChannelProperties());

    /**
     * 同一段在问题 A 得 0.21、在问题 B 得 0.99：不管谁先谁后都在，且只出现一次
     */
    @Test
    void keepsSharedChunkOnceRegardlessOfSubQuestionOrder() {
        RetrievedChunk weightLow = chunk("w", "doc-17e", "IPHONE-17E.md", 4, 0.21F, "重量：17e 的数值");
        RetrievedChunk weightHigh = weightLow.toBuilder().score(0.99F).rerankScore(0.99F).build();
        RetrievedChunk video = chunk("v", "doc-17e", "IPHONE-17E.md", 6, 0.95F, "视频拍摄：4K");

        for (List<RetrievedChunk> order : List.of(
                List.of(weightLow, video, weightHigh),
                List.of(video, weightHigh, weightLow))) {
            String out = renderer.render(order, List.of());

            assertThat(out).containsOnlyOnce("重量：17e 的数值").contains("视频拍摄：4K")
                    .containsOnlyOnce("<doc_name>IPHONE-17E.md</doc_name>");
        }
    }

    /**
     * 一题精排成功（0.9 上下）、一题降级（保留上游的小分数）：降级那题的资料照样全在，文档顺序跟子问题先后走，不跟分数走
     */
    @Test
    void doesNotCompareScoresAcrossSubQuestions() {
        RetrievedChunk reranked = chunk("r", "doc-policy", "保修政策总则.md", 1, 0.92F, "保修期内免费维修");
        RetrievedChunk degradedTop = chunk("d1", "doc-repair", "维修报价.md", 1, 0.03F, "屏幕维修 1099 元起");
        RetrievedChunk degradedSecond = chunk("d2", "doc-repair", "维修报价.md", 2, 0.01F, "电池维修 699 元起");

        String out = renderer.render(List.of(degradedTop, degradedSecond, reranked), List.of());

        assertThat(out).contains("屏幕维修 1099 元起", "电池维修 699 元起", "保修期内免费维修");
        assertThat(out.indexOf("维修报价.md")).isLessThan(out.indexOf("保修政策总则.md"));
    }

    /**
     * 同一文档按原文顺序排；不相邻的两段中间插省略标记，相邻的直接接上
     */
    @Test
    void marksGapsBetweenNonAdjacentChunksOfOneDocument() {
        String out = renderer.render(List.of(
                chunk("c9", "doc", "退换货条件.md", 9, 0.9F, "第九段"),
                chunk("c2", "doc", "退换货条件.md", 2, 0.8F, "第二段"),
                chunk("c3", "doc", "退换货条件.md", 3, 0.7F, "第三段")), List.of());

        assertThat(out).contains("<doc_name>退换货条件.md</doc_name>\n第二段\n第三段\n……\n第九段\n</document>");
    }

    /**
     * 两个子问题的图谱结果都把排第一的文档编成 1 号：同模板的两份手册正文相同，按文档仍是两段
     */
    @Test
    void keepsSameTextFromDifferentDocuments() {
        String out = renderer.render(List.of(
                excerpt("1", "doc-pro", "AirPods Pro 使用手册.md", null, "左耳无声时先确认两只耳机都已放回充电盒"),
                excerpt("1", "doc-4", "AirPods 4 使用手册.md", null, "左耳无声时先确认两只耳机都已放回充电盒")), List.of());

        assertThat(out).contains("<doc_name>AirPods Pro 使用手册.md</doc_name>", "<doc_name>AirPods 4 使用手册.md</doc_name>");
    }

    /**
     * 联网结果以 URL 为 id：两个子问题拿到同一页面的不同摘录，两段都要在
     */
    @Test
    void keepsDifferentExcerptsOfOneUrl() {
        String url = "https://support.apple.com/airpods";
        String out = renderer.render(List.of(
                excerpt(url, null, null, null, "摘录：重置 AirPods 的步骤"),
                excerpt(url, null, null, null, "摘录：AirPods 保修期一年")), List.of());

        assertThat(out).contains("摘录：重置 AirPods 的步骤", "摘录：AirPods 保修期一年");
    }

    /**
     * 同一文档的同一段在两次图谱查询里引用号不同：只出现一次
     */
    @Test
    void mergesSameExcerptUnderDifferentReferenceIds() {
        String out = renderer.render(List.of(
                excerpt("1", "doc-pro", "AirPods Pro 使用手册.md", null, "固件更新后重新配对"),
                excerpt("2", "doc-pro", "AirPods Pro 使用手册.md", null, "固件更新后重新配对")), List.of());

        assertThat(out).containsOnlyOnce("固件更新后重新配对");
    }

    /**
     * 同一手册里第一段和第三段正文相同：按位置是两段，不能当重复删掉后在原处标省略
     */
    @Test
    void keepsSameTextAtDifferentPositionsOfOneDocument() {
        String out = renderer.render(List.of(
                excerpt("c0", "doc-pro", "AirPods Pro 使用手册.md", 0, "放回充电盒"),
                excerpt("c1", "doc-pro", "AirPods Pro 使用手册.md", 1, "长按设置按钮至指示灯闪白"),
                excerpt("c2", "doc-pro", "AirPods Pro 使用手册.md", 2, "放回充电盒"),
                excerpt("c3", "doc-pro", "AirPods Pro 使用手册.md", 3, "重新连接 iPhone")), List.of());

        assertThat(out).contains("放回充电盒\n长按设置按钮至指示灯闪白\n放回充电盒\n重新连接 iPhone\n</document>");
    }

    /**
     * 复合问题「iPhone 17e 多重？屏幕摔裂保修管吗？」：两题共享一份商品文档，规则带意图路径只写一次，正文不重复粘贴
     */
    @Test
    void rendersCompoundQuestionAsOneDocumentPerSource() {
        // 子问题一「iPhone 17e 多重」按名次命中：重量块、同文档的尺寸块
        RetrievedChunk weight = chunk("w", "doc-17e", "IPHONE-17E.md", 5, 0.97F, "重量：170 克");
        RetrievedChunk size = chunk("s", "doc-17e", "IPHONE-17E.md", 4, 0.61F, "尺寸：146.7 × 71.5 × 7.8 毫米");
        // 子问题二「iPhone 17e 屏幕摔裂保修管吗」按名次命中：保修条款、维修报价、又命中一次尺寸块
        RetrievedChunk warranty = chunk("p", "doc-policy", "保修政策总则.md", 2, 0.93F, "人为损坏不在免费保修范围内");
        RetrievedChunk repairPrice = chunk("q", "doc-repair", "iPhone维修服务报价表.md", 1, 0.88F, "iPhone 17e 屏幕维修 1599 元");
        RetrievedChunk sizeAgain = size.toBuilder().score(0.12F).rerankScore(0.12F).build();

        String out = renderer.render(List.of(weight, size, warranty, repairPrice, sizeAgain),
                List.of("[售后服务 > 保修] 先说是否在保修范围，再给费用"));

        assertThat(out).isEqualTo("""
                <retrieval>
                <rules>
                [售后服务 > 保修] 先说是否在保修范围，再给费用
                </rules>
                <document>
                <doc_name>IPHONE-17E.md</doc_name>
                尺寸：146.7 × 71.5 × 7.8 毫米
                重量：170 克
                </document>
                <document>
                <doc_name>保修政策总则.md</doc_name>
                人为损坏不在免费保修范围内
                </document>
                <document>
                <doc_name>iPhone维修服务报价表.md</doc_name>
                iPhone 17e 屏幕维修 1599 元
                </document>
                </retrieval>""");
    }

    private static RetrievedChunk chunk(String id, String docId, String docName, int index, float score, String text) {
        return RetrievedChunk.builder()
                .id(id)
                .docId(docId)
                .docName(docName)
                .chunkIndex(index)
                .score(score)
                .rerankScore(score)
                .text(text)
                .build();
    }

    private static RetrievedChunk excerpt(String id, String docId, String docName, Integer index, String text) {
        return RetrievedChunk.builder().id(id).docId(docId).docName(docName).chunkIndex(index).text(text).build();
    }
}
