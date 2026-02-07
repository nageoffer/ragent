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

package com.nageoffer.ai.ragent.core.parser;

import java.util.Map;

/**
 * 文档解析结果
 *
 * @param text     解析后的文本内容
 * @param metadata 文档元数据（可选）
 */
public record ParseResult(String text, Map<String, Object> metadata) {

    /**
     * 创建只包含文本的解析结果
     */
    public static ParseResult ofText(String text) {
        return new ParseResult(text, Map.of());
    }

    /**
     * 创建包含文本和元数据的解析结果
     */
    public static ParseResult of(String text, Map<String, Object> metadata) {
        return new ParseResult(text, metadata != null ? metadata : Map.of());
    }
}
