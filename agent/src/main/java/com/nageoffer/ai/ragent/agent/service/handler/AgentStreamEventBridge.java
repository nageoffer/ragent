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

package com.nageoffer.ai.ragent.agent.service.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.dto.AgentCompletionPayload;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmCall;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmField;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmPayload;
import com.nageoffer.ai.ragent.agent.dto.AgentHintPayload;
import com.nageoffer.ai.ragent.agent.dto.AgentMessageDelta;
import com.nageoffer.ai.ragent.agent.dto.AgentTextBlockSeal;
import com.nageoffer.ai.ragent.agent.dto.AgentToolProgress;
import com.nageoffer.ai.ragent.agent.enums.AgentMessageStatus;
import com.nageoffer.ai.ragent.agent.enums.AgentSSEEventType;
import com.nageoffer.ai.ragent.agent.enums.AgentToolStatus;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.ResolvedCatalog;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts.ToolBatchFact;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts.ToolFact;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AgentScope 事件流 → SSE 协议转换，负责增量转发、轨迹落库与取消收尾
 */
@Slf4j
public class AgentStreamEventBridge {

    private static final String HINT_AGENT = "AGENT_HINT";
    private static final String HINT_MAX_ITERATIONS = "MAX_ITERATIONS";
    /**
     * 工具结果截断上限
     */
    private static final int TOOL_RESULT_MAX_CHARS = 64_000;
    private static final String FALLBACK_CALL_KEY = "__anonymous__";
    /**
     * 中断时的用户提示，写操作可能已经发出去，一律提醒核对再重试
     */
    private static final String NOTICE_INTERRUPTED =
            "回复到这里中断了。如果上面有提交类操作，请先到对应业务系统核对是否生效，确认没生效再重新提问";
    /**
     * 块时间戳格式，沿用前端约定不带时区
     */
    private static final DateTimeFormatter BLOCK_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final SseEmitterSender sender;
    private final AgentRunHandle runHandle;
    private final AgentConversationService conversationService;
    private final ResolvedCatalog catalog;
    private final String conversationId;
    private final String userId;
    private final String title;
    private final String replyToMessageId;
    /**
     * 块时刻来源，测试传定格时钟
     */
    private final Clock clock;
    /**
     * 批次号、序号与工具起止的唯一来源，桥只投影
     */
    private final AgentToolExecutionFacts facts;

    private final Object stateLock = new Object();

    /**
     * 非流式终答，流式增量为空时拿它兜底
     */
    private String fallbackText = "";

    private final List<AgentBlock> blocks = new ArrayList<>();
    private final Map<String, AgentBlock> openToolBlocks = new HashMap<>();
    private final Map<String, StringBuilder> toolResultBuffers = new HashMap<>();

    /**
     * 待用户确认的工具调用，非 null 表示本轮挂起等待确认
     */
    private List<AgentConfirmCall> pendingConfirmCalls;

    /**
     * 当前未封口的文本块，工具事件到来时封口
     */
    private AgentBlock openTextBlock;
    private StringBuilder openTextBuffer;

    /**
     * 已封口待广播的文本块，SSE 出锁再发
     */
    private final List<AgentBlock> sealedTextBlocks = new ArrayList<>();

    @Builder
    public AgentStreamEventBridge(AgentRunHandle runHandle, AgentConversationService conversationService,
                                  ResolvedCatalog catalog, String conversationId, String userId, String title,
                                  String replyToMessageId, Clock clock, AgentToolExecutionFacts facts) {
        this.runHandle = runHandle;
        this.sender = runHandle.getSender();
        this.conversationService = conversationService;
        this.catalog = catalog;
        this.conversationId = conversationId;
        this.userId = userId;
        this.title = title;
        this.replyToMessageId = replyToMessageId;
        this.clock = clock;
        this.facts = facts;
    }

