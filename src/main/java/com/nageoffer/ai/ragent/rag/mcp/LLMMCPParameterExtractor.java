package com.nageoffer.ai.ragent.rag.mcp;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.nageoffer.ai.ragent.convention.ChatMessage;
import com.nageoffer.ai.ragent.convention.ChatRequest;
import com.nageoffer.ai.ragent.rag.chat.LLMService;
import com.nageoffer.ai.ragent.rag.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.constant.RAGConstant.MCP_PARAMETER_EXTRACT_PROMPT_PATH;

/**
 * 基于 LLM 的 MCP 参数提取器实现（V3 Enterprise 专用）
 * <p>
 * 使用大模型从用户问题中智能提取工具调用所需的参数。
 * 适合处理复杂的自然语言参数提取场景。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMMCPParameterExtractor implements MCPParameterExtractor {

    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;
    private final Gson gson = new Gson();

    @Override
    public Map<String, Object> extractParameters(String userQuestion, MCPTool tool) {
        return extractParameters(userQuestion, tool, null);
    }

    @Override
    public Map<String, Object> extractParameters(String userQuestion, MCPTool tool, String customPromptTemplate) {
        if (tool == null || CollUtil.isEmpty(tool.getParameters())) {
            return Collections.emptyMap();
        }

        // 构建 Prompt：优先使用自定义提示词
        List<ChatMessage> messages = new ArrayList<>(3);
        String systemPrompt = StrUtil.isNotBlank(customPromptTemplate)
                ? customPromptTemplate
                : promptTemplateLoader.load(MCP_PARAMETER_EXTRACT_PROMPT_PATH);

        messages.add(ChatMessage.system(systemPrompt));
        messages.add(ChatMessage.user("工具定义如下：\n" + buildToolDefinition(tool)));
        messages.add(ChatMessage.user("请根据以上工具定义，从下面的问题中提取参数：\n" + userQuestion));

        String raw = null;
        try {
            // 调用 LLM 提取参数
            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .temperature(0.1D)
                    .topP(0.3D)
                    .thinking(false)
                    .build();
            raw = llmService.chat(request);
            log.info("MCP 参数提取 LLM 响应: {}", raw);

            // 解析 JSON 响应
            Map<String, Object> extracted = parseJsonResponse(raw, tool);

            // 填充默认值
            fillDefaults(extracted, tool);

            log.info("MCP 参数提取完成, toolId: {}, 参数: {}", tool.getToolId(), extracted);

            return extracted;
        } catch (JsonSyntaxException e) {
            log.warn("MCP 参数提取-JSON解析失败, toolId: {}, 响应: {}", tool.getToolId(), raw, e);
            return buildDefaultParameters(tool);
        } catch (Exception e) {
            log.error("MCP 参数提取异常, toolId: {}", tool.getToolId(), e);
            return buildDefaultParameters(tool);
        }
    }

    private Map<String, Object> buildDefaultParameters(MCPTool tool) {
        Map<String, Object> defaultParams = new HashMap<>();
        fillDefaults(defaultParams, tool);
        return defaultParams;
    }

    /**
     * 构建工具定义描述（供 LLM 理解）
     */
    private String buildToolDefinition(MCPTool tool) {
        StringBuilder sb = new StringBuilder();
        sb.append("工具名称: ").append(tool.getName()).append("\n");
        sb.append("工具ID: ").append(tool.getToolId()).append("\n");
        sb.append("功能描述: ").append(tool.getDescription()).append("\n");
        sb.append("参数列表:\n");

        for (Map.Entry<String, MCPTool.ParameterDef> entry : tool.getParameters().entrySet()) {
            String paramName = entry.getKey();
            MCPTool.ParameterDef def = entry.getValue();

            sb.append("  - ").append(paramName);
            sb.append(" (类型: ").append(def.getType());
            sb.append(def.isRequired() ? ", 必填" : ", 可选");
            sb.append("): ").append(def.getDescription());

            if (def.getDefaultValue() != null) {
                sb.append(" [默认值: ").append(def.getDefaultValue()).append("]");
            }
            if (CollUtil.isNotEmpty(def.getEnumValues())) {
                sb.append(" [可选值: ").append(String.join(", ", def.getEnumValues())).append("]");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 解析 LLM 返回的 JSON 响应
     */
    private Map<String, Object> parseJsonResponse(String raw, MCPTool tool) {
        if (StrUtil.isBlank(raw)) {
            return new HashMap<>();
        }
        // 清理可能的 markdown 代码块
        String cleaned = cleanMarkdownCode(raw);
        JsonElement element = JsonParser.parseString(cleaned);
        if (!element.isJsonObject()) {
            log.warn("LLM 返回的不是 JSON 对象: {}", raw);
            return new HashMap<>();
        }
        JsonObject obj = element.getAsJsonObject();
        Map<String, Object> result = new HashMap<>();
        // 只提取工具定义中声明的参数
        for (String paramName : tool.getParameters().keySet()) {
            if (obj.has(paramName) && !obj.get(paramName).isJsonNull()) {
                JsonElement value = obj.get(paramName);
                result.put(paramName, convertJsonElement(value));
            }
        }
        return result;
    }

    /**
     * 清理 Markdown 代码块
     */
    private String cleanMarkdownCode(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }

    /**
     * 转换 JsonElement 为普通 Java 对象
     */
    private Object convertJsonElement(JsonElement element) {
        if (element.isJsonPrimitive()) {
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                double d = primitive.getAsDouble();

                if (Double.isNaN(d)) {
                    return null;
                }

                if (d == Math.floor(d) && !Double.isInfinite(d)) {
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
    private void fillDefaults(Map<String, Object> params, MCPTool tool) {
        if (tool.getParameters() == null) {
            return;
        }

        for (Map.Entry<String, MCPTool.ParameterDef> entry : tool.getParameters().entrySet()) {
            String paramName = entry.getKey();
            MCPTool.ParameterDef def = entry.getValue();

            if (!params.containsKey(paramName) && def.getDefaultValue() != null) {
                params.put(paramName, def.getDefaultValue());
            }
        }
    }
}
