/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.mcp;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
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
        if (CollectionUtils.isEmpty(autoDiscoveredExecutors)) {
            log.info("MCP 工具注册跳过, 未发现任何工具执行器");
        }

        for (MCPToolExecutor executor : autoDiscoveredExecutors) {
            register(executor);
        }
        log.info("MCP 工具自动注册完成, 共注册 {} 个工具", autoDiscoveredExecutors.size());
    }

    @Override
    public void register(MCPToolExecutor executor) {
        if (executor == null || executor.getToolDefinition() == null) {
            log.warn("尝试注册空的执行器，已忽略");
            return;
        }

        String toolId = executor.getToolId();
        if (toolId == null || toolId.isBlank()) {
            log.warn("工具 ID 为空，已忽略");
            return;
        }

        MCPToolExecutor existing = executorMap.put(toolId, executor);
        if (existing != null) {
            log.warn("工具 {} 已存在，已覆盖", toolId);
        } else {
            log.info("MCP 工具注册成功, toolId: {}, 工具名称: {}", toolId, executor.getToolDefinition().getName());
        }
    }

    @Override
    public void unregister(String toolId) {
        MCPToolExecutor removed = executorMap.remove(toolId);
        if (removed != null) {
            log.info("MCP 工具注销成功, toolId: {}", toolId);
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
