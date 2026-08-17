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

package com.nageoffer.ai.ragent.rag.core.vector.strategy;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 逐库并行检索器
 * <p>
 * 后端不支持跨库单查时的兜底取数路：对给定 collection 集合并行各取一份、汇总排序
 * 单库失败只损失该库结果，不影响其余库
 */
@Slf4j
public class CollectionParallelRetriever {

    private final VectorRetrieverService retrieverService;
    private final Executor executor;

    public CollectionParallelRetriever(VectorRetrieverService retrieverService,
                                       Executor executor) {
        this.retrieverService = retrieverService;
        this.executor = executor;
    }

    /**
     * 并行检索，内部生成查询向量
     */
    public List<RetrievedChunk> executeParallelRetrieval(String question,
                                                         List<String> collections,
                                                         int topK) {
        return executeParallelRetrieval(question, collections, topK, retrieverService.embedAndNormalize(question));
    }

    /**
     * 并行检索，复用调用方已算好的查询向量
     * 供同一次请求内还有其他向量取数路（如向量通道的补充路）时共用一次 embedding
     *
     * @param queryVector 已归一化的查询向量
     */
    public List<RetrievedChunk> executeParallelRetrieval(String question,
                                                         List<String> collections,
                                                         int topK,
                                                         float[] queryVector) {
        record RetrievalFuture(String collection, CompletableFuture<List<RetrievedChunk>> future) {
        }

        List<RetrievalFuture> futures = collections.stream()
                .map(collection -> new RetrievalFuture(collection, CompletableFuture.supplyAsync(
                        () -> retrieveOne(question, collection, queryVector, topK),
                        executor
                )))
                .toList();

        List<RetrievedChunk> allChunks = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (RetrievalFuture future : futures) {
            try {
                allChunks.addAll(future.future().join());
                successCount++;
            } catch (Exception e) {
                failureCount++;
                log.error("全局检索 获取检索结果失败 - Collection: {}", future.collection(), e);
            }
        }

        // 各库并行返回的子列表仅在自身内部有序，拼接后跨库名次等于拼接顺序，
        // 会让下游截断与 RRF 的名次基准失真，故在出口统一按 score 降序
        allChunks.sort(RetrievedChunk.BY_SCORE_DESC);

        log.info("全局检索 检索统计 - 总目标数: {}, 成功: {}, 失败: {}, 检索到 Chunk 总数: {}",
                collections.size(), successCount, failureCount, allChunks.size());

        return allChunks;
    }

    /**
     * 单库取数，失败返回空列表兑现「单库失败只损失自己」
     */
    private List<RetrievedChunk> retrieveOne(String question, String collectionName, float[] queryVector, int topK) {
        try {
            return retrieverService.retrieveByVector(
                    queryVector,
                    RetrieveRequest.builder()
                            .collectionName(collectionName)
                            .query(question)
                            .topK(topK)
                            .build()
            );
        } catch (Exception e) {
            log.error("在 collection {} 中检索失败，错误: {}", collectionName, e.getMessage(), e);
            return List.of();
        }
    }
}
