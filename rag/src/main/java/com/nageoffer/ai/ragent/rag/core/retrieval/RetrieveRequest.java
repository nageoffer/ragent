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

package com.nageoffer.ai.ragent.rag.core.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 向量检索请求参数：
 * - 支持基础 query + topK
 * - 支持指定 Milvus collectionName
 * - 支持简单的 metadata 等值过滤（扩展用）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetrieveRequest {

    /**
     * 用户自然语言问题 / 查询语句
     */
    private String query;

    /**
     * 返回 TopK，默认 5
     */
    @Builder.Default
    private int topK = 5;

    /**
     * 目标向量集合名称：
     * - 为空时走默认 Collection
     * - 非空时按指定 Collection 检索
     */
    private String collectionName;

    /**
     * 目标逻辑 Collection 列表
     * 多个值表示在同一次检索中按这些 Collection 过滤，topK 是整个过滤范围的总预算
     */
    private List<String> collectionNames;

    /**
     * 元数据等值过滤条件（扩展项）：
     * - key 为 metadata 字段名
     * - value 为匹配值
     * 实现层可以根据 Map 自动拼接 Milvus Expr（AND 连接）。
     * <p>
     * 例如：
     * {"biz_type": "ATTENDANCE", "env": "TEST"}
     */
    private Map<String, Object> metadataFilters;

    /**
     * 新的多 Collection 参数优先，旧的单 Collection 参数用于兼容已有调用方
     */
    public List<String> getEffectiveCollectionNames() {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (collectionNames != null) {
            collectionNames.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .forEach(normalized::add);
        }
        if (normalized.isEmpty() && collectionName != null && !collectionName.isBlank()) {
            normalized.add(collectionName.trim());
        }
        return List.copyOf(normalized);
    }
}