    public void onEvent(AgentEvent event) {
        switch (event.getType()) {
            case TEXT_BLOCK_DELTA -> onTextDelta(TextKind.ANSWER, ((TextBlockDeltaEvent) event).getDelta());
            case THINKING_BLOCK_DELTA -> onTextDelta(TextKind.REASONING, ((ThinkingBlockDeltaEvent) event).getDelta());
            case TOOL_CALL_START -> onToolCallStart((ToolCallStartEvent) event);
            case TOOL_RESULT_START -> onToolExecutionStart((ToolResultStartEvent) event);
            case TOOL_RESULT_TEXT_DELTA -> onToolResultDelta((ToolResultTextDeltaEvent) event);
            case TOOL_RESULT_END -> onToolEnd((ToolResultEndEvent) event);
            case ALL_TOOLS_DENIED -> onAllToolsDenied((AllToolsDeniedEvent) event);
            case HINT_BLOCK -> onHint(((HintBlockEvent) event).getHint());
            // 达到迭代上限后框架仍会生成总结与 AgentResult，只提示不判失败
            case EXCEED_MAX_ITERS -> sender.sendEvent(AgentSSEEventType.HINT.value(),
                    new AgentHintPayload(HINT_MAX_ITERATIONS, "已达到最大迭代次数，正在生成当前执行结果的总结"));
            case AGENT_RESULT -> onAgentResult(((AgentResultEvent) event).getResult());
            case REQUIRE_USER_CONFIRM -> onRequireUserConfirm((RequireUserConfirmEvent) event);
            default -> {
            }
        }
        // 统一出锁广播封口帧
        flushSealedTextBlocks();
    }

    public void onComplete() {
        // 被取消的轮次框架也会触发 onComplete，由 finishCancelledStream 统一收尾
        if (runHandle.isCancelled()) {
            return;
        }
        runHandle.complete(() -> {
            // 有待确认的工具时走确认流程，不发 finish
            if (settleAwaitingConfirm()) {
                return;
            }
            String content;
            boolean resend;
            synchronized (stateLock) {
                String streamed = textOf(TextKind.ANSWER);
                // 优先用流式增量，为空时回退到终答消息
                content = StrUtil.isNotBlank(streamed) ? streamed : fallbackText;
                // 非流式场景没有增量，块和增量都要一次性补发
                resend = streamed.isEmpty() && StrUtil.isNotBlank(content);
                if (resend) {
                    appendTextBlock(TextKind.ANSWER, content, false);
                }
            }
            if (resend) {
                sender.sendEvent(AgentSSEEventType.MESSAGE.value(),
                        new AgentMessageDelta(TextKind.ANSWER.kind, content));
            }
            String messageId = settleAndPersistMessage(content, AgentMessageStatus.NORMAL);
            sendTerminal(AgentSSEEventType.FINISH,
                    new AgentCompletionPayload(messageId, title, AgentMessageStatus.NORMAL.name(), facts.settleRun()));
        });
    }

    public void onError(Throwable throwable) {
        // 取消导致的信号中断不算错误，由 finishCancelledStream 收尾
        if (runHandle.isCancelled()) {
            return;
        }
        runHandle.fail(() -> {
            log.error("Agent 流式会话异常, taskId: {}", runHandle.getTaskId(), throwable);
            // 中断提示单独成 error 块：这是系统在说话，混进 answer 就跟模型的回答一个身份了
            // 块和当场的增量都要发，只塞进 content 的话历史回放有块就不读 content，刷新后这句就没了
            String content;
            synchronized (stateLock) {
                String streamed = textOf(TextKind.ANSWER);
                content = StrUtil.isBlank(streamed)
                        ? NOTICE_INTERRUPTED
                        : streamed + "\n\n" + NOTICE_INTERRUPTED;
                appendTextBlock(TextKind.ERROR, NOTICE_INTERRUPTED, false);
            }
            sender.sendEvent(AgentSSEEventType.MESSAGE.value(),
                    new AgentMessageDelta(TextKind.ERROR.kind, NOTICE_INTERRUPTED));
            // 出错也落库留痕，否则已执行的工具操作在历史里查不到
            String messageId = settleAndPersistMessage(content, AgentMessageStatus.INTERRUPTED);
            // finish 让前端把已流出的内容与工具块定型，口径与落库一致，刷新前后看到的是同一条
            sendTerminal(AgentSSEEventType.FINISH,
                    new AgentCompletionPayload(messageId, title, AgentMessageStatus.INTERRUPTED.name(), facts.settleRun()));
        });
    }

