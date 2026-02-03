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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 向量全局检索通道
 * <p>
 * 在所有知识库中进行向量检索，作为兜底策略
 * 当意图识别失败或置信度低时启用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorGlobalSearchChannel implements SearchChannel {

    private final RetrieverService retrieverService;
    private final SearchChannelProperties properties;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    @Qualifier("ragInnerRetrievalThreadPoolExecutor")
    private final Executor ragInnerRetrievalExecutor;

    @Override
    public String getName() {
        return "VectorGlobalSearch";
    }

    @Override
    public int getPriority() {
        return 10;  // 较低优先级
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        // 检查配置是否启用
        if (!properties.getChannels().getVectorGlobal().isEnabled()) {
            return false;
        }

        // 条件1：没有识别出任何意图
        if (CollUtil.isEmpty(context.getIntents())) {
            log.info("未识别出任何意图，启用全局检索");
            return true;
        }

        // 条件2：意图置信度都很低
        double maxScore = context.getIntents().stream()
                .flatMap(si -> si.nodeScores().stream())
                .mapToDouble(NodeScore::getScore)
                .max()
                .orElse(0.0);

        double threshold = properties.getChannels().getVectorGlobal().getConfidenceThreshold();
        if (maxScore < threshold) {
            log.info("意图置信度过低（{}），启用全局检索", maxScore);
            return true;
        }

        return false;
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("执行向量全局检索，问题：{}", context.getMainQuestion());

            // 获取所有 KB 类型的 collection
            List<String> collections = getAllKBCollections();

            if (collections.isEmpty()) {
                log.warn("未找到任何 KB collection，跳过全局检索");
                return SearchChannelResult.builder()
                        .channelType(SearchChannelType.VECTOR_GLOBAL)
                        .channelName(getName())
                        .chunks(List.of())
                        .confidence(0.0)
                        .latencyMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            log.info("全局检索将在 {} 个 collection 中进行：{}", collections.size(), collections);

            // 并行在所有 collection 中检索
            int topKMultiplier = properties.getChannels().getVectorGlobal().getTopKMultiplier();
            List<RetrievedChunk> allChunks = retrieveFromAllCollections(
                    context.getMainQuestion(),
                    collections,
                    context.getTopK() * topKMultiplier
            );

            long latency = System.currentTimeMillis() - startTime;

            log.info("向量全局检索完成，检索到 {} 个 Chunk，耗时 {}ms", allChunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR_GLOBAL)
                    .channelName(getName())
                    .chunks(allChunks)
                    .confidence(0.7)  // 全局检索置信度中等
                    .latencyMs(latency)
                    .build();

        } catch (Exception e) {
            log.error("向量全局检索失败", e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR_GLOBAL)
                    .channelName(getName())
                    .chunks(List.of())
                    .confidence(0.0)
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * 获取所有 KB 类型的 collection
     */
    private List<String> getAllKBCollections() {
        Set<String> collections = new HashSet<>();

        // 从知识库表获取全量 collection（全局检索兜底）
        List<KnowledgeBaseDO> kbList = knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .select(KnowledgeBaseDO::getCollectionName)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        for (KnowledgeBaseDO kb : kbList) {
            String collectionName = kb.getCollectionName();
            if (collectionName != null && !collectionName.isBlank()) {
                collections.add(collectionName);
            }
        }

        return new ArrayList<>(collections);
    }

    /**
     * 并行在所有 collection 中检索
     */
    private List<RetrievedChunk> retrieveFromAllCollections(String question,
                                                            List<String> collections,
                                                            int topK) {
        // 创建带 collection 信息的 Future 列表
        record CollectionFuture(String collectionName, CompletableFuture<List<RetrievedChunk>> future) {
        }

        List<CollectionFuture> collectionFutures = collections.stream()
                .map(collection -> {
                    CompletableFuture<List<RetrievedChunk>> future = CompletableFuture.supplyAsync(() -> {
                        try {
                            return retrieverService.retrieve(
                                    RetrieveRequest.builder()
                                            .collectionName(collection)
                                            .query(question)
                                            .topK(topK)
                                            .build()
                            );
                        } catch (Exception e) {
                            log.error("在 collection {} 中检索失败，错误: {}", collection, e.getMessage(), e);
                            return List.of();
                        }
                    }, ragInnerRetrievalExecutor);
                    return new CollectionFuture(collection, future);
                })
                .toList();

        // 等待所有检索完成并合并结果，统计成功/失败数
        List<RetrievedChunk> allChunks = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (CollectionFuture cf : collectionFutures) {
            try {
                List<RetrievedChunk> chunks = cf.future.join();
                allChunks.addAll(chunks);
                successCount++;
                log.debug("Collection {} 检索成功，检索到 {} 个 Chunk", cf.collectionName, chunks.size());
            } catch (Exception e) {
                failureCount++;
                log.error("获取 collection {} 检索结果失败", cf.collectionName, e);
            }
        }

        log.info("全局检索统计 - 总 Collection 数: {}, 成功: {}, 失败: {}, 检索到 Chunk 总数: {}",
                collections.size(), successCount, failureCount, allChunks.size());

        return allChunks;
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.VECTOR_GLOBAL;
    }
}
