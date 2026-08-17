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

package com.nageoffer.ai.ragent.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Comparator;

/**
 * RAG 检索命中结果
 * <p>
 * 表示一次向量检索或相关性搜索命中的单条记录
 * 包含原始文档片段 主键以及相关性得分
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class RetrievedChunk {

    /**
     * 相关性降序 缺分与非有限值沉底
     * 各取数与通道出口共用的唯一排序规则 下游截断与 RRF 均以该名次为基准
     * 合法分数（余弦 / BM25 / 倒数名次）均为有限值 NaN 与 ±Infinity 只能来自上游缺陷
     * 且 Float.compare 会把 NaN 当最大值 不归一就让毒值抢占最高名次
     */
    public static final Comparator<RetrievedChunk> BY_SCORE_DESC = (a, b) -> Float.compare(sortScore(b), sortScore(a));

    private static float sortScore(RetrievedChunk chunk) {
        Float score = chunk.getScore();
        return score == null || !Float.isFinite(score) ? Float.NEGATIVE_INFINITY : score;
    }

    /**
     * 命中记录的唯一标识
     * 比如向量库中的 primary key 或文档 id
     */
    private String id;

    /**
     * 命中的文本内容
     * 一般是被切分后的文档片段或段落
     */
    private String text;

    /**
     * 命中得分
     * 数值越大表示与查询的相关性越高
     */
    private Float score;

    /**
     * 所属知识库 collection
     * 检索时由各后端从存储侧字段填充 用于按库推导意图归属 无库来源（如联网检索）为 null
     */
    private String collectionName;

    /**
     * 所属文档 ID
     * 检索后由元数据富化补齐 未富化时为 null
     */
    private String docId;

    /**
     * 分块在所属文档中的序号 从 0 开始
     * 检索后由元数据富化补齐 未富化时为 null
     */
    private Integer chunkIndex;

    /**
     * 所属文档名称 用于组装上下文时作为文档标题的内部锚点
     * 检索后由元数据富化补齐 未富化时为 null
     */
    private String docName;
}
