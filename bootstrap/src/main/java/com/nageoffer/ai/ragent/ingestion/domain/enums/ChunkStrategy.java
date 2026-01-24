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
 * 文档分块策略枚举
 * 定义将文档内容切分成块的不同策略，适用于不同的文档类型和场景
 * 策略值使用小写 snake_case，如 fixed_size、sentence
 */
@Getter
@RequiredArgsConstructor
public enum ChunkStrategy {

    /**
     * 固定大小切分 - 按固定字符数或token数切分
     */
    FIXED_SIZE("fixed_size"),

    /**
     * 按句子切分 - 以句子为单位进行切分
     */
    SENTENCE("sentence"),

    /**
     * 按段落切分 - 以段落为单位进行切分
     */
    PARAGRAPH("paragraph"),

    /**
     * 语义切分 - 基于语义相似度进行智能切分
     */
    SEMANTIC("semantic");

    /**
     * 策略值（小写 snake_case）
     */
    private final String value;

    /**
     * 根据字符串值解析策略
     */
    @JsonCreator
    public static ChunkStrategy fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = normalize(value);
        for (ChunkStrategy strategy : values()) {
            if (strategy.value.equalsIgnoreCase(normalized) || strategy.name().equalsIgnoreCase(normalized)) {
                return strategy;
            }
        }
        throw new IllegalArgumentException("Unknown chunk strategy: " + value);
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
