package com.nageoffer.ai.ragent.rag.mcp;

import java.util.Map;

/**
 * MCP 参数提取器接口（V3 Enterprise 专用）
 * <p>
 * 负责从用户问题中提取 MCP 工具所需的参数。
 * 支持 LLM 提取和规则提取两种实现方式。
 */
public interface MCPParameterExtractor {

    /**
     * 从用户问题中提取 MCP 工具所需的参数
     *
     * @param userQuestion 用户原始问题
     * @param tool         MCP 工具定义（包含参数定义）
     * @return 提取到的参数键值对
     */
    Map<String, Object> extractParameters(String userQuestion, MCPTool tool);

    /**
     * 校验必填参数是否完整
     *
     * @param params 已提取的参数
     * @param tool   工具定义
     * @return 校验结果
     */
    default ParameterValidationResult validate(Map<String, Object> params, MCPTool tool) {
        if (tool.getParameters() == null || tool.getParameters().isEmpty()) {
            return ParameterValidationResult.valid(params);
        }

        java.util.List<String> missingRequired = new java.util.ArrayList<>();
        for (Map.Entry<String, MCPTool.ParameterDef> entry : tool.getParameters().entrySet()) {
            String paramName = entry.getKey();
            MCPTool.ParameterDef def = entry.getValue();

            if (def.isRequired()) {
                Object value = params.get(paramName);
                if (value == null) {
                    // 尝试使用默认值
                    if (def.getDefaultValue() != null) {
                        params.put(paramName, def.getDefaultValue());
                    } else {
                        missingRequired.add(paramName);
                    }
                }
            }
        }

        if (missingRequired.isEmpty()) {
            return ParameterValidationResult.valid(params);
        }
        return ParameterValidationResult.invalid(params, missingRequired, tool);
    }

    /**
     * 参数校验结果
     */
    record ParameterValidationResult(
            boolean valid,
            Map<String, Object> params,
            java.util.List<String> missingParams,
            String clarificationMessage
    ) {
        public static ParameterValidationResult valid(Map<String, Object> params) {
            return new ParameterValidationResult(true, params, java.util.List.of(), null);
        }

        public static ParameterValidationResult invalid(Map<String, Object> params,
                                                         java.util.List<String> missingParams,
                                                         MCPTool tool) {
            StringBuilder sb = new StringBuilder();
            sb.append("为了更准确地查询，请补充以下信息：\n");
            for (String paramName : missingParams) {
                MCPTool.ParameterDef def = tool.getParameters().get(paramName);
                sb.append("- ").append(def.getDescription());
                if (def.getEnumValues() != null && !def.getEnumValues().isEmpty()) {
                    sb.append("（可选：").append(String.join("、", def.getEnumValues())).append("）");
                }
                sb.append("\n");
            }
            return new ParameterValidationResult(false, params, missingParams, sb.toString().trim());
        }
    }
}
