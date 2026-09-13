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

package com.nageoffer.ai.ragent.mcp.config;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * MCP Server 配置类
 * 
 * MCP客户端(远程) → HTTP POST/SSE → /mcp
    ↓
HttpServletStreamableServerTransportProvider(Servlet) 解析MCP JSON‑RPC报文
    ↓
McpSyncServer 路由消息
    ├─tools/list → 返回全部工具元数据(Tool对象)
    └─tools/call → 找到对应SyncToolSpecification，执行callHandler(就是你的handleCall)
    ↓
工具执行结果再通过transportProvider以HTTP/SSE流返回客户端

 */
@Configuration
public class McpServerConfig {//使用HTTP Streamable网络 HTTP 接口，远程客户端调用；而非使用标准 stdio（本地子进程）

    @Bean
    //MCP 的 HTTP 传输层，处理 HTTP、SSE、JSON‑RPC 编解码；
    public HttpServletStreamableServerTransportProvider transportProvider() {
        return HttpServletStreamableServerTransportProvider.builder()
                .build();
    }

    @Bean
    //把 Servlet 挂载到 Tomcat
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transportProvider) {
        return new ServletRegistrationBean<>(transportProvider, "/mcp");
    }

    @Bean
    //MCP 协议业务核心，管理工具列表、路由`tools/list`/`tools/call`
    public McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transportProvider,
                                   List<McpServerFeatures.SyncToolSpecification> toolSpecs) {
        return McpServer.sync(transportProvider)
                .serverInfo("ragent-mcp-server", "0.0.1")
                .tools(toolSpecs)
                .build();
    }
}
