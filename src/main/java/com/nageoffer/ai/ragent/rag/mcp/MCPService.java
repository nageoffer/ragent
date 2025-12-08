package com.nageoffer.ai.ragent.rag.mcp;

import java.util.List;

/**
 * MCP 服务接口
 * <p>
 * 提供 MCP 工具调用的统一入口，负责：
 * - 根据 toolId 找到对应的执行器
 * - 执行工具调用
 * - 处理调用结果
 */
public interface MCPService {

    /**
     * 执行单个工具调用
     *
     * @param request MCP 请求
     * @return MCP 响应
     */
    MCPResponse execute(MCPRequest request);

    /**
     * 批量执行多个工具调用（并行）
     *
     * @param requests MCP 请求列表
     * @return MCP 响应列表（顺序与请求一致）
     */
    List<MCPResponse> executeBatch(List<MCPRequest> requests);

    /**
     * 获取所有可用的工具定义
     *
     * @return 工具定义列表
     */
    List<MCPTool> listAvailableTools();

    /**
     * 检查工具是否可用
     *
     * @param toolId 工具 ID
     * @return 是否可用
     */
    boolean isToolAvailable(String toolId);

    /**
     * 构建工具描述文本（用于 Prompt）
     * <p>
     * 生成类似于 Function Calling 的工具描述，供 LLM 理解可用工具
     *
     * @return 工具描述文本
     */
    String buildToolsDescription();

    /**
     * 将多个 MCP 响应合并为文本（用于拼接到 Prompt）
     *
     * @param responses MCP 响应列表
     * @return 合并后的文本
     */
    String mergeResponsesToText(List<MCPResponse> responses);
}
