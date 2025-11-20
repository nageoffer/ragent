package com.nageoffer.ai.ragent.core.service.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final Gson gson = new Gson();

    @Value("${ollama.embedding-model:qwen3-embedding:8b}")
    private String embeddingModel;

    @Value("${ollama.url:http://localhost:11434}")
    private String ollamaUrl;

    public List<Float> embed(String text) {
        String url = ollamaUrl + "/api/embed";

        JsonObject body = new JsonObject();
        body.addProperty("model", embeddingModel);
        body.addProperty("input", text);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> entity = new HttpEntity<>(body.toString(), headers);

        ResponseEntity<String> resp =
                restTemplate.postForEntity(url, entity, String.class);

        JsonObject json = gson.fromJson(resp.getBody(), JsonObject.class);

        // ✔ 真实字段名是 "embeddings"
        var embeddings = json.getAsJsonArray("embeddings");

        if (embeddings == null || embeddings.size() == 0) {
            throw new IllegalStateException("No embeddings returned from Ollama");
        }

        // ✔ 取第一个 embedding（因为我们一次只输入 1 个 text）
        var first = embeddings.get(0).getAsJsonArray();

        List<Float> vector = new ArrayList<>();
        first.forEach(v -> vector.add(v.getAsFloat()));

        return vector;
    }
}