    /**
     * 取消后收尾：持久化已生成内容，发 cancel/done 事件
     */
    public void finishCancelledStream() {
        runHandle.cancel(() -> {
            // 取消时如果有待确认的工具，走确认流程而非中断
            if (settleAwaitingConfirm()) {
                return;
            }
            String content;
            boolean tracked;
            synchronized (stateLock) {
                content = textOf(TextKind.ANSWER);
                // 有块就值得落库留痕
                tracked = !blocks.isEmpty();
            }
            String messageId = null;
            if (tracked) {
                messageId = settleAndPersistMessage(content, AgentMessageStatus.INTERRUPTED);
            }
            sendTerminal(AgentSSEEventType.CANCEL,
                    new AgentCompletionPayload(messageId, title, AgentMessageStatus.INTERRUPTED.name(), facts.settleRun()));
        });
    }

    private void onTextDelta(TextKind textKind, String delta) {
        if (StrUtil.isEmpty(delta)) {
            return;
        }
        synchronized (stateLock) {
            appendTextBlock(textKind, delta, true);
        }
        sender.sendEvent(AgentSSEEventType.MESSAGE.value(), new AgentMessageDelta(textKind.kind, delta));
    }

    private void onAgentResult(Msg result) {
        String text = result == null ? "" : StrUtil.emptyIfNull(result.getTextContent());
        synchronized (stateLock) {
            fallbackText = text;
        }
    }

    /**
     * 收到确认事件：封装 confirm 块等待用户确认，参数仅用于展示，确认后从 Agent 状态重取
     */
    private void onRequireUserConfirm(RequireUserConfirmEvent event) {
        List<AgentConfirmCall> calls = event.getToolCalls().stream()
                .filter(toolCall -> !isInternalTool(toolCall.getName()))
                .map(this::toConfirmCall)
                .toList();
        if (calls.isEmpty()) {
            return;
        }
        AgentBlock block = AgentBlock.builder()
                .kind(AgentBlock.KIND_CONFIRM)
                .at(LocalDateTime.now(clock).format(BLOCK_TIME))
                .status("pending")
                .calls(calls)
                .build();
        synchronized (stateLock) {
            sealOpenTextBlock();
            // 整批推 awaiting，不止卡片点名的——同批都没跑，留在 pending 收尾会被判成 interrupted
            for (AgentBlock opened : openToolBlocks.values()) {
                if (isOpen(opened.getStatus())) {
                    opened.setStatus(AgentToolStatus.AWAITING.value());
                }
            }
            blocks.add(block);
            pendingConfirmCalls = calls;
        }
    }

    private AgentConfirmCall toConfirmCall(ToolUseBlock toolCall) {
        Map<String, Object> input = toolCall.getInput();
        return AgentConfirmCall.builder()
                .toolCallId(toolCall.getId())
                .name(toolCall.getName())
                .displayName(catalog.displayNameOf(toolCall.getName()))
                .fields(toConfirmFields(toolCall.getName(), input))
                .arguments(input == null || input.isEmpty() ? null : JSONUtil.toJsonPrettyStr(input))
                .build();
    }

    /**
     * 字段按 schema 声明序排列，schema 未声明的附在后面，确保所有参数可见
     */
    private List<AgentConfirmField> toConfirmFields(String toolName, Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        Map<String, String> labels = catalog.fieldLabelsOf(toolName);
        Set<String> ordered = new LinkedHashSet<>(labels.keySet());
        ordered.addAll(input.keySet());
        List<AgentConfirmField> fields = ordered.stream()
                .map(field -> AgentConfirmField.builder()
                        .name(field)
                        .label(labels.getOrDefault(field, field))
                        .value(StrUtil.trim(StrUtil.toStringOrNull(input.get(field))))
                        .build())
                // 过滤掉模型未填的可选参数
                .filter(field -> StrUtil.isNotEmpty(field.getValue()))
                .toList();
        return fields.isEmpty() ? null : fields;
    }

    /**
     * 有待确认工具时走此分支：以 AWAITING_CONFIRM 落库并发 confirm 事件
     */
    private boolean settleAwaitingConfirm() {
        List<AgentConfirmCall> pending;
        String streamed;
        synchronized (stateLock) {
            if (pendingConfirmCalls == null) {
                return false;
            }
            pending = pendingConfirmCalls;
            streamed = textOf(TextKind.ANSWER);
        }
        String messageId = settleAndPersistMessage(streamed, AgentMessageStatus.AWAITING_CONFIRM);
        if (messageId == null) {
            settleUnpersistedConfirm();
            return true;
        }
        sendTerminal(AgentSSEEventType.CONFIRM, new AgentConfirmPayload(messageId, title, pending, facts.settleRun()));
        return true;
    }

