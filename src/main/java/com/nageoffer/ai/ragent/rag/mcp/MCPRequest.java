package com.nageoffer.ai.ragent.rag.mcp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 调用请求
 * <p>
 * 封装对 MCP 工具的调用参数
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPRequest {

    /**
     * 要调用的工具 ID
     */
    private String toolId;

    /**
     * 用户 ID（用于权限校验和个人数据查询）
     */
    private String userId;

    /**
     * 会话 ID（可选，用于上下文关联）
     */
    private String sessionId;

    /**
     * 原始用户问题
     */
    private String userQuestion;

    /**
     * 调用参数
     */
    @Builder.Default
    private Map<String, Object> parameters = new HashMap<>();

    /**
     * 添加参数
     */
    public MCPRequest addParameter(String key, Object value) {
        if (this.parameters == null) {
            this.parameters = new HashMap<>();
        }
        this.parameters.put(key, value);
        return this;
    }

    /**
     * 获取参数
     */
    @SuppressWarnings("unchecked")
    public <T> T getParameter(String key, Class<T> clazz) {
        Object value = parameters.get(key);
        if (value == null) {
            return null;
        }
        return (T) value;
    }

    /**
     * 获取字符串参数
     */
    public String getStringParameter(String key) {
        Object value = parameters.get(key);
        return value != null ? value.toString() : null;
    }
}
