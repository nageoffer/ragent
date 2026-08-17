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

package com.nageoffer.ai.ragent.rag.core.mcp;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP 工具注册表默认实现
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultMcpToolRegistry implements McpToolRegistry {

    /**
     * 工具执行器存储
     * key: toolId, value: executor
     */
    private final Map<String, McpToolExecutor> executorMap = new HashMap<>();

    /**
     * Spring 容器中的所有 McpToolExecutor Bean（自动注入）
     */
    private final List<McpToolExecutor> autoDiscoveredExecutors;

    /**
     * 启动时自动注册所有发现的执行器
     */
    @PostConstruct
    public void init() {
        if (CollUtil.isEmpty(autoDiscoveredExecutors)) {
            log.info("MCP 工具注册跳过, 未发现任何工具执行器");
        }

        for (McpToolExecutor executor : autoDiscoveredExecutors) {
            register(executor);
        }
        log.info("MCP 工具自动注册完成, 共注册 {} 个工具", autoDiscoveredExecutors.size());
    }

    @Override
    public void register(McpToolExecutor executor) {
        if (executor == null || executor.getToolDefinition() == null) {
            log.warn("尝试注册空的执行器，已忽略");
            return;
        }

        String toolId = executor.getToolId();
        if (StrUtil.isBlank(toolId)) {
            log.warn("工具 ID 为空，已忽略");
            return;
        }

        McpToolExecutor existing = executorMap.put(toolId, executor);
        if (existing != null) {
            log.warn("工具 {} 已存在，已覆盖", toolId);
        } else {
            log.info("MCP 工具注册成功, toolId: {}", toolId);
        }
    }

    @Override
    public void unregister(String toolId) {
        McpToolExecutor removed = executorMap.remove(toolId);
        if (removed != null) {
            log.info("MCP 工具注销成功, toolId: {}", toolId);
        }
    }

    @Override
    public Optional<McpToolExecutor> getExecutor(String toolId) {
        return Optional.ofNullable(executorMap.get(toolId));
    }

    @Override
    public List<Tool> listAllTools() {
        return executorMap.values().stream()
                .map(McpToolExecutor::getToolDefinition)
                .toList();
    }

    @Override
    public List<McpToolExecutor> listAllExecutors() {
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
