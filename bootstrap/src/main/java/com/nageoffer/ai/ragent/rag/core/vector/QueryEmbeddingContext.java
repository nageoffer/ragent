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

import java.util.List;
import java.util.Map;

/**
 * 请求级查询向量上下文。
 * <p>
 * 向量以“模型 + 查询文本”为复用键，Collection 只负责映射到实际模型。
 * 上下文仅在当前检索请求内共享，不做跨请求缓存。
 */
public final class QueryEmbeddingContext {

    public static final String METADATA_KEY = QueryEmbeddingContext.class.getName();
    public static final String DEFAULT_MODEL_KEY = "__default__";

    private final boolean prepared;
    private final Map<String, String> collectionModels;
    private final Map<VectorKey, float[]> vectors;
    private final Map<String, List<String>> questionCollections;

    QueryEmbeddingContext(boolean prepared,
                          Map<String, String> collectionModels,
                          Map<VectorKey, float[]> vectors,
                          Map<String, List<String>> questionCollections) {
        this.prepared = prepared;
        this.collectionModels = Map.copyOf(collectionModels);
        this.vectors = Map.copyOf(vectors);
        this.questionCollections = Map.copyOf(questionCollections);
    }

    public static QueryEmbeddingContext unavailable() {
        return new QueryEmbeddingContext(false, Map.of(), Map.of(), Map.of());
    }

    public boolean isPrepared() {
        return prepared;
    }

    public List<String> collectionsFor(String question) {
        return questionCollections.getOrDefault(question, List.of());
    }

    public String modelFor(String collectionName) {
        return collectionModels.getOrDefault(collectionName, DEFAULT_MODEL_KEY);
    }

    public boolean usesDefaultModel(String collectionName) {
        return DEFAULT_MODEL_KEY.equals(modelFor(collectionName));
    }

    public float[] vectorFor(String question, String collectionName) {
        float[] vector = vectors.get(new VectorKey(modelFor(collectionName), question));
        return vector == null ? null : vector.clone();
    }

    record VectorKey(String model, String question) {
    }
}
