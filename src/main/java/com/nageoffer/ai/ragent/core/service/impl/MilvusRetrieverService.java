package com.nageoffer.ai.ragent.core.service.impl;

import com.nageoffer.ai.ragent.core.dto.rag.RAGHit;
import com.nageoffer.ai.ragent.core.service.RetrieverService;
import com.nageoffer.ai.ragent.core.service.rag.embedding.OllamaEmbeddingService;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusRetrieverService implements RetrieverService {

    private final OllamaEmbeddingService embeddingService;
    private final MilvusClientV2 milvusClient;

    @Value("${rag.collection-name}")
    private String collectionName;

    @Value("${rag.metric-type}")
    private String metricType;

    @Override
    public List<RAGHit> retrieve(String query, int topK) {
        List<Float> emb = embeddingService.embed(query);
        float[] vec = toArray(emb);

        float[] norm = normalize(vec);

        return retrieveByVector(norm, topK);
    }

    @Override
    public List<RAGHit> retrieveByVector(float[] vector, int topK) {
        List<BaseVector> vectors = List.of(new FloatVec(vector));

        Map<String, Object> params = new HashMap<>();
        params.put("metric_type", metricType);
        params.put("ef", 128);

        SearchReq req = SearchReq.builder()
                .collectionName(collectionName)
                .annsField("embedding")
                .data(vectors)
                .topK(topK)
                .searchParams(params)
                .outputFields(List.of("doc_id", "content", "metadata"))
                .build();

        SearchResp resp = milvusClient.search(req);
        List<List<SearchResp.SearchResult>> results = resp.getSearchResults();

        if (results == null || results.isEmpty()) {
            return List.of();
        }

        return results.get(0).stream()
                .map(r -> new RAGHit(
                        Objects.toString(r.getEntity().get("doc_id"), ""),
                        Objects.toString(r.getEntity().get("content"), ""),
                        r.getScore()))
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
