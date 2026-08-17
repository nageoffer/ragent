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

package com.nageoffer.ai.ragent.ingestion.prompt;

import com.nageoffer.ai.ragent.ingestion.domain.enums.EnhanceType;

import java.util.EnumMap;
import java.util.Map;

public final class EnhancerPromptManager {

    private static final Map<EnhanceType, String> DEFAULT_SYSTEM_PROMPTS = new EnumMap<>(EnhanceType.class);

    static {
        DEFAULT_SYSTEM_PROMPTS.put(EnhanceType.CONTEXT_ENHANCE, """
                你是文档整理专家。请对以下可能存在格式问题的文档内容进行整理：
                1. 修复明显的格式错误（表格错位、段落混乱）
                2. 保持原文核心信息完整
                3. 保持专业术语准确性
                4. 直接输出整理后的文本，不要添加任何解释
                """);

        DEFAULT_SYSTEM_PROMPTS.put(EnhanceType.KEYWORDS, """
                从文本中提取 5-15 个最重要的关键词/短语。
                优先选择：专业术语、核心概念、重要实体名称。
                输出格式：JSON 数组，如 ["关键词1", "关键词2"]
                只输出 JSON，不要其他内容。
                """);

        DEFAULT_SYSTEM_PROMPTS.put(EnhanceType.QUESTIONS, """
                根据文本内容生成 3-5 个有价值的问题，帮助读者理解核心内容。
                输出格式：JSON 数组，如 ["问题1", "问题2"]
                只输出 JSON，不要其他内容。
                """);

        DEFAULT_SYSTEM_PROMPTS.put(EnhanceType.METADATA, """
                从文本中提取重要的结构化信息，整理为 JSON 对象。
                字段尽量使用英文键名，值类型使用 string/number/array/object。
                只输出 JSON，不要其他内容。
                """);
    }

    private EnhancerPromptManager() {
    }

    public static String systemPrompt(EnhanceType type) {
        return DEFAULT_SYSTEM_PROMPTS.get(type);
    }
}
