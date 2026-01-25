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
import org.springframework.context.annotation.Configuration;

/**
 * RAG 系统默认配置
 *
 * <p>
 * 用于管理 RAG 系统的默认向量数据库配置，包括集合名称、向量维度和度量类型等
 * </p>
 *
 * <pre>
 * 示例配置：
 *
 * rag:
 *   default:
 *     collection-name: default_collection
 *     dimension: 768
 *     metric-type: COSINE
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.default")
public class RAGDefaultProperties {

    /**
     * 默认向量集合名称
     * <p>
     * 用于指定在向量数据库中存储向量数据的默认集合（Collection）名称
     */
    private String collectionName;

    /**
     * 向量维度
     * <p>
     * 指定向量的维数，需要与所使用的 Embedding 模型输出维度保持一致
     * 例如：2048、4096 等
     */
    private Integer dimension;

    /**
     * 向量相似度度量类型
     * <p>
     * 用于计算向量之间相似度的度量方法，常见取值：
     * <ul>
     *   <li>{@code COSINE}：余弦相似度</li>
     *   <li>{@code L2}：欧氏距离</li>
     *   <li>{@code IP}：内积</li>
     * </ul>
     */
    private String metricType;
}
