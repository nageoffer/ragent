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

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import com.nageoffer.ai.ragent.rag.core.vector.QueryEmbeddingBatcher;
import com.nageoffer.ai.ragent.rag.core.vector.QueryEmbeddingContext;
import com.nageoffer.ai.ragent.rag.core.vector.SearchTask;
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
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * 向量检索通道
 * <p>
 * 向量模态收敛为一条通道：意图定向与全局本质是同一 embedding 查询、只是 collection 作用域不同
 * （意图定向 = 命中库；全局 = 全库，是前者的超集）。因此不再拆成两条并列通道各跑一次、再对相关证据做自我 RRF 融合，
 * 而是按 KB 意图置信度在通道内二选一作用域，只做单次向量检索：
 * 有足够置信的 KB 意图 → 收窄到命中库（意图定向）；无 / 低置信 → 退化为全库检索（全局兜底）
 */
@Slf4j
@Component
public class VectorSearchChannel implements SearchChannel {

    private static final String PLANNED_COLLECTIONS_KEY = VectorSearchChannel.class.getName() + ".collections";

    private final SearchChannelProperties properties;
    private final KbCollectionProvider kbCollectionProvider;
    private final QueryEmbeddingBatcher queryEmbeddingBatcher;
    private final VectorRetrieverService retrieverService;
    private final IntentParallelRetriever intentRetriever;
    private final CollectionParallelRetriever globalRetriever;

    public VectorSearchChannel(VectorRetrieverService retrieverService,
                               SearchChannelProperties properties,
                               KbCollectionProvider kbCollectionProvider,
                               QueryEmbeddingBatcher queryEmbeddingBatcher,
                               Executor innerRetrievalExecutor) {
        this.properties = properties;
        this.kbCollectionProvider = kbCollectionProvider;
        this.queryEmbeddingBatcher = queryEmbeddingBatcher;
        this.retrieverService = retrieverService;
        this.intentRetriever = new IntentParallelRetriever(retrieverService, innerRetrievalExecutor);
        this.globalRetriever = new CollectionParallelRetriever(retrieverService, innerRetrievalExecutor);
    }

    @Override
    public String getName() {
        return "VectorSearch";
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        // 一条通道一个开关；启用后内部总有一条作用域可走（意图定向或全局兜底）
        return properties.getChannels().getVector().isEnabled();
    }

