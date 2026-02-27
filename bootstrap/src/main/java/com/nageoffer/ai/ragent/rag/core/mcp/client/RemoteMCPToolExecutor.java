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

import com.nageoffer.ai.ragent.rag.core.mcp.MCPRequest;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPTool;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 远程 MCP 工具执行器
 * 实现 MCPToolExecutor 接口，通过 MCPClient 远程调用 MCP Server 上的工具
 */
@Slf4j
@RequiredArgsConstructor
public class RemoteMCPToolExecutor implements MCPToolExecutor {

    private final MCPClient mcpClient;
    private final MCPTool toolDefinition;

    @Override
    public MCPTool getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public MCPResponse execute(MCPRequest request) {
        long start = System.currentTimeMillis();
        try {
            String result = mcpClient.callTool(toolDefinition.getToolId(), request.getParameters());
            long costMs = System.currentTimeMillis() - start;

            if (result == null) {
                MCPResponse response = MCPResponse.error(request.getToolId(), "REMOTE_CALL_FAILED", "远程工具调用失败");
                response.setCostMs(costMs);
                return response;
            }

            MCPResponse response = MCPResponse.success(request.getToolId(), result);
            response.setCostMs(costMs);
            return response;
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - start;
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("远程 MCP 工具调用异常, toolId={}, reason={}", request.getToolId(), reason);
            MCPResponse response = MCPResponse.error(request.getToolId(), "REMOTE_CALL_ERROR", reason);
            response.setCostMs(costMs);
            return response;
        }
    }
}
