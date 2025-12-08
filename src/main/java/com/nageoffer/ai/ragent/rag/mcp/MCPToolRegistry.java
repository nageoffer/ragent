package com.nageoffer.ai.ragent.rag.mcp;

import java.util.List;
import java.util.Optional;

/**
 * MCP 工具注册表接口
 * <p>
 * 管理所有已注册的 MCP 工具，提供工具查找和列表功能
 */
public interface MCPToolRegistry {

    /**
     * 注册工具执行器
     *
     * @param executor 工具执行器
     */
    void register(MCPToolExecutor executor);

    /**
     * 注销工具
     *
     * @param toolId 工具 ID
     */
    void unregister(String toolId);

    /**
     * 根据工具 ID 获取执行器
     *
     * @param toolId 工具 ID
     * @return 工具执行器（可能不存在）
     */
    Optional<MCPToolExecutor> getExecutor(String toolId);

    /**
     * 获取所有已注册的工具定义
     *
     * @return 工具定义列表
     */
    List<MCPTool> listAllTools();

    /**
     * 获取所有已注册的工具执行器
     *
     * @return 执行器列表
     */
    List<MCPToolExecutor> listAllExecutors();

    /**
     * 检查工具是否已注册
     *
     * @param toolId 工具 ID
     * @return 是否已注册
     */
    boolean contains(String toolId);

    /**
     * 获取已注册工具数量
     *
     * @return 工具数量
     */
    int size();
}
