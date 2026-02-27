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

package com.nageoffer.ai.ragent.rag.core.mcp.client;

import com.nageoffer.ai.ragent.rag.core.mcp.MCPTool;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * MCP 客户端自动配置
 * 根据配置的 MCP Server 列表，自动创建 MCPClient 并注册远程工具到 MCPToolRegistry
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(MCPClientProperties.class)
public class MCPClientAutoConfiguration {

    private final MCPClientProperties properties;
    private final OkHttpClient okHttpClient;
    private final MCPToolRegistry toolRegistry;

    @PostConstruct
    public void init() {
        List<MCPClientProperties.ServerConfig> servers = properties.getServers();
        if (servers == null || servers.isEmpty()) {
            log.info("未配置 MCP Server，跳过远程工具注册");
            return;
        }

        for (MCPClientProperties.ServerConfig server : servers) {
            registerRemoteTools(server);
        }
    }

    private void registerRemoteTools(MCPClientProperties.ServerConfig server) {
        String serverName = server.getName();
        String serverUrl = server.getUrl();
        log.info("连接 MCP Server: name={}, url={}", serverName, serverUrl);

        try {
            HttpMCPClient mcpClient = new HttpMCPClient(okHttpClient, serverUrl);

            // 初始化连接
            mcpClient.initialize();

            // 获取远程工具列表
            List<MCPTool> tools = mcpClient.listTools();
            if (tools.isEmpty()) {
                log.warn("MCP Server [{}] 未发现可用工具，跳过工具注册", serverName);
                return;
            }
            log.info("MCP Server [{}] 返回 {} 个工具", serverName, tools.size());

            // 为每个远程工具创建 RemoteMCPToolExecutor 并注册
            for (MCPTool tool : tools) {
                RemoteMCPToolExecutor executor = new RemoteMCPToolExecutor(mcpClient, tool);
                toolRegistry.register(executor);
                log.info("注册远程 MCP 工具: toolId={}, server={}", tool.getToolId(), serverName);
            }
        } catch (Exception e) {
            log.warn("连接 MCP Server [{}] 失败，跳过工具注册，reason={}", serverName, e.getMessage());
        }
    }
}
