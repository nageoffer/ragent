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

package com.nageoffer.ai.ragent.mcp.protocol;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * JSON-RPC 2.0 请求
 * <p>
 * 对应 HTTP POST /mcp 的请求体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JsonRpcRequest {

    /**
     * 协议版本，固定为 2.0
     */
    private String jsonrpc = "2.0";

    /**
     * 请求 ID，通知请求可为空
     */
    private Object id;

    /**
     * 调用方法名，例如 initialize、tools/list、tools/call
     */
    private String method;

    /**
     * 方法参数，key 为参数名，value 为参数值
     */
    private Map<String, Object> params;
}