    /**
     * 确认消息落库失败：没有 messageId 无法结算，提示用户新建会话
     */
    private void settleUnpersistedConfirm() {
        log.error("待确认消息落库失败, 本轮改为中断收尾, conversationId: {}", conversationId);
        sender.sendEvent(AgentSSEEventType.HINT.value(),
                new AgentHintPayload(HINT_AGENT, "系统繁忙，这一步没有执行；这条会话已无法继续，请新建会话重试"));
        sendTerminal(AgentSSEEventType.FINISH,
                new AgentCompletionPayload(null, title, AgentMessageStatus.INTERRUPTED.name(), facts.settleRun()));
    }

    /**
     * 模型开始吐参数，属推理阶段，只建 pending 块
     */
    private void onToolCallStart(ToolCallStartEvent event) {
        String toolName = event.getToolCallName();
        if (isInternalTool(toolName)) {
            return;
        }
        AgentToolProgress progress;
        synchronized (stateLock) {
            sealOpenTextBlock();
            AgentBlock block = createAndAddToolBlock(toolName, event.getToolCallId());
            block.setStatus(AgentToolStatus.PENDING.value());
            block.setCallIndex(facts.callIndexOf(event.getToolCallId()));
            openToolBlocks.put(callKey(event.getToolCallId()), block);
            progress = AgentToolProgress.of(block);
        }
        sender.sendEvent(AgentSSEEventType.BLOCK.value(), progress);
    }

    /**
     * 整批开始执行，标 running 并归批；真正的起止由工具体自己记
     */
    private void onToolExecutionStart(ToolResultStartEvent event) {
        String toolName = event.getToolCallName();
        if (isInternalTool(toolName)) {
            return;
        }
        AgentToolProgress progress;
        synchronized (stateLock) {
            sealOpenTextBlock();
            String callKey = callKey(event.getToolCallId());
            AgentBlock block = openToolBlocks.get(callKey);
            if (block == null) {
                // 确认续跑那轮没有 ToolCallStart，块在这里补建
                block = createAndAddToolBlock(toolName, event.getToolCallId());
                openToolBlocks.put(callKey, block);
            }
            block.setCallIndex(facts.callIndexOf(event.getToolCallId()));
            block.setStatus(AgentToolStatus.RUNNING.value());
            ToolBatchFact batch = facts.currentBatch();
            if (batch != null) {
                block.setBatchId(batch.batchId());
            }
            progress = AgentToolProgress.of(block);
        }
        sender.sendEvent(AgentSSEEventType.BLOCK.value(), progress);
    }

    private void onToolResultDelta(ToolResultTextDeltaEvent event) {
        if (isInternalTool(event.getToolCallName()) || StrUtil.isEmpty(event.getDelta())) {
            return;
        }
        synchronized (stateLock) {
            StringBuilder buffer = toolResultBuffers.computeIfAbsent(callKey(event.getToolCallId()),
                    ignored -> new StringBuilder());
            // 超出上限的部分不再缓冲，截断后缀不会落库也不展示
            int remaining = TOOL_RESULT_MAX_CHARS - buffer.length();
            if (remaining > 0) {
                String delta = event.getDelta();
                buffer.append(delta, 0, Math.min(delta.length(), remaining));
            }
        }
    }

    private void onToolEnd(ToolResultEndEvent event) {
        String toolName = event.getToolCallName();
        if (isInternalTool(toolName)) {
            return;
        }
        AgentToolProgress progress;
        synchronized (stateLock) {
            sealOpenTextBlock();
            String callKey = callKey(event.getToolCallId());
            StringBuilder buffer = toolResultBuffers.remove(callKey);
            AgentBlock block = openToolBlocks.remove(callKey);
            if (block == null) {
                // 没有开头事件，补建块以保留记录，但无批次也无耗时
                log.warn("工具结束事件没有对应的开头事件, taskId: {}, tool: {}, toolCallId: {}",
                        runHandle.getTaskId(), toolName, event.getToolCallId());
                block = createAndAddToolBlock(toolName, event.getToolCallId());
            }
            block.setStatus(AgentToolStatus.of(event.getState()).value());
            block.setResult(buffer == null ? null : buffer.toString());
            applyExecutionTimes(block);
            progress = AgentToolProgress.of(block);
        }
        sender.sendEvent(AgentSSEEventType.BLOCK.value(), progress);
    }

