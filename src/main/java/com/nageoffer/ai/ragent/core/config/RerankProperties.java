package com.nageoffer.ai.ragent.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai.rerank")
public class RerankProperties {

    private String provider;

    private ChatOllamaProperties ollama;

    private ChatBaiLianProperties bailian;

    public record ChatOllamaProperties(String url, String apiKey, String model) {
    }

    public record ChatBaiLianProperties(String url, String apiKey, String model) {
    }
}
