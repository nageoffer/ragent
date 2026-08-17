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

package com.nageoffer.ai.ragent.rag.core.prompt;

import cn.hutool.core.util.StrUtil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PromptTemplateUtils {

    private static final Pattern MULTI_BLANK_LINES = Pattern.compile("(\\n){3,}");
    private static final Pattern SECTION_HEADER = Pattern.compile("^---\\s*section:\\s*(\\S+)\\s*---$", Pattern.MULTILINE);

    public static String cleanupPrompt(String prompt) {
        if (prompt == null) {
            return "";
        }
        return MULTI_BLANK_LINES.matcher(prompt).replaceAll("\n\n").trim();
    }

    public static String fillSlots(String template, Map<String, String> slots) {
        if (template == null) {
            return "";
        }
        if (slots == null || slots.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : slots.entrySet()) {
            String value = StrUtil.emptyIfNull(entry.getValue());
            result = result.replace("{" + entry.getKey() + "}", value);
        }
        return result;
    }

    /**
     * 将包含 {@code --- section: name ---} 分隔符的模板文件解析为 name → content 映射
     */
    public static Map<String, String> parseSections(String content) {
        Map<String, String> sections = new LinkedHashMap<>();
        if (StrUtil.isBlank(content)) {
            return sections;
        }
        Matcher matcher = SECTION_HEADER.matcher(content);
        int lastStart = -1;
        String lastName = null;
        while (matcher.find()) {
            if (lastName != null) {
                sections.put(lastName, trimSection(content.substring(lastStart, matcher.start())));
            }
            lastName = matcher.group(1);
            lastStart = matcher.end();
        }
        if (lastName != null) {
            sections.put(lastName, trimSection(content.substring(lastStart)));
        }
        return sections;
    }

    /**
     * 去掉 section 内容的首尾空行，但保留内部结构
     */
    private static String trimSection(String section) {
        // 去掉开头的一个换行和结尾的空白
        if (section.startsWith("\n")) {
            section = section.substring(1);
        }
        return section.stripTrailing();
    }
}
