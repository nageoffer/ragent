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

package com.nageoffer.ai.ragent.rag.core.vector;

import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 向量检索服务接口（VectorRetrieverService）
 * <p>
 * 用途说明：
 * - 封装对向量数据库（如 Milvus / pgVector / Elasticsearch KNN）的检索能力
 * - 负责从向量库中查找与用户问题（Query）最相关的若干文档片段（Chunk）
 * - 是 RAG 系统中 Retrieval 阶段的核心组件
 * <p>
 * 工作流程：
 * 1. 获取 Query 的 embedding（通常由 EmbeddingService 提供）
 * 2. 在向量库中进行相似度搜索
 * 3. 返回排序后的相关 Chunk（RAGHit）
 * <p>
 * 特点：
 * - 可将检索与大模型（LLM）调用解耦，便于替换搜索实现
 * - 可基于不同召回策略扩展：向量检索、混合检索、符号搜索、多模态检索等
 * <p>
 * 注意事项：
 * - topK 不宜过大，一般 3〜8 为最佳区间
 * - 建议对 vector 维度进行校验，避免与向量库 schema 不匹配
 */
public interface VectorRetrieverService {

    /**
     * 根据自然语言 Query 进行检索
     * <p>
     * 说明：
     * - 内部通常会先调用 EmbeddingService.embed(query) 获取向量
     * - 然后在向量库中执行相似度搜索
     * - 返回命中文档 Chunk 的列表，已按相似度倒序排序
     * <p>
     * 示例：
     * retrieve("请介绍入职流程", 3)
     *
     * @param query 用户自然语言问题
     * @param topK  返回的命中数量
     * @return RetrievedChunk 列表（包含 chunk 内容、得分、metadata 等）
     */
    default List<RetrievedChunk> retrieve(String query, int topK) {
        RetrieveRequest req = RetrieveRequest.builder()
                .query(query)
                .topK(topK)
                .build();
        return retrieve(req);
    }

    /**
     * 根据自然语言 Query 进行检索，支持扩展参数
     * <p>
     * 说明：
     * - 内部通常会先调用 EmbeddingService.embed(query) 获取向量
     * - 然后在向量库中执行相似度搜索
     * - 返回命中文档 Chunk 的列表，已按相似度倒序排序
     * <p>
     *
     * @param retrieveParam 向量检索请求参数
     * @return RetrievedChunk 列表（包含 chunk 内容、得分、metadata 等）
     */
    List<RetrievedChunk> retrieve(RetrieveRequest retrieveParam);

    /**
     * 根据向量直接检索（可选使用）
     * <p>
     * 说明：
     * - 场景适用于：Query embedding 已预先计算的情况
     * - 避免重复调用 embedding 模型
     * - 常用于多轮对话中复用 embedding、批量检索等
     * <p>
     * 注意：
     * - 调用方负责确保 vector 维度与向量库 schema 保持一致
     *
     * @param vector        查询向量（如 float[4096]）
     * @param retrieveParam 向量检索请求参数
     * @return RetrievedChunk 列表（按相似度排序）
     */
    List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest retrieveParam);

    /**
     * 是否支持单次全局检索
     * <p>
     * - 返回 true 时，全局检索通道会调用 {@link #retrieveGlobal} 一次性跨库召回
     * - 返回 false 时，通道退化为逐库并行 fan-out（如 Milvus 每库一个 collection）
     *
     * @return 是否支持单次全局检索
     */
    default boolean supportsGlobalRetrieval() {
        return false;
    }

    /**
     * 单次全局检索
     * <p>
     * 说明：
     * - 在一批 collection 范围内做一次带总预算的全局 TopN 召回，而非逐库 TopK 再拼接
     * - 仅当 {@link #supportsGlobalRetrieval()} 返回 true 时可用
     *
     * @param query           用户自然语言问题
     * @param collectionNames 参与全局检索的 collection 列表
     * @param candidateBudget 候选总预算（单次查询 LIMIT 上限）
     * @return RetrievedChunk 列表（按相似度倒序）
     */
    default List<RetrievedChunk> retrieveGlobal(String query, List<String> collectionNames, int candidateBudget) {
        throw new UnsupportedOperationException("该检索后端不支持单次全局检索");
    }

    /**
     * 使用预计算向量执行跨库检索。
     * <p>
     * 默认实现逐库检索并合并，支持单次跨库查询的后端应覆盖此方法。
     */
    default List<RetrievedChunk> retrieveGlobalByVector(float[] vector,
                                                        List<String> collectionNames,
                                                        int candidateBudget) {
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (String collectionName : collectionNames) {
            chunks.addAll(retrieveByVector(
                    vector,
                    RetrieveRequest.builder()
                            .collectionName(collectionName)
                            .topK(candidateBudget)
                            .build()
            ));
        }
        chunks.sort(Comparator.comparing(
                RetrievedChunk::getScore,
                Comparator.nullsLast(Comparator.reverseOrder())
        ));
        return chunks.stream().limit(candidateBudget).toList();
    }
}