    @Override
    public void prepare(List<SearchContext> contexts) {
        List<SearchTask> scopedTasks = new ArrayList<>();
        List<String> globalQuestions = new ArrayList<>();
        Map<SearchContext, Boolean> globalScopes = new java.util.IdentityHashMap<>();

        for (SearchContext context : contexts) {
            List<NodeScore> kbIntents = extractKbIntents(context);
            boolean global = !shouldNarrowToIntent(kbIntents);
            globalScopes.put(context, global);
            if (global) {
                globalQuestions.add(context.getMainQuestion());
                continue;
            }
            kbIntents.stream()
                    .map(NodeScore::getNode)
                    .filter(Objects::nonNull)
                    .map(node -> node.getCollectionName())
                    .filter(Objects::nonNull)
                    .distinct()
                    .forEach(collection -> scopedTasks.add(new SearchTask(context.getMainQuestion(), collection)));
        }

        QueryEmbeddingContext embeddingContext = queryEmbeddingBatcher.prepare(scopedTasks, globalQuestions);
        for (SearchContext context : contexts) {
            context.getMetadata().put(QueryEmbeddingContext.METADATA_KEY, embeddingContext);
            if (Boolean.TRUE.equals(globalScopes.get(context)) && embeddingContext.isPrepared()) {
                context.getMetadata().put(
                        PLANNED_COLLECTIONS_KEY,
                        embeddingContext.collectionsFor(context.getMainQuestion())
                );
            }
        }
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();

        try {
            List<NodeScore> kbIntents = extractKbIntents(context);

            List<RetrievedChunk> chunks;
            Map<String, Object> metadata;
            if (shouldNarrowToIntent(kbIntents)) {
                chunks = retrieveByIntent(context, kbIntents);
                metadata = Map.of("scope", "intent", "intentCount", kbIntents.size());
            } else {
                chunks = retrieveGlobal(context);
                metadata = Map.of("scope", "global");
            }

            long latency = System.currentTimeMillis() - startTime;
            log.info("向量检索完成（作用域：{}），检索到 {} 个 Chunk，耗时 {}ms",
                    metadata.get("scope"), chunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR)
                    .channelName(getName())
                    .chunks(chunks)
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
     * 提取达到最低分的 KB 意图，作为「是否收窄作用域」的判定依据
     */
    private List<NodeScore> extractKbIntents(SearchContext context) {
        if (CollUtil.isEmpty(context.getIntents())) {
            return List.of();
        }
        double minScore = properties.getChannels().getVector().getIntentDirected().getMinIntentScore();
        List<NodeScore> allScores = context.getIntents().stream()
                .flatMap(si -> si.nodeScores().stream())
                .toList();
        return NodeScoreFilters.kb(allScores, minScore);
    }

    /**
     * 是否收窄到意图作用域：有 KB 意图且置信度足够高
     * 「要不要只查这几个库」只由 KB 意图置信度决定，与非 KB 意图（如 MCP 工具）无关
     */
    private boolean shouldNarrowToIntent(List<NodeScore> kbIntents) {
        if (CollUtil.isEmpty(kbIntents)) {
            log.info("未识别出 KB 意图，向量检索走全局作用域");
            return false;
        }

        SearchChannelProperties.Global global = properties.getChannels().getVector().getGlobal();
        double maxScore = kbIntents.stream()
                .mapToDouble(NodeScore::getScore)
                .max()
                .orElse(0.0);

        if (maxScore < global.getConfidenceThreshold()) {
            log.info("KB 意图置信度过低（{}），向量检索走全局作用域", maxScore);
            return false;
        }

        if (kbIntents.size() == 1 && maxScore < global.getSingleIntentSupplementThreshold()) {
            log.info("单一中等置信度 KB 意图（{}），向量检索走全局作用域兜底", maxScore);
            return false;
        }

        return true;
    }

    /**
     * 意图作用域：并行检索命中库（每库按 recallBudget 召回，node.topK 可覆盖为该意图绝对深度）
     */
    private List<RetrievedChunk> retrieveByIntent(SearchContext context, List<NodeScore> kbIntents) {
        log.info("执行向量检索（意图作用域），命中 {} 个 KB 意图，问题：{}", kbIntents.size(), context.getMainQuestion());
        return intentRetriever.retrieveByIntents(
                context.getMainQuestion(),
                kbIntents,
                context.getBudget().recallBudget(),
                embeddingContext(context)
        );
    }

    /**
     * 全局作用域：跨全部有效库检索（PG 单条 SQL 带总预算 / 其他后端逐库并行 fan-out 兜底）
     */
    private List<RetrievedChunk> retrieveGlobal(SearchContext context) {
        log.info("执行向量检索（全局作用域），问题：{}", context.getMainQuestion());

        QueryEmbeddingContext embeddingContext = embeddingContext(context);
        List<String> collections = plannedCollections(context);
        if (collections.isEmpty() && !embeddingContext.isPrepared()) {
            collections = kbCollectionProvider.listActiveCollections();
        }
        if (collections.isEmpty()) {
            log.warn("未找到任何 KB collection，跳过全局检索");
            return List.of();
        }

        SearchChannelProperties.Global config = properties.getChannels().getVector().getGlobal();
        if (retrieverService.supportsGlobalRetrieval()) {
            int budget = config.resolveCandidateBudget(context.getBudget().candidateLimit());
            if (!embeddingContext.isPrepared()) {
                return retrieverService.retrieveGlobal(context.getMainQuestion(), collections, budget);
            }
            return retrieveGlobalByModel(context.getMainQuestion(), collections, budget, embeddingContext);
        }
        // 后端不支持单次全局检索时：退化为逐库并行 fan-out 兜底，每库取候选预算，合并后交下游截断
        int perCollectionBudget = config.resolveCandidateBudget(context.getBudget().candidateLimit());
        return globalRetriever.retrieveByCollections(
                context.getMainQuestion(),
                collections,
                perCollectionBudget,
                embeddingContext
        );
    }

    private List<RetrievedChunk> retrieveGlobalByModel(String question,
                                                       List<String> collections,
                                                       int budget,
                                                       QueryEmbeddingContext embeddingContext) {
        Map<String, List<String>> collectionsByModel = new LinkedHashMap<>();
        for (String collection : collections) {
            collectionsByModel.computeIfAbsent(
                    embeddingContext.modelFor(collection),
                    ignored -> new ArrayList<>()
            ).add(collection);
        }

        List<RetrievedChunk> chunks = new ArrayList<>();
        collectionsByModel.forEach((model, modelCollections) -> {
            float[] vector = embeddingContext.vectorFor(question, modelCollections.get(0));
            if (vector != null) {
                chunks.addAll(retrieverService.retrieveGlobalByVector(vector, modelCollections, budget));
            } else if (QueryEmbeddingContext.DEFAULT_MODEL_KEY.equals(model)) {
                chunks.addAll(retrieverService.retrieveGlobal(question, modelCollections, budget));
            } else {
                log.error("全局检索缺少指定模型的预计算向量，跳过模型组: {}", model);
            }
        });
        chunks.sort(Comparator.comparing(
                RetrievedChunk::getScore,
                Comparator.nullsLast(Comparator.reverseOrder())
        ));
        return chunks.stream().limit(budget).toList();
    }

    private QueryEmbeddingContext embeddingContext(SearchContext context) {
        Object value = context.getMetadata().get(QueryEmbeddingContext.METADATA_KEY);
        return value instanceof QueryEmbeddingContext embeddings
                ? embeddings
                : QueryEmbeddingContext.unavailable();
    }

    private List<String> plannedCollections(SearchContext context) {
        Object value = context.getMetadata().get(PLANNED_COLLECTIONS_KEY);
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }
}
