package com.nageoffer.ai.ragent.rag.mcp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 调用响应
 * <p>
 * 封装 MCP 工具调用的返回结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPResponse {

    /**
     * 是否调用成功
     */
    @Builder.Default
    private boolean success = true;

    /**
     * 工具 ID
     */
    private String toolId;

    /**
     * 结果数据（结构化）
     */
    @Builder.Default
    private Map<String, Object> data = new HashMap<>();

    /**
     * 文本形式的结果（用于直接拼接到 Prompt）
     */
    private String textResult;

    /**
     * 错误信息（调用失败时）
     */
    private String errorMessage;

    /**
     * 错误码（调用失败时）
     */
    private String errorCode;

    /**
     * 调用耗时（毫秒）
     */
    private long costMs;

    /**
     * 创建成功响应
     */
    public static MCPResponse success(String toolId, String textResult) {
        return MCPResponse.builder()
                .success(true)
                .toolId(toolId)
                .textResult(textResult)
                .build();
    }

    /**
     * 创建成功响应（带结构化数据）
     */
    public static MCPResponse success(String toolId, String textResult, Map<String, Object> data) {
        return MCPResponse.builder()
                .success(true)
                .toolId(toolId)
                .textResult(textResult)
                .data(data)
                .build();
    }

    /**
     * 创建失败响应
     */
    public static MCPResponse error(String toolId, String errorCode, String errorMessage) {
        return MCPResponse.builder()
                .success(false)
                .toolId(toolId)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }
}
