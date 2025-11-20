package com.nageoffer.ai.ragent.core.service.llm;

import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
@ConditionalOnProperty(name = "ai.embedding.provider", havingValue = "bailian")
public class BaiLianEmbeddingService implements EmbeddingService {

    @Value("${llm.bailian.api-key}")
    private String apiKey;

    @Value("${embedding.bailian.model}")
    private String model;

    private final RestTemplate restTemplate = new RestTemplate();
    private final Gson gson = new Gson();

    @Override
    public List<Float> embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        // 使用百炼向量接口（类 OpenAI /v1/embeddings）
        // 组装 model + input，返回 data[].embedding
        return List.of();
    }

    @Override
    public int dimension() {
        return 4096;
    }
}
