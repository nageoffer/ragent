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

package com.nageoffer.ai.ragent.knowledge.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 文档来源类型枚举
 */
@Getter
@RequiredArgsConstructor
public enum SourceType {

    /**
     * 本地文件上传
     */
    FILE("file"),

    /**
     * 远程URL获取
     */
    URL("url");

    /**
     * 来源类型值
     */
    private final String value;

    /**
     * 根据值获取枚举
     *
     * @param value 来源类型值
     * @return 对应的枚举，如果未找到返回 null
     */
    public static SourceType fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        // 兼容多种文件类型的别名
        if ("file".equals(normalized) || "localfile".equals(normalized) || "local_file".equals(normalized)) {
            return FILE;
        }
        if ("url".equals(normalized)) {
            return URL;
        }
        return null;
    }
}
