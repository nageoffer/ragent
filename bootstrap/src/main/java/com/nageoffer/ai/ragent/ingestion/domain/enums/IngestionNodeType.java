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

package com.nageoffer.ai.ragent.ingestion.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 摄取节点类型枚举
 * 定义文档摄取流水线中支持的节点类型
 */
@Getter
@RequiredArgsConstructor
public enum IngestionNodeType {

    /**
     * 文档获取节点 - 从各种数据源获取文档
     */
    FETCHER("Fetcher"),

    /**
     * 文档解析节点 - 解析文档内容为文本
     */
    PARSER("Parser"),

    /**
     * 文档增强节点 - 对整个文档进行AI增强处理
     */
    ENHANCER("Enhancer"),

    /**
     * 文档分块节点 - 将文档切分为多个文本块
     */
    CHUNKER("Chunker"),

    /**
     * 分块增强节点 - 对每个文本块进行AI增强处理
     */
    ENRICHER("Enricher"),

    /**
     * 索引节点 - 将文本块向量化并存储到向量数据库
     */
    INDEXER("Indexer");

    /**
     * 节点类型的字符串值（首字母大写）
     */
    private final String value;

    /**
     * 根据字符串值获取枚举
     */
    public static IngestionNodeType fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (IngestionNodeType type : values()) {
            if (type.value.equalsIgnoreCase(value) || type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown node type: " + value);
    }
}
