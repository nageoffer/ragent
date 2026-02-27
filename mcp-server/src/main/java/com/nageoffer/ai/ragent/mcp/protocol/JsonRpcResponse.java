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

/**
 * JSON-RPC 2.0 响应
 * <p>
 * 成功时返回 result，失败时返回 error
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JsonRpcResponse {

    /**
     * 协议版本，固定为 2.0
     */
    private String jsonrpc = "2.0";

    /**
     * 与请求对应的 ID
     */
    private Object id;

    /**
     * 成功结果
     */
    private Object result;

    /**
     * 错误对象
     */
    private JsonRpcError error;

    /**
     * 构建成功响应
     *
     * @param id     请求 ID
     * @param result 返回结果
     * @return 成功响应对象
     */
    public static JsonRpcResponse success(Object id, Object result) {
        JsonRpcResponse resp = new JsonRpcResponse();
        resp.setId(id);
        resp.setResult(result);
        return resp;
    }

    /**
     * 构建失败响应
     *
     * @param id      请求 ID
     * @param code    JSON-RPC 错误码
     * @param message 错误消息
     * @return 失败响应对象
     */
    public static JsonRpcResponse error(Object id, int code, String message) {
        JsonRpcResponse resp = new JsonRpcResponse();
        resp.setId(id);
        resp.setError(new JsonRpcError(code, message));
        return resp;
    }
}
