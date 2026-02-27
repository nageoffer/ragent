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

package com.nageoffer.ai.ragent.mcp.endpoint;

import com.nageoffer.ai.ragent.mcp.protocol.JsonRpcRequest;
import com.nageoffer.ai.ragent.mcp.protocol.JsonRpcResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP Streamable HTTP 端点
 * 提供 /mcp 端点接收 JSON-RPC 请求和通知
 */
@RestController
@RequiredArgsConstructor
public class MCPEndpoint {

    private final MCPDispatcher dispatcher;

    @PostMapping("/mcp")
    public ResponseEntity<?> handle(@RequestBody JsonRpcRequest request) {
        JsonRpcResponse response = dispatcher.dispatch(request);
        if (response == null) {
            // JSON-RPC Notification：无需响应体
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(response);
    }
}
