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

package com.nageoffer.ai.ragent.rag.core.vector;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 请求级查询向量批处理器。
 * <p>
 * 先通过一次数据库查询获得目标 Collection 与嵌入模型的映射，再按模型分组调用批量向量化接口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryEmbeddingBatcher {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final EmbeddingService embeddingService;

    /**
     * 为当前请求准备查询向量。
     *
     * @param scopedTasks    已明确 Collection 的检索任务
     * @param globalQuestions 需要检索全部有效 Collection 的查询文本
     */
    public QueryEmbeddingContext prepare(List<SearchTask> scopedTasks, List<String> globalQuestions) {
        List<SearchTask> safeTasks = scopedTasks == null ? List.of() : scopedTasks;
        List<String> safeGlobalQuestions = globalQuestions == null ? List.of() : globalQuestions;
        if (safeTasks.isEmpty() && safeGlobalQuestions.isEmpty()) {
            return new QueryEmbeddingContext(true, Map.of(), Map.of(), Map.of());
        }

        List<KnowledgeBaseDO> knowledgeBases;
        try {
            var query = Wrappers.<KnowledgeBaseDO>query()
                    .select("collection_name", "embedding_model")
                    .eq("deleted", 0);
            if (safeGlobalQuestions.isEmpty()) {
                Set<String> collections = new LinkedHashSet<>();
                for (SearchTask task : safeTasks) {
                    if (task != null && StrUtil.isNotBlank(task.collectionName())) {
                        collections.add(task.collectionName());
                    }
                }
                if (collections.isEmpty()) {
                    return new QueryEmbeddingContext(true, Map.of(), Map.of(), Map.of());
                }
                query.in("collection_name", collections);
            }
            knowledgeBases = knowledgeBaseMapper.selectList(query);
        } catch (Exception e) {
            log.error("批量读取 Collection 嵌入模型失败，回退到原有检索流程", e);
            return QueryEmbeddingContext.unavailable();
        }

        Map<String, String> collectionModels = new LinkedHashMap<>();
        for (KnowledgeBaseDO knowledgeBase : knowledgeBases) {
            if (knowledgeBase == null || StrUtil.isBlank(knowledgeBase.getCollectionName())) {
                continue;
            }
            collectionModels.put(
                    knowledgeBase.getCollectionName(),
                    modelKey(knowledgeBase.getEmbeddingModel())
            );
        }

        Map<String, LinkedHashSet<String>> questionCollections = new LinkedHashMap<>();
        for (SearchTask task : safeTasks) {
            if (task == null || StrUtil.isBlank(task.question()) || StrUtil.isBlank(task.collectionName())) {
                continue;
            }
            collectionModels.putIfAbsent(task.collectionName(), QueryEmbeddingContext.DEFAULT_MODEL_KEY);
            questionCollections.computeIfAbsent(task.question(), ignored -> new LinkedHashSet<>())
                    .add(task.collectionName());
        }
        for (String question : safeGlobalQuestions) {
            if (StrUtil.isBlank(question)) {
                continue;
            }
            questionCollections.computeIfAbsent(question, ignored -> new LinkedHashSet<>())
                    .addAll(collectionModels.keySet());
        }

        Map<String, LinkedHashSet<String>> questionsByModel = new LinkedHashMap<>();
        questionCollections.forEach((question, collections) -> collections.forEach(collection -> {
            String model = collectionModels.getOrDefault(collection, QueryEmbeddingContext.DEFAULT_MODEL_KEY);
            questionsByModel.computeIfAbsent(model, ignored -> new LinkedHashSet<>()).add(question);
        }));

        Map<QueryEmbeddingContext.VectorKey, float[]> vectors = new LinkedHashMap<>();
        questionsByModel.forEach((model, questions) -> embedGroup(model, new ArrayList<>(questions), vectors));

        Map<String, List<String>> immutableQuestionCollections = new LinkedHashMap<>();
        questionCollections.forEach((question, collections) ->
                immutableQuestionCollections.put(question, List.copyOf(collections)));
        return new QueryEmbeddingContext(true, collectionModels, vectors, immutableQuestionCollections);
    }

    private void embedGroup(String model,
                            List<String> questions,
                            Map<QueryEmbeddingContext.VectorKey, float[]> vectors) {
        try {
            List<List<Float>> embeddings = QueryEmbeddingContext.DEFAULT_MODEL_KEY.equals(model)
                    ? embeddingService.embedBatch(questions)
                    : embeddingService.embedBatch(questions, model);
            if (embeddings == null || embeddings.size() != questions.size()) {
                log.error("批量向量化返回数量不匹配，model: {}, questions: {}, embeddings: {}",
                        model, questions.size(), embeddings == null ? 0 : embeddings.size());
                return;
            }
            for (int i = 0; i < questions.size(); i++) {
                List<Float> embedding = embeddings.get(i);
                if (embedding == null || embedding.isEmpty()) {
                    continue;
                }
                vectors.put(
                        new QueryEmbeddingContext.VectorKey(model, questions.get(i)),
                        normalize(toArray(embedding))
                );
            }
        } catch (Exception e) {
            log.error("批量向量化失败，model: {}, questionCount: {}", model, questions.size(), e);
        }
    }

    private String modelKey(String model) {
        return StrUtil.isBlank(model) ? QueryEmbeddingContext.DEFAULT_MODEL_KEY : model.trim();
    }

    private float[] toArray(List<Float> values) {
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i);
        }
        return vector;
    }

    private float[] normalize(float[] vector) {
        double sum = 0D;
        for (float value : vector) {
            sum += value * value;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0D) {
            return vector;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
        return vector;
    }
}
