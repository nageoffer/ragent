package com.nageoffer.ai.ragent.rag.prompt;

import com.nageoffer.ai.ragent.rag.intent.NodeScore;

import java.util.List;

/**
 * MCP 提示词服务接口（V3 Enterprise 专用）
 * <p>
 * 负责构建 MCP 相关场景的提示词，支持：
 * - 纯 MCP 场景（单/多意图）
 * - MCP + KB 混合场景
 */
public interface MCPPromptService {

    /**
     * 构建纯 MCP 场景的提示词
     *
     * @param mcpContext   MCP 工具返回的结果上下文
     * @param userQuestion 用户问题
     * @param mcpIntents   MCP 意图列表
     * @return 构建好的提示词
     */
    String buildMcpOnlyPrompt(String mcpContext, String userQuestion, List<NodeScore> mcpIntents);

    /**
     * 构建 MCP + KB 混合场景的提示词
     *
     * @param mcpContext   MCP 工具返回的结果上下文
     * @param kbContext    KB 检索到的文档上下文
     * @param userQuestion 用户问题
     * @param allIntents   所有意图（MCP + KB）
     * @return 构建好的提示词
     */
    String buildMixedPrompt(String mcpContext, String kbContext, String userQuestion, List<NodeScore> allIntents);

    /**
     * 合并意图的 promptSnippet
     *
     * @param intents 意图列表
     * @return 合并后的规则片段
     */
    String mergeSnippets(List<NodeScore> intents);
}
