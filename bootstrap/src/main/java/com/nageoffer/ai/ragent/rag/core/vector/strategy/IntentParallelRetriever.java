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
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.vector.QueryEmbeddingContext;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 意图并行检索器
 * 继承模板类，实现意图特定的检索逻辑
 */
@Slf4j
public class IntentParallelRetriever extends AbstractParallelRetriever<IntentParallelRetriever.IntentTask> {

    private final VectorRetrieverService retrieverService;

    public record IntentTask(NodeScore nodeScore, int intentTopK, float[] queryVector, boolean defaultModel) {
    }

    public IntentParallelRetriever(VectorRetrieverService retrieverService,
                                   Executor executor) {
        super(executor);
        this.retrieverService = retrieverService;
    }

    /**
     * 按意图节点并行检索：将 NodeScore 解析为各自召回深度后委托模板方法执行
     * （独立命名以避免与父类 {@code executeParallelRetrieval(String, List, int)} 泛型擦除后签名冲突）
     */
    public List<RetrievedChunk> retrieveByIntents(String question,
                                                  List<NodeScore> targets,
                                                  int recallBudget,
                                                  QueryEmbeddingContext embeddingContext) {
        List<IntentTask> intentTasks = targets.stream()
                .map(nodeScore -> {
                    String collectionName = nodeScore.getNode().getCollectionName();
                    return new IntentTask(
                            nodeScore,
                            resolveIntentTopK(nodeScore, recallBudget),
                            embeddingContext != null && embeddingContext.isPrepared()
                                    ? embeddingContext.vectorFor(question, collectionName)
                                    : null,
                            embeddingContext == null
                                    || !embeddingContext.isPrepared()
                                    || embeddingContext.usesDefaultModel(collectionName)
                    );
                })
                .toList();
        return super.executeParallelRetrieval(question, intentTasks, recallBudget);
    }

    @Override
    protected List<RetrievedChunk> createRetrievalTask(String question, IntentTask task, int ignoredTopK) {
        NodeScore nodeScore = task.nodeScore();
        IntentNode node = nodeScore.getNode();
        try {
            RetrieveRequest request = RetrieveRequest.builder()
                    .collectionName(node.getCollectionName())
                    .query(question)
                    .topK(task.intentTopK())
                    .build();
            if (task.queryVector() != null) {
                return retrieverService.retrieveByVector(task.queryVector(), request);
            }
            if (task.defaultModel()) {
                return retrieverService.retrieve(request);
            }
            log.error("意图检索缺少指定模型的预计算向量，跳过 Collection: {}", node.getCollectionName());
            return List.of();
        } catch (Exception e) {
            log.error("意图检索失败 - 意图ID: {}, 意图名称: {}, Collection: {}, 错误: {}",
                    node.getId(), node.getName(), node.getCollectionName(), e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    protected String getTargetIdentifier(IntentTask task) {
        NodeScore nodeScore = task.nodeScore();
        IntentNode node = nodeScore.getNode();
        return String.format("意图ID: %s, 意图名称: %s", node.getId(), node.getName());
    }

    @Override
    protected String getStatisticsName() {
        return "意图检索";
    }

    /**
     * 计算单个意图节点检索 TopK
     * 节点级 node.topK 为该意图的绝对召回深度、优先；否则用统一的每通道召回条数 recallBudget
     */
    private int resolveIntentTopK(NodeScore nodeScore, int recallBudget) {
        if (nodeScore != null && nodeScore.getNode() != null) {
            Integer nodeTopK = nodeScore.getNode().getTopK();
            if (nodeTopK != null && nodeTopK > 0) {
                return nodeTopK;
            }
        }
        return recallBudget;
    }
}
