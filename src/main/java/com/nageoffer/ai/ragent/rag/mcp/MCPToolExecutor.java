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

/**
 * MCP 工具执行器接口
 * <p>
 * 每个具体工具需要实现此接口，定义工具的执行逻辑
 * 类似于 Function Calling 中的具体函数实现
 */
public interface MCPToolExecutor {

    /**
     * 获取工具定义
     *
     * @return 工具元信息
     */
    MCPTool getToolDefinition();

    /**
     * 执行工具调用
     *
     * @param request MCP 请求
     * @return MCP 响应
     */
    MCPResponse execute(MCPRequest request);

    /**
     * 工具 ID（快捷方法）
     */
    default String getToolId() {
        return getToolDefinition().getToolId();
    }

    /**
     * 是否支持该请求
     * 默认只检查 toolId 是否匹配
     */
    default boolean supports(MCPRequest request) {
        return getToolId().equals(request.getToolId());
    }
}
