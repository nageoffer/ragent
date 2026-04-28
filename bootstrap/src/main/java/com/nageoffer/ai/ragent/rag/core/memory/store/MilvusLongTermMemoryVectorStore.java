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

package com.nageoffer.ai.ragent.rag.core.memory.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 Milvus 的长期记忆向量存储。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "milvus", matchIfMissing = true)
public class MilvusLongTermMemoryVectorStore implements LongTermMemoryVectorStore {

    private static final String COLLECTION_NAME = "user_long_term_memory";

    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;
    private final RAGDefaultProperties ragDefaultProperties;

    @PostConstruct
    public void initCollection() {
        Boolean exists = milvusClient.hasCollection(HasCollectionReq.builder().collectionName(COLLECTION_NAME).build());
        if (Boolean.TRUE.equals(exists)) {
            return;
        }
        List<CreateCollectionReq.FieldSchema> fieldSchemaList = new ArrayList<>();
        fieldSchemaList.add(CreateCollectionReq.FieldSchema.builder()
                .name("id")
                .dataType(DataType.VarChar)
                .maxLength(36)
                .isPrimaryKey(true)
                .autoID(false)
                .build());
        fieldSchemaList.add(CreateCollectionReq.FieldSchema.builder()
                .name("user_id")
                .dataType(DataType.VarChar)
                .maxLength(64)
                .build());
        fieldSchemaList.add(CreateCollectionReq.FieldSchema.builder()
                .name("content")
                .dataType(DataType.VarChar)
                .maxLength(65535)
                .build());
        fieldSchemaList.add(CreateCollectionReq.FieldSchema.builder()
                .name("embedding")
                .dataType(DataType.FloatVector)
                .dimension(ragDefaultProperties.getDimension())
                .build());
        CreateCollectionReq.CollectionSchema collectionSchema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(fieldSchemaList)
                .build();
        IndexParam hnswIndex = IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.COSINE)
                .indexName("embedding")
                .extraParams(Map.of("M", "32", "efConstruction", "128"))
                .build();
        milvusClient.createCollection(CreateCollectionReq.builder()
                .collectionName(COLLECTION_NAME)
                .collectionSchema(collectionSchema)
                .primaryFieldName("id")
                .vectorFieldName("embedding")
                .metricType("COSINE")
                .consistencyLevel(ConsistencyLevel.BOUNDED)
                .indexParams(List.of(hnswIndex))
                .description("User long term memory")
                .build());
    }

    @Override
    public void upsert(String memoryId, String userId, String content, String embeddingModel) {
        float[] vector = toArray(embeddingModel == null || embeddingModel.isBlank()
                ? embeddingService.embed(content)
                : embeddingService.embed(content, embeddingModel));
        JsonObject row = new JsonObject();
        row.addProperty("id", memoryId);
        row.addProperty("user_id", userId);
        row.addProperty("content", content);
        row.add("embedding", toJsonArray(vector));
        milvusClient.upsert(UpsertReq.builder()
                .collectionName(COLLECTION_NAME)
                .data(List.of(row))
                .build());
    }

    @Override
    public List<String> search(String userId, String query, int topK) {
        float[] vector = toArray(embeddingService.embed(query));
        SearchResp resp = milvusClient.search(SearchReq.builder()
                .collectionName(COLLECTION_NAME)
                .annsField("embedding")
                .data(List.of((BaseVector) new FloatVec(vector)))
                .topK(topK)
                .filter("user_id == \"" + userId + "\"")
                .outputFields(List.of("id"))
                .searchParams(Map.of("metric_type", "COSINE", "ef", 128))
                .build());
        if (resp.getSearchResults() == null || resp.getSearchResults().isEmpty()) {
            return List.of();
        }
        return resp.getSearchResults().get(0).stream()
                .map(result -> String.valueOf(result.getEntity().get("id")))
                .toList();
    }

    private float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private JsonArray toJsonArray(float[] vector) {
        JsonArray array = new JsonArray();
        for (float item : vector) {
            array.add(item);
        }
        return array;
    }
}
