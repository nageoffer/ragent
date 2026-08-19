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

package com.nageoffer.ai.ragent.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 模型配置属性类
 * 用于从配置文件中读取 AI 相关的配置信息，包括提供商配置、模型组配置等
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai")
public class AIModelProperties {

    /**
     * AI 提供商配置映射
     * key: 提供商名称，value: 提供商配置信息
     */
    private Map<String, ProviderConfig> providers = new HashMap<>();

    /**
     * 聊天模型组配置
     */
    private ModelGroup chat = new ModelGroup();

    /**
     * 向量嵌入模型组配置
     */
    private ModelGroup embedding = new ModelGroup();

    /**
     * 重排序模型组配置
     */
    private ModelGroup rerank = new ModelGroup();

    /**
     * 视觉大模型组配置（图生文，知识库入库期使用）
     */
    private ModelGroup vlm = new ModelGroup();

    /**
     * 语音合成模型组配置
     */
    private ModelGroup tts = new ModelGroup();

    /**
     * 模型选择策略配置
     */
    private Selection selection = new Selection();

    /**
     * 流式响应配置
     */
    private Stream stream = new Stream();

    /**
     * WebSocket 连接及任务生命周期配置
     */
    private WebSocketConfig websocket = new WebSocketConfig();

    /**
     * 模型组配置类
     * 包含默认模型与候选模型列表
     */
    @Data
    public static class ModelGroup {
        /**
         * 默认使用的模型标识（embedding/rerank/vlm 使用；chat 已改用 tier）
         */
        private String defaultModel;

        /**
         * 候选模型列表
         * <p>
         * chat 组下退化为"物理模型注册表"：只登记 id→provider/model，档位排序由 tiers 决定
         */
        private List<ModelCandidate> candidates = new ArrayList<>();

        /**
         * 模型组调用超时预算
         */
        private Long timeoutMs;

        /**
         * 默认档位名（仅 chat 使用）
         * 未显式指定 Tier 覆盖时的默认档位（兜底档）
         */
        private String defaultTier;

        /**
         * 深度思考档位名（仅 chat 使用）
         * 用户开启深度思考时的目标档位
         */
        private String deepThinkingTier;

        /**
         * 档位配置映射（仅 chat 使用）
         * key: 档位名（如 fast/standard/deep），value: 该档位的候选与超时
         */
        private Map<String, TierConfig> tiers = new HashMap<>();
    }

    /**
     * WebSocket 配置
     */
    @Data
    public static class WebSocketConfig {

        private long connectTimeoutMs;

        private long taskStartTimeoutMs;

        private long taskPacketIdleTimeoutMs;

        private int maxTotalPerModel;

        private int maxIdlePerModel;

        /**
         * 连接空闲驱逐超时 0 表示不驱逐
         */
        private long idleTimeoutMs;

        /**
         * 空闲驱逐扫描间隔
         */
        private long evictionIntervalMs;
    }

    /**
     * 档位配置类
     * 定义单个档位的有序候选与超时预算
     */
    @Data
    public static class TierConfig {

        /**
         * 有序候选模型 id 列表
         * 引用 candidates 注册表中的 id，顺序即候选顺序（局部，不依赖全局 priority）
         */
        private List<String> candidates = new ArrayList<>();

        /**
         * 该档位的调用超时预算（毫秒），null 表示不额外限制
         */
        private Long timeoutMs;
    }

    /**
     * 模型候选配置类
     * 定义单个候选模型的详细配置信息
     */
    @Data
    public static class ModelCandidate {

        /**
         * 模型唯一标识符
         */
        private String id;

        /**
         * 模型提供商名称
         */
        private String provider;

        /**
         * 模型名称
         */
        private String model;

        /**
         * 模型访问 URL
         */
        private String url;

        /**
         * 向量维度（用于 embedding 模型）
         */
        private Integer dimension;

        /**
         * 模型优先级，数值越小优先级越高
         */
        private Integer priority = 100;

        /**
         * 是否启用该模型
         */
        private Boolean enabled = true;

        /**
         * 是否支持思考链功能
         */
        private Boolean supportsThinking = false;

        /**
         * 语音类模型默认音色
         */
        private String voice;
    }

    /**
     * 提供商配置类
     * 包含提供商的基本连接信息和端点配置
     */
    @Data
    public static class ProviderConfig {

        /**
         * 提供商基础 URL
         */
        private String url;

        /**
         * API 密钥
         */
        private String apiKey;

        /**
         * 供应商工作空间标识
         */
        private String workspace;

        /**
         * 端点映射配置
         * key: 端点类型，value: 端点路径
         */
        private Map<String, String> endpoints = new HashMap<>();
    }

    /**
     * 模型选择策略配置类
     * 用于配置模型故障转移和熔断策略
     */
    @Data
    public static class Selection {

        /**
         * 失败阈值，超过该值将触发熔断
         */
        private Integer failureThreshold = 2;

        /**
         * 熔断器打开持续时间（毫秒）
         */
        private Long openDurationMs = 30000L;
    }

    /**
     * 流式响应配置类
     * 用于配置流式输出的相关参数
     */
    @Data
    public static class Stream {

        /**
         * 消息分块大小
         */
        private Integer messageChunkSize = 5;
    }
}
