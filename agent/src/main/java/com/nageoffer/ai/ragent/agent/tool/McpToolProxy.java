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

import com.nageoffer.ai.ragent.agent.skill.AgentSkillMaskingMiddleware;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.McpToolBinding;
import com.nageoffer.ai.ragent.agent.trace.AgentToolBodyTracer;
import com.nageoffer.ai.ragent.framework.cancellation.TaskCancellation;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpContentConverter;
import io.agentscope.core.tool.mcp.McpMeta;
import io.agentscope.core.tool.mcp.McpTool;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 代理远程 MCP 工具调用，复用 AgentScope 的连接、Schema 与结果转换，并补充本项目的执行规则
 */
@Slf4j
public class McpToolProxy extends McpTool {

    private static final String CALL_FAILED_MESSAGE = "工具调用失败，请稍后重试";
    private static final String MASKED_TOOL_MESSAGE = "该工具尚未解锁。请根据当前任务，从 load_skill 的技能清单中选择并加载对应手册，再调用此工具。";
    private final boolean needsConfirm;
    private final McpClientWrapper client;

    public McpToolProxy(McpToolBinding binding) {
        super(binding.toolId(), binding.description(), binding.inputSchema(),
                binding.remote().definition().outputSchema(), binding.remote().client(), null,
                binding.remote().client().getName(), binding.readOnly());
        this.needsConfirm = binding.needsConfirm();
        this.client = binding.remote().client();
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput, PermissionContextState context) {
        return Mono.deferContextual(view -> {
            RuntimeContext runtime = view.getOrDefault(AgentBase.RUNTIME_CONTEXT_KEY, null);
            boolean ask = needsConfirm && !(runtime != null && isMasked(runtime));
            return Mono.just(ask
                    ? PermissionDecision.ask("该操作执行前需要你确认")
                    : PermissionDecision.allow("该工具无需执行前确认"));
        });
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Optional<RuntimeContext> context = Optional.ofNullable(param.getRuntimeContext());
        if (context.map(this::isMasked).orElse(false)) {
            String callId = Optional.ofNullable(param.getToolUseBlock())
                    .map(ToolUseBlock::getId)
                    .orElse(null);
            AgentToolExecutionFacts facts = AgentToolExecutionFacts.from(param.getRuntimeContext());
            if (facts != null) {
                facts.markShortCircuit(callId, AgentToolExecutionFacts.SHORT_CIRCUIT_MASKED, null);
            }
            return Mono.just(ToolResultBlock.builder()
                    .id(callId)
                    .name(getName())
                    .output(TextBlock.builder().text(MASKED_TOOL_MESSAGE).build())
                    .state(ToolResultState.ERROR)
                    .build());
        }
        return AgentToolBodyTracer.trace(this, param, () -> {
            Map<String, Object> meta = context.map(ctx -> ctx.get(McpMeta.class))
                    .map(McpMeta::entries)
                    .orElseGet(Map::of);
            return Mono.defer(() -> client.callTool(getName(), param.getInput(), meta))
                    .map(McpContentConverter::convertCallToolResult)
                    .map(result -> result.getState() == ToolResultState.RUNNING
                            ? result.withState(ToolResultState.SUCCESS) : result)
                    .onErrorResume(e -> {
                        if (TaskCancellation.isCancellation(e)) {
                            return Mono.just(ToolResultBlock.text("用户已停止，本次工具调用未完成")
                                    .withState(ToolResultState.INTERRUPTED));
                        }
                        log.error("MCP 工具调用异常, toolId={}", getName(), e);
                        return Mono.just(ToolResultBlock.error(CALL_FAILED_MESSAGE));
                    });
        });
    }

    private boolean isMasked(RuntimeContext context) {
        Object masked = context.get(AgentSkillMaskingMiddleware.MASKED_TOOLS_ATTRIBUTE);
        return masked instanceof Set<?> tools && tools.contains(getName());
    }
}
