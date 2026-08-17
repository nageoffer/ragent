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

package com.nageoffer.ai.ragent.rag.controller.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 系统设置视图对象，只读汇总运行中配置
 * 分组：引擎档位 / 后端选型 / RAG 检索管线 / AI 模型，密钥一律脱敏或省略
 */
@Data
@Builder
public class SystemSettingsVO {

    private EngineSettings engine;
    private BackendSettings backends;
    private RagSettings rag;
    private AISettings ai;
    private UploadSettings upload;

    @Data
    @Builder
    public static class EngineSettings {

        /**
         * 执行架构档位：workflow（编排管线）/ agent（ReAct 架构）
         */
        private String type;
    }

    @Data
    @Builder
    public static class UploadSettings {
        private Long maxFileSize;
        private Long maxRequestSize;
    }

    /**
     * 后端组件选型，对应 yaml 里 rag.storage/vector/keyword/graph 四个 type 开关
     */
    @Data
    @Builder
    public static class BackendSettings {
        private StorageBackend storage;
        private VectorBackend vector;
        private KeywordBackend keyword;
        private GraphBackend graph;

        @Data
        @Builder
        public static class StorageBackend {

            /**
             * s3（rustfs / minio）/ oss（阿里云），连接信息取当前生效后端的值
             */
            private String type;
            private String kbBucket;
            private String assetBucket;
            private String endpoint;
            private String publicUrl;
            private String region;
        }

        @Data
        @Builder
        public static class VectorBackend {

            /**
             * pg / milvus
             */
            private String type;
        }

        @Data
        @Builder
        public static class KeywordBackend {

            /**
             * none / es，es 连接信息仅 type=es 时有意义
             */
            private String type;
            private String uris;
            private String index;
            private String analyzer;
            private String searchAnalyzer;
        }

        @Data
        @Builder
        public static class GraphBackend {

            /**
             * none / lightrag
             */
            private String type;
            private String baseUrl;
            private String queryMode;
            private String embeddingModel;
        }
    }

    @Data
    @Builder
    public static class RagSettings {

        @JsonProperty("default")
        private DefaultSettings defaultConfig;
        private FeatureSettings features;
        private SearchSettings search;
        private RateLimitSettings rateLimit;
        private MemorySettings memory;
    }

    @Data
    @Builder
    public static class DefaultSettings {
        private String collectionName;
        private Integer dimension;
        private String metricType;
        private Long sseTimeoutMs;
    }

    /**
     * RAG 功能开关合集
     */
    @Data
    @Builder
    public static class FeatureSettings {
        private Boolean queryRewrite;
        private Boolean rerank;
        private Boolean citation;
        private Boolean contextEnrich;
        private Boolean trace;
    }

    /**
     * 检索漏斗配置：recallBudget → rerankCandidateLimit → defaultTopK 单调收窄
     */
    @Data
    @Builder
    public static class SearchSettings {
        private Integer defaultTopK;
        private Integer recallBudget;
        private ScopeSettings scope;
        private ChannelSettings channels;
        private FusionSettings fusion;

        @Data
        @Builder
        public static class ScopeSettings {
            private Double minIntentScore;
            private Double confidenceThreshold;
            private Double supplementRatio;
        }

        @Data
        @Builder
        public static class ChannelSettings {
            private Long timeoutMs;
            private Channel vector;
            private Channel keyword;
            private Channel graph;
            private WebSearchChannel webSearch;
        }

        /**
         * 权重在 yaml 里配置于 fusion.channel-weights 下，展示上归属各通道
         */
        @Data
        @Builder
        public static class Channel {
            private Boolean enabled;
            private Double weight;
        }

        @Data
        @Builder
        public static class WebSearchChannel {
            private Boolean enabled;
            private Double weight;
            private Integer count;
            private Integer timeoutSeconds;
            private Boolean apiKeyConfigured;
        }

        @Data
        @Builder
        public static class FusionSettings {
            private String strategy;
            private Integer rrfK;
            private Integer rerankCandidateLimit;
        }
    }

    @Data
    @Builder
    public static class MemorySettings {
        private Integer historyKeepTurns;
        private Boolean summaryEnabled;
        private Integer summaryStartTurns;
        private Integer summaryMaxChars;
        private Integer titleMaxLength;
    }

    @Data
    @Builder
    public static class RateLimitSettings {
        private GlobalRateLimit global;
    }

    @Data
    @Builder
    public static class GlobalRateLimit {
        private Boolean enabled;
        private Integer maxConcurrent;
        private Integer maxWaitSeconds;
        private Integer leaseSeconds;
        private Integer pollIntervalMs;
    }

    @Data
    @Builder
    public static class AISettings {
        private Map<String, ProviderConfig> providers;
        private ModelGroup chat;
        private ModelGroup embedding;
        private ModelGroup rerank;
        private ModelGroup vlm;
        private Selection selection;
        private Stream stream;

        @Data
        @Builder
        public static class ProviderConfig {
            private String url;
            private String apiKey;
            private Map<String, String> endpoints;
        }

        @Data
        @Builder
        public static class ModelGroup {

            /**
             * defaultModel 供 embedding/rerank/vlm 使用；chat 组走档位（tiers）字段
             */
            private String defaultModel;
            private List<ModelCandidate> candidates;
            private String defaultTier;
            private String deepThinkingTier;
            private Map<String, TierConfig> tiers;
        }

        @Data
        @Builder
        public static class TierConfig {
            private List<String> candidates;
            private Long timeoutMs;
        }

        @Data
        @Builder
        public static class ModelCandidate {
            private String id;
            private String provider;
            private String model;
            private String url;
            private Integer dimension;
            private Integer priority;
            private Boolean enabled;
            private Boolean supportsThinking;
        }

        @Data
        @Builder
        public static class Selection {
            private Integer failureThreshold;
            private Long openDurationMs;
        }

        @Data
        @Builder
        public static class Stream {
            private Integer messageChunkSize;
        }
    }
}
