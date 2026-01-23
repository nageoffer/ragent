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
 * 文档源类型枚举
 * 定义支持的文档来源类型，用于标识文档的获取方式
 */
@Getter
@RequiredArgsConstructor
public enum SourceType {

    /**
     * 本地文件 - 文档来源为本地文件系统
     */
    FILE("File"),

    /**
     * URL地址 - 文档来源为网络 URL
     */
    URL("Url"),

    /**
     * 飞书文档 - 文档来源为飞书文档
     */
    FEISHU("Feishu"),

    /**
     * S3对象存储 - 文档来源为S3兼容的对象存储（如RustFS）
     */
    S3("S3");

    /**
     * 类型值（首字母大写）
     */
    private final String value;

    /**
     * 根据字符串值解析类型
     */
    @JsonCreator
    public static SourceType fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (SourceType type : values()) {
            if (type.value.equalsIgnoreCase(value) || type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown source type: " + value);
    }

    /**
     * 获取序列化值
     */
    @JsonValue
    public String getValue() {
        return value;
    }
}
