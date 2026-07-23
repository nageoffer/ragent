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
import com.nageoffer.ai.ragent.rag.core.vector.QueryEmbeddingContext;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Collection 并行检索器
 * 继承模板类，实现 Collection 特定的检索逻辑
 */
@Slf4j
public class CollectionParallelRetriever extends AbstractParallelRetriever<CollectionParallelRetriever.CollectionTask> {

    private final VectorRetrieverService retrieverService;

    public CollectionParallelRetriever(VectorRetrieverService retrieverService, Executor executor) {
        super(executor);
        this.retrieverService = retrieverService;
    }

    public record CollectionTask(String collectionName, float[] queryVector, boolean defaultModel) {
    }

    public List<RetrievedChunk> retrieveByCollections(String question,
                                                      List<String> collections,
                                                      int topK,
                                                      QueryEmbeddingContext embeddingContext) {
        List<CollectionTask> tasks = collections.stream()
                .map(collection -> new CollectionTask(
                        collection,
                        embeddingContext != null && embeddingContext.isPrepared()
                                ? embeddingContext.vectorFor(question, collection)
                                : null,
                        embeddingContext == null
                                || !embeddingContext.isPrepared()
                                || embeddingContext.usesDefaultModel(collection)
                ))
                .toList();
        return super.executeParallelRetrieval(question, tasks, topK);
    }

    @Override
    protected List<RetrievedChunk> createRetrievalTask(String question, CollectionTask task, int topK) {
        try {
            RetrieveRequest request = RetrieveRequest.builder()
                    .collectionName(task.collectionName())
                    .query(question)
                    .topK(topK)
                    .build();
            if (task.queryVector() != null) {
                return retrieverService.retrieveByVector(task.queryVector(), request);
            }
            if (task.defaultModel()) {
                return retrieverService.retrieve(request);
            }
            log.error("全局检索缺少指定模型的预计算向量，跳过 Collection: {}", task.collectionName());
            return List.of();
        } catch (Exception e) {
            log.error("在 collection {} 中检索失败，错误: {}", task.collectionName(), e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    protected String getTargetIdentifier(CollectionTask task) {
        return "Collection: " + task.collectionName();
    }

    @Override
    protected String getStatisticsName() {
        return "全局检索";
    }
}
