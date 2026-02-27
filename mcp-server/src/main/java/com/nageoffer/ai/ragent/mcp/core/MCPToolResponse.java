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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 工具调用响应
 * <p>
 * 由执行器返回，随后由协议层转换为 MCP 标准响应结构
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPToolResponse {

    /**
     * 执行是否成功
     */
    @Builder.Default
    private boolean success = true;

    /**
     * 工具 ID
     */
    private String toolId;

    /**
     * 结构化数据，可选
     */
    @Builder.Default
    private Map<String, Object> data = new HashMap<>();

    /**
     * 文本结果
     */
    private String textResult;
    /**
     * 错误消息，失败时使用
     */
    private String errorMessage;
    /**
     * 业务错误码，失败时使用
     */
    private String errorCode;
    /**
     * 执行耗时，单位毫秒
     */
    private long costMs;

    /**
     * 构建成功响应，仅包含文本结果
     *
     * @param toolId 工具 ID
     * @param textResult 文本结果
     * @return 成功响应
     */
    public static MCPToolResponse success(String toolId, String textResult) {
        return MCPToolResponse.builder()
                .success(true)
                .toolId(toolId)
                .textResult(textResult)
                .build();
    }

    /**
     * 构建成功响应，包含文本和结构化数据
     *
     * @param toolId 工具 ID
     * @param textResult 文本结果
     * @param data 结构化数据
     * @return 成功响应
     */
    public static MCPToolResponse success(String toolId, String textResult, Map<String, Object> data) {
        return MCPToolResponse.builder()
                .success(true)
                .toolId(toolId)
                .textResult(textResult)
                .data(data)
                .build();
    }

    /**
     * 构建失败响应
     *
     * @param toolId 工具 ID
     * @param errorCode 错误码
     * @param errorMessage 错误消息
     * @return 失败响应
     */
    public static MCPToolResponse error(String toolId, String errorCode, String errorMessage) {
        return MCPToolResponse.builder()
                .success(false)
                .toolId(toolId)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }
}
