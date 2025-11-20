package com.nageoffer.ai.ragent.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai.embedding")
public class EmbeddingProperties {

    private String provider;

    private EmbeddingOllamaProperties ollama;

    public record EmbeddingOllamaProperties(String url, String model) {
    }
}
