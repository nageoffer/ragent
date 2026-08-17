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
 * 知识图谱检索配置
 * <p>
 * type=none（默认）时不注册任何图谱读写实现（检索通道与写入同步装饰器均不织入），
 * 与「从未引入图谱检索」运行期等价
 * <p>
 * 与关键词检索对称：此处管后端类型与连接（对应 rag.keyword），
 * 通道行为（启用/范围/倍数）放在 {@link SearchChannelProperties} 的 channels.graph
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.graph")
public class GraphProperties {

    /**
     * 图谱检索后端类型
     * 可选 none（关闭）/ lightrag；none 时不注册任何图谱读写实现
     */
    private String type = "none";

    /**
     * LightRAG 微服务连接配置
     */
    private LightRag lightrag = new LightRag();

    /**
     * 图谱侧 embedding 模型标识
     * 独立于各知识库的向量 embedding，首次索引后不可更换
     */
    private String embeddingModel = "";

    /**
     * 是否启用 lightrag 后端
     */
    public boolean isLightrag() {
        return "lightrag".equalsIgnoreCase(type);
    }

    @Data
    public static class LightRag {

        /**
         * LightRAG server 基址
         */
        private String baseUrl = "http://127.0.0.1:9621";

        /**
         * API Key（对应 LightRAG 的 X-API-Key 头）
         * 本地部署默认留空、不发送该头；如需对外鉴权再显式配置
         */
        private String apiKey = "";

        /**
         * LightRAG 检索算法（naive / local / global / hybrid / mix），透传 /query 的 mode 字段
         * mix 在图谱证据外混入 LightRAG 内部向量检索的 chunk：与向量通道内容重复但 id 不同，RRF 去不掉重；
         * hybrid 只回图结构关联的证据，与向量 / 关键词通道正交；naive 完全不走图，本架构下不建议
         */
        private String queryMode = "hybrid";
    }
}
