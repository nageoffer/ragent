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

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.Map;

/**
 * MCP 工具执行器接口
 */
public interface McpToolExecutor {

    /**
     * 获取工具定义
     *
     * @return 工具元信息（使用官方 SDK 的 Tool）
     */
    Tool getToolDefinition();

    /**
     * 执行工具调用
     *
     * @param parameters 调用参数
     * @return 工具调用结果（使用官方 SDK 的 CallToolResult）
     */
    CallToolResult execute(Map<String, Object> parameters);

    /**
     * 工具 ID（快捷方法）
     */
    default String getToolId() {
        return getToolDefinition().name();
    }
}
