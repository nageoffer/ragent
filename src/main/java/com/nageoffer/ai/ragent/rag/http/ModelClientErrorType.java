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

package com.nageoffer.ai.ragent.rag.http;

public enum ModelClientErrorType {

    /**
     * 未授权错误 - 认证失败或令牌无效
     */
    UNAUTHORIZED,

    /**
     * 速率限制错误 - 请求频率超过限制
     */
    RATE_LIMITED,

    /**
     * 服务器错误 - 模型服务端内部错误
     */
    SERVER_ERROR,

    /**
     * 客户端错误 - 请求参数或格式错误
     */
    CLIENT_ERROR,

    /**
     * 网络错误 - 网络连接或超时问题
     */
    NETWORK_ERROR,

    /**
     * 无效响应 - 模型返回的响应格式不正确
     */
    INVALID_RESPONSE,

    /**
     * 供应商错误 - 模型提供商服务错误
     */
    PROVIDER_ERROR
}
