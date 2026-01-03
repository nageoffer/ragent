package com.nageoffer.ai.ragent.rag.embedding;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.model.ModelTarget;
import com.nageoffer.ai.ragent.enums.ModelCapability;
import com.nageoffer.ai.ragent.rag.http.ModelUrlResolver;
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
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class OllamaEmbeddingClient implements EmbeddingClient {

    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    @Override
    public String provider() {
        return "ollama";
    }

    @Override
    public List<Float> embed(String text, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        String url = resolveUrl(provider, target);

        JsonObject body = new JsonObject();
        body.addProperty("model", requireModel(target));
        body.addProperty("input", text);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .build();

        JsonObject json;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = readBody(response.body());
                log.warn("Ollama embedding 请求失败: status={}, body={}", response.code(), errBody);
                throw new IllegalStateException("Ollama embedding 请求失败: HTTP " + response.code());
            }
            json = parseJsonBody(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Ollama embedding 请求失败: " + e.getMessage(), e);
        }

        var embeddings = json.getAsJsonArray("embeddings");

        if (embeddings == null || embeddings.isEmpty()) {
            throw new IllegalStateException("No embeddings returned from Ollama");
        }

        var first = embeddings.get(0).getAsJsonArray();

        List<Float> vector = new ArrayList<>();
        first.forEach(v -> vector.add(v.getAsFloat()));

        return vector;
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        List<List<Float>> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text, target));
        }
        return vectors;
    }

    private AIModelProperties.ProviderConfig requireProvider(ModelTarget target) {
        if (target == null || target.provider() == null) {
            throw new IllegalStateException("Ollama provider config is missing");
        }
        return target.provider();
    }

    private String requireModel(ModelTarget target) {
        if (target == null || target.candidate() == null || target.candidate().getModel() == null) {
            throw new IllegalStateException("Ollama model name is missing");
        }
        return target.candidate().getModel();
    }

    private String resolveUrl(AIModelProperties.ProviderConfig provider, ModelTarget target) {
        return ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.EMBEDDING);
    }

    private JsonObject parseJsonBody(ResponseBody body) throws IOException {
        if (body == null) {
            throw new IllegalStateException("Ollama embedding 响应为空");
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
