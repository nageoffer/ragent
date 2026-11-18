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

package com.nageoffer.ai.ragent.rag.core.retrieval.channel;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import com.nageoffer.ai.ragent.rag.core.vector.strategy.CollectionParallelRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 向量检索通道
 * <p>
 * 向量模态收敛为一条通道：同模型库共享查询向量，不同模型组分别召回后按位次合并，
 * 拆成两条并列通道会让同一份证据在 RRF 里自我加权
 * <p>
 * 定向作用域下并行补一路「未命中库」：意图判错时正确证据只在未命中库里，
 * 而判错与否事前无从可靠判定（意图分未校准）、事后也测不出（错库内容余弦未必低），
 * 故不做判定、直接给补充路固定候选名额，与定向路一起交下游精排
 */
@Slf4j
@Component
public class VectorSearchChannel implements SearchChannel {

    private final SearchChannelProperties properties;
    private final VectorRetrieverService retrieverService;
    private final CollectionParallelRetriever globalRetriever;
    private final KbCollectionProvider kbCollectionProvider;
    private final Executor retrievalExecutor;

    public VectorSearchChannel(VectorRetrieverService retrieverService,
                               SearchChannelProperties properties,
                               KbCollectionProvider kbCollectionProvider,
                               Executor innerRetrievalExecutor) {
        this.properties = properties;
        this.retrieverService = retrieverService;
        this.globalRetriever = new CollectionParallelRetriever(retrieverService, innerRetrievalExecutor);
        this.kbCollectionProvider = kbCollectionProvider;
        this.retrievalExecutor = innerRetrievalExecutor;
    }

    @Override
    public String getName() {
        return "VectorSearch";
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        // 一条通道一个开关；启用后内部总有一条作用域可走
        return properties.getChannels().getVector().isEnabled();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();

        try {
            RetrievalScope scope = context.getRetrievalScope();
            Map<String, String> collectionModels = kbCollectionProvider.listActiveCollectionModels();
            List<RetrievedChunk> chunks;
            Map<String, Object> metadata;
            if (scope.directed()) {
                chunks = retrieveDirected(context, scope, collectionModels);
                metadata = Map.of("scope", "directed", "topScore", scope.topScore());
            } else {
                chunks = retrieveGlobal(context, scope, collectionModels);
                metadata = Map.of("scope", "global", "topScore", scope.topScore());
            }

            long latency = System.currentTimeMillis() - startTime;
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR)
                    .channelName(getName())
                    .chunks(chunks)
                    .latencyMs(latency)
                    .metadata(metadata)
                    .build();

        } catch (Exception e) {
            log.error("向量检索失败", e);
            return emptyResult(System.currentTimeMillis() - startTime);
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.VECTOR;
    }

    /**
     * 定向作用域：对命中库取主路候选，同时并行补一路未命中库
     * 两路按模型共用查询向量，若所嵌入模型相同，按score排序；嵌入模型不同，按位次排序。
     */
    private List<RetrievedChunk> retrieveDirected(SearchContext context, RetrievalScope scope,
                                                 Map<String, String> collectionModels) {
        String question = context.getMainQuestion();
        int budget = resolveDirectedBudget(scope, context.getBudget());
        ScopeQuota quota = ScopeQuota.split(scope, budget, supplementRatio());
        // 定向库中嵌入模型和关联知识库的映射集合
        Map<String, List<String>> primaryGroups = quota.primary() > 0
                ? groupCollections(scope.targetCollections(), collectionModels) : Map.of();
        // 全局库中嵌入模型和关联知识库的映射集合
        Map<String, List<String>> supplementGroups = quota.supplement() > 0
                ? groupCollections(scope.supplementCollections(), collectionModels) : Map.of();

        Map<String, float[]> vectors = embedModels(question, primaryGroups, supplementGroups);

        // 补充路失败必须只损失自己：它拿到的是兜底名额，而 join() 抛出会让已经取回的定向证据一起被
        // 通道级 catch 丢掉——兜底路把主路带走，鲁棒性方向正好反了
        CompletableFuture<Map<String, List<RetrievedChunk>>> supplementTask = !supplementGroups.isEmpty()
                ? CompletableFuture.<Map<String, List<RetrievedChunk>>>supplyAsync(
                () -> retrieveGroups(question, supplementGroups, vectors, quota.supplement()),
                retrievalExecutor)
                .exceptionally(e -> {
                    log.warn("向量补充路检索失败，仅丢弃补充证据: {}", e.getMessage());
                    return Map.of();
                })
                : CompletableFuture.completedFuture(Map.of());

        Map<String, List<RetrievedChunk>> directed = retrieveGroups(question, primaryGroups, vectors, quota.primary());
        Map<String, List<RetrievedChunk>> supplement = supplementTask.join();

        // 定向库和全局库检索结果的合并逻辑 按照嵌入模型来合并
        Map<String, List<RetrievedChunk>> merged = new TreeMap<>(directed);
        supplement.forEach((model, chunks) -> {
            List<RetrievedChunk> sameModel = new ArrayList<>(merged.getOrDefault(model, List.of()));
            sameModel.addAll(chunks);
            // 相同嵌入模型，按照score排序
            merged.put(model, sortWithinModel(sameModel));
        });
        log.info("向量检索完成（定向），意图 top1={}，主路各模型候选 {}，补充路各模型候选 {}",
                scope.topScore(), groupSizes(directed), groupSizes(supplement));
        // 两路已经提前按照budget截断，此处只做重排，不再抢占或重分配两路名额
        return interleave(merged, budget).stream().map(ModelChunk::chunk).toList();
    }

