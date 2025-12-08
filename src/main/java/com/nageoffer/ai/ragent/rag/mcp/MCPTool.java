package com.nageoffer.ai.ragent.rag.mcp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具定义
 * <p>
 * 描述一个可被调用的外部工具/API，包含工具元信息和参数定义
 * 类似于 Function Calling 中的 function definition
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPTool {

    /**
     * 工具唯一标识
     * 例如：attendance_query、approval_list、leave_balance
     */
    private String toolId;

    /**
     * 工具名称（用于展示）
     */
    private String name;

    /**
     * 工具描述（用于 LLM 理解工具用途）
     */
    private String description;

    /**
     * 示例问题（帮助意图识别匹配）
     */
    private List<String> examples;

    /**
     * 参数定义
     * key: 参数名, value: 参数描述
     */
    private Map<String, ParameterDef> parameters;

    /**
     * 是否需要用户身份（调用时自动注入 userId）
     */
    @Builder.Default
    private boolean requireUserId = true;

    /**
     * MCP Server 地址（可选，用于远程调用）
     */
    private String mcpServerUrl;

    /**
     * 参数定义
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParameterDef {

        /**
         * 参数描述
         */
        private String description;

        /**
         * 参数类型：string, number, boolean, array, object
         */
        @Builder.Default
        private String type = "string";

        /**
         * 是否必填
         */
        @Builder.Default
        private boolean required = false;

        /**
         * 默认值
         */
        private Object defaultValue;

        /**
         * 枚举值（可选）
         */
        private List<String> enumValues;
    }
}
