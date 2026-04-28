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

package com.nageoffer.ai.ragent.rag.core.memory.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;

/**
 * 统一处理语义记忆、记忆类型分类和可读渲染。
 */
public final class SemanticMemorySupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SemanticMemorySupport() {
    }

    public static String normalizeValueJson(String type, String content, String source, String existingJson) {
        JsonNode existing = readTree(existingJson);
        if (existing != null && existing.hasNonNull("kind")) {
            return existingJson;
        }
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("kind", normalizeType(type));
        root.put("source", defaultString(resolveSource(existing, source)));
        root.put("rawContent", defaultString(content));
        root.put("content", defaultString(content));
        if (isPreference(type)) {
            root.put("subject", defaultString(inferPreferenceSubject(content)));
            root.put("polarity", defaultString(extractPreferencePolarity(content)));
            root.put("expression", defaultString(extractPreferenceExpression(content)));
        } else if (isProfile(type)) {
            String attribute = inferProfileAttribute(content);
            root.put("attribute", defaultString(attribute));
            root.put("value", defaultString(inferProfileValue(content, attribute)));
        } else {
            root.put("value", defaultString(content));
        }
        return writeJson(root, existingJson);
    }

    public static String resolveSemanticKey(String type, String content, String valueJson) {
        JsonNode root = readTree(valueJson);
        if (isPreference(type)) {
            String subject = firstNonBlank(text(root, "subject"), inferPreferenceSubject(content), content);
            return "preference:" + normalizeToken(subject, "general");
        }
        if (isProfile(type)) {
            String attribute = firstNonBlank(text(root, "attribute"), inferProfileAttribute(content), "general");
            String value = firstNonBlank(text(root, "value"), inferProfileValue(content, attribute), content);
            return "profile:" + normalizeToken(attribute, "general") + ":" + normalizeToken(value, "general");
        }
        String fallback = firstNonBlank(text(root, "value"), content, type);
        return normalizeToken(fallback, "memory");
    }

    public static String renderContent(String semanticKey, String semanticType, String valueJson) {
        JsonNode root = readTree(valueJson);
        if (isPreference(semanticType)) {
            String subject = firstNonBlank(text(root, "subject"), stripPrefix(semanticKey, "preference:"), semanticKey);
            String polarity = firstNonBlank(text(root, "polarity"), "positive");
            return "negative".equalsIgnoreCase(polarity) ? "用户不喜欢" + subject : "用户偏好" + subject;
        }
        if (isProfile(semanticType)) {
            String attribute = firstNonBlank(text(root, "attribute"), "general");
            String value = firstNonBlank(text(root, "value"), semanticKey);
            return switch (attribute.toLowerCase(Locale.ROOT)) {
                case "role", "identity" -> "用户身份: " + value;
                case "organization" -> "用户所在组织: " + value;
                case "tool" -> "用户常用工具: " + value;
                case "stack" -> "用户技术栈: " + value;
                case "location" -> "用户所在位置: " + value;
                case "language" -> "用户常用语言: " + value;
                default -> "用户画像: " + value;
            };
        }
        return firstNonBlank(text(root, "rawContent"), text(root, "value"), semanticKey);
    }

    public static String extractSource(String valueJson) {
        return text(readTree(valueJson), "source");
    }

    public static String extractProfileAttribute(String valueJson) {
        return firstNonBlank(text(readTree(valueJson), "attribute"), "general");
    }

    public static String extractProfileValue(String valueJson) {
        return firstNonBlank(text(readTree(valueJson), "value"), "");
    }

    public static String extractPreferenceSubject(String valueJson) {
        return firstNonBlank(text(readTree(valueJson), "subject"), "");
    }

    public static String querySemanticCategory(String query) {
        String memoryType = queryMemoryType(query);
        if ("PREFERENCE".equals(memoryType)) {
            return "preference";
        }
        if (!"PROFILE".equals(memoryType)) {
            return "";
        }
        if (containsAny(query, "技术栈", "后端", "前端", "框架", "语言栈")) {
            return "stack";
        }
        if (containsAny(query, "工具", "IDE", "编辑器", "终端", "软件")) {
            return "tool";
        }
        if (containsAny(query, "公司", "团队", "组织", "就职", "供职")) {
            return "organization";
        }
        if (containsAny(query, "身份", "职业", "岗位", "角色", "是谁")) {
            return "identity";
        }
        if (containsAny(query, "哪里", "在哪", "来自", "住在", "城市")) {
            return "location";
        }
        if (containsAny(query, "语言", "中文", "英文", "英语", "日语")) {
            return "language";
        }
        return "";
    }

    public static String queryMemoryType(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        if (containsAny(query, "待办", "todo", "还要做", "要做", "需要做", "后续")) {
            return "TODO";
        }
        if (containsAny(query, "问题", "报错", "异常", "失败", "bug", "error")) {
            return "ISSUE";
        }
        if (containsAny(query, "总结", "摘要", "概括", "回顾")) {
            return "SUMMARY";
        }
        if (containsAny(query, "事实", "信息", "记录", "情况")) {
            return "FACT";
        }
        if (containsAny(query, "偏好", "喜欢", "不喜欢", "讨厌")) {
            return "PREFERENCE";
        }
        if (containsAny(query, "画像", "身份", "职业", "岗位", "角色",
                "组织", "公司", "团队", "工具", "技术栈", "地点", "语言", "哪里", "来自", "住在")) {
            return "PROFILE";
        }
        return "";
    }

    public static boolean isNegativePreference(String semanticType, String semanticKey, String valueJson) {
        if (!isPreference(semanticType)) {
            return false;
        }
        String polarity = text(readTree(valueJson), "polarity");
        if (polarity != null) {
            return "negative".equalsIgnoreCase(polarity);
        }
        return semanticKey != null && (semanticKey.contains("不喜欢") || semanticKey.contains("讨厌"));
    }

    public static boolean isPositivePreference(String semanticType, String semanticKey, String valueJson) {
        if (!isPreference(semanticType)) {
            return false;
        }
        String polarity = text(readTree(valueJson), "polarity");
        if (polarity != null) {
            return "positive".equalsIgnoreCase(polarity);
        }
        return semanticKey != null && (semanticKey.contains("喜欢") || semanticKey.contains("偏好"));
    }

    public static String normalizePreferenceSubject(String semanticKey, String valueJson) {
        String subject = firstNonBlank(text(readTree(valueJson), "subject"), stripPrefix(semanticKey, "preference:"));
        return normalizeToken(subject, "");
    }

    public static boolean looksLikePreference(String content) {
        return containsAny(content, "喜欢", "偏好", "不喜欢", "讨厌");
    }

    public static boolean looksLikeProfile(String content) {
        return containsAny(content, "我是", "我在", "我用", "常用", "主要用",
                "技术栈", "后端", "前端", "来自", "住在", "中文", "英文");
    }

    private static String resolveSource(JsonNode existing, String fallback) {
        String source = text(existing, "source");
        return source == null ? fallback : source;
    }

    private static boolean isPreference(String type) {
        return "PREFERENCE".equalsIgnoreCase(type);
    }

    private static boolean isProfile(String type) {
        return "PROFILE".equalsIgnoreCase(type);
    }

    private static String normalizeType(String type) {
        return type == null ? "memory" : type.toLowerCase(Locale.ROOT);
    }

    private static String inferPreferenceSubject(String content) {
        String subject = extractAfterKeywords(content, "不喜欢", "喜欢", "偏好", "讨厌", "热爱");
        if (subject.isBlank()) {
            subject = content == null ? "" : content;
        }
        return cleanupValue(subject);
    }

    private static String extractPreferencePolarity(String content) {
        if (content == null) {
            return "positive";
        }
        return (content.contains("不喜欢") || content.contains("讨厌")) ? "negative" : "positive";
    }

    private static String extractPreferenceExpression(String content) {
        if (content == null) {
            return "";
        }
        if (content.contains("不喜欢")) {
            return "不喜欢";
        }
        if (content.contains("喜欢")) {
            return "喜欢";
        }
        if (content.contains("偏好")) {
            return "偏好";
        }
        if (content.contains("讨厌")) {
            return "讨厌";
        }
        return "";
    }

    private static String inferProfileAttribute(String content) {
        if (content == null || content.isBlank()) {
            return "general";
        }
        if (containsAny(content, "技术栈", "后端", "前端", "框架", "主要写")) {
            return "stack";
        }
        if (containsAny(content, "我是", "身份", "职业", "担任", "负责")) {
            return "identity";
        }
        if (containsAny(content, "我在", "就职于", "供职于", "公司", "团队")) {
            return "organization";
        }
        if (containsAny(content, "我用", "常用", "主要用", "习惯用", "使用")) {
            return "tool";
        }
        if (containsAny(content, "住在", "来自", "在北京", "在上海", "在深圳", "在广州", "在杭州")) {
            return "location";
        }
        if (containsAny(content, "中文", "英文", "日语", "英语")) {
            return "language";
        }
        return "general";
    }

    private static String inferProfileValue(String content, String attribute) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String value = switch (attribute == null ? "general" : attribute) {
            case "identity" -> extractAfterKeywords(content, "我是一名", "我是个", "我是", "身份是", "职业是", "担任");
            case "organization" -> extractAfterKeywords(content, "我在", "就职于", "供职于", "公司", "团队");
            case "tool" -> extractAfterKeywords(content, "我用", "常用", "主要用", "习惯用", "使用");
            case "stack" -> extractAfterKeywords(content, "技术栈", "后端", "前端", "框架", "主要写");
            case "location" -> extractAfterKeywords(content, "住在", "来自", "在");
            case "language" -> extractLanguageValue(content);
            default -> content;
        };
        return cleanupValue(value);
    }

    private static String extractLanguageValue(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        if (content.contains("中文")) {
            return "中文";
        }
        if (content.contains("英文") || content.contains("英语")) {
            return "英文";
        }
        if (content.contains("日语")) {
            return "日语";
        }
        return extractAfterKeywords(content, "用", "说");
    }

    private static String extractAfterKeywords(String content, String... keywords) {
        if (content == null || content.isBlank()) {
            return "";
        }
        for (String keyword : keywords) {
            int index = content.indexOf(keyword);
            if (index < 0) {
                continue;
            }
            String tail = content.substring(index + keyword.length());
            String cleaned = cleanupValue(tail);
            if (!cleaned.isBlank()) {
                return cleaned;
            }
        }
        return "";
    }

    private static String cleanupValue(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        cleaned = cleaned.replace("。", " ").replace("，", " ").replace(",", " ")
                .replace("；", " ").replace(";", " ").replace("：", " ")
                .replace(":", " ").replace("！", " ").replace("!", " ")
                .replace("？", " ").replace("?", " ").replace("、", " ")
                .replace("的", " ").replace("是", " ").trim();
        int splitIndex = cleaned.indexOf(' ');
        if (splitIndex > 0) {
            cleaned = cleaned.substring(0, splitIndex);
        }
        if (cleaned.length() > 48) {
            return cleaned.substring(0, 48);
        }
        return cleaned;
    }

    private static String normalizeToken(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replace("用户", "")
                .replace("我", "")
                .replace("的", "")
                .replace(" ", "-")
                .replace("_", "-")
                .replace(":", "-");
        normalized = normalized.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsIdeographic}-]", "");
        normalized = normalized.replaceAll("-{2,}", "-");
        normalized = stripEdgeDash(normalized);
        if (normalized.isBlank()) {
            return fallback;
        }
        return normalized.length() > 48 ? normalized.substring(0, 48) : normalized;
    }

    private static String stripEdgeDash(String value) {
        String result = value;
        while (result.startsWith("-")) {
            result = result.substring(1);
        }
        while (result.endsWith("-")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String stripPrefix(String value, String prefix) {
        if (value == null) {
            return "";
        }
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private static boolean containsAny(String value, String... keywords) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lowerValue = value.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lowerValue.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || field == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String writeJson(ObjectNode root, String fallback) {
        try {
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            return fallback == null ? "{}" : fallback;
        }
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }
}
