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

package com.nageoffer.ai.ragent.rag.core.mcp;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.infra.util.LogSafe;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MCP_PARAMETER_EXTRACT_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MCP_PARAMETER_EXTRACT_USER_PROMPT_PATH;

/**
 * 基于 LLM 的 MCP 参数提取器实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMMcpParameterExtractor implements McpParameterExtractor {

    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;
    private final Gson gson = new Gson();

    @Override
    public McpExtractionResult extractParameters(String userQuestion, Tool tool) {
        return extractParameters(userQuestion, tool, null);
    }

    @Override
    public McpExtractionResult extractParameters(String userQuestion, Tool tool, String customPromptTemplate) {
        if (tool == null || tool.inputSchema() == null || CollUtil.isEmpty(tool.inputSchema().properties())) {
            // 无参工具：直接成功、空参调用
            return McpExtractionResult.success(new HashMap<>());
        }

        // 构建 Prompt：优先使用自定义提示词
        List<ChatMessage> messages = new ArrayList<>(2);
        String systemPrompt = StrUtil.isNotBlank(customPromptTemplate)
                ? customPromptTemplate
                : promptTemplateLoader.load(MCP_PARAMETER_EXTRACT_PROMPT_PATH);

        messages.add(ChatMessage.system(systemPrompt));
        String userPrompt = promptTemplateLoader.render(MCP_PARAMETER_EXTRACT_USER_PROMPT_PATH, Map.of(
                "tool_definition", buildToolDefinition(tool),
                "user_question", userQuestion
        ));
        messages.add(ChatMessage.user(userPrompt));

        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();

        // 标准档调用；协议畸形 / 值非法或调用失败均判 FAILED、不调用工具（档位内已有多候选做传输容错，失败即兜底）
        McpExtractionResult result;
        try {
            result = validateMcpParams(llmService.chat(request), tool);
        } catch (Exception e) {
            log.warn("MCP 参数提取 LLM 调用失败, toolId: {}", tool.name(), e);
            result = McpExtractionResult.failed();
        }

        // 仅 SUCCESS 才填默认值并交由消费端调用；NEED_CLARIFICATION / FAILED 不调用工具故不填
        if (result.status() == McpExtractionResult.Status.SUCCESS) {
            fillDefaults(result.params(), tool);
        }
        log.info("MCP 参数提取完成, toolId: {}, 使用自定义提示词: {}, 结局: {}, 参数: {}",
                tool.name(), StrUtil.isNotBlank(customPromptTemplate), result.status(), result.params());
        return result;
    }

    /**
     * 校验 MCP 提参结果并映射为提取结局
     * <p>
     * 区分三类结局：
     * - JSON 解析失败 / 空响应 / 非对象 / 值类型枚举非法＝模型未遵守协议或输出畸形，判 FAILED、不调用工具
     * - 必填无默认参数缺失或为 null＝用户确实没提供该信息，判 NEED_CLARIFICATION、由消费端向用户追问
     * - 其余正常提取，判 SUCCESS
     */
    private McpExtractionResult validateMcpParams(String raw, Tool tool) {
        log.info("MCP 参数提取 LLM 响应: {}", LogSafe.preview(raw));
        McpParse parsed;
        try {
            parsed = parseAndClassify(raw, tool);
        } catch (Exception e) {
            log.warn("MCP 参数提取响应解析失败, toolId: {}", tool.name(), e);
            return McpExtractionResult.failed();
        }

        if (!parsed.failReasons().isEmpty()) {
            log.warn("MCP 参数提取失败（模型未遵守协议 / 值非法）, toolId: {}, 问题: {}",
                    tool.name(), parsed.failReasons());
            return McpExtractionResult.failed();
        }
        if (!parsed.userMissing().isEmpty()) {
            log.warn("MCP 参数提取缺少必填参数（用户未提供，触发澄清）, toolId: {}, missing: {}",
                    tool.name(), parsed.userMissing());
            return McpExtractionResult.needClarification(parsed.params(), parsed.userMissing());
        }
        return McpExtractionResult.success(parsed.params());
    }

    /**
     * 解析 LLM 响应并按声明参数逐项分类
     * <p>
     * 必填无默认参数缺失或为 null → 归为 userMissing（用户没给，触发澄清）：模型省略 key 与显式输出
     * null 在实践中不可区分，同一业务情形不做分叉。值存在时按 schema type/enum 保守校验，
     * garbage 永不进工具入参：值非法一律判 FAILED（含可选 / 有默认），杜绝静默丢弃导致过滤条件被无声移除
     */
    @SuppressWarnings("unchecked")
    private McpParse parseAndClassify(String raw, Tool tool) {
        Map<String, Object> params = new HashMap<>();
        List<String> failReasons = new ArrayList<>();
        List<String> userMissing = new ArrayList<>();

        JsonSchema schema = tool.inputSchema();
        Map<String, Object> properties = schema != null ? schema.properties() : null;
        if (properties == null || properties.isEmpty()) {
            return new McpParse(params, failReasons, userMissing);
        }
        List<String> required = schema.required() != null ? schema.required() : List.of();

        JsonObject obj = parseJsonObject(raw);

        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> propDef = entry.getValue() instanceof Map
                    ? (Map<String, Object>) entry.getValue() : Map.of();
            boolean isRequired = required.contains(name);
            boolean hasDefault = propDef.get("default") != null;

            boolean present = obj.has(name);
            boolean isNull = present && obj.get(name).isJsonNull();

            if (!present || isNull) {
                // 必填无默认且缺失或为 null：都视为"用户未提供该必填信息"，触发澄清
                if (isRequired && !hasDefault) {
                    userMissing.add(name);
                }
                // 非必填 / 有默认：忽略，交由 fillDefaults 兜底
                continue;
            }

            Object value = convertJsonElement(obj.get(name));
            Optional<Object> coerced = coerceAndValidate(value, propDef);
            if (coerced.isPresent()) {
                params.put(name, coerced.get());
            } else {
                // 字段存在但值类型 / 枚举非法：无论必填与否都判 FAILED
                // 静默丢弃可选 / 有默认字段会让过滤条件被无声移除（如误判枚举 → 时间过滤消失 → 范围扩大）
                failReasons.add(name + "（值类型 / 枚举非法）");
            }
        }
        return new McpParse(params, failReasons, userMissing);
    }

    /**
     * 构建工具定义描述（供 LLM 理解）
     */
    @SuppressWarnings("unchecked")
    private String buildToolDefinition(Tool tool) {
        StringBuilder sb = new StringBuilder();
        sb.append("工具ID: ").append(tool.name()).append("\n");
        sb.append("功能描述: ").append(tool.description()).append("\n");
        sb.append("参数列表:\n");

        JsonSchema schema = tool.inputSchema();
        if (schema == null || schema.properties() == null) {
            return sb.toString();
        }

        List<String> requiredList = schema.required() != null ? schema.required() : List.of();

        for (Map.Entry<String, Object> entry : schema.properties().entrySet()) {
            String paramName = entry.getKey();
            Map<String, Object> propDef = (Map<String, Object>) entry.getValue();

            String type = propDef.getOrDefault("type", "string").toString();
            boolean required = requiredList.contains(paramName);
            String description = propDef.getOrDefault("description", "").toString();
            Object defaultValue = propDef.get("default");
            Object enumValues = propDef.get("enum");

            sb.append("  - ").append(paramName);
            sb.append(" (类型: ").append(type);
            sb.append(required ? ", 必填" : ", 可选");
            sb.append("): ").append(description);

            if (defaultValue != null) {
                sb.append(" [默认值: ").append(defaultValue).append("]");
            }
            if (enumValues instanceof List<?> enumList && !enumList.isEmpty()) {
                String enumStr = enumList.stream().map(Object::toString).collect(Collectors.joining(", "));
                sb.append(" [可选值: ").append(enumStr).append("]");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 把 LLM 原始响应解析为 JSON 对象
     * <p>
     * 空响应＝模型协议失败（并非合法的空对象 {@code {}}），抛出交由 validateMcpParams 判 FAILED；
     * 非 JSON 对象＝模型输出畸形，同样抛出
     */
    private JsonObject parseJsonObject(String raw) {
        if (StrUtil.isBlank(raw)) {
            throw new IllegalStateException("MCP 提参响应为空");
        }
        // 清理可能的 markdown 代码块
        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
        JsonElement element = JsonParser.parseString(cleaned);
        if (!element.isJsonObject()) {
            throw new IllegalStateException("MCP 提参响应不是 JSON 对象");
        }
        return element.getAsJsonObject();
    }

    /**
     * 按参数定义的 type/enum 做保守校验与类型转换
     * <p>
     * 可安全转换的（如数字串→数字、"true"→布尔）转换后返回；不可转换或越出枚举的返回 empty（视为非法值）；
     * type 缺省时不做类型约束
     */
    private Optional<Object> coerceAndValidate(Object value, Map<String, Object> propDef) {
        if (value == null) {
            return Optional.empty();
        }
        Object typed = coerceType(value, Objects.toString(propDef.get("type"), null));
        if (typed == null) {
            return Optional.empty();
        }
        Object enumDef = propDef.get("enum");
        if (enumDef instanceof List<?> enumList && !enumList.isEmpty() && !enumContains(enumList, typed)) {
            return Optional.empty();
        }
        return Optional.of(typed);
    }

    private Object coerceType(Object value, String type) {
        if (StrUtil.isBlank(type)) {
            return value;
        }
        return switch (type) {
            case "string" -> (value instanceof String || value instanceof Number || value instanceof Boolean)
                    ? value.toString() : null;
            case "integer" -> {
                if (value instanceof Integer || value instanceof Long) {
                    yield value;
                }
                yield value instanceof String s ? parseLongOrNull(s) : null;
            }
            case "number" -> {
                if (value instanceof Number) {
                    yield value;
                }
                yield value instanceof String s ? parseDoubleOrNull(s) : null;
            }
            case "boolean" -> {
                if (value instanceof Boolean) {
                    yield value;
                }
                yield value instanceof String s ? parseBooleanOrNull(s) : null;
            }
            case "array" -> value instanceof List ? value : null;
            case "object" -> value instanceof Map ? value : null;
            default -> value;
        };
    }

    private Object parseLongOrNull(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Object parseDoubleOrNull(String s) {
        try {
            double d = Double.parseDouble(s.trim());
            // 拒绝 NaN / Infinity 等非有限值：Double.parseDouble 会接受 "NaN"/"Infinity" 字面量，但它们不是合法 JSON 数值
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Object parseBooleanOrNull(String s) {
        String t = s.trim();
        if ("true".equalsIgnoreCase(t)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(t)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * 枚举包含判断：先按值相等，再按字符串形态相等（容忍 Long/Integer/Double 与枚举字面差异）
     */
    private boolean enumContains(List<?> enumList, Object value) {
        String valueStr = Objects.toString(value, null);
        for (Object e : enumList) {
            if (Objects.equals(e, value) || Objects.equals(Objects.toString(e, null), valueStr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提参分类结果：params＝有效提取值，failReasons＝导致 FAILED 的问题（协议畸形 / 值非法），userMissing＝用户未提供的必填参
     */
    private record McpParse(Map<String, Object> params, List<String> failReasons, List<String> userMissing) {
    }

    /**
     * 转换 JsonElement 为普通 Java 对象
     */
    private Object convertJsonElement(JsonElement element) {
        if (element.isJsonPrimitive()) {
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                double d = primitive.getAsDouble();

                // 非有限值（NaN / Infinity，Gson 宽松解析会接受该字面量）非法：返回 null，后续按值非法判 FAILED
                if (!Double.isFinite(d)) {
                    return null;
                }

                if (d == Math.floor(d)) {
                    if (d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                        return (int) d;
                    } else if (d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) {
                        return (long) d;
                    }
                }
                return d;
            } else if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            } else {
                return primitive.getAsString();
            }
        } else if (element.isJsonArray()) {
            return gson.fromJson(element, List.class);
        } else if (element.isJsonObject()) {
            return gson.fromJson(element, LinkedHashMap.class);
        }
        return null;
    }

    /**
     * 填充默认值
     */
    @SuppressWarnings("unchecked")
    private void fillDefaults(Map<String, Object> params, Tool tool) {
        if (tool.inputSchema() == null || tool.inputSchema().properties() == null) {
            return;
        }

        for (Map.Entry<String, Object> entry : tool.inputSchema().properties().entrySet()) {
            String paramName = entry.getKey();
            Map<String, Object> propDef = (Map<String, Object>) entry.getValue();
            Object defaultValue = propDef.get("default");

            if (!params.containsKey(paramName) && defaultValue != null) {
                params.put(paramName, defaultValue);
            }
        }
    }
}
