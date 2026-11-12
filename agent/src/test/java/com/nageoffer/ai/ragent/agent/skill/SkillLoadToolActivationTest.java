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

package com.nageoffer.ai.ragent.agent.skill;

import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients.RemoteTool;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.McpToolBinding;
import com.nageoffer.ai.ragent.agent.tool.McpToolProxy;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkill;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillLoadToolActivationTest {

    private static final String SKILL_CODE = "return_apply";
    private static final String MCP_TOOL = "return_submit";

    @Test
    void shouldExposeAndExecuteMcpToolAfterLoadingSkillInSameCall() {
        AgentSkill skill = new AgentSkill(SKILL_CODE, "退货办理", "办理退货",
                "先核对订单，再提交退货申请", List.of(MCP_TOOL));
        AgentSkillRegistry registry = mock(AgentSkillRegistry.class);
        when(registry.listEnabled()).thenReturn(List.of(skill));
        when(registry.findByCode(SKILL_CODE)).thenReturn(skill);

        McpClientWrapper client = mock(McpClientWrapper.class);
        when(client.getName()).thenReturn("returns");
        when(client.callTool(eq(MCP_TOOL), anyMap(), anyMap())).thenReturn(Mono.just(CallToolResult.builder()
                .content(List.of(new TextContent("退货申请已提交")))
                .isError(false)
                .build()));
        Tool definition = Tool.builder()
                .name(MCP_TOOL)
                .description("提交退货申请")
                .inputSchema(new JsonSchema("object", Map.of(), List.of(), false, null, null))
                .annotations(new ToolAnnotations(null, true, null, null, null, null))
                .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new SkillLoadTool(registry));
        toolkit.registerAgentTool(new McpToolProxy(McpToolBinding.of(
                MCP_TOOL, "提交退货", "提交退货申请", false, new RemoteTool(definition, client))));
        ActivationModel model = new ActivationModel();
        ReActAgent agent = ReActAgent.builder()
                .name("returns")
                .sysPrompt("按技能手册办理退货")
                .model(model)
                .toolkit(toolkit)
                .stateStore(new InMemoryAgentStateStore())
                .permissionContext(PermissionContextState.builder().mode(PermissionMode.BYPASS).build())
                .middleware(new AgentSkillMaskingMiddleware(registry))
                .maxIters(3)
                .build();

        Msg answer = agent.call(
                        List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("帮我退货").build()),
                        RuntimeContext.builder().userId("user-a").sessionId("session-a").build())
                .block(Duration.ofSeconds(5));

        assertThat(answer).isNotNull();
        assertThat(model.reasoningTurns.get()).isEqualTo(3);
        assertThat(model.mcpResult).isNotNull();
        assertThat(model.mcpResult.getState()).isEqualTo(ToolResultState.SUCCESS);
        verify(client).callTool(eq(MCP_TOOL), anyMap(), anyMap());
    }

    private static ToolResultBlock result(List<Msg> messages, String toolName) {
        return messages.stream()
                .filter(message -> message.getRole() == MsgRole.TOOL)
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .filter(block -> toolName.equals(block.getName()))
                .findFirst()
                .orElse(null);
    }

    private static ChatResponse toolCall(String id, String name, Map<String, Object> input, String content) {
        ToolUseBlock call = ToolUseBlock.builder().id(id).name(name).input(input).content(content).build();
        return ChatResponse.builder().content(List.of(call)).finishReason("tool_calls").build();
    }

    private static final class ActivationModel implements Model {

        private final AtomicInteger reasoningTurns = new AtomicInteger();
        private ToolResultBlock mcpResult;

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            reasoningTurns.incrementAndGet();
            ToolResultBlock loadResult = result(messages, SkillLoadTool.TOOL_NAME);
            if (loadResult == null) {
                assertThat(tools).extracting(ToolSchema::getName)
                        .contains(SkillLoadTool.TOOL_NAME)
                        .doesNotContain(MCP_TOOL);
                return Flux.just(toolCall("load-1", SkillLoadTool.TOOL_NAME,
                        Map.of("skill_code", SKILL_CODE), "{\"skill_code\":\"return_apply\"}"));
            }

            assertThat(loadResult.getState()).as("load_skill result: %s", loadResult.getOutput())
                    .isEqualTo(ToolResultState.SUCCESS);
            assertThat(loadResult.getMetadata()).containsEntry(
                    SkillLoadTool.LOADED_SKILL_METADATA_KEY, SKILL_CODE);
            assertThat(loadResult.getOutput()).extracting(block -> ((TextBlock) block).getText())
                    .singleElement().asString()
                    .contains("先核对订单，再提交退货申请")
                    .doesNotContain("本技能解锁的工具");
            assertThat(tools).extracting(ToolSchema::getName).contains(MCP_TOOL);

            ToolResultBlock submitResult = result(messages, MCP_TOOL);
            if (submitResult == null) {
                return Flux.just(toolCall("submit-1", MCP_TOOL, Map.of(), "{}"));
            }
            mcpResult = submitResult;
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("退货办理完成").build()))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "deterministic-skill-activation-model";
        }
    }
}
