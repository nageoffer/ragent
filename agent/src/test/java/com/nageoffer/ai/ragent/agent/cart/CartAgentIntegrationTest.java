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

package com.nageoffer.ai.ragent.agent.cart;

import com.nageoffer.ai.ragent.agent.memory.AgentMemoryFlusher;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.skill.AgentSkillMaskingMiddleware;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.McpToolBinding;
import com.nageoffer.ai.ragent.agent.trace.AgentTraceContextKeys;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CartAgentIntegrationTest {
    private final CartOperationService operations = mock(CartOperationService.class);
    private final CartOperationStore store = mock(CartOperationStore.class);
    private final CartAgentIntegration integration = new CartAgentIntegration(operations, store);
    private final McpClientWrapper client = mock(McpClientWrapper.class);

    private RuntimeContext context() {
        RuntimeContext ctx = RuntimeContext.builder().userId("u").sessionId("c").build();
        ctx.put(AgentTraceContextKeys.TASK_ID, "task");
        return ctx;
    }

    private ToolUseBlock call(String action) {
        return ToolUseBlock.builder().id("call").name(CartOperationService.WRITE_TOOL)
                .input(Map.of("action", action, "skuCode", "A", "quantity", 1)).build();
    }

    private AgentMcpClients.RemoteTool remote(String name) {
        when(client.getName()).thenReturn("bit");
        return new AgentMcpClients.RemoteTool(Tool.builder().name(name).description("工具")
                .inputSchema(new JsonSchema("object", Map.of(), List.of(), false, null, null)).build(), client);
    }

    @Test
    void ordinaryAndDeniedRunsNeverAccessCartStorage() {
        RuntimeContext ctx = context();
        integration.onStart(ctx, null);
        integration.onStart(ctx, List.of(new ConfirmResult(false, call("add"))));
        integration.onStart(ctx, List.of(new ConfirmResult(true, call("decrease"))));
        integration.onInterrupt(ctx);
        integration.onDisconnect(ctx);
        integration.onFinish(ctx);
        assertThat((Object) ctx.get(CartRunControl.class)).isNull();
        verifyNoInteractions(store, operations);
    }

    @Test
    void interruptBlocksWritesBeforePersistenceAtFinish() {
        RuntimeContext ctx = context();
        integration.onStart(ctx, List.of(new ConfirmResult(true, call("add"))));
        CartRunControl control = ctx.get(CartRunControl.class);
        assertThat(control.approved(call("add"))).isTrue();
        integration.onInterrupt(ctx);
        assertThat(control.cancelled()).isTrue();
        verifyNoInteractions(store);
        integration.onFinish(ctx);
        verify(store).stopTask("task", false);
    }

    @Test
    void disconnectReasonSurvivesInterruptAndNormalCompletionDoesNotStop() {
        RuntimeContext ctx = context();
        integration.onStart(ctx, List.of(new ConfirmResult(true, call("add"))));
        integration.onFinish(ctx);
        verifyNoInteractions(store);
        integration.onDisconnect(ctx);
        integration.onInterrupt(ctx);
        integration.onFinish(ctx);
        verify(store).stopTask("task", true);
    }

    @Test
    void catalogUsesContributionAndKeepsInternalToolsHidden() {
        var registry = mock(IntentNodeRegistry.class);
        var clients = mock(AgentMcpClients.class);
        List<String> names = List.of(CartOperationService.WRITE_TOOL, CartOperationService.RPC_WRITE_TOOL,
                CartOperationService.QUERY_TOOL, CartVerificationTool.NAME);
        when(registry.listMcpToolNodes()).thenReturn(names.stream().map(name -> IntentNode.builder()
                .id(name).name(name).mcpToolId(name).build()).toList());
        names.forEach(name -> {
            var remote = remote(name);
            when(clients.get(name)).thenReturn(remote);
        });
        AgentMemoryProperties memory = new AgentMemoryProperties();
        memory.setLongTermEnabled(false);
        var catalog = new AgentToolCatalog(mock(KnowledgeSearchFacade.class), registry, clients,
                memory, mock(AgentMemoryFlusher.class), mock(AgentSkillRegistry.class), List.of(integration));
        var resolved = catalog.resolve(Map.of("KNOWLEDGE_TOOL_DESCRIPTION", "知识检索"));
        var toolkit = catalog.buildToolkit(resolved);
        assertThat(toolkit.getTool(CartOperationService.WRITE_TOOL)).isInstanceOf(CartMcpToolProxy.class);
        assertThat(toolkit.getTool(CartVerificationTool.NAME)).isInstanceOf(CartVerificationTool.class);
        assertThat(resolved.fingerprint().mcpTools()).hasSize(1);
        assertThat(resolved.fingerprint().unavailableToolIds()).containsExactlyInAnyOrder(
                CartOperationService.RPC_WRITE_TOOL, CartOperationService.QUERY_TOOL, CartVerificationTool.NAME);
        assertThat(integration.createTool(McpToolBinding.of("other", "其他", "说明", false, remote("other")))).isNull();
        assertThat(integration.additionalTools(List.of())).isEmpty();
    }

    @Test
    void cartExecutionStillHonorsSkillMaskBeforeCoordinator() {
        var binding = McpToolBinding.of(CartOperationService.WRITE_TOOL, "加购", "说明", true,
                remote(CartOperationService.WRITE_TOOL));
        var tool = integration.createTool(binding);
        RuntimeContext ctx = context();
        ToolUseBlock call = call("add");
        var param = ToolCallParam.builder().toolUseBlock(call).input(call.getInput()).runtimeContext(ctx).build();
        ctx.put(AgentSkillMaskingMiddleware.MASKED_TOOLS_ATTRIBUTE, Set.of(CartOperationService.WRITE_TOOL));
        assertThat(tool.callAsync(param).block().getState()).isEqualTo(ToolResultState.ERROR);
        verifyNoInteractions(operations);
        ctx.put(AgentSkillMaskingMiddleware.MASKED_TOOLS_ATTRIBUTE, Set.of());
        when(operations.call(param, client)).thenReturn(Mono.just(ToolResultBlock.text("已加购")));
        assertThat(tool.callAsync(param).block()).isNotNull();
        verify(operations).call(param, client);
        verify(client, never()).callTool(anyString(), anyMap(), anyMap());
    }

    @Test
    void decreaseStillUsesTheGenericRemoteExecution() {
        var binding = McpToolBinding.of(CartOperationService.WRITE_TOOL, "购物车", "说明", true,
                remote(CartOperationService.WRITE_TOOL));
        var tool = integration.createTool(binding);
        when(client.callTool(eq(CartOperationService.WRITE_TOOL), anyMap(), anyMap())).thenReturn(Mono.just(
                CallToolResult.builder().addTextContent("已减少").isError(false).build()));
        ToolUseBlock call = call("decrease");
        var param = ToolCallParam.builder().toolUseBlock(call).input(call.getInput()).runtimeContext(context()).build();
        assertThat(tool.callAsync(param).block().getState()).isEqualTo(ToolResultState.SUCCESS);
        verifyNoInteractions(operations);
        verify(client).callTool(eq(CartOperationService.WRITE_TOOL), eq(call.getInput()), anyMap());
    }

}
