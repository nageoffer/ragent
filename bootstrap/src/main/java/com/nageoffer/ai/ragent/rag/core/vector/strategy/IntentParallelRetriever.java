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
import com.nageoffer.ai.ragent.rag.core.retrieval.IntentChunkAttribution;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 意图并行检索器
 * 继承模板类，实现意图特定的检索逻辑
 */
@Slf4j
public class IntentParallelRetriever extends AbstractParallelRetriever<IntentParallelRetriever.IntentTask> {

    public record IntentTask(NodeScore nodeScore, int intentTopK) {
    }

    public record IntentRetrievalResult(List<RetrievedChunk> chunks,
                                        IntentChunkAttribution intentAttribution) {
    }

    public IntentParallelRetriever(VectorRetrieverService retrieverService,
                                   Executor executor) {
        super(retrieverService, executor);
    }

    /**
     * 按意图节点并行检索：将 NodeScore 解析为各自召回深度后委托模板方法执行
     * （独立命名以避免与父类 {@code executeParallelRetrieval(String, List, int)} 泛型擦除后签名冲突）
     */
    public IntentRetrievalResult retrieveByIntents(String question,
                                                   List<NodeScore> targets,
                                                   int recallBudget) {
        List<IntentTask> intentTasks = targets.stream()
                .map(nodeScore -> new IntentTask(
                        nodeScore,
                        resolveIntentTopK(nodeScore, recallBudget)
                ))
                .toList();
        ParallelRetrievalResult<IntentTask> result =
                super.executeParallelRetrievalDetailed(question, intentTasks, recallBudget);

        Map<String, List<RetrievedChunk>> chunksByIntent = new LinkedHashMap<>();
        for (TargetRetrievalResult<IntentTask> targetResult : result.targetResults()) {
            IntentNode node = targetResult.target().nodeScore().getNode();
            if (node == null || node.getId() == null || node.getId().isBlank() || targetResult.chunks().isEmpty()) {
                continue;
            }
            chunksByIntent.computeIfAbsent(node.getId(), ignored -> new ArrayList<>())
                    .addAll(targetResult.chunks());
        }
        return new IntentRetrievalResult(
                result.chunks(),
                IntentChunkAttribution.fromIntentChunks(chunksByIntent)
        );
    }

    @Override
    protected List<RetrievedChunk> createRetrievalTask(String question, IntentTask task, float[] queryVector, int ignoredTopK) {
        NodeScore nodeScore = task.nodeScore();
        IntentNode node = nodeScore.getNode();
        List<String> collectionNames = node.getEffectiveCollectionNames();
        if (collectionNames.isEmpty()) {
            return List.of();
        }
        try {
            return retrieverService.retrieveByVector(
                    queryVector,
                    RetrieveRequest.builder()
                            .collectionNames(collectionNames)
                            .query(question)
                            .topK(task.intentTopK())
                            .build()
            );
        } catch (Exception e) {
            log.error("意图检索失败 - 意图ID: {}, 意图名称: {}, Collections: {}, 错误: {}",
                    node.getId(), node.getName(), collectionNames, e.getMessage(), e);
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
