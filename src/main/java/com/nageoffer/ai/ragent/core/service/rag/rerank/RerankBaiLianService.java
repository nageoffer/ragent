package com.nageoffer.ai.ragent.core.service.rag.rerank;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.core.config.RerankProperties;
import com.nageoffer.ai.ragent.core.dto.rag.RAGHit;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.rerank.provider", havingValue = "bailian")
public class RerankBaiLianService implements RerankService {

    private final RerankProperties rerankProperties;

    private final Gson gson = new Gson();
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public List<RAGHit> rerank(String query, List<RAGHit> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (topN <= 0 || candidates.size() <= topN) {
            return candidates;
        }

        return chat(query, candidates, topN);
    }

    public List<RAGHit> chat(String query, List<RAGHit> candidates, int topN) {
        RerankProperties.ChatBaiLianProperties properties = rerankProperties.getBailian();

        if (candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }

        // 1. 构造请求体
        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", properties.model());

        JsonObject input = new JsonObject();
        input.addProperty("query", query);

        JsonArray documentsArray = new JsonArray();
        for (RAGHit each : candidates) {
            documentsArray.add(each.getText() == null ? "" : each.getText());
        }
        input.add("documents", documentsArray);

        JsonObject parameters = new JsonObject();
        parameters.addProperty("top_n", topN);
        parameters.addProperty("return_documents", true);

        reqBody.add("input", input);
        reqBody.add("parameters", parameters);

        // 2. 发送请求
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.apiKey());

        HttpEntity<String> httpEntity = new HttpEntity<>(reqBody.toString(), headers);
        ResponseEntity<String> response =
                restTemplate.postForEntity(properties.url(), httpEntity, String.class);

        // 3. 解析返回
        JsonObject respJson = gson.fromJson(response.getBody(), JsonObject.class);
        JsonObject output = respJson.getAsJsonObject("output");
        if (output == null || !output.has("results")) {
            // 兜底：如果没有 results，就直接返回原 candidates 前 topN
            return candidates.stream().limit(topN).collect(Collectors.toList());
        }

        JsonArray results = output.getAsJsonArray("results");
        if (results == null || results.size() == 0) {
            return candidates.stream().limit(topN).collect(Collectors.toList());
        }

        List<RAGHit> reranked = new ArrayList<>();

        for (JsonElement elem : results) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject item = elem.getAsJsonObject();

            // 3.1 拿 index（注意一般是 0-based）
            if (!item.has("index")) {
                continue;
            }
            int idx = item.get("index").getAsInt();

            if (idx < 0 || idx >= candidates.size()) {
                // 防止越界
                continue;
            }

            // 3.2 从原 candidates 里取出对应的命中
            RAGHit src = candidates.get(idx);

            // 3.3 取 relevance_score（可选）
            Float score = null;
            if (item.has("relevance_score") && !item.get("relevance_score").isJsonNull()) {
                score = item.get("relevance_score").getAsFloat();
            }

            // 这里看你的 RAGHit 是不可变还是可变的：
            // - 如果 RAGHit 有 setScore，可在原对象上直接设置；
            // - 如果是不可变对象，就 new 一个拷贝。
            RAGHit hit;
            if (score != null) {
                // 举例：假设 RAGHit 有 (docId, text, score) 这样的构造器
                hit = new RAGHit(src.getId(), src.getText(), score);
            } else {
                hit = src;
            }

            reranked.add(hit);

            if (reranked.size() >= topN) {
                break;
            }
        }

        // 如果因为解析问题没拿够 topN，就用原 candidates 补齐
        if (reranked.size() < topN) {
            for (RAGHit c : candidates) {
                if (!reranked.contains(c)) {
                    reranked.add(c);
                }
                if (reranked.size() >= topN) {
                    break;
                }
            }
        }

        return reranked;
    }
}
