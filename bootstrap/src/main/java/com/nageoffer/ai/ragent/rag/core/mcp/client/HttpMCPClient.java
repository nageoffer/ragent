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

package com.nageoffer.ai.ragent.rag.core.mcp.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 OkHttp 的 MCP 客户端实现
 * 使用 Streamable HTTP 传输协议（JSON-RPC 2.0）与远程 MCP Server 通信
 */
@Slf4j
@RequiredArgsConstructor
public class HttpMCPClient implements MCPClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Gson GSON = new Gson();
    private static final String INITIALIZED_NOTIFICATION_METHOD = "notifications/initialized";

    private final OkHttpClient httpClient;
    private final String serverUrl;
    private final AtomicLong requestId = new AtomicLong(1);

    @Override
    public boolean initialize() {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", "2026-02-28");
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "ragent-bootstrap");
        clientInfo.addProperty("version", "1.0.0");
        params.add("clientInfo", clientInfo);

        JsonObject result = sendRequest("initialize", params);
        if (result == null) {
            log.warn("MCP 初始化失败，跳过 initialized 通知发送");
            return false;
        }
        // MCP 协议要求：收到 initialize 响应后，发送 notifications/initialized 通知
        sendInitializedNotification();
        return true;
    }

    @Override
    public List<MCPTool> listTools() {
        JsonObject result = sendRequest("tools/list", new JsonObject());
        List<MCPTool> tools = new ArrayList<>();
        if (result == null || !result.has("tools")) {
            return tools;
        }

        JsonArray toolsArray = result.getAsJsonArray("tools");
        for (JsonElement element : toolsArray) {
            JsonObject toolObj = element.getAsJsonObject();
            MCPTool tool = convertToMcpTool(toolObj);
            tools.add(tool);
        }
        return tools;
    }

    @Override
    public String callTool(String toolName, Map<String, Object> arguments) {
        if (toolName == null || toolName.isEmpty()) {
            log.warn("MCP 工具调用失败，toolName 为空");
            return null;
        }

        JsonObject params = new JsonObject();
        params.addProperty("name", toolName);
        params.add("arguments", GSON.toJsonTree(arguments != null ? arguments : new HashMap<>()));

        JsonObject result = sendRequest("tools/call", params);
        if (result == null) {
            return null;
        }

        String textResult = extractTextContent(result);
        boolean isError = result.has("isError") && result.get("isError").getAsBoolean();
        if (isError) {
            log.warn("MCP 工具调用返回错误，toolName={}, errorText={}", toolName, textResult);
            return null;
        }
        return textResult;
    }

    /**
     * 发送 JSON-RPC 2.0 请求
     */
    private JsonObject sendRequest(String method, JsonObject params) {
        JsonObject rpcRequest = new JsonObject();
        rpcRequest.addProperty("jsonrpc", "2.0");
        rpcRequest.addProperty("id", requestId.getAndIncrement());
        rpcRequest.addProperty("method", method);
        rpcRequest.add("params", params);

        String url = resolveMcpEndpointUrl();
        String requestBody = GSON.toJson(rpcRequest);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(requestBody, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("MCP 请求失败，method={}, url={}, 原因=HTTP 状态码 {}", method, url, response.code());
                return null;
            }

            String body = response.body() != null ? response.body().string() : null;
            if (body == null) {
                log.warn("MCP 请求失败，method={}, url={}, 原因=响应体为空", method, url);
                return null;
            }

            JsonObject rpcResponse = GSON.fromJson(body, JsonObject.class);
            if (rpcResponse.has("error") && !rpcResponse.get("error").isJsonNull()) {
                JsonObject error = rpcResponse.getAsJsonObject("error");
                log.error("MCP JSON-RPC 错误，method={}, code={}, message={}",
                        method, error.get("code"), error.get("message"));
                return null;
            }

            return rpcResponse.has("result") ? rpcResponse.getAsJsonObject("result") : null;
        } catch (IOException e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("MCP 请求异常，method={}, url={}, 原因={}", method, url, reason);
            return null;
        }
    }

    /**
     * 发送 JSON-RPC 2.0 通知（无 id，不期望响应）
     */
    private void sendInitializedNotification() {
        String method = INITIALIZED_NOTIFICATION_METHOD;
        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", "2.0");
        notification.addProperty("method", method);

        String url = resolveMcpEndpointUrl();
        String body = GSON.toJson(notification);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("MCP 通知发送失败，method={}, HTTP 状态码={}", method, response.code());
            }
        } catch (IOException e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("MCP 通知发送异常，method={}, 原因={}", method, reason);
        }
    }

    private String resolveMcpEndpointUrl() {
        return serverUrl.endsWith("/mcp") ? serverUrl : serverUrl + "/mcp";
    }

    private String extractTextContent(JsonObject result) {
        if (result == null || !result.has("content") || !result.get("content").isJsonArray()) {
            return null;
        }
        JsonArray content = result.getAsJsonArray("content");
        List<String> textSegments = new ArrayList<>();
        for (JsonElement item : content) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject contentObj = item.getAsJsonObject();
            if (contentObj.has("text") && !contentObj.get("text").isJsonNull()) {
                textSegments.add(contentObj.get("text").getAsString());
            }
        }
        if (textSegments.isEmpty()) {
            return null;
        }
        return String.join("\n", textSegments);
    }

    /**
     * 将 MCP 标准 Tool Schema 转换为 bootstrap 的 MCPTool
     */
    private MCPTool convertToMcpTool(JsonObject toolObj) {
        String name = "";
        if (toolObj.has("name") && !toolObj.get("name").isJsonNull()) {
            name = toolObj.get("name").getAsString();
        }
        String description = "";
        if (toolObj.has("description") && !toolObj.get("description").isJsonNull()) {
            description = toolObj.get("description").getAsString();
        }

        Map<String, MCPTool.ParameterDef> parameters = new HashMap<>();
        List<String> requiredList = new ArrayList<>();

        if (toolObj.has("inputSchema")) {
            JsonObject inputSchema = toolObj.getAsJsonObject("inputSchema");

            // 解析 required 列表
            if (inputSchema.has("required") && inputSchema.get("required").isJsonArray()) {
                for (JsonElement req : inputSchema.getAsJsonArray("required")) {
                    requiredList.add(req.getAsString());
                }
            }

            // 解析 properties
            if (inputSchema.has("properties")) {
                JsonObject properties = inputSchema.getAsJsonObject("properties");
                for (String key : properties.keySet()) {
                    JsonObject propObj = properties.getAsJsonObject(key);
                    MCPTool.ParameterDef paramDef = MCPTool.ParameterDef.builder()
                            .type(propObj.has("type") ? propObj.get("type").getAsString() : "string")
                            .description(propObj.has("description") ? propObj.get("description").getAsString() : "")
                            .required(requiredList.contains(key))
                            .build();

                    // 解析枚举值
                    if (propObj.has("enum") && propObj.get("enum").isJsonArray()) {
                        List<String> enumValues = new ArrayList<>();
                        for (JsonElement e : propObj.getAsJsonArray("enum")) {
                            enumValues.add(e.getAsString());
                        }
                        paramDef.setEnumValues(enumValues);
                    }

                    parameters.put(key, paramDef);
                }
            }
        }

        return MCPTool.builder()
                .toolId(name)
                .name(name)
                .description(description)
                .parameters(parameters)
                .mcpServerUrl(serverUrl)
                .build();
    }
}
