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
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "milvus", matchIfMissing = true)
public class MilvusVectorRetrieverService implements VectorRetrieverService {

    private final EmbeddingService embeddingService;
    private final MilvusClientV2 milvusClient;
    private final RAGDefaultProperties ragDefaultProperties;

    @Override
    public List<RetrievedChunk> retrieve(RetrieveRequest retrieveParam) {
        float[] norm = normalize(toArray(embeddingService.embed(retrieveParam.getQuery())));
        return retrieveByVector(norm, retrieveParam);
    }

    @Override
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest retrieveParam) {
        // 单库/意图检索：在共享 collection 内按 collection_name 过滤；为空则检索全共享库
        String filter = StrUtil.isBlank(retrieveParam.getCollectionName())
                ? null
                : "collection_name == \"" + retrieveParam.getCollectionName() + "\"";
        return searchShared(vector, filter, retrieveParam.getTopK());
    }

    @Override
    public boolean supportsGlobalRetrieval() {
        return true;
    }

    @Override
    public List<RetrievedChunk> retrieveGlobal(String query, List<String> collectionNames, int candidateBudget) {
        if (collectionNames == null || collectionNames.isEmpty()) {
            return List.of();
        }
        float[] norm = normalize(toArray(embeddingService.embed(query)));
        // 全局检索：单次在共享 collection 内按 collection_name in [...] 跨库召回，替代逐库 fan-out
        String inList = collectionNames.stream()
                .map(c -> "\"" + c + "\"")
                .collect(Collectors.joining(", "));
        String filter = "collection_name in [" + inList + "]";
        return searchShared(norm, filter, candidateBudget);
    }

    @Override
    public List<RetrievedChunk> retrieveGlobalByVector(float[] vector,
                                                       List<String> collectionNames,
                                                       int candidateBudget) {
        if (collectionNames == null || collectionNames.isEmpty()) {
            return List.of();
        }
        String inList = collectionNames.stream()
                .map(c -> "\"" + c + "\"")
                .collect(Collectors.joining(", "));
        return searchShared(vector, "collection_name in [" + inList + "]", candidateBudget);
    }

    /**
     * 在共享 collection 内执行一次向量检索
     *
     * @param filter 可选的标量过滤表达式（为空则不过滤，检索全共享库）
     */
    private List<RetrievedChunk> searchShared(float[] vector, String filter, int topK) {
        List<BaseVector> vectors = List.of(new FloatVec(vector));

        Map<String, Object> params = new HashMap<>();
        params.put("metric_type", ragDefaultProperties.getMetricType());
        params.put("ef", 128);

        var builder = SearchReq.builder()
                .collectionName(ragDefaultProperties.getCollectionName())
                .annsField("embedding")
                .data(vectors)
                .topK(topK)
                .searchParams(params)
                .outputFields(List.of("id", "content", "metadata"));
        if (StrUtil.isNotBlank(filter)) {
            builder.filter(filter);
        }

        SearchResp resp = milvusClient.search(builder.build());
        List<List<SearchResp.SearchResult>> results = resp.getSearchResults();

        if (results == null || results.isEmpty()) {
            return List.of();
        }

        return results.get(0).stream()
                .map(r -> RetrievedChunk.builder()
                        .id(Objects.toString(r.getEntity().get("id"), ""))
                        .text(Objects.toString(r.getEntity().get("content"), ""))
                        .score(r.getScore())
                        .build())
                .collect(Collectors.toList());
    }

    private static float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private static float[] normalize(float[] v) {
        double sum = 0.0;
        for (float x : v) sum += x * x;
        double len = Math.sqrt(sum);
        float[] nv = new float[v.length];
        for (int i = 0; i < v.length; i++) nv[i] = (float) (v[i] / len);
        return nv;
    }
}
