package com.nageoffer.ai.ragent.rag.mcp;

/**
 * MCP 工具执行器接口
 * <p>
 * 每个具体工具需要实现此接口，定义工具的执行逻辑
 * 类似于 Function Calling 中的具体函数实现
 */
public interface MCPToolExecutor {

    /**
     * 获取工具定义
     *
     * @return 工具元信息
     */
    MCPTool getToolDefinition();

    /**
     * 执行工具调用
     *
     * @param request MCP 请求
     * @return MCP 响应
     */
    MCPResponse execute(MCPRequest request);

    /**
     * 工具 ID（快捷方法）
     */
    default String getToolId() {
        return getToolDefinition().getToolId();
    }

    /**
     * 是否支持该请求
     * 默认只检查 toolId 是否匹配
     */
    default boolean supports(MCPRequest request) {
        return getToolId().equals(request.getToolId());
    }
}
