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

package com.nageoffer.ai.ragent.agent.tool;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 协议解耦：AgentScope 工具协议 ↔ MCP 协议隔离
 * MCP 工具桥：把 rag 已连接的 MCP 执行器适配为 AgentScope 原生工具
 * 不走 AgentScope 自带 MCP 客户端（SDK 版本被压制），路由描述优先取意图树配置
 */
@Slf4j
@RequiredArgsConstructor
public class McpToolBridge implements AgentTool {

    private final McpToolExecutor executor;

    /**
     * 意图树中面向主 Agent 路由的描述，空则回落 MCP 服务端原始描述
     */
    private final String descriptionOverride;

    @Override
    public String getName() {
        return executor.getToolId();
    }

    @Override
    public String getDescription() {  //支持描述覆盖
        if (StrUtil.isNotBlank(descriptionOverride)) {
            return descriptionOverride;
        }
        return StrUtil.emptyIfNull(executor.getToolDefinition().description());
    }

    @Override
    public Map<String, Object> getParameters() {//读取 MCP 工具的`inputSchema(JsonSchema)`，转换成 AgentScope 需要的 JSON‑Schema 参数结构（type/properties/required）。
        JsonSchema schema = executor.getToolDefinition().inputSchema();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", schema == null || StrUtil.isBlank(schema.type()) ? "object" : schema.type());
        parameters.put("properties", schema == null || schema.properties() == null ? Map.of() : schema.properties());
        if (schema != null && CollUtil.isNotEmpty(schema.required())) {
            parameters.put("required", schema.required());
        }
        return parameters;
    }

    /**
     * 透传 MCP 的 readOnlyHint，服务端没声明就按写工具处理
     * 缺省取 false 是因为猜错的两个方向不对等：把写工具当只读会放过重复副作用
     */
    @Override
    public boolean isReadOnly() {
        ToolAnnotations annotations = executor.getToolDefinition().annotations();
        return annotations != null && Boolean.TRUE.equals(annotations.readOnlyHint());
    }

    @Override
    //把老的同步阻塞代码execute（），扔到专门的线程池boundedElastic，包装成响应式 Mono 对外暴露。
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> execute(param))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock execute(ToolCallParam param) {
        String toolCallId = param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId();
        try {
            CallToolResult result = executor.execute(new HashMap<>(param.getInput()));//调用桥接：AgentScope 工具调用 → 转发给 McpToolExecutor 执行
            boolean isError = result != null && Boolean.TRUE.equals(result.isError());
            return buildResult(toolCallId, extractText(result), isError);
        } catch (Exception e) {
            log.error("MCP 工具调用异常, toolId: {}", getName(), e);
            return buildResult(toolCallId, "工具调用异常: " + e.getMessage(), true);
        }
    }

    private ToolResultBlock buildResult(String toolCallId, String text, boolean isError) {
        return ToolResultBlock.builder()
                .id(toolCallId)
                .name(getName())
                .output(TextBlock.builder().text(text).build())
                .state(isError ? ToolResultState.ERROR : ToolResultState.SUCCESS)
                .build();
    }
    //MCP 返回结果解析，转换成 AgentScope 的 ToolResultBlock
    private String extractText(CallToolResult result) {
        if (result == null || CollUtil.isEmpty(result.content())) {
            return "（工具无返回内容）";
        }
        return result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(content -> ((TextContent) content).text())
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining("\n"));
    }
}
