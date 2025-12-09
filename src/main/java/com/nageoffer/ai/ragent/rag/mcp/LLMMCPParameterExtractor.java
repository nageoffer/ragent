package com.nageoffer.ai.ragent.rag.mcp;

import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.rag.chat.LLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.nageoffer.ai.ragent.constant.RAGConstant.MCP_PARAMETER_EXTRACT_PROMPT;

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
    private final Gson gson = new Gson();

    @Override
    public Map<String, Object> extractParameters(String userQuestion, MCPTool tool) {
        if (tool == null || tool.getParameters() == null || tool.getParameters().isEmpty()) {
            return new HashMap<>();
        }

        // 构建工具定义描述
        String toolDefinition = buildToolDefinition(tool);

        // 构建 Prompt
        String prompt = MCP_PARAMETER_EXTRACT_PROMPT.formatted(toolDefinition, userQuestion);

        log.debug("MCP 参数提取 Prompt: {}", prompt);

        try {
            // 调用 LLM 提取参数
            String raw = llmService.chat(prompt);
            log.debug("MCP 参数提取 LLM 响应: {}", raw);

            // 解析 JSON 响应
            Map<String, Object> extracted = parseJsonResponse(raw, tool);

            // 填充默认值
            fillDefaults(extracted, tool);

            log.info("MCP 参数提取完成, toolId: {}, 参数: {}", tool.getToolId(), extracted);
            return extracted;

        } catch (Exception e) {
            log.warn("MCP 参数提取失败, toolId: {}, 原因: {}", tool.getToolId(), e.getMessage());
            // 返回空参数，让后续流程使用默认值
            Map<String, Object> defaultParams = new HashMap<>();
            fillDefaults(defaultParams, tool);
            return defaultParams;
        }
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
            if (def.isRequired()) {
                sb.append(", 必填");
            } else {
                sb.append(", 可选");
            }
            sb.append("): ").append(def.getDescription());

            if (def.getDefaultValue() != null) {
                sb.append(" [默认值: ").append(def.getDefaultValue()).append("]");
            }
            if (def.getEnumValues() != null && !def.getEnumValues().isEmpty()) {
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
        Map<String, Object> result = new HashMap<>();

        if (StrUtil.isBlank(raw)) {
            return result;
        }

        // 清理可能的 markdown 代码块
        String cleaned = raw.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        try {
            JsonElement element = JsonParser.parseString(cleaned);
            if (!element.isJsonObject()) {
                log.warn("LLM 返回的不是 JSON 对象: {}", raw);
                return result;
            }

            JsonObject obj = element.getAsJsonObject();

            // 只提取工具定义中声明的参数
            for (String paramName : tool.getParameters().keySet()) {
                if (obj.has(paramName) && !obj.get(paramName).isJsonNull()) {
                    JsonElement value = obj.get(paramName);
                    result.put(paramName, convertJsonElement(value));
                }
            }

        } catch (Exception e) {
            log.warn("解析 LLM 响应失败: {}", e.getMessage());
        }

        return result;
    }

    /**
     * 转换 JsonElement 为普通 Java 对象
     */
    private Object convertJsonElement(JsonElement element) {
        if (element.isJsonPrimitive()) {
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                // 尝试转为整数
                double d = primitive.getAsDouble();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    return (int) d;
                }
                return d;
            } else if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            } else {
                return primitive.getAsString();
            }
        } else if (element.isJsonArray()) {
            return gson.fromJson(element, Object.class);
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
