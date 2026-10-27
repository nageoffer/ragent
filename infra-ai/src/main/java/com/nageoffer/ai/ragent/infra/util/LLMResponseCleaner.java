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

package com.nageoffer.ai.ragent.infra.util;

import lombok.NoArgsConstructor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 输出清理工具类
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class LLMResponseCleaner {

    private static final String FENCE = "```";

    private static final Pattern LEADING_CODE_FENCE = Pattern.compile("^```[\\w-]*\\s*\\n?");
    private static final Pattern CLOSING_CODE_FENCE = Pattern.compile("(?m)^[\\t ]*```[\\t ]*$");
    private static final Pattern TRAILING_CODE_FENCE = Pattern.compile("\\n?```\\s*$");

    /**
     * 移除 Markdown 代码块围栏（例如 ```json ... ```）
     * <p>
     * 有开头围栏时只保留第一段代码块，忽略闭栏后的说明或其他代码块。
     * 独立闭栏行前后允许空格或制表符；没有独立闭栏时，仅剥离末尾围栏以兼容单行输出。
     */
    public static String stripMarkdownCodeFence(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim();
        if (!cleaned.startsWith(FENCE)) {
            return TRAILING_CODE_FENCE.matcher(cleaned).replaceFirst("").trim();
        }
        cleaned = LEADING_CODE_FENCE.matcher(cleaned).replaceFirst("");
        Matcher closing = CLOSING_CODE_FENCE.matcher(cleaned);
        return (closing.find()
                ? cleaned.substring(0, closing.start())
                : TRAILING_CODE_FENCE.matcher(cleaned).replaceFirst("")).trim();
    }
}