    /**
     * 整批被用户取消，框架不发工具事件，块在这里补建
     */
    private void onAllToolsDenied(AllToolsDeniedEvent event) {
        List<AgentToolProgress> progresses = new ArrayList<>();
        synchronized (stateLock) {
            sealOpenTextBlock();
            for (ToolUseBlock toolCall : event.getDeniedToolCalls()) {
                if (isInternalTool(toolCall.getName())) {
                    continue;
                }
                AgentBlock block = createAndAddToolBlock(toolCall.getName(), toolCall.getId());
                // 没执行过，不给批次号与起止
                block.setStatus(AgentToolStatus.DENIED.value());
                progresses.add(AgentToolProgress.of(block));
            }
        }
        progresses.forEach(progress -> sender.sendEvent(AgentSSEEventType.BLOCK.value(), progress));
    }

    /**
     * 建块并入列，登记到 openToolBlocks 由开头事件自己做
     * 调用方需持 stateLock
     */
    private AgentBlock createAndAddToolBlock(String toolName, String toolCallId) {
        AgentBlock block = AgentBlock.builder()
                .kind(AgentBlock.KIND_TOOL)
                .at(LocalDateTime.now(clock).format(BLOCK_TIME))
                .name(toolName)
                .displayName(catalog.displayNameOf(toolName))
                // 记录真实 id，空值不落
                .toolCallId(StrUtil.blankToDefault(toolCallId, null))
                .build();
        blocks.add(block);
        return block;
    }

    /**
     * 是否尚未到达终态
     */
    private static boolean isOpen(String status) {
        return AgentToolStatus.PENDING.value().equals(status) || AgentToolStatus.RUNNING.value().equals(status);
    }

    /**
     * 投影工具体的真起止到块上，两端齐了才给耗时。没进过工具体的调用无时刻可投影
     * 调用方需持 stateLock
     */
    private void applyExecutionTimes(AgentBlock block) {
        ToolFact fact = facts.toolFact(block.getToolCallId());
        block.setStartedAt(fact.startedAt());
        block.setEndedAt(fact.endedAt());
        Long duration = fact.durationMs();
        if (duration == null) {
            return;
        }
        block.setDurationMs(duration);
        block.setDurationSource(AgentBlock.DURATION_SOURCE_TOOL);
    }

    /**
     * toolCallId 为空时用固定 key 兜底
     */
    private String callKey(String toolCallId) {
        return StrUtil.blankToDefault(toolCallId, FALLBACK_CALL_KEY);
    }

    private void onHint(String hint) {
        if (StrUtil.isBlank(hint)) {
            return;
        }
        sender.sendEvent(AgentSSEEventType.HINT.value(), new AgentHintPayload(HINT_AGENT, hint));
    }

    /**
     * 框架内部工具不展示给用户
     */
    private boolean isInternalTool(String toolName) {
        return StrUtil.isBlank(toolName) || ReActAgent.STRUCTURED_OUTPUT_TOOL_NAME.equals(toolName);
    }

    /**
     * 按块序拼回全文：封过口的块读正文，未封口的那块正文还没回填，读缓冲
     * 调用方需持 stateLock
     */
    private String textOf(TextKind textKind) {
        StringBuilder text = new StringBuilder();
        for (AgentBlock block : blocks) {
            if (!textKind.kind.equals(block.getKind())) {
                continue;
            }
            if (block == openTextBlock) {
                text.append(openTextBuffer);
            } else if (block.getText() != null) {
                text.append(block.getText());
            }
        }
        return text.toString();
    }

