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
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * 系统设置视图对象
 * 包含RAG和AI相关的配置信息
 */
@Setter
@Getter
public class SystemSettingsVO {

    private RagSettings rag;
    private AISettings ai;
    private UploadSettings upload;

    public SystemSettingsVO(RagSettings rag, AISettings ai, UploadSettings upload) {
        this.rag = rag;
        this.ai = ai;
        this.upload = upload;
    }

    public static SystemSettingsVOBuilder builder() {
        return new SystemSettingsVOBuilder();
    }

    public static class SystemSettingsVOBuilder {
        private RagSettings rag;
        private AISettings ai;
        private UploadSettings upload;

        public SystemSettingsVOBuilder rag(RagSettings rag) {
            this.rag = rag;
            return this;
        }

        public SystemSettingsVOBuilder ai(AISettings ai) {
            this.ai = ai;
            return this;
        }

        public SystemSettingsVOBuilder upload(UploadSettings upload) {
            this.upload = upload;
            return this;
        }

        public SystemSettingsVO build() {
            return new SystemSettingsVO(rag, ai, upload);
        }
    }

    @Data
    @Builder
    public static class UploadSettings {
        private Long maxFileSize;
        private Long maxRequestSize;
    }

    @Data
    @Builder
    public static class AISettings {
        private Map<String, ProviderConfig> providers;
        private ModelGroup chat;
        private ModelGroup embedding;
        private ModelGroup rerank;
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
            private String defaultModel;
            private String deepThinkingModel;
            private List<ModelCandidate> candidates;
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

    @Data
    @Builder
    public static class DefaultSettings {
        private String collectionName;
        private Integer dimension;
        private String metricType;
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

    @Setter
    @Getter
    public static class RagSettings {
        @JsonProperty("default")
        private DefaultSettings defaultConfig;
        private QueryRewriteSettings queryRewrite;
        private RateLimitSettings rateLimit;
        private MemorySettings memory;
        private ChannelSettings channels;

        public RagSettings(DefaultSettings defaultConfig, QueryRewriteSettings queryRewrite,
                           RateLimitSettings rateLimit, MemorySettings memory,
                           ChannelSettings channels) {
            this.defaultConfig = defaultConfig;
            this.queryRewrite = queryRewrite;
            this.rateLimit = rateLimit;
            this.memory = memory;
            this.channels = channels;
        }

        public static RagSettingsBuilder builder() {
            return new RagSettingsBuilder();
        }

        public static class RagSettingsBuilder {
            private DefaultSettings defaultConfig;
            private QueryRewriteSettings queryRewrite;
            private RateLimitSettings rateLimit;
            private MemorySettings memory;
            private ChannelSettings channels;

            public RagSettingsBuilder defaultConfig(DefaultSettings defaultConfig) {
                this.defaultConfig = defaultConfig;
                return this;
            }

            public RagSettingsBuilder queryRewrite(QueryRewriteSettings queryRewrite) {
                this.queryRewrite = queryRewrite;
                return this;
            }

            public RagSettingsBuilder rateLimit(RateLimitSettings rateLimit) {
                this.rateLimit = rateLimit;
                return this;
            }

            public RagSettingsBuilder memory(MemorySettings memory) {
                this.memory = memory;
                return this;
            }

            public RagSettingsBuilder channels(ChannelSettings channels) {
                this.channels = channels;
                return this;
            }

            public RagSettings build() {
                return new RagSettings(defaultConfig, queryRewrite, rateLimit, memory, channels);
            }
        }
    }

    @Setter
    @Getter
    public static class ChannelSettings {
        private ChannelConfig vectorGlobal;
        private ChannelConfig intentDirected;
        private ChannelConfig keyword;
        private HybridChannelConfig hybrid;

        public ChannelSettings(ChannelConfig vectorGlobal, ChannelConfig intentDirected,
                               ChannelConfig keyword, HybridChannelConfig hybrid) {
            this.vectorGlobal = vectorGlobal;
            this.intentDirected = intentDirected;
            this.keyword = keyword;
            this.hybrid = hybrid;
        }

        public static ChannelSettingsBuilder builder() {
            return new ChannelSettingsBuilder();
        }

        public static class ChannelSettingsBuilder {
            private ChannelConfig vectorGlobal;
            private ChannelConfig intentDirected;
            private ChannelConfig keyword;
            private HybridChannelConfig hybrid;

