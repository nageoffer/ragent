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
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

@Slf4j
public class IntentParallelRetriever extends AbstractParallelRetriever<IntentParallelRetriever.IntentTask> {

    public record IntentTask(NodeScore nodeScore, int intentTopK, Set<String> intentIds) {

        public IntentTask {
            intentIds = Set.copyOf(intentIds);
        }
    }

    public record IntentRetrievalResult(List<RetrievedChunk> chunks,
                                        Map<String, Set<String>> intentIdsByChunkKey) {
    }

    public IntentParallelRetriever(VectorRetrieverService retrieverService,
                                   Executor executor) {
        super(retrieverService, executor);
    }

    /**
     * 按意图节点并行检索；独立命名用于避免与父类
     * {@code executeParallelRetrieval(String, List, int)} 泛型擦除后的签名冲突
     */
    public IntentRetrievalResult retrieveByIntents(String question,
                                                   List<NodeScore> targets,
                                                   int recallBudget) {
        ParallelRetrievalResult<IntentTask> result =
                super.executeParallelRetrievalDetailed(question, buildTasks(targets, recallBudget), recallBudget);
        return toIntentRetrievalResult(result);
    }

    /**
     * 按意图节点并行检索，复用调用方已算好的查询向量
     */
    public IntentRetrievalResult retrieveByIntents(String question,
                                                   List<NodeScore> targets,
                                                   int recallBudget,
                                                   float[] queryVector) {
        ParallelRetrievalResult<IntentTask> result = super.executeParallelRetrievalDetailed(
                question, buildTasks(targets, recallBudget), recallBudget, queryVector);
        return toIntentRetrievalResult(result);
    }

    private IntentRetrievalResult toIntentRetrievalResult(ParallelRetrievalResult<IntentTask> result) {
        Map<String, Set<String>> intentIdsByChunkKey = new LinkedHashMap<>();
        for (TargetRetrievalResult<IntentTask> targetResult : result.targetResults()) {
            Set<String> intentIds = targetResult.target().intentIds();
            if (intentIds.isEmpty()) {
                continue;
            }
            for (RetrievedChunk chunk : targetResult.chunks()) {
                intentIdsByChunkKey.computeIfAbsent(RetrievedChunkKey.of(chunk), ignored -> new LinkedHashSet<>())
                        .addAll(intentIds);
            }
        }
        return new IntentRetrievalResult(result.chunks(), intentIdsByChunkKey);
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
     * 计算命中意图的总召回深度
     * <p>
     * 定向路是每意图各取一份、并行 fan-out，故通道产能是各意图深度之和而非单个 recallBudget；
     * 调用方据此切分主路 / 补充路名额，避免拿「每意图深度」当「通道总额度」用
     */
    public int resolveTotalDepth(List<NodeScore> targets, int recallBudget) {
        return buildTasks(targets, recallBudget).stream()
                .mapToInt(IntentTask::intentTopK)
                .sum();
    }

    /**
     * 按 collection 集合和召回深度合并等价查询，并保留全部意图归属
     * <p>
     * 问题与查询向量在单次调用中恒定；重复查询会重复计分并虚增通道产能；多 collection 节点仍是一次并集查询，
     * 总共只返回 topK 条；{@link #resolveTotalDepth} 与实际检索共用本方法，使通道产能与真实查询数一致
     */
    private List<IntentTask> buildTasks(List<NodeScore> targets, int recallBudget) {
        record QueryIdentity(Set<String> collections, int topK) {
        }
        Map<QueryIdentity, IntentTask> tasks = new LinkedHashMap<>();
        for (NodeScore nodeScore : targets) {
            int intentTopK = resolveIntentTopK(nodeScore, recallBudget);
            Set<String> collections = Set.copyOf(collectionsOf(nodeScore));
            String intentId = intentIdOf(nodeScore);
            IntentTask task = new IntentTask(
                    nodeScore, intentTopK, intentId == null ? Set.of() : Set.of(intentId));
            tasks.merge(new QueryIdentity(collections, intentTopK), task, (existing, duplicate) -> {
                Set<String> intentIds = new LinkedHashSet<>(existing.intentIds());
                intentIds.addAll(duplicate.intentIds());
                return new IntentTask(existing.nodeScore(), existing.intentTopK(), intentIds);
            });
        }
        return List.copyOf(tasks.values());
    }

    private static String intentIdOf(NodeScore nodeScore) {
        if (nodeScore == null || nodeScore.getNode() == null) {
            return null;
        }
        String intentId = nodeScore.getNode().getId();
        return intentId == null || intentId.isBlank() ? null : intentId;
    }

    private static List<String> collectionsOf(NodeScore nodeScore) {
        if (nodeScore == null || nodeScore.getNode() == null) {
            return List.of();
        }
        return nodeScore.getNode().getEffectiveCollectionNames();
    }

    /**
     * 计算单个意图节点检索 TopK
     * 节点级 node.topK 为该意图的绝对召回深度、优先；否则使用统一的每通道召回条数 recallBudget
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
