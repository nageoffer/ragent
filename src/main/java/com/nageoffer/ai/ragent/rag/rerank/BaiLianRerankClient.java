package com.nageoffer.ai.ragent.rag.rerank;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.model.ModelTarget;
import com.nageoffer.ai.ragent.rag.retrieve.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;
import com.nageoffer.ai.ragent.rag.http.HttpMediaTypes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class BaiLianRerankClient implements RerankClient {

    private final Gson gson = new Gson();
    private final OkHttpClient httpClient;

    @Override
    public String provider() {
        return "bailian";
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<RetrievedChunk> dedup = new ArrayList<>(candidates.size());
        Set<String> seen = new HashSet<>();
        for (RetrievedChunk rc : candidates) {
            if (seen.add(rc.getId())) {
                dedup.add(rc);
            }
        }

        if (topN <= 0 || dedup.size() <= topN) {
            return dedup;
        }

        return doRerank(query, dedup, topN, target);
    }

    private List<RetrievedChunk> doRerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProvider(target);

        if (candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }

        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", requireModel(target));

        JsonObject input = new JsonObject();
        input.addProperty("query", query);

        JsonArray documentsArray = new JsonArray();
        for (RetrievedChunk each : candidates) {
            documentsArray.add(each.getText() == null ? "" : each.getText());
        }
        input.add("documents", documentsArray);

        JsonObject parameters = new JsonObject();
        parameters.addProperty("top_n", topN);
        parameters.addProperty("return_documents", true);

        reqBody.add("input", input);
        reqBody.add("parameters", parameters);

        Request request = new Request.Builder()
                .url(provider.getUrl())
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();

        JsonObject respJson;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = readBody(response.body());
                log.warn("百炼 rerank 请求失败: status={}, body={}", response.code(), body);
                throw new IllegalStateException("百炼 rerank 请求失败: HTTP " + response.code());
            }
            respJson = parseJsonBody(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("百炼 rerank 请求失败: " + e.getMessage(), e);
        }
        JsonObject output = respJson.getAsJsonObject("output");
        if (output == null || !output.has("results")) {
            return candidates.stream().limit(topN).collect(Collectors.toList());
        }

        JsonArray results = output.getAsJsonArray("results");
        if (results == null || results.size() == 0) {
            return candidates.stream().limit(topN).collect(Collectors.toList());
        }

        List<RetrievedChunk> reranked = new ArrayList<>();

        for (JsonElement elem : results) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject item = elem.getAsJsonObject();

            if (!item.has("index")) {
                continue;
            }
            int idx = item.get("index").getAsInt();

            if (idx < 0 || idx >= candidates.size()) {
                continue;
            }

            RetrievedChunk src = candidates.get(idx);

            Float score = null;
            if (item.has("relevance_score") && !item.get("relevance_score").isJsonNull()) {
                score = item.get("relevance_score").getAsFloat();
            }

            RetrievedChunk hit;
            if (score != null) {
                hit = new RetrievedChunk(src.getId(), src.getText(), score);
            } else {
                hit = src;
            }

            reranked.add(hit);

            if (reranked.size() >= topN) {
                break;
            }
        }

        if (reranked.size() < topN) {
            for (RetrievedChunk c : candidates) {
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

    private AIModelProperties.ProviderConfig requireProvider(ModelTarget target) {
        if (target == null || target.provider() == null || target.provider().getUrl() == null) {
            throw new IllegalStateException("BaiLian rerank provider config is missing");
        }
        return target.provider();
    }

    private String requireModel(ModelTarget target) {
        if (target == null || target.candidate() == null || target.candidate().getModel() == null) {
            throw new IllegalStateException("BaiLian rerank model name is missing");
        }
        return target.candidate().getModel();
    }

    private JsonObject parseJsonBody(ResponseBody body) throws IOException {
        if (body == null) {
            throw new IllegalStateException("百炼 rerank 响应为空");
        }
        String content = body.string();
        return gson.fromJson(content, JsonObject.class);
    }

    private String readBody(ResponseBody body) throws IOException {
        if (body == null) {
            return "";
        }
        return new String(body.bytes(), StandardCharsets.UTF_8);
    }
}