            public ChannelSettingsBuilder vectorGlobal(ChannelConfig c) { this.vectorGlobal = c; return this; }
            public ChannelSettingsBuilder intentDirected(ChannelConfig c) { this.intentDirected = c; return this; }
            public ChannelSettingsBuilder keyword(ChannelConfig c) { this.keyword = c; return this; }
            public ChannelSettingsBuilder hybrid(HybridChannelConfig c) { this.hybrid = c; return this; }

            public ChannelSettings build() {
                return new ChannelSettings(vectorGlobal, intentDirected, keyword, hybrid);
            }
        }
    }

    @Data
    @Builder
    public static class ChannelConfig {
        private Boolean enabled;
        private Double confidenceThreshold;
        private Double minIntentScore;
        private Double singleIntentSupplementThreshold;
        private Integer topKMultiplier;
        private Float boost;
    }

    @Data
    @Builder
    public static class HybridChannelConfig {
        private Boolean enabled;
        private String fusion;
        private Float vectorWeight;
    }

    @Setter
    @Getter
    public static class QueryRewriteSettings {
        private Boolean enabled;

        public QueryRewriteSettings(Boolean enabled) {
            this.enabled = enabled;
        }

        public static QueryRewriteSettingsBuilder builder() {
            return new QueryRewriteSettingsBuilder();
        }

        public static class QueryRewriteSettingsBuilder {
            private Boolean enabled;

            public QueryRewriteSettingsBuilder enabled(Boolean enabled) {
                this.enabled = enabled;
                return this;
            }

            public QueryRewriteSettings build() {
                return new QueryRewriteSettings(enabled);
            }
        }
    }

    @Setter
    @Getter
    public static class RateLimitSettings {
        private GlobalRateLimit global;

        public RateLimitSettings(GlobalRateLimit global) {
            this.global = global;
        }

        public static RateLimitSettingsBuilder builder() {
            return new RateLimitSettingsBuilder();
        }

        public static class RateLimitSettingsBuilder {
            private GlobalRateLimit global;

            public RateLimitSettingsBuilder global(GlobalRateLimit global) {
                this.global = global;
                return this;
            }

            public RateLimitSettings build() {
                return new RateLimitSettings(global);
            }
        }
    }

    @Setter
    @Getter
    public static class GlobalRateLimit {
        private Boolean enabled;
        private Integer maxConcurrent;
        private Integer maxWaitSeconds;
        private Integer leaseSeconds;
        private Integer pollIntervalMs;

        public GlobalRateLimit(Boolean enabled, Integer maxConcurrent, Integer maxWaitSeconds,
                               Integer leaseSeconds, Integer pollIntervalMs) {
            this.enabled = enabled;
            this.maxConcurrent = maxConcurrent;
            this.maxWaitSeconds = maxWaitSeconds;
            this.leaseSeconds = leaseSeconds;
            this.pollIntervalMs = pollIntervalMs;
        }

        public static GlobalRateLimitBuilder builder() {
            return new GlobalRateLimitBuilder();
        }

        public static class GlobalRateLimitBuilder {
            private Boolean enabled;
            private Integer maxConcurrent;
            private Integer maxWaitSeconds;
            private Integer leaseSeconds;
            private Integer pollIntervalMs;

            public GlobalRateLimitBuilder enabled(Boolean enabled) {
                this.enabled = enabled;
                return this;
            }

            public GlobalRateLimitBuilder maxConcurrent(Integer maxConcurrent) {
                this.maxConcurrent = maxConcurrent;
                return this;
            }

            public GlobalRateLimitBuilder maxWaitSeconds(Integer maxWaitSeconds) {
                this.maxWaitSeconds = maxWaitSeconds;
                return this;
            }

            public GlobalRateLimitBuilder leaseSeconds(Integer leaseSeconds) {
                this.leaseSeconds = leaseSeconds;
                return this;
            }

            public GlobalRateLimitBuilder pollIntervalMs(Integer pollIntervalMs) {
                this.pollIntervalMs = pollIntervalMs;
                return this;
            }

            public GlobalRateLimit build() {
                return new GlobalRateLimit(enabled, maxConcurrent, maxWaitSeconds, leaseSeconds, pollIntervalMs);
            }
        }
    }
}
