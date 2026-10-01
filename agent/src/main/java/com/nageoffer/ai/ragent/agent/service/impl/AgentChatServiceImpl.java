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

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider.ActiveAgent;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmSettlement;
import com.nageoffer.ai.ragent.agent.dto.AgentMetaPayload;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryTriggerType;
import com.nageoffer.ai.ragent.agent.enums.AgentSSEEventType;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.service.AgentChatService;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunHandle;
import com.nageoffer.ai.ragent.agent.service.handler.AgentStreamEventBridge;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import com.nageoffer.ai.ragent.agent.trace.AgentTraceContextKeys;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpMeta;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.mcp.McpMeta;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 流式对话，负责发起提问与用户确认两条入口
 */
@Slf4j
@Service
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentChatServiceImpl implements AgentChatService {

    private final ReActAgentProvider agentProvider;
    private final AgentConversationService conversationService;
    private final StreamTaskManager taskManager;
    private final AgentRunGate runGate;
    private final AgentMemoryProperties memoryProperties;
    private final AgentMemoryPipeline memoryPipeline;

    @Override
    public void streamChat(String question, String conversationId, SseEmitter emitter) {
        String userId = UserContext.getUserId();
        String actualConversationId = StrUtil.isBlank(conversationId)
                ? IdUtil.getSnowflakeNextIdStr()
                : conversationId;
        String taskId = IdUtil.getSnowflakeNextIdStr();

        Runnable releaseGate = runGate.acquire(userId, taskId, actualConversationId);
        try {
            if (StrUtil.isNotBlank(conversationId)
                    && conversationService.hasPendingConfirm(actualConversationId, userId)) {
                throw new ClientException("上一步操作还在等你确认，请先确认或取消");
            }
            startRun(question, userId, actualConversationId, taskId, emitter, releaseGate);
        } catch (RuntimeException | Error e) {
            cleanupFailedStart(taskId, releaseGate);
            throw e;
        }
    }

    @Override
    public void confirmPendingTool(String conversationId, String messageId, boolean approved, SseEmitter emitter) {
        String userId = UserContext.getUserId();
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(messageId)) {
            throw new ClientException("确认参数不完整");
        }
        String taskId = IdUtil.getSnowflakeNextIdStr();

        Runnable releaseGate = runGate.acquire(userId, taskId, conversationId);
        try {
            startConfirmRun(userId, conversationId, messageId, approved, taskId, emitter, releaseGate);
        } catch (RuntimeException | Error e) {
            cleanupFailedStart(taskId, releaseGate);
            throw e;
        }
    }

    /**
     * 只在启动失败时走：未归还的会话锁和名额会被看门狗持续续期，直到释放或进程退出
     * 启动成功后闸门归运行句柄的释放钩子管，这里不碰
     * 两步各自兜异常，前一步失败不能连累后一步
     */
    private void cleanupFailedStart(String taskId, Runnable releaseGate) {
        try {
            releaseGate.run();
        } catch (Exception e) {
            log.error("Agent 启动失败后释放闸门失败, taskId: {}", taskId, e);
        }
        try {
            taskManager.unregister(taskId);
        } catch (Exception e) {
            log.error("Agent 启动失败后注销任务失败, taskId: {}", taskId, e);
        }
    }

    private void startRun(String question, String userId, String conversationId, String taskId,
                          SseEmitter emitter, Runnable releaseGate) {
        ActiveAgent activeAgent = agentProvider.getAgent();

        String title = conversationService.touchConversation(conversationId, userId, question);
        // 必须在 addUserMessage 之前建立基线，否则本轮消息会被划进历史、漏抽
        memoryPipeline.ensureExtractionBaseline(userId);
        String questionMessageId = conversationService.addUserMessage(conversationId, userId, question);

        launchStream(new UserMessage(question), activeAgent, RunScope.builder()
                .emitter(emitter)
                .userId(userId)
                .conversationId(conversationId)
                .taskId(taskId)
                .title(title)
                .replyToMessageId(questionMessageId)
                .releaseGate(releaseGate)
                .build());
    }

    /**
     * 用户确认/拒绝后继续执行：先只读校验，启动准备完成后才结算卡片
     */
    private void startConfirmRun(String userId, String conversationId, String messageId, boolean approved,
                                 String taskId, SseEmitter emitter, Runnable releaseGate) {
        ActiveAgent activeAgent = agentProvider.getAgent();
        AgentConfirmSettlement settlement = conversationService.getPendingConfirm(conversationId, userId, messageId);
        List<ConfirmResult> confirmResults = resolveConfirmResultsOrExpire(activeAgent, userId, conversationId, messageId, approved);

        // 空正文消息仅携带确认/拒绝结果，框架不会把它并进对话上下文
        Msg resumeMsg = UserMessage.builder()
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, confirmResults))
                .build();
        launchStream(resumeMsg, activeAgent, RunScope.builder()
                .emitter(emitter)
                .userId(userId)
                .conversationId(conversationId)
                .taskId(taskId)
                .title(settlement.title())
                .replyToMessageId(settlement.replyToMessageId())
                .confirmMessageId(messageId)
                .confirmApproved(approved)
                .releaseGate(releaseGate)
                .build());
    }

    /**
     * 工具入参从 Agent 状态取，前端只传同意/拒绝，防止篡改
     */
    private List<ConfirmResult> resolveConfirmResultsOrExpire(ActiveAgent activeAgent, String userId,
                                                              String conversationId, String messageId, boolean approved) {
        @SuppressWarnings("resource")
        ReActAgent agent = activeAgent.agent();
        List<ToolUseBlock> asking = askingToolCalls(agent.getAgentState(userId, conversationId).getContext());
        if (asking.isEmpty()) {
            agent.clearStateCache(userId, conversationId);
            // 状态里没有待确认工具了，先结算卡片再报错，否则会话会一直卡住
            conversationService.expirePendingConfirm(conversationId, userId, messageId);
            throw new ClientException("待确认的操作已失效，请重新提问");
        }
        return asking.stream().map(toolCall -> new ConfirmResult(approved, toolCall)).toList();
    }

    /**
     * 待批工具只在上下文最后一条 assistant 消息上，与框架内部判据一致
     */
    private static List<ToolUseBlock> askingToolCalls(List<Msg> context) {
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg msg = context.get(i);
            if (msg.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            return msg.getContent().stream()
                    .filter(ToolUseBlock.class::isInstance)
                    .map(ToolUseBlock.class::cast)
                    .filter(toolCall -> toolCall.getState() == ToolCallState.ASKING)
                    .toList();
        }
        return List.of();
    }

    /**
     * 首问与确认后继续执行共用的流式启动逻辑
     */
    private void launchStream(Msg input, ActiveAgent activeAgent, RunScope scope) {
        String userId = scope.userId();
        String conversationId = scope.conversationId();
        String taskId = scope.taskId();
        ReActAgent agent = activeAgent.agent();

        // 事实源：桥、上下文与句柄共用，ID 与时刻只在这里产生
        Clock clock = Clock.systemDefaultZone();
        AgentToolExecutionFacts facts = new AgentToolExecutionFacts(taskId, clock);
        // 上下文传到底，中断时句柄从这里取根 span
        RuntimeContext runtimeContext = buildRuntimeContext(scope, facts);
        SseEmitterSender sender = new SseEmitterSender(scope.emitter());
        AgentRunHandle runHandle = new AgentRunHandle(taskId, sender, taskManager, facts, runtimeContext);
        bindReleaseHooks(runHandle, agent, scope);
        try {
            bindEmitterLifecycle(scope.emitter(), runHandle, taskId);
            sender.sendEvent(AgentSSEEventType.META.value(), new AgentMetaPayload(conversationId, taskId));
            AgentStreamEventBridge bridge = buildBridge(activeAgent, scope, runHandle, facts, clock);
            taskManager.register(taskId, userId, bridge::finishCancelledStream);
            // 预埋取消标记会让 register 当场跑完收尾，此时不再启动 Agent
            if (runHandle.isSettled()) {
                return;
            }

            Flux<AgentEvent> events = agent.streamEvents(input, runtimeContext)
                    // 让 runHandle 知道框架流已结束，取消时不必再强行断流
                    .doFinally(signal -> runHandle.markUpstreamTerminated());
            runHandle.start(() -> {
                settleConfirmCard(scope);
                Disposable disposable = events.subscribe(bridge::onEvent, bridge::onError, bridge::onComplete);
                runHandle.bindStream(disposable, () -> agent.interrupt(userId, conversationId));
                taskManager.bindHandle(taskId, runHandle::interruptUpstream);
            });
        } catch (RuntimeException | Error e) {
            // 启动失败时释放钩子不会执行，手动清掉缓存里的坏状态
            agent.clearStateCache(userId, conversationId);
            throw e;
        }
    }

    /**
     * 登记流结束后的三件事，注册顺序就是执行顺序，换顺序会出问题
     */
    private void bindReleaseHooks(AgentRunHandle runHandle, ReActAgent agent, RunScope scope) {
        String userId = scope.userId();
        String conversationId = scope.conversationId();
        runHandle.onRelease(() -> {
            // 出错或取消时，在清缓存前尝试补存当前状态；优雅中断的流结束也不代表框架存盘成功
            // 正常完成仍由框架保存，不重复补存
            if (runHandle.isStateSaveRequired()) {
                try {
                    agent.saveAgentState(userId, conversationId);
                } catch (Exception e) {
                    log.error("Agent 收尾补存盘失败, conversationId: {}", conversationId, e);
                }
            }
            agent.clearStateCache(userId, conversationId);
        });
        // 最后再放行同一会话的下一轮，避免新流加载状态后被本轮收尾清掉
        runHandle.onRelease(scope.releaseGate());
        // 会话锁与名额释放流程结束后，再调度记忆抽取
        runHandle.onRelease(() -> scheduleMemoryExtraction(userId, conversationId));
    }

    /**
     * 组装事件，agent 和 catalog 从同一个 ActiveAgent 取出，保证展示名一致
     */
    private AgentStreamEventBridge buildBridge(ActiveAgent activeAgent, RunScope scope, AgentRunHandle runHandle,
                                               AgentToolExecutionFacts facts, Clock clock) {
        return AgentStreamEventBridge.builder()
                .runHandle(runHandle)
                .conversationService(conversationService)
                .catalog(activeAgent.catalog())
                .conversationId(scope.conversationId())
                .userId(scope.userId())
                .title(scope.title())
                .replyToMessageId(scope.replyToMessageId())
                .clock(clock)
                .facts(facts)
                .build();
    }

    /**
     * 把确认卡片改写成终态，首问路径没有卡片可结算，直接跳过
     * 只能在启动互斥区里做：先结算后订阅，取消才不会漏掉这次工具执行
     */
    private void settleConfirmCard(RunScope scope) {
        if (scope.confirmMessageId() == null) {
            return;
        }
        conversationService.settlePendingConfirm(scope.conversationId(), scope.userId(),
                scope.confirmMessageId(), scope.confirmApproved());
    }

    /**
     * 事实源在构建时就位，中间件从上下文取它；其余 key 建完再 put——底层 ConcurrentHashMap 不接受 null
     */
    private static RuntimeContext buildRuntimeContext(RunScope scope, AgentToolExecutionFacts facts) {
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(scope.userId())
                .sessionId(scope.conversationId())
                .put(McpMeta.class, new McpMeta(AgentMcpMeta.ofUser(scope.userId())))
                .put(AgentToolExecutionFacts.RUNTIME_CONTEXT_KEY, facts)
                .build();
        runtimeContext.put(AgentTraceContextKeys.TASK_ID, scope.taskId());
        runtimeContext.put(AgentTraceContextKeys.REPLY_TO_MESSAGE_ID, scope.replyToMessageId());
        runtimeContext.put(AgentTraceContextKeys.CONFIRM_MESSAGE_ID, scope.confirmMessageId());
        return runtimeContext;
    }

    /**
     * 单次运行的上下文参数，在 startRun/startConfirmRun 与 launchStream 之间传递
     */
    @Builder
    private record RunScope(SseEmitter emitter, String userId, String conversationId,
                            String taskId, String title, String replyToMessageId, String confirmMessageId,
                            boolean confirmApproved, Runnable releaseGate) {
    }

    /**
     * 轮次结束后异步抽取长期记忆
     */
    private void scheduleMemoryExtraction(String userId, String conversationId) {
        if (!memoryProperties.isLongTermEnabled()) {
            return;
        }
        Mono.fromCallable(() -> memoryPipeline.extract(userId, conversationId, AgentMemoryTriggerType.BACKGROUND))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(outcome -> {
                            if (outcome.idle()) {
                                return;
                            }
                            log.info("轮次结束后台记忆抽取完成, userId: {}, conversationId: {}, 状态: {}, 落库: {}",
                                    userId, conversationId, outcome.status(), outcome.applied());
                        },
                        // 用户已拿到答复，失败只记日志
                        e -> log.error("轮次结束后台记忆抽取异常, userId: {}, conversationId: {}",
                                userId, conversationId, e));
    }

    /**
     * SSE 断开（关页/断网/超时）时触发取消，否则 ReAct 循环会空跑到迭代上限
     */
    private void bindEmitterLifecycle(SseEmitter emitter, AgentRunHandle runHandle, String taskId) {
        AtomicBoolean recycled = new AtomicBoolean(false);
        Runnable recycleUpstream = () -> {
            // 已结算的任务不再重复提交本地取消
            if (!runHandle.isSettled() && recycled.compareAndSet(false, true)) {
                // 中断最多等待 2 秒，随后还需补存状态，不占用容器生命周期回调线程
                try {
                    Schedulers.boundedElastic().schedule(() -> taskManager.cancel(taskId));
                } catch (RuntimeException e) {
                    recycled.set(false);
                    log.error("提交 Agent 本地取消失败，允许后续生命周期回调重试，taskId={}", taskId, e);
                }
            }
        };
        emitter.onTimeout(recycleUpstream);
        emitter.onError(e -> recycleUpstream.run());
        // 用户关闭页面走 completeWithError，容器不触发 onTimeout/onError，只有 onCompletion 能兜住
        emitter.onCompletion(recycleUpstream);
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancelByUser(taskId);
    }
}
