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
 * MCP 工具调用请求
 * <p>
 * 由协议层解析 tools/call 参数后构建，并传入具体执行器
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPToolRequest {

    /**
     * 目标工具 ID，通常等于 tools/call 的 name
     */
    private String toolId;
    /**
     * 调用方用户 ID，可选
     */
    private String userId;
    /**
     * 会话 ID，可选
     */
    private String conversationId;
    /**
     * 原始用户问题，可选
     */
    private String userQuestion;

    /**
     * 工具参数，key 为参数名，value 为参数值
     */
    @Builder.Default
    private Map<String, Object> parameters = new HashMap<>();

    /**
     * 按指定类型读取参数
     *
     * @param key 参数名
     * @param <T> 目标类型
     * @return 参数值，不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T getParameter(String key) {
        Object value = parameters.get(key);
        return value != null ? (T) value : null;
    }

    /**
     * 读取字符串参数
     *
     * @param key 参数名
     * @return 参数字符串，不存在时返回 null
     */
    public String getStringParameter(String key) {
        Object value = parameters.get(key);
        return value != null ? value.toString() : null;
    }
}
