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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
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
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 复现 AgentScope #2439：单例 Agent 并发处理不同会话时，共享 Toolkit 的工具组状态会串话
 *
 * <p>这是缺陷复现测试，断言的是当前框架的错误行为。上游修复后，工具结果应改为 SUCCESS，
 * 本测试也应随之删除或改成回归断言
 */
class AgentScopeToolGroupConcurrencyTest {

    private static final String USER_A = "user-a";
    private static final String USER_B = "user-b";
    private static final String SESSION_A = "session-a";
    private static final String SESSION_B = "session-b";
    private static final String LOAD_SKILL = "load_skill_through_path";
    private static final String RETURN_SKILL_NAME = "return";
    private static final String RETURN_SKILL_ID = "return_test";
    private static final String RETURN_GROUP = "return-skill";
    private static final String RETURN_SUBMIT = "return_submit";

    @Test
    @DisplayName("B 恢复自身工具组状态后，A 已经看到的退货工具仍会在执行时被拒绝")
    void shouldReproduceCrossSessionToolGroupPollution() throws Exception {
        AtomicBoolean returnSubmitted = new AtomicBoolean();
        InterleavingModel model = new InterleavingModel(true);
        ReActAgent agent = buildAgent(model, returnSubmitted);

        CompletableFuture<Msg> callA = agent.call(
                        List.of(userMessage("A-申请退货")), runtimeContext(USER_A, SESSION_A))
                .subscribeOn(Schedulers.boundedElastic())
                .toFuture();

        assertThat(model.awaitAReasoning()).as("A 应先拿到退货工具并进入模型推理").isTrue();
        assertThat(agent.getToolkit().getActiveGroups()).contains(RETURN_GROUP);

        Msg replyB;
        try {
            replyB = agent.call(
                            List.of(userMessage("B-咨询物流")), runtimeContext(USER_B, SESSION_B))
                    .block(Duration.ofSeconds(5));
            assertThat(agent.getToolkit().getActiveGroups())
                    .as("B 未激活退货工具组，会覆盖 Agent 实例里的共享 Toolkit 状态")
                    .doesNotContain(RETURN_GROUP);
        } finally {
            model.releaseA();
        }
        Msg replyA = callA.get(5, TimeUnit.SECONDS);

        assertThat(replyB).isNotNull();
        assertThat(replyA).isNotNull();
        assertThat(model.getObservedToolResult()).isNotNull();
        assertThat(model.getObservedToolResult().getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(toolResultText(model.getObservedToolResult()))
                .contains("Unauthorized tool call", RETURN_SUBMIT);
        assertThat(returnSubmitted)
                .as("A 的退货工具只因 B 覆盖了共享 Toolkit 而没有真正执行")
                .isFalse();
    }

    @Test
    @DisplayName("没有其他会话并发时，A 加载手册后可以正常执行退货工具")
    void shouldExecuteSkillToolWithoutConcurrentSession() {
        AtomicBoolean returnSubmitted = new AtomicBoolean();
        InterleavingModel model = new InterleavingModel(false);
        ReActAgent agent = buildAgent(model, returnSubmitted);

        Msg reply = agent.call(
                        List.of(userMessage("A-申请退货")), runtimeContext(USER_A, SESSION_A))
                .block(Duration.ofSeconds(5));

        assertThat(reply).isNotNull();
        assertThat(model.getObservedToolResult()).isNotNull();
        assertThat(model.getObservedToolResult().getState()).isEqualTo(ToolResultState.SUCCESS);
        assertThat(returnSubmitted).isTrue();
    }

    private static ReActAgent buildAgent(Model model, AtomicBoolean returnSubmitted) {
        Toolkit toolkit = new Toolkit();
        toolkit.createSkillToolGroup(RETURN_GROUP, "退货操作", false, RETURN_SKILL_NAME);
        toolkit.registration()
                .agentTool(new ReturnSubmitTool(returnSubmitted))
                .group(RETURN_GROUP)
                .apply();

        return ReActAgent.builder()
                .name("customer-service")
                .sysPrompt("你是售后客服")
                .model(model)
                .toolkit(toolkit)
                .stateStore(new InMemoryAgentStateStore())
                .permissionContext(
                        PermissionContextState.builder().mode(PermissionMode.BYPASS).build())
                .skillRepository(returnSkillRepository())
                .maxIters(3)
                .build();
    }

    private static AgentSkillRepository returnSkillRepository() {
        AgentSkillRepository repository = mock(AgentSkillRepository.class);
        AgentSkill skill = new AgentSkill(
                RETURN_SKILL_NAME,
                "办理退货前必须加载的操作手册",
                "# 退货操作手册\n先查询订单并确认售后条件，再提交退货申请。",
                Map.of(),
                "test");
        assertThat(skill.getSkillId()).isEqualTo(RETURN_SKILL_ID);
        when(repository.getAllSkills()).thenReturn(List.of(skill));
        return repository;
    }

    private static RuntimeContext runtimeContext(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    private static Msg userMessage(String text) {
        return Msg.builder().name("user").role(MsgRole.USER).textContent(text).build();
    }

    private static String toolResultText(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .findFirst()
                .orElse("");
    }

    private static final class ReturnSubmitTool extends ToolBase {

        private final AtomicBoolean invoked;

        private ReturnSubmitTool(AtomicBoolean invoked) {
            super(ToolBase.builder()
                    .name(RETURN_SUBMIT)
                    .description("提交退货申请")
                    .inputSchema(Map.of(
                            "type", "object",
                            "properties", Map.of(),
                            "required", List.of()))
                    .readOnly(false)
                    .concurrencySafe(true));
            this.invoked = invoked;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            invoked.set(true);
            return Mono.just(ToolResultBlock.text("退货申请已提交"));
        }
    }

    /**
     * A 加载手册后，在第二轮模型推理时暂停。B 的模型调用只有在框架已经用 B 的工具组状态覆盖共享 Toolkit
     * 之后才会发生；此时放行 A，就能稳定复现 A 在执行阶段被拒绝，而不是依赖偶发线程时序
     */
    private static final class InterleavingModel implements Model {

        private final boolean interleaveAnotherSession;
        private final CountDownLatch aReasoningStarted = new CountDownLatch(1);
        private final CountDownLatch bReasoningStarted = new CountDownLatch(1);
        private final CountDownLatch aMayReturnToolCall = new CountDownLatch(1);
        private final AtomicReference<ToolResultBlock> observedToolResult = new AtomicReference<>();

        private InterleavingModel(boolean interleaveAnotherSession) {
            this.interleaveAnotherSession = interleaveAnotherSession;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(() -> {
                if (containsUserText(messages, "A-申请退货")) {
                    ToolResultBlock submitResult = findToolResult(messages, RETURN_SUBMIT);
                    if (submitResult != null) {
                        observedToolResult.compareAndSet(null, submitResult);
                        return Flux.just(textResponse("A-处理结束"));
                    }

                    ToolResultBlock loadResult = findToolResult(messages, LOAD_SKILL);
                    if (loadResult == null) {
                        assertThat(tools)
                                .extracting(ToolSchema::getName)
                                .contains(LOAD_SKILL)
                                .doesNotContain(RETURN_SUBMIT);
                        return Flux.just(loadSkillResponse());
                    }

                    assertThat(loadResult.getState()).isEqualTo(ToolResultState.SUCCESS);
                    assertThat(tools)
                            .extracting(ToolSchema::getName)
                            .contains(RETURN_SUBMIT);
                    if (interleaveAnotherSession) {
                        aReasoningStarted.countDown();
                        await(bReasoningStarted, "B 应在 A 返回工具调用前进入推理");
                        await(aMayReturnToolCall, "测试线程应在确认 Toolkit 被覆盖后放行 A");
                    }
                    return Flux.just(toolCallResponse());
                }

                assertThat(tools)
                        .extracting(ToolSchema::getName)
                        .doesNotContain(RETURN_SUBMIT);
                bReasoningStarted.countDown();
                return Flux.just(textResponse("B-处理结束"));
            });
        }

        @Override
        public String getModelName() {
            return "deterministic-concurrency-model";
        }

        private boolean awaitAReasoning() throws InterruptedException {
            return aReasoningStarted.await(5, TimeUnit.SECONDS);
        }

        private ToolResultBlock getObservedToolResult() {
            return observedToolResult.get();
        }

        private void releaseA() {
            aMayReturnToolCall.countDown();
        }

        private static boolean containsUserText(List<Msg> messages, String expected) {
            return messages.stream()
                    .flatMap(message -> message.getContent().stream())
                    .filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast)
                    .map(TextBlock::getText)
                    .anyMatch(expected::equals);
        }

        private static ToolResultBlock findToolResult(List<Msg> messages, String toolName) {
            return messages.stream()
                    .flatMap(message -> message.getContent().stream())
                    .filter(ToolResultBlock.class::isInstance)
                    .map(ToolResultBlock.class::cast)
                    .filter(result -> toolName.equals(result.getName()))
                    .findFirst()
                    .orElse(null);
        }

        private static void await(CountDownLatch latch, String message) {
            try {
                assertThat(latch.await(5, TimeUnit.SECONDS)).as(message).isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待并发测试时被中断", ex);
            }
        }

        private static ChatResponse loadSkillResponse() {
            ToolUseBlock toolCall = ToolUseBlock.builder()
                    .id("load-return-skill")
                    .name(LOAD_SKILL)
                    .input(Map.of("skillId", RETURN_SKILL_ID, "path", "SKILL.md"))
                    .content("{\"skillId\":\"return_test\",\"path\":\"SKILL.md\"}")
                    .build();
            return ChatResponse.builder()
                    .content(List.of(toolCall))
                    .finishReason("tool_calls")
                    .build();
        }

        private static ChatResponse toolCallResponse() {
            ToolUseBlock toolCall = ToolUseBlock.builder()
                    .id("return-call")
                    .name(RETURN_SUBMIT)
                    .input(Map.of())
                    .content("{}")
                    .build();
            return ChatResponse.builder()
                    .content(List.of(toolCall))
                    .finishReason("tool_calls")
                    .build();
        }

        private static ChatResponse textResponse(String text) {
            ContentBlock block = TextBlock.builder().text(text).build();
            return ChatResponse.builder()
                    .content(List.of(block))
                    .finishReason("stop")
                    .build();
        }
    }
}