    /**
     * 定向路的通道产出额度：意图级 node.topK 覆盖每通道默认额度 recallBudget，是绝对深度、可大可小
     * <p>
     * 逐库取数后一次查询只有一个深度，多意图命中时取最大值——取大只放宽召回、多出的候选交给精排收敛，
     * 任何按意图切分配额的方案都是在重造 fan-out。再按候选池上限钳制：超出的部分进不了 Rerank，查了也是空转
     */
    private int resolveDirectedBudget(RetrievalScope scope, RetrievalBudget budget) {
        int depth = scope.intents().stream()
                .mapToInt(nodeScore -> {
                    Integer topK = nodeScore.getNode() == null ? null : nodeScore.getNode().getTopK();
                    return topK != null && topK > 0 ? topK : budget.recallBudget();
                })
                .max()
                .orElse(budget.recallBudget());
        int candidateLimit = budget.candidateLimit();
        return candidateLimit > 0 ? Math.min(depth, candidateLimit) : depth;
    }

    /**
     * 全局作用域：跨全部有效库检索
     * 取数深度与其他通道同源、只受 recallBudget 管——候选池上限是 RRF 之后的闸门而非取数目标
     */
    private List<RetrievedChunk> retrieveGlobal(SearchContext context, RetrievalScope scope,
                                               Map<String, String> collectionModels) {
        if (scope.targetCollections().isEmpty()) {
            log.warn("未找到任何 KB collection，跳过全局检索");
            return List.of();
        }
        String question = context.getMainQuestion();
        int budget = context.getBudget().recallBudget();
        Map<String, List<String>> groups = budget > 0
                ? groupCollections(scope.targetCollections(), collectionModels) : Map.of();
        Map<String, float[]> vectors = embedModels(question, groups, Map.of());
        Map<String, List<RetrievedChunk>> chunks = retrieveGroups(question, groups, vectors, budget);

        log.info("向量检索完成（全局），意图 top1={}，各模型候选 {}", scope.topScore(), groupSizes(chunks));
        return interleave(chunks, budget).stream().map(ModelChunk::chunk).toList();
    }

    /**
     * 根据嵌入模型对知识库分类
     * key: modelName  value: 知识库集合
     */
    private Map<String, List<String>> groupCollections(List<String> collections, Map<String, String> models) {
        Map<String, List<String>> groups = new TreeMap<>();
        for (String collection : new LinkedHashSet<>(collections)) {
            String model = models.get(collection);
            if (StrUtil.isBlank(model)) {
                log.warn("知识库未关联 embedding 模型，跳过该知识库的向量检索: collection={}", collection);
                continue;
            }
            groups.computeIfAbsent(model, ignored -> new ArrayList<>()).add(collection);
        }
        return groups;
    }

    /**
     * 计算不同嵌入模型下的用户问题向量
     */
    private Map<String, float[]> embedModels(String question, Map<String, List<String>> primary,
                                             Map<String, List<String>> supplement) {
        TreeSet<String> models = new TreeSet<>(primary.keySet());
        models.addAll(supplement.keySet());
        Map<String, float[]> vectors = new TreeMap<>();
        for (String model : models) {
            try {
                float[] vector = retrieverService.embedAndNormalize(question, model);
                if (!usableVector(vector)) {
                    throw new IllegalStateException("查询向量为空、全零或包含非有限值");
                }
                vectors.put(model, vector);
            } catch (Exception e) {
                log.warn("生成查询向量失败，跳过该模型对应的知识库，不回退其他模型: model={}", model, e);
            }
        }
        if (!models.isEmpty() && vectors.isEmpty()) {
            log.error("所有 embedding 模型均不可用，向量检索整体跳过: models={}", models);
        }
        return vectors;
    }

