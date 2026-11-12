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

package com.nageoffer.ai.ragent.agent.tool;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 模式的 MCP 连接和工具发现由 AgentScope 持有
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
@EnableConfigurationProperties(AgentMcpProperties.class)
public class AgentMcpClients {

    private final AgentMcpProperties properties;
    private final Map<String, RemoteTool> tools = new LinkedHashMap<>();
    private final List<McpClientWrapper> clients = new ArrayList<>();

    @PostConstruct
    public void init() {
        List<AgentMcpProperties.ServerConfig> servers = properties.getServers();
        if (servers == null) {
            return;
        }
        for (AgentMcpProperties.ServerConfig server : servers) {
            connect(server);
        }
    }

    private void connect(AgentMcpProperties.ServerConfig server) {
        McpClientWrapper client = null;
        try {
            String baseUrl = StrUtil.removeSuffix(server.getUrl(), "/");
            String url = baseUrl.endsWith("/mcp") ? baseUrl : baseUrl + "/mcp";
            client = McpClientBuilder.create(server.getName())
                    .streamableHttpTransport(url)
                    .buildSync();
            client.initialize().block();
            List<Tool> discovered = client.listTools().block();
            clients.add(client);
            if (discovered != null) {
                for (Tool tool : discovered) {
                    RemoteTool existing = tools.putIfAbsent(tool.name(), new RemoteTool(tool, client));
                    if (existing != null) {
                        log.warn("MCP 工具重名，保留先连接的服务, toolId={}, server={}", tool.name(), server.getName());
                    }
                }
            }
            log.info("AgentScope MCP 服务已连接, server={}, tools={}", server.getName(),
                    discovered == null ? 0 : discovered.size());
        } catch (Exception e) {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception closeError) {
                    log.warn("关闭失败的 MCP 连接时发生异常, server={}", server.getName(), closeError);
                }
            }
            log.error("AgentScope MCP 服务连接失败, server={}, reason={}", server.getName(), e.getMessage());
        }
    }

    public RemoteTool get(String toolId) {
        return tools.get(toolId);
    }

    @PreDestroy
    public void close() {
        for (McpClientWrapper client : clients) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭 MCP 连接失败, server={}", client.getName(), e);
            }
        }
    }

    public record RemoteTool(Tool definition, McpClientWrapper client) {
    }
}
