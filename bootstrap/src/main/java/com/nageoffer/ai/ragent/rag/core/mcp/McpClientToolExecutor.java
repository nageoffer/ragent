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

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具执行器：通过官方 SDK 的 McpSyncClient 调用远端 MCP Server 暴露的工具
 * 负责工具发现（tools/list）、参数封装、调用结果与异常的标准化处理
 */
@Slf4j
@RequiredArgsConstructor
public class McpClientToolExecutor implements McpToolExecutor {

    private final McpSyncClient mcpClient;
    private final Tool toolDefinition;

    @Override
    public Tool getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public CallToolResult execute(Map<String, Object> parameters) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = parameters != null ? parameters : Map.of();
            CallToolResult result = mcpClient.callTool(new CallToolRequest(toolDefinition.name(), args));
            log.info("MCP 远程工具调用完成, toolId={}, params={}, contentSize={}, elapsed={}ms",
                    toolDefinition.name(), args,
                    result.content() != null ? result.content().size() : 0,
                    System.currentTimeMillis() - startMs);
            return result;
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("MCP 远程工具调用异常, toolId={}, params={}, elapsed={}ms, reason={}",
                    toolDefinition.name(), parameters,
                    System.currentTimeMillis() - startMs, reason);
            return CallToolResult.builder()
                    .content(List.of(new TextContent("远程调用失败: " + reason)))
                    .isError(true)
                    .build();
        }
    }
}
