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
import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients.RemoteTool;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.McpToolBinding;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpMeta;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolProxyTest {

    private static final String TOOL_ID = "query_order";
    private McpClientWrapper client;
    private McpToolProxy tool;

    @BeforeEach
    void setUp() {
        client = mock(McpClientWrapper.class);
        when(client.getName()).thenReturn("default");
        Tool definition = Tool.builder()
                .name(TOOL_ID)
                .description("查询订单")
                .inputSchema(new JsonSchema("object", Map.of(), List.of(), null, null, null))
                .build();
        tool = new McpToolProxy(McpToolBinding.of(TOOL_ID, "订单查询", "查询订单", false,
                new RemoteTool(definition, client)));
    }

    @Test
    void shouldPassIdentityThroughAgentScopeMeta() {
        when(client.callTool(eq(TOOL_ID), anyMap(), anyMap())).thenReturn(Mono.just(CallToolResult.builder()
                .content(List.of(new TextContent("订单查询结果")))
                .isError(false)
                .build()));
        RuntimeContext context = RuntimeContext.builder()
                .userId("2001")
                .put(McpMeta.class, new McpMeta(AgentMcpMeta.ofUser("2001")))
                .build();

        assertThat(Objects.requireNonNull(tool.callAsync(param(context)).block()).getState()).isEqualTo(ToolResultState.SUCCESS);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(client).callTool(eq(TOOL_ID), anyMap(), meta.capture());
        assertThat(meta.getValue()).containsEntry(AgentMcpMeta.USER_ID_KEY, "2001");
    }

    @Test
    void shouldRejectMaskedToolBeforeRemoteCall() {
        RuntimeContext context = RuntimeContext.builder().build();
        context.put(AgentSkillMaskingMiddleware.MASKED_TOOLS_ATTRIBUTE, Set.of(TOOL_ID));

        ToolResultBlock result = tool.callAsync(param(context)).block();
        assert result != null;
        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(result.getOutput()).singleElement().isInstanceOfSatisfying(TextBlock.class,
                text -> assertThat(text.getText()).isEqualTo(
                        "该工具尚未解锁。请根据当前任务，从 load_skill 的技能清单中选择并加载对应手册，再调用此工具。"));
        verify(client, never()).callTool(eq(TOOL_ID), anyMap(), anyMap());
    }

    @Test
    void shouldKeepRemoteFailureAndCancellationDistinct() {
        when(client.callTool(eq(TOOL_ID), anyMap(), anyMap()))
                .thenReturn(Mono.error(new IllegalStateException("connection refused")))
                .thenReturn(Mono.error(new CancellationException("stopped")));

        RuntimeContext context = RuntimeContext.builder().build();
        assertThat(Objects.requireNonNull(tool.callAsync(param(context)).block()).getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(Objects.requireNonNull(tool.callAsync(param(context)).block()).getState()).isEqualTo(ToolResultState.INTERRUPTED);
    }

    private ToolCallParam param(RuntimeContext context) {
        return ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder().id("call-1").name(TOOL_ID).input(Map.of()).build())
                .input(Map.of("orderNo", "88231"))
                .runtimeContext(context)
                .build();
    }
}
