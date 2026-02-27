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

package com.nageoffer.ai.ragent.mcp.core;

/**
 * MCP 工具执行器接口
 */
public interface MCPToolExecutor {

    /**
     * 获取工具定义信息
     * 返回该工具的元数据定义，包括工具ID、名称、描述、参数定义等信息
     *
     * @return 工具定义对象
     */
    MCPToolDefinition getToolDefinition();

    /**
     * 执行工具逻辑
     * 根据传入的请求参数执行具体的工具逻辑，并返回执行结果
     *
     * @param request 工具执行请求，包含执行所需的参数
     * @return 工具执行响应，包含执行结果和状态信息
     */
    MCPToolResponse execute(MCPToolRequest request);

    /**
     * 获取工具唯一标识
     * 默认实现从工具定义中获取工具ID，用于标识和查找工具
     *
     * @return 工具唯一标识符
     */
    default String getToolId() {
        return getToolDefinition().getToolId();
    }
}
