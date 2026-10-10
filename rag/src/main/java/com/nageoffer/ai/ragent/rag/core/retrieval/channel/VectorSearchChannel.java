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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 向量检索通道
 * <p>
 * 向量模态收敛为一条通道：定向与全局是同一 embedding 查询、只是 collection 范围不同，
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
        this.kbCollectionProvider = kbCollectionProvider;
        this.globalRetriever = new CollectionParallelRetriever(retrieverService, innerRetrievalExecutor);
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
            Map<String, String> collectionModels = safeCollectionModels();
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
     * 定向与全局同一取数原语、只差库集合；两路共用同一批模型向量（按库并集各嵌一次），同池并发，补充路不增加通道延迟
     */
    private List<RetrievedChunk> retrieveDirected(SearchContext context, RetrievalScope scope,
                                                  Map<String, String> collectionModels) {
        String question = context.getMainQuestion();
        ScopeQuota quota = ScopeQuota.split(scope, resolveDirectedBudget(scope, context.getBudget()), supplementRatio());
        // 定向与补充两路的库并集：每个模型只生成一次 query 向量，两路共用，避免同模型重复嵌入
        Map<String, float[]> vectorsByModel = embedPerModel(question,
                merge(scope.targetCollections(), scope.supplementCollections()), collectionModels);

        // 补充路失败必须只损失自己：它拿到的是兜底名额，而 join() 抛出会让已经取回的定向证据一起被
        // 通道级 catch 丢掉——兜底路把主路带走，鲁棒性方向正好反了
        CompletableFuture<List<RetrievedChunk>> supplementTask = quota.supplement() > 0
                ? CompletableFuture.<List<RetrievedChunk>>supplyAsync(
                () -> retrieveGroups(question, scope.supplementCollections(), quota.supplement(), collectionModels, vectorsByModel),
                retrievalExecutor)
                .exceptionally(e -> {
                    log.warn("向量补充路检索失败，仅丢弃补充证据: {}", e.getMessage());
                    return List.of();
                })
                : CompletableFuture.completedFuture(List.of());

        List<RetrievedChunk> directed = retrieveGroups(question, scope.targetCollections(), quota.primary(), collectionModels, vectorsByModel);
        List<RetrievedChunk> supplement = supplementTask.join();

        log.info("向量检索完成（定向），意图 top1={}，命中 {} 库 {} 条（最高余弦 {}），补充 {} 库 {} 条（最高余弦 {}）",
                scope.topScore(), scope.targetCollections().size(), directed.size(), ChunkRanking.topScoreOf(directed),
                scope.supplementCollections().size(), supplement.size(), ChunkRanking.topScoreOf(supplement));
        return ChunkRanking.mergeByScore(directed, supplement);
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
        Map<String, float[]> vectorsByModel = embedPerModel(question, scope.targetCollections(), collectionModels);
        List<RetrievedChunk> chunks = retrieveGroups(question, scope.targetCollections(),
                context.getBudget().recallBudget(), collectionModels, vectorsByModel);

        log.info("向量检索完成（全局），意图 top1={}，{} 库 {} 条（最高余弦 {}）",
                scope.topScore(), scope.targetCollections().size(), chunks.size(), ChunkRanking.topScoreOf(chunks));
        return chunks;
    }

    /**
     * 在给定 collection 范围内取一路候选：按相关性降序、条数不超过 budget
     * <p>
     * collection 按各自绑定的 embedding 模型分组（issue #159）：query 向量必须与库向量同模型，
     * 否则二者不在同一语义空间，相似度计算无意义。每组用组内模型的向量单独取数（向量已由
     * {@link #embedPerModel} 按模型各算一份），组内余弦可比较；跨组分数不具可比性，
     * 仅按位次合并，最终统一截断到 budget 总量
     */
    private List<RetrievedChunk> retrieveGroups(String question, List<String> collections, int budget,
                                                Map<String, String> collectionModels,
                                                Map<String, float[]> vectorsByModel) {
        if (collections.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> merged = new ArrayList<>();
        for (Map.Entry<String, List<String>> group : groupByEmbeddingModel(collections, collectionModels).entrySet()) {
            merged.addAll(retrieveOverWithVector(question, vectorsByModel.get(group.getKey()), group.getValue(), budget));
        }
        return ScopeQuota.cap(ChunkRanking.sortedByScore(merged), budget);
    }

    /**
     * 按库集合中出现的不同 embedding 模型，每个模型生成一份 query 向量
     * <p>
     * 同一模型在多个库（含定向与补充两路）间只嵌入一次、共享同一向量；
     * 未绑定/空白模型的库归入 ""（走默认优先级链）
     */
    private Map<String, float[]> embedPerModel(String question, List<String> collections,
                                               Map<String, String> collectionModels) {
        Map<String, float[]> vectorsByModel = new HashMap<>();
        for (String modelKey : distinctModelKeys(collections, collectionModels)) {
            vectorsByModel.put(modelKey, embedForModel(question, modelKey));
        }
        return vectorsByModel;
    }

    /**
     * 按知识库绑定的 embedding 模型分组：key 为模型 id，未绑定/空白模型的库归入 ""（走默认优先级链）
     * <p>
     * 组内 query 与库向量同模型、同语义空间；跨组绝不混用向量，避免不同语义空间算相似度
     */
    private Map<String, List<String>> groupByEmbeddingModel(List<String> collections,
                                                            Map<String, String> collectionModels) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String collection : collections) {
            String modelId = collectionModels == null ? null : collectionModels.get(collection);
            groups.computeIfAbsent(StrUtil.isBlank(modelId) ? "" : modelId, key -> new ArrayList<>()).add(collection);
        }
        return groups;
    }

    private List<String> distinctModelKeys(List<String> collections, Map<String, String> collectionModels) {
        List<String> modelKeys = new ArrayList<>();
        for (String collection : collections) {
            String modelId = collectionModels == null ? null : collectionModels.get(collection);
            String modelKey = StrUtil.isBlank(modelId) ? "" : modelId;
            if (!modelKeys.contains(modelKey)) {
                modelKeys.add(modelKey);
            }
        }
        return modelKeys;
    }

    private List<String> merge(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<>(first);
        for (String item : second) {
            if (!merged.contains(item)) {
                merged.add(item);
            }
        }
        return merged;
    }

    /**
     * 生成查询向量：优先使用知识库绑定模型；绑定模型不可用时回退默认优先级链并告警，
     * 避免单个模型故障把整条向量通道（含主路证据）一起拖垮
     */
    private float[] embedForModel(String question, String modelId) {
        if (StrUtil.isBlank(modelId)) {
            return retrieverService.embedAndNormalize(question);
        }
        try {
            return retrieverService.embedAndNormalize(question, modelId);
        } catch (Exception e) {
            log.warn("知识库绑定模型 {} 生成查询向量失败，回退默认 embedding 模型: {}", modelId, e.getMessage());
            return retrieverService.embedAndNormalize(question);
        }
    }

    /**
     * 单模型组取数：后端支持跨库过滤（PG / Milvus 共享库）时一次查询带总预算即可；否则逐库并行 fan-out 兜底，
     * 每库各取 budget 再统一截断——多取是为了拿到真正的全局前 budget 条（哪个库有好料事前不知道），
     * 但截断不能省：省掉它 budget 就从「总量」悄悄变成「每库上限」，补充路名额被放大成 库数 × 名额
     * <p>
     * 排序在截断之前，且后端返回序不能直接信：PG 开了 {@code hnsw.iterative_scan=relaxed_order}，
     * pgvector 在该模式下允许轻微乱序且规划器不补 Sort 节点，先排后截才是取全局最优的前 budget 条
     */
    private List<RetrievedChunk> retrieveOverWithVector(String question, float[] queryVector, List<String> collections, int budget) {
        if (collections.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> chunks = retrieverService.supportsGlobalRetrieval()
                ? retrieverService.retrieveByVector(queryVector, RetrieveRequest.builder()
                .collectionNames(collections)
                .query(question)
                .topK(budget)
                .build())
                : globalRetriever.executeParallelRetrieval(question, collections, budget, queryVector);
        return ScopeQuota.cap(ChunkRanking.sortedByScore(chunks), budget);
    }

    /**
     * 读取 collection → embedding 模型映射；失败时降级为空映射（全部走默认模型，等价于修复前行为）
     */
    private Map<String, String> safeCollectionModels() {
        try {
            return kbCollectionProvider.listActiveCollectionModels();
        } catch (Exception e) {
            log.warn("获取知识库 embedding 模型映射失败，本次检索回退默认 embedding 模型: {}", e.getMessage());
            return Map.of();
        }
    }

    private double supplementRatio() {
        return properties.getScope().getSupplementRatio();
    }
}
