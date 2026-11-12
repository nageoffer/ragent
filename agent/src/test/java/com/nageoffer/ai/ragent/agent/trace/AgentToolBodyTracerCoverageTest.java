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

package com.nageoffer.ai.ragent.agent.trace;

import com.nageoffer.ai.ragent.agent.memory.AgentMemoryFlusher;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.skill.AgentSkillMaskingMiddleware;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients.RemoteTool;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkill;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 目录里注册了几把工具，就该有几把接了追踪：漏接一把不会报错，只会让那把工具在链路上凭空消失
 * 这条用例扫的是生产目录本身，新增工具忘了接就在这里变红
 */
class AgentToolBodyTracerCoverageTest {

    private static ContextPropagationOperator reactorHook;

    private List<SpanData> exported;
    private SdkTracerProvider tracerProvider;
    private AgentToolExecutionFacts facts;
    private RuntimeContext ctx;

    @BeforeAll
    static void installReactorHook() {
        reactorHook = ContextPropagationOperator.builder().build();
        reactorHook.registerOnEachOperator();
    }

    @AfterAll
    static void resetReactorHook() {
        reactorHook.resetOnEachOperator();
    }

    @BeforeEach
    void setUp() {
        GlobalOpenTelemetry.resetForTest();
        exported = Collections.synchronizedList(new ArrayList<>());
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.builder(new CollectingExporter(exported)).build())
                .build();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());
        facts = new AgentToolExecutionFacts("t-9001", Clock.systemUTC());
        ctx = RuntimeContext.builder().userId("u-1001").sessionId("c-2002").build();
        ctx.put(AgentToolExecutionFacts.RUNTIME_CONTEXT_KEY, facts);
        // 记忆整理工具要知道本次请求是哪条消息，生产路径由聊天入口写入
        ctx.put(AgentTraceContextKeys.REPLY_TO_MESSAGE_ID, "m-3003");
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
        GlobalOpenTelemetry.resetForTest();
    }

    /**
     * 逐一调用生产目录里的每把工具，每把都必须恰好留下一个自己名下的执行体节点
     */
    @Test
    void shouldTraceEveryToolRegisteredInProductionToolkit() {
        Toolkit toolkit = productionToolkit();
        assertThat(toolkit.getToolNames()).hasSizeGreaterThanOrEqualTo(4);

        for (String toolName : toolkit.getToolNames()) {
            AgentTool tool = toolkit.getTool(toolName);
            String toolCallId = "call-" + toolName;
            Span batch = tracerProvider.get("test").spanBuilder("tool_batch").startSpan();
            try (Scope ignored = batch.makeCurrent()) {
                tool.callAsync(param(toolCallId, toolName)).block();
            } finally {
                batch.end();
            }

            assertThat(exported.stream()
                    .filter(data -> ("execute_tool " + toolName).equals(data.getName())))
                    .as("工具 %s 没有留下执行体 span", toolName)
                    .hasSize(1);
            // 事实源同样要有真起止，否则 PG 与前端那头照样是空的
            assertThat(facts.toolFact(toolCallId).durationMs())
                    .as("工具 %s 没有在事实源留下耗时", toolName)
                    .isNotNull();
        }
    }

    /**
     * 遮蔽发生在追踪之前：一步没跑的调用不该有执行体 span，也不该有起止
     * 原因得在事实源里留一条，否则批上那条事件只能靠结果状态猜是谁否的
     */
    @Test
    void shouldNotOpenToolSpanWhenSkillMaskingRefusesTheCall() {
        Toolkit toolkit = productionToolkit();
        ctx.put(AgentSkillMaskingMiddleware.MASKED_TOOLS_ATTRIBUTE, Set.of("leave_submit"));

        Span batch = tracerProvider.get("test").spanBuilder("tool_batch").startSpan();
        try (Scope ignored = batch.makeCurrent()) {
            toolkit.getTool("leave_submit").callAsync(param("call-1", "leave_submit")).block();
        } finally {
            batch.end();
        }

        assertThat(exported.stream().filter(data -> data.getName().startsWith("execute_tool"))).isEmpty();
        assertThat(facts.toolFact("call-1").startedAt()).isNull();
        assertThat(facts.toolFact("call-1").endedAt()).isNull();
        assertThat(facts.toolFact("call-1").shortCircuitReason())
                .isEqualTo(AgentToolExecutionFacts.SHORT_CIRCUIT_MASKED);
        assertThat(facts.toolFact("call-1").shortCircuitDetail()).isNull();
    }

    private ToolCallParam param(String toolCallId, String toolName) {
        return ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder().id(toolCallId).name(toolName).build())
                .input(Map.of())
                .runtimeContext(ctx)
                .build();
    }

    /**
     * 按生产路径建目录：知识库、记忆、技能、MCP 四类工具一次全挂上
     */
    private Toolkit productionToolkit() {
        IntentNodeRegistry intentNodeRegistry = mock(IntentNodeRegistry.class);
        when(intentNodeRegistry.listMcpToolNodes()).thenReturn(List.of(IntentNode.builder()
                .id("leave")
                .name("请假申请")
                .description("提交请假申请")
                .kind(IntentKind.MCP)
                .mcpToolId("leave_submit")
                .build()));
        AgentMcpClients mcpClients = mock(AgentMcpClients.class);
        RemoteTool leaveTool = executor();
        when(mcpClients.get("leave_submit")).thenReturn(leaveTool);
        Map<String, String> prompts = Map.of(
                AgentPromptSlot.KNOWLEDGE_TOOL_DESCRIPTION.name(), "知识库工具描述",
                AgentPromptSlot.AGENT_MEMORY_TOOL_DESCRIPTION.name(), "记忆工具描述");
        AgentSkillRegistry skillRegistry = mock(AgentSkillRegistry.class);
        when(skillRegistry.listEnabled()).thenReturn(List.of(
                new AgentSkill("leave", "请假", "请假办理步骤", "手册正文", List.of("leave_submit"))));
        AgentMemoryProperties memoryProperties = new AgentMemoryProperties();
        memoryProperties.setLongTermEnabled(true);

        AgentToolCatalog catalog = new AgentToolCatalog(
                mock(KnowledgeSearchFacade.class),
                intentNodeRegistry,
                mcpClients,
                memoryProperties,
                mock(AgentMemoryFlusher.class),
                skillRegistry);
        return catalog.buildToolkit(catalog.resolve(prompts));
    }

    private RemoteTool executor() {
        Tool tool = Tool.builder()
                .name("leave_submit")
                .description("提交请假申请")
                .inputSchema(new JsonSchema("object", Map.of(), List.of(), false, null, null))
                .build();
        McpClientWrapper client = mock(McpClientWrapper.class);
        when(client.getName()).thenReturn("default");
        when(client.callTool(eq("leave_submit"), anyMap(), anyMap())).thenReturn(Mono.just(
                CallToolResult.builder().content(List.of(new TextContent("已处理"))).isError(false).build()));
        return new RemoteTool(tool, client);
    }

    private record CollectingExporter(List<SpanData> sink) implements SpanExporter {

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            sink.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
