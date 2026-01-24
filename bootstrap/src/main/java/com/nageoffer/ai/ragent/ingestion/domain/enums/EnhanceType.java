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
 * 文档增强类型枚举
 * 定义对整个文档内容进行增强处理的类型，用于提升文档的检索和理解质量
 * 类型值使用小写 snake_case，如 context_enhance、keywords
 */
@Getter
@RequiredArgsConstructor
public enum EnhanceType {

    /**
     * 上下文增强 - 为文本添加上下文信息，提升理解能力
     */
    CONTEXT_ENHANCE("context_enhance"),

    /**
     * 关键词提取 - 从文档中提取重要关键词
     */
    KEYWORDS("keywords"),

    /**
     * 问题生成 - 基于文档内容生成相关问题
     */
    QUESTIONS("questions"),

    /**
     * 元数据提取 - 提取文档的元数据信息
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
    public static EnhanceType fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = normalize(value);
        for (EnhanceType type : values()) {
            if (type.value.equalsIgnoreCase(normalized) || type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown enhance type: " + value);
    }

    private static String normalize(String value) {
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase();
        return lower.replace('-', '_');
    }

    /**
     * 获取序列化值
     */
    @JsonValue
    public String getValue() {
        return value;
    }
}
