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
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 并行检索抽象模板类
 * <p>
 * 封装通用的并行检索逻辑：
 * 1. 创建 Future 列表
 * 2. 并行提交到线程池
 * 3. 收集结果并统计成功/失败数
 * 4. 打印统计日志
 * <p>
 * 子类只需实现：
 * - createRetrievalTask: 创建单个检索任务
 * - getTargetIdentifier: 获取目标标识（用于日志）
 * - getStatisticsName: 获取统计名称（用于日志）
 *
 * @param <T> 检索目标类型（如 NodeScore、String）
 */
@Slf4j
public abstract class AbstractParallelRetriever<T> {

    protected final VectorRetrieverService retrieverService;
    private final Executor executor;

    protected AbstractParallelRetriever(VectorRetrieverService retrieverService,
                                        Executor executor) {
        this.retrieverService = retrieverService;
        this.executor = executor;
    }

    /**
     * 并行检索模板方法
     *
     * @param question 查询问题
     * @param targets  检索目标列表
     * @param topK     每个目标的 TopK
     * @return 合并后的检索结果
     */
    public final List<RetrievedChunk> executeParallelRetrieval(String question,
                                                               List<T> targets,
                                                               int topK) {
        return executeParallelRetrieval(question, targets, topK, retrieverService.embedAndNormalize(question));
    }

    /**
     * 并行检索模板方法，复用调用方已算好的查询向量
     * 供同一次请求内还有其他向量取数路（如向量通道的补充路）时共用一次 embedding
     *
     * @param queryVector 已归一化的查询向量
     */
    public final List<RetrievedChunk> executeParallelRetrieval(String question,
                                                               List<T> targets,
                                                               int topK,
                                                               float[] queryVector) {
        return executeParallelRetrievalDetailed(question, targets, topK, queryVector).chunks();
    }

    protected final ParallelRetrievalResult<T> executeParallelRetrievalDetailed(String question,
                                                                                 List<T> targets,
                                                                                 int topK) {
        return executeParallelRetrievalDetailed(
                question, targets, topK, retrieverService.embedAndNormalize(question));
    }

    protected final ParallelRetrievalResult<T> executeParallelRetrievalDetailed(String question,
                                                                                 List<T> targets,
                                                                                 int topK,
                                                                                 float[] queryVector) {
        // 1. 创建 Future 列表
        record RetrievalFuture<T>(T target, CompletableFuture<List<RetrievedChunk>> future) {
        }

        List<RetrievalFuture<T>> futures = targets.stream()
                .map(target -> {
                    CompletableFuture<List<RetrievedChunk>> future = CompletableFuture.supplyAsync(
                            () -> createRetrievalTask(question, target, queryVector, topK),
                            executor
                    );
                    return new RetrievalFuture<>(target, future);
                })
                .toList();

        // 2. 收集结果并统计成功/失败数
        List<RetrievedChunk> allChunks = new ArrayList<>();
        List<TargetRetrievalResult<T>> targetResults = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (RetrievalFuture<T> future : futures) {
            try {
                List<RetrievedChunk> chunks = future.future.join();
                allChunks.addAll(chunks);
                targetResults.add(new TargetRetrievalResult<>(future.target, chunks));
                successCount++;
            } catch (Exception e) {
                failureCount++;
                targetResults.add(new TargetRetrievalResult<>(future.target, List.of()));
                log.error("{} 获取检索结果失败 - 目标: {}", getStatisticsName(), getTargetIdentifier(future.target), e);
            }
        }

        // 3. 跨目标按相关性得分归并排序
        // 各目标并行返回的子列表仅在自身内部有序，addAll 拼接后跨目标名次等于拼接顺序，
        // 叠加目标集合本身可能无序（如 HashSet），会让下游 RRF 的名次基准失真、截断误砍高分
        // 故在通道出口统一按 score 降序，兑现「该通道视角下的全局相关性排序」这一不变式
        allChunks.sort((a, b) -> Float.compare(scoreOf(b), scoreOf(a)));

        // 4. 打印统计日志
        log.info("{} 检索统计 - 总目标数: {}, 成功: {}, 失败: {}, 检索到 Chunk 总数: {}",
                getStatisticsName(), targets.size(), successCount, failureCount, allChunks.size());

        return new ParallelRetrievalResult<>(allChunks, targetResults);
    }

    protected record TargetRetrievalResult<T>(T target, List<RetrievedChunk> chunks) {
    }

    protected record ParallelRetrievalResult<T>(List<RetrievedChunk> chunks,
                                                List<TargetRetrievalResult<T>> targetResults) {
    }

    /**
     * 取 chunk 得分，缺失时视为最低分沉底
     */
    private static float scoreOf(RetrievedChunk chunk) {
        return chunk.getScore() == null ? Float.NEGATIVE_INFINITY : chunk.getScore();
    }

    /**
     * 创建单个检索任务（子类实现）
     * 注意：此方法内部应包含异常处理，失败时返回空列表
     *
     * @param question 查询问题
     * @param target   检索目标
     * @param queryVector 预计算后的查询向量
     * @param topK     TopK
     * @return 检索结果列表
     */
    protected abstract List<RetrievedChunk> createRetrievalTask(String question, T target, float[] queryVector, int topK);

    /**
     * 获取目标标识（用于日志）
     *
     * @param target 检索目标
     * @return 目标标识字符串
     */
    protected abstract String getTargetIdentifier(T target);

    /**
     * 获取统计名称（用于日志）
     *
     * @return 统计名称，如 "意图检索"、"全局检索"
     */
    protected abstract String getStatisticsName();
}
