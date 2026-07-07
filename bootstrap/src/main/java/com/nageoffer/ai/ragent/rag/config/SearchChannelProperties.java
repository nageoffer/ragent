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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 检索配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.search")
public class SearchChannelProperties {

    /**
     * 默认返回的 TopK
     */
    private int defaultTopK = 10;

    /**
     * 检索通道配置
     */
    private Channels channels = new Channels();

    /**
     * 多通道结果融合配置
     */
    private Fusion fusion = new Fusion();

    @Data
    public static class Channels {

        /**
         * 向量全局检索配置
         */
        private VectorGlobal vectorGlobal = new VectorGlobal();

        /**
         * 意图定向检索配置
         */
        private IntentDirected intentDirected = new IntentDirected();

        /**
         * 关键词检索配置
         */
        private Keyword keyword = new Keyword();

        /**
         * 联网检索配置（You.com Search）
         */
        private WebSearch webSearch = new WebSearch();
    }

    @Data
    public static class VectorGlobal {

        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * 意图置信度阈值
         * 当意图识别的最高分数低于此阈值时，启用全局检索
         */
        private double confidenceThreshold = 0.6;

        /**
         * 单意图补充检索阈值
         * 当仅识别出一个意图且分数低于此阈值时，启用全局检索作为安全网
         */
        private double singleIntentSupplementThreshold = 0.8;

        /**
         * TopK 倍数
         * 仅逐库并行 fan-out 兜底路径（如 Milvus）使用，每库召回 topK * 倍数
         */
        private int topKMultiplier = 3;

        /**
         * 全局检索候选预算
         * 单次全局查询的 LIMIT 上限，与 fusion.rerankCandidateLimit 配合控制候选规模
         * <=0 时回退到 topK * topKMultiplier 的旧语义
         */
        private int candidateBudget = 100;

        /**
         * 解析全局检索候选预算
         * 优先使用绝对预算 candidateBudget；未配置（<=0）时回退到 topK * topKMultiplier
         */
        public int resolveCandidateBudget(int topK) {
            return candidateBudget > 0 ? candidateBudget : topK * Math.max(1, topKMultiplier);
        }
    }

    @Data
    public static class IntentDirected {

        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * 最低意图分数
         * 低于此分数的意图节点会被过滤
         */
        private double minIntentScore = 0.4;

        /**
         * TopK 倍数
         */
        private int topKMultiplier = 2;
    }

    @Data
    public static class Keyword {

        /**
         * 是否启用
         * 仅当 rag.keyword.type != none（存在关键词检索实现）时才会真正生效
         */
        private boolean enabled = false;

        /**
         * 检索范围
         * intent 仅意图域 / global 全库 / both 意图优先，无意图时全库兜底
         */
        private String mode = "both";

        /**
         * TopK 倍数
         * 关键词召回更多候选，后续通过融合与 Rerank 筛选
         */
        private int topKMultiplier = 2;
    }

    @Data
    public static class WebSearch {

        /**
         * 是否启用
         * 默认关闭；开启后还需配置 api-key（或环境变量 YDC_API_KEY），两者缺一通道不生效
         */
        private boolean enabled = false;

        /**
         * 返回结果数量
         * 默认 5，上限 20（超出会被通道内截断）
         */
        private int count = 5;

        /**
         * 请求超时（秒）
         */
        private int timeoutSeconds = 10;

        /**
         * You.com Search API Key
         * 建议留空，此时回退读取环境变量 YDC_API_KEY，避免密钥落入配置文件
         */
        private String apiKey = "";

        /**
         * You.com Search API 地址
         * 一般无需修改，测试时可指向本地 stub
         */
        private String apiUrl = "https://ydc-index.io/v1/search";
    }

    @Data
    public static class Fusion {

        /**
         * 融合策略
         * rrf 倒数名次融合（当前唯一实现），off 关闭融合直接透传
         */
        private String strategy = "rrf";

        /**
         * RRF 平滑常数 k
         * 值越大越弱化高名次的优势，通常取 60
         */
        private int rrfK = 60;

        /**
         * Rerank 候选上限
         * RRF 融合排序后仅保留前 N 个高分候选送入 Rerank 精排，
         * 既控制 Rerank 的成本与延迟，又让多路命中的候选凭 RRF 分数优先入选
         * <=0 表示不截断（全量送入 Rerank），行业经验值 40~100
         */
        private int rerankCandidateLimit = 50;
    }
}
