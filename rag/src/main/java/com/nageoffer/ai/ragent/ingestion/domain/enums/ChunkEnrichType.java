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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 文本块富集类型枚举
 * 定义对文档分块进行富集处理的类型，用于增强分块的元数据和检索能力
 * 类型值使用小写 snake_case，如 keywords、summary
 */
@Getter
@RequiredArgsConstructor
public enum ChunkEnrichType {

    /**
     * 关键词提取 - 从文本块中提取关键词
     */
    KEYWORDS("keywords"),

    /**
     * 摘要生成 - 为文本块生成摘要
     */
    SUMMARY("summary"),

    /**
     * 元数据添加 - 为文本块添加额外的元数据信息
     */
    METADATA("metadata");

    /**
     * 类型值（小写 snake_case）
     */
    private final String value;

    /**
     * 根据字符串值解析类型
     */
    @JsonCreator
    public static ChunkEnrichType fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = normalize(value);
        for (ChunkEnrichType type : values()) {
            if (type.value.equalsIgnoreCase(normalized) || type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown chunk enrich type: " + value);
    }

    /**
     * 获取序列化值
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    private static String normalize(String value) {
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase();
        return lower.replace('-', '_');
    }
}
