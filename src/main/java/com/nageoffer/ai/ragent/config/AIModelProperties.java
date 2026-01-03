package com.nageoffer.ai.ragent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai")
public class AIModelProperties {

    private Map<String, ProviderConfig> providers = new HashMap<>();

    private ModelGroup chat = new ModelGroup();

    private ModelGroup embedding = new ModelGroup();

    private ModelGroup rerank = new ModelGroup();

    private Selection selection = new Selection();

    @Data
    public static class ModelGroup {
        private String defaultModel;
        private List<ModelCandidate> candidates = new ArrayList<>();
    }

    @Data
    public static class ModelCandidate {
        private String id;
        private String provider;
        private String model;
        private String url;
        private Integer dimension;
        private Integer priority = 100;
        private Boolean enabled = true;
    }

    @Data
    public static class ProviderConfig {
        private String url;
        private String apiKey;
        private Map<String, String> endpoints = new HashMap<>();
    }

    @Data
    public static class Selection {
        private Integer failureThreshold = 2;
        private Long openDurationMs = 30000L;
    }
}
