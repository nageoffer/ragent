package com.nageoffer.ai.ragent.rag.mcp;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具注册表默认实现
 * <p>
 * 使用 ConcurrentHashMap 存储工具执行器，支持运行时动态注册/注销
 * 启动时自动扫描并注册所有 MCPToolExecutor Bean
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultMCPToolRegistry implements MCPToolRegistry {

    /**
     * 工具执行器存储
     * key: toolId, value: executor
     */
    private final Map<String, MCPToolExecutor> executorMap = new ConcurrentHashMap<>();

    /**
     * Spring 容器中的所有 MCPToolExecutor Bean（自动注入）
     */
    private final List<MCPToolExecutor> autoDiscoveredExecutors;

    /**
     * 启动时自动注册所有发现的执行器
     */
    @PostConstruct
    public void init() {
        if (autoDiscoveredExecutors != null && !autoDiscoveredExecutors.isEmpty()) {
            for (MCPToolExecutor executor : autoDiscoveredExecutors) {
                register(executor);
            }
            log.info("[MCPToolRegistry] 自动注册 {} 个 MCP 工具", autoDiscoveredExecutors.size());
        } else {
            log.info("[MCPToolRegistry] 未发现任何 MCP 工具执行器");
        }
    }

    @Override
    public void register(MCPToolExecutor executor) {
        if (executor == null || executor.getToolDefinition() == null) {
            log.warn("[MCPToolRegistry] 尝试注册空的执行器，已忽略");
            return;
        }

        String toolId = executor.getToolId();
        if (toolId == null || toolId.isBlank()) {
            log.warn("[MCPToolRegistry] 工具 ID 为空，已忽略");
            return;
        }

        MCPToolExecutor existing = executorMap.put(toolId, executor);
        if (existing != null) {
            log.warn("[MCPToolRegistry] 工具 {} 已存在，已覆盖", toolId);
        } else {
            log.info("[MCPToolRegistry] 注册工具: {} - {}", toolId, executor.getToolDefinition().getName());
        }
    }

    @Override
    public void unregister(String toolId) {
        MCPToolExecutor removed = executorMap.remove(toolId);
        if (removed != null) {
            log.info("[MCPToolRegistry] 注销工具: {}", toolId);
        }
    }

    @Override
    public Optional<MCPToolExecutor> getExecutor(String toolId) {
        return Optional.ofNullable(executorMap.get(toolId));
    }

    @Override
    public List<MCPTool> listAllTools() {
        return executorMap.values().stream()
                .map(MCPToolExecutor::getToolDefinition)
                .toList();
    }

    @Override
    public List<MCPToolExecutor> listAllExecutors() {
        return new ArrayList<>(executorMap.values());
    }

    @Override
    public boolean contains(String toolId) {
        return executorMap.containsKey(toolId);
    }

    @Override
    public int size() {
        return executorMap.size();
    }
}