    /**
     * streamed=false 是一次性补发（非流式终答、中断提示），没有流的过程，不给起止
     * 调用方需持 stateLock
     */
    private void appendTextBlock(TextKind textKind, String delta, boolean streamed) {
        if (openTextBlock == null || !textKind.kind.equals(openTextBlock.getKind())) {
            sealOpenTextBlock();
            openTextBlock = AgentBlock.builder()
                    .kind(textKind.kind)
                    .at(LocalDateTime.now(clock).format(BLOCK_TIME))
                    .startedAt(streamed ? facts.now() : null)
                    .build();
            openTextBuffer = new StringBuilder();
            blocks.add(openTextBlock);
        }
        openTextBuffer.append(delta);
    }

    /**
     * 封口当前文本块并盖服务端时间戳，调用方需持 stateLock
     */
    private void sealOpenTextBlock() {
        if (openTextBlock == null) {
            return;
        }
        AgentBlock sealed = openTextBlock;
        sealed.setText(openTextBuffer.toString());
        openTextBlock = null;
        openTextBuffer = null;
        // 只广播有起止的，一次性补发的块发空帧会让前端认错待收口块
        if (sealed.getStartedAt() != null) {
            long endedAt = facts.now();
            sealed.setEndedAt(endedAt);
            sealed.setDurationMs(endedAt - sealed.getStartedAt());
            sealedTextBlocks.add(sealed);
        }
    }

    /**
     * 把封好的文本块起止发给前端，按封口先后发
     */
    private void flushSealedTextBlocks() {
        List<AgentBlock> pending;
        synchronized (stateLock) {
            if (sealedTextBlocks.isEmpty()) {
                return;
            }
            pending = List.copyOf(sealedTextBlocks);
            sealedTextBlocks.clear();
        }
        for (AgentBlock sealed : pending) {
            sender.sendEvent(AgentSSEEventType.BLOCK.value(), AgentTextBlockSeal.of(sealed));
        }
    }

    /**
     * 终态事件与 done 成对发出，四条出口一个口径
     */
    private void sendTerminal(AgentSSEEventType type, Object payload) {
        sender.sendEvent(type.value(), payload);
        sender.sendEvent(AgentSSEEventType.DONE.value(), "[DONE]");
    }

    /**
     * 本轮块收口后落库：封口文本块、未完工具改 interrupted、补发封口帧，最后写库
     * 调用方拿到 null 表示没写进去，此时不能再发带 messageId 的收尾事件
     */
    private String settleAndPersistMessage(String content, AgentMessageStatus status) {
        String thinking;
        List<AgentBlock> settled;
        // 思考文本与块列表在同一个锁内取快照
        synchronized (stateLock) {
            thinking = textOf(TextKind.REASONING);
            settled = settleBlocks();
        }
        // 末段封口帧要赶在 finish/confirm/cancel 之前发出去
        flushSealedTextBlocks();
        try {
            return conversationService.addAssistantMessage(conversationId, userId, content,
                    thinking, settled, replyToMessageId, status, facts.settleRun());
        } catch (Exception e) {
            log.error("Agent 终答落库失败, conversationId: {}", conversationId, e);
            return null;
        }
    }

    /**
     * 调用方需持 stateLock：封口文本块、running 改 interrupted、剔除空文本块
     */
    private List<AgentBlock> settleBlocks() {
        sealOpenTextBlock();
        List<AgentBlock> settled = new ArrayList<>(blocks.size());
        for (AgentBlock block : blocks) {
            boolean textual = !AgentBlock.KIND_TOOL.equals(block.getKind())
                    && !AgentBlock.KIND_CONFIRM.equals(block.getKind());
            if (textual && StrUtil.isBlank(block.getText())) {
                continue;
            }
            // 只判工具块，confirm 的 pending 由结算流程改写
            if (AgentBlock.KIND_TOOL.equals(block.getKind()) && isOpen(block.getStatus())) {
                block.setStatus(AgentToolStatus.INTERRUPTED.value());
                // 可能已进过工具体，补上真实起点
                applyExecutionTimes(block);
            }
            settled.add(block);
        }
        return settled.isEmpty() ? null : settled;
    }

    /**
     * 文本类型：增量与块共用，限定为回答、思考和中断提示
     */
    @AllArgsConstructor
    private enum TextKind {

        ANSWER(AgentBlock.KIND_ANSWER),
        REASONING(AgentBlock.KIND_REASONING),
        ERROR(AgentBlock.KIND_ERROR);

        /**
         * 发给前端的增量 type 与落库的块 kind 共用的值
         */
        private final String kind;
    }
}
