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

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import com.nageoffer.ai.ragent.rag.core.vector.strategy.CollectionParallelRetriever;
import com.nageoffer.ai.ragent.rag.core.vector.strategy.IntentParallelRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final IntentParallelRetriever intentRetriever;
    private final CollectionParallelRetriever globalRetriever;
    private final Executor retrievalExecutor;

    public VectorSearchChannel(VectorRetrieverService retrieverService,
                               SearchChannelProperties properties,
                               Executor innerRetrievalExecutor) {
        this.properties = properties;
        this.retrieverService = retrieverService;
        this.intentRetriever = new IntentParallelRetriever(retrieverService, innerRetrievalExecutor);
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
            List<RetrievedChunk> chunks;
            Map<String, Set<String>> intentIdsByChunkKey = Map.of();
            Map<String, Object> metadata;
            if (scope.directed()) {
                DirectedRetrieval retrieval = retrieveDirected(context, scope);
                chunks = retrieval.chunks();
                intentIdsByChunkKey = retrieval.intentIdsByChunkKey();
                metadata = Map.of("scope", "directed", "topScore", scope.topScore());
            } else {
                chunks = retrieveGlobal(context, scope);
                metadata = Map.of("scope", "global", "topScore", scope.topScore());
            }

            long latency = System.currentTimeMillis() - startTime;
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR)
                    .channelName(getName())
                    .chunks(chunks)
                    .intentIdsByChunkKey(intentIdsByChunkKey)
                    .latencyMs(latency)
                    .metadata(metadata)
                    .build();

        } catch (Exception e) {
            log.error("向量检索失败", e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR)
                    .channelName(getName())
                    .chunks(List.of())
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.VECTOR;
    }

    /**
     * 定向作用域：命中意图并行检索，同时并行补一路未命中库
     * 两路共用一次 embedding、同池并发，补充路不增加通道延迟
     */
    private DirectedRetrieval retrieveDirected(SearchContext context, RetrievalScope scope) {
        RetrievalBudget budget = context.getBudget();
        String question = context.getMainQuestion();
        float[] queryVector = retrieverService.embedAndNormalize(question);

        // 定向路是每意图各取一份，通道产能为各意图深度之和；与候选池上限取小值作切分基数，
        // 既让补充路名额真正从主路划出（而非净增），又不产出下游必被截断的空转候选
        int candidateLimit = budget.candidateLimit();
        int capacity = intentRetriever.resolveTotalDepth(scope.intents(), budget.recallBudget());
        int basis = candidateLimit > 0 ? Math.min(candidateLimit, capacity) : capacity;
        ScopeQuota quota = ScopeQuota.split(scope, basis, supplementRatio());

        // 补充路失败必须只损失自己：它拿到的是兜底名额，而 join() 抛出会让已经取回的定向证据一起被
        // 通道级 catch 丢掉——兜底路把主路带走，鲁棒性方向正好反了
        CompletableFuture<List<RetrievedChunk>> supplementTask = quota.supplement() > 0
                ? CompletableFuture.<List<RetrievedChunk>>supplyAsync(
                () -> retrieveOver(question, queryVector, scope.supplementCollections(), quota.supplement()),
                retrievalExecutor)
                .exceptionally(e -> {
                    log.warn("向量补充路检索失败，仅丢弃补充证据: {}", e.getMessage());
                    return List.of();
                })
                : CompletableFuture.completedFuture(List.of());

        IntentParallelRetriever.IntentRetrievalResult intentResult = intentRetriever.retrieveByIntents(
                question, scope.intents(), budget.recallBudget(), queryVector);
        List<RetrievedChunk> directed = distinct(intentResult.chunks());
        List<RetrievedChunk> supplement = supplementTask.join();
        List<RetrievedChunk> capped = ScopeQuota.cap(directed, quota.primary());

        // 「召回 N 取前 M」而非「M/N」：主路是先按各意图取满、再跨意图统一排序截断，
        // 两个数字不等是设计使然（拿到更优的前 M 条），写成分数形式易被误读为召回不足
        log.info("向量检索完成（定向），意图 top1={}，定向召回 {} 取前 {} 条（最高余弦 {}），补充 {} 库 {} 条（最高余弦 {}）",
                scope.topScore(), directed.size(), capped.size(), topScoreOf(directed),
                scope.supplementCollections().size(), supplement.size(), topScoreOf(supplement));
        Map<String, Set<String>> attribution = retainAttribution(
                intentResult.intentIdsByChunkKey(), capped);
        return new DirectedRetrieval(merge(capped, supplement), attribution);
    }

    /**
     * 全局作用域：跨全部有效库检索
     */
    private List<RetrievedChunk> retrieveGlobal(SearchContext context, RetrievalScope scope) {
        if (scope.targetCollections().isEmpty()) {
            log.warn("未找到任何 KB collection，跳过全局检索");
            return List.of();
        }
        String question = context.getMainQuestion();
        List<RetrievedChunk> chunks = retrieveOver(question, retrieverService.embedAndNormalize(question),
                scope.targetCollections(), globalFetchSize(context.getBudget()));

        log.info("向量检索完成（全局），意图 top1={}，{} 库 {} 条（最高余弦 {}）",
                scope.topScore(), scope.targetCollections().size(), chunks.size(), topScoreOf(chunks));
        return chunks;
    }

    /**
     * 在给定 collection 范围内取一路候选：按相关性降序、条数不超过 budget
     * <p>
     * 后端支持跨库过滤（PG / Milvus 共享库）时一次查询带总预算即可；否则逐库并行 fan-out 兜底，
     * 每库各取 budget 再统一截断——多取是为了拿到真正的全局前 budget 条（哪个库有好料事前不知道），
     * 但截断不能省：省掉它 budget 就从「总量」悄悄变成「每库上限」，补充路名额被放大成 库数 × 名额
     * <p>
     * 排序在截断之前，两者都不能省：先排后截才是取全局最优的前 budget 条
     */
    private List<RetrievedChunk> retrieveOver(String question, float[] queryVector, List<String> collections, int budget) {
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
        return ScopeQuota.cap(sortedByScore(chunks), budget);
    }

    /**
     * 按相关性降序，兑现「通道出口全局有序」这一下游 RRF 依赖的不变式
     * <p>
     * 后端返回序不能直接信：PG 开了 {@code hnsw.iterative_scan=relaxed_order}，pgvector 在该模式下允许
     * 轻微乱序且规划器不补 Sort 节点。其余取数路径（fan-out 归并、两路 merge）都排过，唯独这条曾原样返回
     */
    private static List<RetrievedChunk> sortedByScore(List<RetrievedChunk> chunks) {
        if (chunks.size() < 2) {
            return chunks;
        }
        List<RetrievedChunk> sorted = new ArrayList<>(chunks);
        sorted.sort(BY_SCORE_DESC);
        return sorted;
    }

    /**
     * 全局路一次取数的条数上限
     * <p>
     * 候选池上限 <=0 是融合阶段「不截断」的语义，原样拿来当取数上限就成了 LIMIT 0、一条都召不回，
     * 与配置意图正好相反，故先回退到通道召回额度，保证传给后端的上限恒为正
     */
    private int globalFetchSize(RetrievalBudget budget) {
        int candidateLimit = budget.candidateLimit();
        return properties.getChannels().getVector()
                .resolveCandidateBudget(candidateLimit > 0 ? candidateLimit : budget.recallBudget());
    }

    private double supplementRatio() {
        return properties.getScope().getSupplementRatio();
    }

    /**
     * 兑现「同一 chunk 在通道原始列表里只占一个名次」——下游 RRF 按名次累加，占两个名次即分数翻倍
     * <p>
     * 三条 KB 通道里只有定向路需要：意图节点的 collection 集合可以部分重叠（如 A=[财务,人事]、B=[财务]），
     * 两次扇出的范围不同、结果却交叠，扇出层按查询身份去重拦不住这种情况。
     * 补充路与主路的库互为差集、关键词与图谱的两份也各自不相交，故无需同等处理
     * <p>
     * 入参已按分数降序，保留首次出现即保留名次最高的那份
     */
    private static List<RetrievedChunk> distinct(List<RetrievedChunk> chunks) {
        Map<String, RetrievedChunk> unique = new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            unique.putIfAbsent(RetrievedChunkKey.of(chunk), chunk);
        }
        return unique.size() == chunks.size() ? chunks : List.copyOf(unique.values());
    }

    private static Map<String, Set<String>> retainAttribution(
            Map<String, Set<String>> attribution,
            List<RetrievedChunk> retainedChunks) {
        Map<String, Set<String>> retained = new LinkedHashMap<>();
        for (RetrievedChunk chunk : retainedChunks) {
            String key = RetrievedChunkKey.of(chunk);
            Set<String> intentIds = attribution.get(key);
            if (intentIds != null && !intentIds.isEmpty()) {
                retained.put(key, intentIds);
            }
        }
        return retained;
    }

    /**
     * 合并两路候选并按相关性降序，兑现「通道出口全局有序」这一下游 RRF 依赖的不变式
     */
    private static List<RetrievedChunk> merge(List<RetrievedChunk> directed, List<RetrievedChunk> supplement) {
        if (supplement.isEmpty()) {
            return directed;
        }
        List<RetrievedChunk> merged = new ArrayList<>(directed.size() + supplement.size());
        merged.addAll(directed);
        merged.addAll(supplement);
        merged.sort(BY_SCORE_DESC);
        return merged;
    }

    /**
     * 相关性降序，缺分沉底
     */
    private static final Comparator<RetrievedChunk> BY_SCORE_DESC =
            (a, b) -> Float.compare(scoreOf(b), scoreOf(a));

    /**
     * 取一路候选的最高余弦，供阈值校准观测
     */
    private static float topScoreOf(List<RetrievedChunk> chunks) {
        return chunks.isEmpty() ? 0F : scoreOf(chunks.get(0));
    }

    private static float scoreOf(RetrievedChunk chunk) {
        return chunk.getScore() == null ? Float.NEGATIVE_INFINITY : chunk.getScore();
    }

    private record DirectedRetrieval(List<RetrievedChunk> chunks,
                                     Map<String, Set<String>> intentIdsByChunkKey) {
    }
}
