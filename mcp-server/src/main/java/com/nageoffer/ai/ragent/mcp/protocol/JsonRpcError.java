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
 * JSON-RPC 2.0 错误对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JsonRpcError {

    /**
     * 方法不存在
     */
    public static final int METHOD_NOT_FOUND = -32601;

    /**
     * 参数非法
     */
    public static final int INVALID_PARAMS = -32602;

    /**
     * 服务器内部错误
     */
    public static final int INTERNAL_ERROR = -32603;

    /**
     * 错误码
     */
    private Integer code;

    /**
     * 错误消息
     */
    private String message;
}
