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
 * 摄取任务状态枚举
 * 定义文档摄取任务的执行状态
 * 状态值使用小写 snake_case，如 pending、running、completed
 */
@Getter
@RequiredArgsConstructor
public enum IngestionStatus {

    /**
     * 等待中 - 任务已创建但尚未开始执行
     */
    PENDING("pending"),

    /**
     * 运行中 - 任务正在执行中
     */
    RUNNING("running"),

    /**
     * 失败 - 任务执行失败
     */
    FAILED("failed"),

    /**
     * 已完成 - 任务执行成功完成
     */
    COMPLETED("completed");

    /**
     * 状态值（小写 snake_case）
     */
    private final String value;

    /**
     * 根据字符串值解析状态
     */
    @JsonCreator
    public static IngestionStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = normalize(value);
        for (IngestionStatus status : values()) {
            if (status.value.equalsIgnoreCase(normalized) || status.name().equalsIgnoreCase(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown ingestion status: " + value);
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