    /**
     * 判断向量是否有效
     */
    private boolean usableVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            return false;
        }
        boolean nonZero = false;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                return false;
            }
            nonZero |= value != 0;
        }
        return nonZero;
    }

    /**
     * 批量检索，在不同的向量空间中检索
     */
    private Map<String, List<RetrievedChunk>> retrieveGroups(String question, Map<String, List<String>> groups,
                                                            Map<String, float[]> vectors, int budget) {
        // 不同嵌入模型检索到的chunk
        Map<String, List<RetrievedChunk>> results = new TreeMap<>();
        if (budget <= 0) {
            return results;
        }
        groups.forEach((model, collections) -> {
            float[] vector = vectors.get(model);
            if (vector == null) {
                return;
            }
            try {
                results.put(model, retrieveOver(question, vector, collections, budget));
            } catch (Exception e) {
                log.warn("向量检索失败，仅跳过当前模型组: model={}, collections={}", model, collections, e);
            }
        });
        Map<String, List<RetrievedChunk>> selected = new TreeMap<>();
        // 这里会对results进行截断，按照budget
        for (ModelChunk chunk : interleave(results, budget)) {
            // 对budget范围内的chunk，按照嵌入模型分类
            selected.computeIfAbsent(chunk.model(), ignored -> new ArrayList<>()).add(chunk.chunk());
        }
        return selected;
    }

    /**
     * 双重for循环，按照rank位次，每轮从各嵌入模型组取出一个chunk，超过预算就停止；且分数只在模型组内有意义
     */
    private List<ModelChunk> interleave(Map<String, List<RetrievedChunk>> groups, int budget) {
        List<ModelChunk> selected = new ArrayList<>();
        for (int rank = 0; selected.size() < budget; rank++) {
            boolean added = false;
            for (Map.Entry<String, List<RetrievedChunk>> group : groups.entrySet()) {
                if (rank < group.getValue().size()) {
                    selected.add(new ModelChunk(group.getKey(), group.getValue().get(rank)));
                    added = true;
                    if (selected.size() == budget) {
                        return selected;
                    }
                }
            }
            if (!added) {
                break;
            }
        }
        return selected;
    }

    private List<RetrievedChunk> sortWithinModel(List<RetrievedChunk> chunks) {
        return chunks.stream().sorted(RetrievedChunk.BY_SCORE_DESC
                        .thenComparing(RetrievedChunk::getCollectionName, Comparator.nullsLast(String::compareTo))
                        .thenComparing(RetrievedChunk::getId, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private Map<String, Integer> groupSizes(Map<String, List<RetrievedChunk>> groups) {
        Map<String, Integer> sizes = new TreeMap<>();
        groups.forEach((model, chunks) -> sizes.put(model, chunks.size()));
        return sizes;
    }

    private record ModelChunk(String model, RetrievedChunk chunk) {
    }

    /**
     * 在同一模型的 collection 范围内取候选：按相关性降序、条数不超过 budget
     * <p>
     * 后端支持跨库过滤（PG / Milvus 共享库）时一次查询带总预算即可；否则逐库并行 fan-out 兜底，
     * 每库各取 budget 再统一截断——多取是为了拿到真正的全局前 budget 条（哪个库有好料事前不知道），
     * 但截断不能省：省掉它 budget 就从「总量」悄悄变成「每库上限」，补充路名额被放大成 库数 × 名额
     * <p>
     * 排序在截断之前，且后端返回序不能直接信：PG 开了 {@code hnsw.iterative_scan=relaxed_order}，
     * pgvector 在该模式下允许轻微乱序且规划器不补 Sort 节点，先排后截才是取全局最优的前 budget 条
     */
    private List<RetrievedChunk> retrieveOver(String question, float[] queryVector, List<String> collections, int budget) {
        if (collections.isEmpty() || budget <= 0) {
            return List.of();
        }
        List<RetrievedChunk> chunks = retrieverService.supportsGlobalRetrieval()
                ? retrieverService.retrieveByVector(queryVector, RetrieveRequest.builder()
                .collectionNames(collections)
                .query(question)
                .topK(budget)
                .build())
                : globalRetriever.executeParallelRetrieval(question, collections, budget, queryVector);
        return ScopeQuota.cap(sortWithinModel(chunks), budget);
    }

    private double supplementRatio() {
        return properties.getScope().getSupplementRatio();
    }
}
