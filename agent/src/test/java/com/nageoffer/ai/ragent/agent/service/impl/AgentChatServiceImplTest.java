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

package com.nageoffer.ai.ragent.agent.service.impl;

import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider.ActiveAgent;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmSettlement;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryTriggerType;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.ResolvedCatalog;
import com.nageoffer.ai.ragent.agent.trace.AgentTraceContextKeys;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpMeta;
import io.agentscope.core.tool.mcp.McpMeta;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AgentChatServiceImplTest {

    private static final String USER_ID = "u-1001";
    private static final String CONVERSATION_ID = "c-2002";

    private ReActAgentProvider agentProvider;
    private AgentConversationService conversationService;
    private StreamTaskManager taskManager;
    private AgentRunGate runGate;
    private AgentMemoryProperties memoryProperties;
    private AgentMemoryPipeline memoryPipeline;
    private AtomicInteger gateReleased;
    private ReActAgent agent;
    private AgentChatServiceImpl service;

    @BeforeEach
    void setUp() {
        agentProvider = mock(ReActAgentProvider.class);
        conversationService = mock(AgentConversationService.class);
        taskManager = mock(StreamTaskManager.class);
        runGate = mock(AgentRunGate.class);
        agent = mock(ReActAgent.class);
        memoryProperties = new AgentMemoryProperties();
        memoryProperties.setLongTermEnabled(false);
        memoryPipeline = mock(AgentMemoryPipeline.class);
        service = new AgentChatServiceImpl(agentProvider, conversationService, taskManager, runGate,
                memoryProperties, memoryPipeline);

        gateReleased = new AtomicInteger();
        when(runGate.acquire(anyString(), anyString(), anyString())).thenReturn(gateReleased::incrementAndGet);
        when(agentProvider.getAgent()).thenReturn(new ActiveAgent(
                agent, new ResolvedCatalog("知识库工具描述", null, List.of(), List.of(), List.of())));
        when(conversationService.touchConversation(anyString(), anyString(), anyString()))
                .thenReturn("会话标题");
        when(conversationService.addUserMessage(anyString(), anyString(), anyString())).thenReturn("m-3003");
        // 每轮收尾都会调一次，不给默认结局其余用例会在后台线程上吃 NPE
        when(memoryPipeline.extract(anyString(), anyString(), any(AgentMemoryTriggerType.class)))
                .thenReturn(new AgentMemoryOutcome(AgentMemoryOutcome.Status.BELOW_THRESHOLD, 0, 1, false));
        UserContext.set(LoginUser.builder().userId(USER_ID).username("tester").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void shouldPrepareNewConversationWithServerGeneratedId(String requestedId) {
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());
        ArgumentCaptor<String> conversationId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RuntimeContext> runtimeContext = ArgumentCaptor.forClass(RuntimeContext.class);

        service.streamChat("首问", requestedId, new SseEmitter());

        verify(conversationService).touchConversation(conversationId.capture(), eq(USER_ID), eq("首问"));
        assertThat(conversationId.getValue()).matches("\\d+");
        verify(runGate).acquire(eq(USER_ID), anyString(), eq(conversationId.getValue()));
        verify(conversationService).addUserMessage(conversationId.getValue(), USER_ID, "首问");
        verify(agent).streamEvents(any(Msg.class), runtimeContext.capture());
        assertThat(runtimeContext.getValue().getSessionId()).isEqualTo(conversationId.getValue());
        verify(conversationService, never()).hasPendingConfirm(anyString(), anyString());
    }

    @Test
    void shouldValidateExistingConversationBeforePreparingIt() {
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());

        service.streamChat("续问", CONVERSATION_ID, new SseEmitter());

        InOrder order = inOrder(runGate, conversationService);
        order.verify(runGate).acquire(eq(USER_ID), anyString(), eq(CONVERSATION_ID));
        order.verify(conversationService).hasPendingConfirm(CONVERSATION_ID, USER_ID);
        order.verify(conversationService).touchConversation(CONVERSATION_ID, USER_ID, "续问");
        order.verify(conversationService).addUserMessage(CONVERSATION_ID, USER_ID, "续问");
    }

    @Test
    void shouldStopStartupWhenExistingConversationIsUnavailable() {
        when(conversationService.hasPendingConfirm(CONVERSATION_ID, USER_ID))
                .thenThrow(new ClientException("会话不存在"));

        assertThatThrownBy(() -> service.streamChat("续问", CONVERSATION_ID, new SseEmitter()))
                .isInstanceOf(ClientException.class).hasMessageContaining("会话不存在");

        verify(conversationService, never()).touchConversation(anyString(), anyString(), anyString());
        verify(conversationService, never()).addUserMessage(anyString(), anyString(), anyString());
        verify(taskManager, never()).register(anyString(), anyString(), any());
        verifyNoInteractions(agentProvider, agent, memoryPipeline);
        assertThat(gateReleased.get()).isOne();
    }

    @Test
    void shouldEvictStateCacheWhenStreamCompletes() {
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());

        service.streamChat("问题", CONVERSATION_ID, new SseEmitter());

        // 不驱逐则每个 (用户, 会话) 的全量记忆在单例 Agent 里常驻到进程重启
        verify(agent).clearStateCache(USER_ID, CONVERSATION_ID);
        verify(agent, never()).saveAgentState(anyString(), anyString());
        verify(agentProvider, never()).evictStateCache(USER_ID, CONVERSATION_ID);
    }

    @Test
    void shouldSaveStateBeforeEvictWhenStreamFails() {
        Runnable releaseGate = mock(Runnable.class);
        when(runGate.acquire(anyString(), anyString(), anyString())).thenReturn(releaseGate);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Flux.error(new IllegalStateException("上游出错")));

        service.streamChat("问题", CONVERSATION_ID, new SseEmitter());

        InOrder order = inOrder(agent, releaseGate);
        order.verify(agent).saveAgentState(USER_ID, CONVERSATION_ID);
        order.verify(agent).clearStateCache(USER_ID, CONVERSATION_ID);
        order.verify(releaseGate).run();
    }

    /**
     * 轮次收尾触发抽取，且必须离开请求线程
     */
    @Test
    void shouldTriggerBackgroundExtractionOffTheRequestThread() throws Exception {
        memoryProperties.setLongTermEnabled(true);
        CountDownLatch extracted = new CountDownLatch(1);
        AtomicReference<Thread> extractThread = new AtomicReference<>();
        AtomicInteger releasedWhenExtracting = new AtomicInteger(-1);
        when(memoryPipeline.extract(USER_ID, CONVERSATION_ID, AgentMemoryTriggerType.BACKGROUND))
                .thenAnswer(invocation -> {
                    extractThread.set(Thread.currentThread());
                    releasedWhenExtracting.set(gateReleased.get());
                    extracted.countDown();
                    return new AgentMemoryOutcome(AgentMemoryOutcome.Status.WRITTEN, 1, 3, true);
                });
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());

        service.streamChat("问题", CONVERSATION_ID, new SseEmitter());

        assertThat(extracted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(extractThread.get()).isNotSameAs(Thread.currentThread());
        assertThat(releasedWhenExtracting.get()).isEqualTo(1);
    }

    /**
     * 控制行建行时刻是抽取下界，必须先于本轮消息落库：反过来首条消息成「历史」，永久漏出抽取范围
     */
    @Test
    void shouldEnsureBaselineBeforeSavingUserMessage() {
        memoryProperties.setLongTermEnabled(true);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());

        service.streamChat("我对花生严重过敏", CONVERSATION_ID, new SseEmitter());

        InOrder inOrder = inOrder(memoryPipeline, conversationService);
        inOrder.verify(memoryPipeline).ensureExtractionBaseline(USER_ID);
        inOrder.verify(conversationService).addUserMessage(CONVERSATION_ID, USER_ID, "我对花生严重过敏");
    }

    /**
     * 关掉开关连异步线程都不该起
     */
    @Test
    void shouldSkipBackgroundExtractionWhenLongTermDisabled() {
        memoryProperties.setLongTermEnabled(false);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());

        service.streamChat("问题", CONVERSATION_ID, new SseEmitter());

        verify(memoryPipeline).ensureExtractionBaseline(USER_ID);
        verifyNoMoreInteractions(memoryPipeline);
    }

    /**
     * 业务 ID 随上下文进框架，供链路追踪反查
     */
    @Test
    void shouldCarryBusinessIdsIntoRuntimeContext() {
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());
        ArgumentCaptor<RuntimeContext> runtimeContext = ArgumentCaptor.forClass(RuntimeContext.class);
        ArgumentCaptor<String> taskId = ArgumentCaptor.forClass(String.class);

        service.streamChat("问题", CONVERSATION_ID, new SseEmitter());
        verify(agent).streamEvents(any(Msg.class), runtimeContext.capture());
        verify(taskManager).register(taskId.capture(), anyString(), any());

        RuntimeContext captured = runtimeContext.getValue();
        assertThat(captured.getUserId()).isEqualTo(USER_ID);
        assertThat(captured.getSessionId()).isEqualTo(CONVERSATION_ID);
        assertThat(captured.get(McpMeta.class).entries())
                .containsEntry(AgentMcpMeta.USER_ID_KEY, USER_ID);
        // 与 SSE META 给前端的任务号一致
        assertThat(contextId(captured, AgentTraceContextKeys.TASK_ID)).isEqualTo(taskId.getValue());
        assertThat(contextId(captured, AgentTraceContextKeys.REPLY_TO_MESSAGE_ID)).isEqualTo("m-3003");
        // 首问无确认消息号，null 不能炸
        assertThat(contextId(captured, AgentTraceContextKeys.CONFIRM_MESSAGE_ID)).isNull();
    }

    /**
     * 续跑补上确认消息号，答复挂回原提问
     */
    @Test
    void shouldCarryConfirmMessageIdWhenResumingAfterApproval() {
        ToolUseBlock asking = ToolUseBlock.builder()
                .id("call-1")
                .name("submit_leave")
                .input(Map.of("days", 1))
                .state(ToolCallState.ASKING)
                .build();
        AgentState state = AgentState.builder()
                .userId(USER_ID)
                .sessionId(CONVERSATION_ID)
                .addMessage(AssistantMessage.builder().content(asking).build())
                .build();
        when(agent.getAgentState(USER_ID, CONVERSATION_ID)).thenReturn(state);
        when(conversationService.getPendingConfirm(CONVERSATION_ID, USER_ID, "m-4004"))
                .thenReturn(new AgentConfirmSettlement("会话标题", "m-3003"));
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());
        ArgumentCaptor<RuntimeContext> runtimeContext = ArgumentCaptor.forClass(RuntimeContext.class);

        service.confirmPendingTool(CONVERSATION_ID, "m-4004", true, new SseEmitter());
        verify(agent).streamEvents(any(Msg.class), runtimeContext.capture());

        RuntimeContext captured = runtimeContext.getValue();
        assertThat(contextId(captured, AgentTraceContextKeys.CONFIRM_MESSAGE_ID)).isEqualTo("m-4004");
        assertThat(contextId(captured, AgentTraceContextKeys.REPLY_TO_MESSAGE_ID)).isEqualTo("m-3003");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldEvictStateBeforeExpiringInvalidConfirmation(boolean expirationFails) {
        Runnable releaseGate = mock(Runnable.class);
        when(runGate.acquire(anyString(), anyString(), anyString())).thenReturn(releaseGate);
        when(conversationService.getPendingConfirm(CONVERSATION_ID, USER_ID, "m-4004"))
                .thenReturn(new AgentConfirmSettlement("会话标题", "m-3003"));
        when(agent.getAgentState(USER_ID, CONVERSATION_ID)).thenReturn(AgentState.builder()
                .userId(USER_ID).sessionId(CONVERSATION_ID).build());
        RuntimeException expirationFailure = new IllegalStateException("更新确认卡失败");
        if (expirationFails) {
            doThrow(expirationFailure).when(conversationService)
                    .expirePendingConfirm(CONVERSATION_ID, USER_ID, "m-4004");
        }

        var failure = assertThatThrownBy(() -> service.confirmPendingTool(
                CONVERSATION_ID, "m-4004", true, new SseEmitter()));
        if (expirationFails) {
            failure.isSameAs(expirationFailure);
        } else {
            failure.isInstanceOf(ClientException.class)
                    .hasMessage("待确认的操作已失效，请重新提问");
        }

        // 即使更新确认卡失败，也必须先清缓存，再释放运行位。
        InOrder order = inOrder(agent, conversationService, releaseGate);
        order.verify(agent).getAgentState(USER_ID, CONVERSATION_ID);
        order.verify(agent).clearStateCache(USER_ID, CONVERSATION_ID);
        order.verify(conversationService).expirePendingConfirm(CONVERSATION_ID, USER_ID, "m-4004");
        order.verify(releaseGate).run();
        verify(taskManager).unregister(anyString());
        verify(taskManager, never()).register(anyString(), anyString(), any());
        verify(agent, never()).streamEvents(any(Msg.class), any(RuntimeContext.class));
        verify(agent, never()).saveAgentState(anyString(), anyString());
    }

    /**
     * 泛型 get 落地成 String，避免 assertThat 重载歧义
     */
    private static String contextId(RuntimeContext ctx, String key) {
        String value = ctx.get(key);
        return value;
    }

    @Test
    void shouldEvictStateCacheWhenStreamCancelled() {
        // never 流不会自行走到完成路，驱逐只可能来自取消收尾
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.never());
        ArgumentCaptor<Runnable> finalizer = ArgumentCaptor.forClass(Runnable.class);

        service.streamChat("问题", CONVERSATION_ID, new SseEmitter());
        verify(taskManager).register(anyString(), anyString(), finalizer.capture());
        finalizer.getValue().run();

        verify(agent).clearStateCache(USER_ID, CONVERSATION_ID);
    }

    @Test
    void shouldSaveStateBeforeEvictWhenCancelledStreamErrorsDuringInterrupt() {
        Runnable releaseGate = mock(Runnable.class);
        when(runGate.acquire(anyString(), anyString(), anyString())).thenReturn(releaseGate);
        Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(sink.asFlux());
        doAnswer(invocation -> {
            assertThat(sink.tryEmitError(new IllegalStateException("中断期间上游出错")))
                    .isEqualTo(Sinks.EmitResult.OK);
            return null;
        }).when(agent).interrupt(USER_ID, CONVERSATION_ID);
        ArgumentCaptor<Runnable> cancelAction = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> finalizer = ArgumentCaptor.forClass(Runnable.class);

        service.streamChat("问题", CONVERSATION_ID, new SseEmitter());
        verify(taskManager).register(anyString(), anyString(), finalizer.capture());
        verify(taskManager).bindHandle(anyString(), cancelAction.capture());
        // 与 StreamTaskManager 一致：先标记取消，再中断并等待上游结束，最后取消收尾。
        when(taskManager.isCancelled(anyString())).thenReturn(true);
        cancelAction.getValue().run();
        verify(agent, never()).saveAgentState(anyString(), anyString());
        verify(agent, never()).clearStateCache(anyString(), anyString());
        verifyNoInteractions(releaseGate);
        finalizer.getValue().run();

        InOrder order = inOrder(agent, releaseGate);
        order.verify(agent).interrupt(USER_ID, CONVERSATION_ID);
        order.verify(agent).saveAgentState(USER_ID, CONVERSATION_ID);
        order.verify(agent).clearStateCache(USER_ID, CONVERSATION_ID);
        order.verify(releaseGate).run();
        verify(agentProvider).getAgent();
        verify(agentProvider, never()).evictStateCache(anyString(), anyString());
    }

    /**
     * 强制断流后先补存缓存中的状态，再清缓存并释放运行位；重复取消不得重复收尾
     */
    @Test
    void shouldSaveStateBeforeEvictWhenForcedDisposal() {
        Runnable releaseGate = mock(Runnable.class);
        Runnable disposeUpstream = mock(Runnable.class);
        when(runGate.acquire(anyString(), anyString(), anyString())).thenReturn(releaseGate);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Flux.<AgentEvent>never().doOnCancel(disposeUpstream));
        // 打断动作异常是三种强制断流入口里唯一不用等 2 秒窗口的，测试走这条
        doThrow(new IllegalStateException("打断动作炸了")).when(agent).interrupt(USER_ID, CONVERSATION_ID);
        ArgumentCaptor<Runnable> cancelAction = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> finalizer = ArgumentCaptor.forClass(Runnable.class);

        service.streamChat("问题", CONVERSATION_ID, new SseEmitter());
        verify(taskManager).register(anyString(), anyString(), finalizer.capture());
        verify(taskManager).bindHandle(anyString(), cancelAction.capture());
        when(taskManager.isCancelled(anyString())).thenReturn(true);
        cancelAction.getValue().run();
        finalizer.getValue().run();
        cancelAction.getValue().run();
        finalizer.getValue().run();

        InOrder order = inOrder(agent, disposeUpstream, releaseGate);
        order.verify(agent).interrupt(USER_ID, CONVERSATION_ID);
        order.verify(disposeUpstream).run();
        order.verify(agent).saveAgentState(USER_ID, CONVERSATION_ID);
        order.verify(agent).clearStateCache(USER_ID, CONVERSATION_ID);
        order.verify(releaseGate).run();
        verify(agent).saveAgentState(USER_ID, CONVERSATION_ID);
        verify(agent).clearStateCache(USER_ID, CONVERSATION_ID);
        verify(taskManager).unregister(anyString());
        verify(releaseGate).run();
    }

    /**
     * 优雅中断的完成信号不能证明框架保存成功，取消收尾仍需补存；补存失败也必须释放资源
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldSaveStateBeforeEvictWhenInterruptedGracefully(boolean stateSaveFails) {
        Runnable releaseGate = mock(Runnable.class);
        when(runGate.acquire(anyString(), anyString(), anyString())).thenReturn(releaseGate);
        Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(sink.asFlux());
        // 打断动作触发流正常完成，模拟框架在窗口内收尾
        doAnswer(invocation -> {
            assertThat(sink.tryEmitComplete()).isEqualTo(Sinks.EmitResult.OK);
            return null;
        }).when(agent).interrupt(USER_ID, CONVERSATION_ID);
        if (stateSaveFails) {
            doThrow(new IllegalStateException("补存失败")).when(agent).saveAgentState(USER_ID, CONVERSATION_ID);
        }
        ArgumentCaptor<Runnable> cancelAction = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> finalizer = ArgumentCaptor.forClass(Runnable.class);

        service.streamChat("问题", CONVERSATION_ID, new SseEmitter());
        verify(taskManager).register(anyString(), anyString(), finalizer.capture());
        verify(taskManager).bindHandle(anyString(), cancelAction.capture());
        when(taskManager.isCancelled(anyString())).thenReturn(true);
        cancelAction.getValue().run();
        verify(agent, never()).clearStateCache(anyString(), anyString());
        verifyNoInteractions(releaseGate);
        finalizer.getValue().run();

        InOrder order = inOrder(agent, releaseGate);
        order.verify(agent).interrupt(USER_ID, CONVERSATION_ID);
        order.verify(agent).saveAgentState(USER_ID, CONVERSATION_ID);
        order.verify(agent).clearStateCache(USER_ID, CONVERSATION_ID);
        order.verify(releaseGate).run();
        verify(taskManager).unregister(anyString());
    }

    /**
     * 预埋取消标记会让 register 当场跑完收尾，此时不该再启动 Agent
     */
    @Test
    void shouldNotStartAgentWhenCancelledAtRegister() {
        Model model = mock(Model.class);
        AgentStateStore stateStore = mock(AgentStateStore.class);
        // 使用真实框架验证：未启动、无缓存时，补存不会新建或加载会话状态。
        try (ReActAgent original = ReActAgent.builder().name("pre-start-cancel")
                .model(model).stateStore(stateStore).build()) {
            ReActAgent unstartedAgent = spy(original);
            when(agentProvider.getAgent()).thenReturn(new ActiveAgent(unstartedAgent,
                    new ResolvedCatalog("知识库工具描述", null, List.of(), List.of(), List.of())));
            doAnswer(invocation -> {
                invocation.getArgument(2, Runnable.class).run();
                return null;
            }).when(taskManager).register(anyString(), anyString(), any(Runnable.class));

            service.streamChat("问题", CONVERSATION_ID, new SseEmitter());

            InOrder order = inOrder(unstartedAgent);
            order.verify(unstartedAgent).saveAgentState(USER_ID, CONVERSATION_ID);
            order.verify(unstartedAgent).clearStateCache(USER_ID, CONVERSATION_ID);
            verify(unstartedAgent, never()).streamEvents(any(Msg.class), any(RuntimeContext.class));
            verify(unstartedAgent, never()).interrupt(anyString(), anyString());
            verify(unstartedAgent, never()).getAgentState(anyString(), anyString());
            verifyNoInteractions(stateStore, model);
            assertThat(gateReleased.get()).isOne();
        }
    }

    @Test
    void shouldEvictOnlyOnceWhenCancelRacesCompletion() {
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());
        ArgumentCaptor<Runnable> finalizer = ArgumentCaptor.forClass(Runnable.class);

        service.streamChat("问题", CONVERSATION_ID, new SseEmitter());
        verify(taskManager).register(anyString(), anyString(), finalizer.capture());
        finalizer.getValue().run();

        verify(agent, times(1)).clearStateCache(USER_ID, CONVERSATION_ID);
        verify(agent, never()).saveAgentState(anyString(), anyString());
        verify(taskManager).unregister(anyString());
        assertThat(gateReleased.get()).isOne();
    }

    @Test
    void shouldReleaseGateWhenStreamCompletes() {
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());

        service.streamChat("问题", CONVERSATION_ID, new SseEmitter());

        // 闸门不还，该用户到 TTL 过期前发不出下一轮
        assertThat(gateReleased.get()).isOne();
    }

    @Test
    void shouldClearStateCacheBeforeReleasingGate() {
        Runnable releaseGate = mock(Runnable.class);
        when(runGate.acquire(anyString(), anyString(), anyString())).thenReturn(releaseGate);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());

        service.streamChat("问题", CONVERSATION_ID, new SseEmitter());

        InOrder order = inOrder(agent, releaseGate);
        order.verify(agent).clearStateCache(USER_ID, CONVERSATION_ID);
        order.verify(releaseGate).run();
    }

    @Test
    void shouldReleaseGateWhenStreamCancelled() {
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.never());
        ArgumentCaptor<Runnable> finalizer = ArgumentCaptor.forClass(Runnable.class);

        service.streamChat("问题", CONVERSATION_ID, new SseEmitter());
        verify(taskManager).register(anyString(), anyString(), finalizer.capture());
        finalizer.getValue().run();

        assertThat(gateReleased.get()).isOne();
    }

    @Test
    void shouldReleaseGateWhenStartupFails() {
        when(conversationService.touchConversation(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("库炸了"));

        assertThatThrownBy(() -> service.streamChat("问题", CONVERSATION_ID, new SseEmitter()))
                .isInstanceOf(IllegalStateException.class);

        // 启动期失败还没有收尾路可挂，闸门要就地归还
        assertThat(gateReleased.get()).isOne();
    }

    @Test
    void shouldReleaseGateWhenStartupThrowsError() {
        when(conversationService.touchConversation(anyString(), anyString(), anyString()))
                .thenThrow(new NoClassDefFoundError("类没了"));

        assertThatThrownBy(() -> service.streamChat("问题", CONVERSATION_ID, new SseEmitter()))
                .isInstanceOf(NoClassDefFoundError.class);

        // 只接 RuntimeException 的话，启动段抛 Error 会把该用户挡到 TTL 过期（默认半小时）
        assertThat(gateReleased.get()).isOne();
    }

    @Test
    void shouldUnregisterTaskWhenStartupFails() {
        memoryProperties.setLongTermEnabled(true);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenThrow(new IllegalStateException("上游没起来"));

        assertThatThrownBy(() -> service.streamChat("问题", CONVERSATION_ID, new SseEmitter()))
                .isInstanceOf(IllegalStateException.class);

        // 已 register 未 unregister 的任务会守灵到 30 分钟 TTL，期间还能被取消去戳已丢弃的 emitter
        verify(taskManager).unregister(anyString());
        verify(agent).clearStateCache(USER_ID, CONVERSATION_ID);
        verify(agent, never()).saveAgentState(anyString(), anyString());
        verify(memoryPipeline, never()).extract(anyString(), anyString(), any());
        assertThat(gateReleased.get()).isOne();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {CONVERSATION_ID})
    void shouldGetAgentBeforeWritingConversationOrQuestion(String requestedId) {
        when(agentProvider.getAgent()).thenThrow(new IllegalStateException("Prompt 不可用"));

        assertThatThrownBy(() -> service.streamChat("问题", requestedId, new SseEmitter()))
                .isInstanceOf(IllegalStateException.class);

        verify(conversationService, never()).touchConversation(anyString(), anyString(), anyString());
        verify(conversationService, never()).addUserMessage(anyString(), anyString(), anyString());
        assertThat(gateReleased.get()).isOne();
    }

    @Test
    void shouldCheckPendingConfirmationOnlyAfterAcquiringGate() {
        when(runGate.acquire(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            // 模拟上一轮在本轮拿到闸门之前刚写入确认卡片。
            when(conversationService.hasPendingConfirm(CONVERSATION_ID, USER_ID)).thenReturn(true);
            return (Runnable) gateReleased::incrementAndGet;
        });

        assertThatThrownBy(() -> service.streamChat("问题", CONVERSATION_ID, new SseEmitter()))
                .isInstanceOf(ClientException.class).hasMessageContaining("确认");

        verifyNoInteractions(agentProvider);
        verify(conversationService, never()).addUserMessage(anyString(), anyString(), anyString());
        assertThat(gateReleased.get()).isOne();
    }

    @Test
    void shouldKeepConfirmPendingWhenRegistrationFails() {
        prepareConfirmation();
        memoryProperties.setLongTermEnabled(true);
        IllegalStateException failure = new IllegalStateException("Redis 写入失败");
        doThrow(failure).when(taskManager).register(anyString(), anyString(), any());

        assertThatThrownBy(() -> runEntry(true)).isSameAs(failure);

        verify(conversationService, never()).settlePendingConfirm(anyString(), anyString(), anyString(), anyBoolean());
        verify(agent).clearStateCache(USER_ID, CONVERSATION_ID);
        verify(agent, never()).saveAgentState(anyString(), anyString());
        verifyNoInteractions(memoryPipeline);
        assertThat(gateReleased.get()).isOne();
    }

    @Test
    void shouldKeepConfirmPendingWhenFluxCreationFails() {
        prepareConfirmation();
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenThrow(new IllegalStateException("构造流失败"));

        assertThatThrownBy(() -> runEntry(true)).isInstanceOf(IllegalStateException.class);

        verify(conversationService, never()).settlePendingConfirm(anyString(), anyString(), anyString(), anyBoolean());
        verify(agent).clearStateCache(USER_ID, CONVERSATION_ID);
        assertThat(gateReleased.get()).isOne();
    }

    @Test
    void shouldKeepConfirmPendingWhenCancelledDuringRegistration() {
        prepareConfirmation();
        doAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return null;
        }).when(taskManager).register(anyString(), anyString(), any());

        runEntry(true);

        verify(conversationService, never()).settlePendingConfirm(anyString(), anyString(), anyString(), anyBoolean());
        verify(agent, never()).streamEvents(any(Msg.class), any(RuntimeContext.class));
        assertThat(gateReleased.get()).isOne();
    }

    @Test
    void shouldSettleConfirmationAfterPreparationAndBeforeSubscription() {
        prepareConfirmation();
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.defer(() -> {
            verify(conversationService).settlePendingConfirm(CONVERSATION_ID, USER_ID, "m-4004", true);
            return Flux.empty();
        }));

        runEntry(true);

        InOrder order = inOrder(taskManager, agent, conversationService);
        order.verify(taskManager).register(anyString(), anyString(), any());
        order.verify(agent).streamEvents(any(Msg.class), any(RuntimeContext.class));
        order.verify(conversationService).settlePendingConfirm(CONVERSATION_ID, USER_ID, "m-4004", true);
    }

    @Test
    void shouldNotSubscribeWhenConfirmationSettlementFails() {
        prepareConfirmation();
        AtomicInteger subscriptions = new AtomicInteger();
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Flux.defer(() -> { subscriptions.incrementAndGet(); return Flux.empty(); }));
        when(conversationService.settlePendingConfirm(CONVERSATION_ID, USER_ID, "m-4004", true))
                .thenThrow(new ClientException("卡片已处理"));

        assertThatThrownBy(() -> runEntry(true)).isInstanceOf(ClientException.class);

        assertThat(subscriptions.get()).isZero();
        verify(agent).clearStateCache(USER_ID, CONVERSATION_ID);
        assertThat(gateReleased.get()).isOne();
    }

    @Test
    void shouldNotSubscribeChatWhenCancellationWinsDuringPreparation() throws Exception {
        assertCancellationWinsDuringPreparation(false);
    }

    @Test
    void shouldNotSettleOrSubscribeConfirmWhenCancellationWinsDuringPreparation() throws Exception {
        prepareConfirmation();
        assertCancellationWinsDuringPreparation(true);
        verify(conversationService, never()).settlePendingConfirm(anyString(), anyString(), anyString(), anyBoolean());
    }

    private void assertCancellationWinsDuringPreparation(boolean confirm) throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        CountDownLatch preparing = new CountDownLatch(1);
        CountDownLatch continuePreparation = new CountDownLatch(1);
        AtomicReference<Runnable> finalizer = new AtomicReference<>();
        AtomicInteger subscriptions = new AtomicInteger();
        doAnswer(invocation -> { finalizer.set(invocation.getArgument(2)); return null; })
                .when(taskManager).register(anyString(), anyString(), any());
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenAnswer(invocation -> {
            preparing.countDown();
            await(continuePreparation);
            return Flux.defer(() -> { subscriptions.incrementAndGet(); return Flux.empty(); });
        });
        try {
            var starting = pool.submit(() -> runEntry(confirm));
            assertThat(preparing.await(5, TimeUnit.SECONDS)).isTrue();
            finalizer.get().run();
            assertThat(gateReleased.get()).isOne();
            continuePreparation.countDown();
            starting.get(5, TimeUnit.SECONDS);
            assertThat(subscriptions.get()).isZero();
        } finally {
            continuePreparation.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void shouldStopChatBeforeReleasingGateWhenCancelledDuringSubscription() throws Exception {
        assertCancellationDuringSubscription(false);
    }

    @Test
    void shouldStopConfirmBeforeReleasingGateWhenCancelledDuringSubscription() throws Exception {
        prepareConfirmation();
        assertCancellationDuringSubscription(true);
        verify(conversationService).settlePendingConfirm(CONVERSATION_ID, USER_ID, "m-4004", true);
    }

    private void assertCancellationDuringSubscription(boolean confirm) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch subscribing = new CountDownLatch(1);
        CountDownLatch continueSubscription = new CountDownLatch(1);
        CountDownLatch cancelling = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicBoolean cancellationRequested = new AtomicBoolean();
        AtomicBoolean disposed = new AtomicBoolean();
        AtomicReference<Runnable> finalizer = new AtomicReference<>();
        when(taskManager.isCancelled(anyString())).thenAnswer(invocation -> cancellationRequested.get());
        doAnswer(invocation -> { finalizer.set(invocation.getArgument(2)); return null; })
                .when(taskManager).register(anyString(), anyString(), any());
        doAnswer(invocation -> {
            if (cancellationRequested.get()) {
                invocation.getArgument(1, Runnable.class).run();
            }
            return null;
        }).when(taskManager).bindHandle(anyString(), any());
        doThrow(new IllegalStateException("强制停止测试上游")).when(agent).interrupt(USER_ID, CONVERSATION_ID);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.defer(() -> {
            subscribing.countDown();
            await(continueSubscription);
            return Flux.<AgentEvent>never().doOnCancel(() -> disposed.set(true));
        }));
        try {
            var starting = pool.submit(() -> runEntry(confirm));
            assertThat(subscribing.await(5, TimeUnit.SECONDS)).isTrue();
            var stopping = pool.submit(() -> {
                cancellationRequested.set(true);
                cancelling.countDown();
                finalizer.get().run();
                cancelled.countDown();
            });
            assertThat(cancelling.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(cancelled.await(100, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(gateReleased.get()).isZero();
            continueSubscription.countDown();
            starting.get(5, TimeUnit.SECONDS);
            stopping.get(5, TimeUnit.SECONDS);
            assertThat(disposed.get()).isTrue();
            assertThat(gateReleased.get()).isOne();
        } finally {
            continueSubscription.countDown();
            pool.shutdownNow();
        }
    }

    private void prepareConfirmation() {
        ToolUseBlock asking = ToolUseBlock.builder().id("call-1").name("submit_leave")
                .input(Map.of("days", 1)).state(ToolCallState.ASKING).build();
        when(agent.getAgentState(USER_ID, CONVERSATION_ID)).thenReturn(AgentState.builder()
                .userId(USER_ID).sessionId(CONVERSATION_ID)
                .addMessage(AssistantMessage.builder().content(asking).build()).build());
        when(conversationService.getPendingConfirm(CONVERSATION_ID, USER_ID, "m-4004"))
                .thenReturn(new AgentConfirmSettlement("会话标题", "m-3003"));
    }

    private void runEntry(boolean confirm) {
        UserContext.set(LoginUser.builder().userId(USER_ID).username("tester").build());
        try {
            if (confirm) {
                service.confirmPendingTool(CONVERSATION_ID, "m-4004", true, new SseEmitter());
            } else {
                service.streamChat("问题", CONVERSATION_ID, new SseEmitter());
            }
        } finally {
            UserContext.clear();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    void shouldNotStartRunWhenGateRejects() {
        when(runGate.acquire(anyString(), anyString(), anyString()))
                .thenThrow(new ClientException("当前会话处理中，请稍后再发起新的对话"));

        assertThatThrownBy(() -> service.streamChat("问题", CONVERSATION_ID, new SseEmitter()))
                .isInstanceOf(ClientException.class);

        // 被拒的请求不该留下会话行与任务登记，否则闸门反倒制造了脏数据
        verify(conversationService, never()).touchConversation(anyString(), anyString(), anyString());
        verify(conversationService, never()).addUserMessage(anyString(), anyString(), anyString());
        verifyNoInteractions(taskManager);
        verify(agent, never()).streamEvents(any(Msg.class), any(RuntimeContext.class));
    }

    @Test
    void shouldCancelUpstreamWhenEmitterTimesOut() {
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.never());
        SseEmitter emitter = mock(SseEmitter.class);
        ArgumentCaptor<String> taskId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Runnable> callbacks = ArgumentCaptor.forClass(Runnable.class);

        service.streamChat("问题", CONVERSATION_ID, emitter);
        verify(taskManager).register(taskId.capture(), anyString(), any());
        verify(emitter, atLeastOnce()).onTimeout(callbacks.capture());
        callbacks.getAllValues().forEach(Runnable::run);

        // 超时只关响应不回收上游，ReAct 会在无人消费的情况下跑满迭代上限
        verify(taskManager).cancel(taskId.getValue());
    }

    @Test
    void shouldCancelUpstreamWhenEmitterErrors() {
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.never());
        SseEmitter emitter = mock(SseEmitter.class);
        ArgumentCaptor<String> taskId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Consumer<Throwable>> callbacks = ArgumentCaptor.forClass(Consumer.class);

        service.streamChat("问题", CONVERSATION_ID, emitter);
        verify(taskManager).register(taskId.capture(), anyString(), any());
        verify(emitter, atLeastOnce()).onError(callbacks.capture());
        callbacks.getAllValues().forEach(callback -> callback.accept(new IOException("客户端断开")));

        verify(taskManager).cancel(taskId.getValue());
    }

    @Test
    void shouldCancelUpstreamWhenEmitterClosesWithoutSettling() {
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.never());
        SseEmitter emitter = mock(SseEmitter.class);
        ArgumentCaptor<String> taskId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Runnable> callbacks = ArgumentCaptor.forClass(Runnable.class);

        service.streamChat("问题", CONVERSATION_ID, emitter);
        verify(taskManager).register(taskId.capture(), anyString(), any());
        verify(emitter, atLeastOnce()).onCompletion(callbacks.capture());
        callbacks.getAllValues().forEach(Runnable::run);

        // 关页导致写失败时容器不报超时也不报错，只有 completion 回调兜得住
        verify(taskManager).cancel(taskId.getValue());
    }

    @Test
    void shouldNotCancelAfterRunAlreadySettled() {
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());
        SseEmitter emitter = mock(SseEmitter.class);
        ArgumentCaptor<Runnable> callbacks = ArgumentCaptor.forClass(Runnable.class);

        service.streamChat("问题", CONVERSATION_ID, emitter);
        verify(emitter, atLeastOnce()).onCompletion(callbacks.capture());
        callbacks.getAllValues().forEach(Runnable::run);

        // 正常完成也会触发 completion 回调，这里再取消等于每个请求都往 Redis 写一条 30 分钟死标记
        verify(taskManager, never()).cancel(anyString());
    }
}
