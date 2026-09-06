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
import com.nageoffer.ai.ragent.agent.dto.AgentToolProgress;
import com.nageoffer.ai.ragent.agent.enums.AgentMessageStatus;
import com.nageoffer.ai.ragent.agent.enums.AgentSSEEventType;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.ResolvedCatalog;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

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

    private static final String DELTA_TYPE_RESPONSE = "response";
    private static final String DELTA_TYPE_THINK = "think";
    /**
     * 中断提示的增量类型，前端据此单开 error 块，与模型说的话区分开
     */
    private static final String DELTA_TYPE_ERROR = "error";
    private static final String TOOL_STATUS_START = "start";
    private static final String TOOL_STATUS_END = "end";
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

    private final Object stateLock = new Object();

    private final StringBuilder responseBuffer = new StringBuilder();
    private final StringBuilder thinkingBuffer = new StringBuilder();
    private Msg resultMsg;

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

    public AgentStreamEventBridge(Params params) {
        this.runHandle = params.getRunHandle();
        this.sender = runHandle.getSender();
        this.conversationService = params.getConversationService();
        this.catalog = params.getCatalog();
        this.conversationId = params.getConversationId();
        this.userId = params.getUserId();
        this.title = params.getTitle();
        this.replyToMessageId = params.getReplyToMessageId();
    }

    public void onEvent(AgentEvent event) {
        switch (event.getType()) {
            case TEXT_BLOCK_DELTA -> onResponseDelta(((TextBlockDeltaEvent) event).getDelta());
            case THINKING_BLOCK_DELTA -> onThinkingDelta(((ThinkingBlockDeltaEvent) event).getDelta());
            case TOOL_CALL_START -> onToolStart((ToolCallStartEvent) event);
            case TOOL_RESULT_TEXT_DELTA -> onToolResultDelta((ToolResultTextDeltaEvent) event);
            case TOOL_RESULT_END -> onToolEnd((ToolResultEndEvent) event);
            case HINT_BLOCK -> onHint(((HintBlockEvent) event).getHint());
            // 达到迭代上限后框架仍会生成总结与 AgentResult，只提示不判失败
            case EXCEED_MAX_ITERS -> sender.sendEvent(AgentSSEEventType.HINT.value(),
                    new AgentHintPayload(HINT_MAX_ITERATIONS, "已达到最大迭代次数，正在生成当前执行结果的总结"));
            case AGENT_RESULT -> onAgentResult(((AgentResultEvent) event).getResult());
            case REQUIRE_USER_CONFIRM -> onRequireUserConfirm((RequireUserConfirmEvent) event);
            default -> {
            }
        }
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
            String streamed;
            synchronized (stateLock) {
                streamed = responseBuffer.toString();
            }
            // 优先用流式增量，为空时回退到终答消息
            String content = StrUtil.isNotBlank(streamed) ? streamed : fallbackContent();
            // 非流式场景没有增量，一次性补发
            if (streamed.isEmpty() && StrUtil.isNotBlank(content)) {
                synchronized (stateLock) {
                    appendTextBlock(DELTA_TYPE_RESPONSE, content);
                }
                sender.sendEvent(AgentSSEEventType.MESSAGE.value(), new AgentMessageDelta(DELTA_TYPE_RESPONSE, content));
            }
            String messageId = persistAssistantMessage(content, AgentMessageStatus.NORMAL);
            sender.sendEvent(AgentSSEEventType.FINISH.value(),
                    new AgentCompletionPayload(messageId, title, AgentMessageStatus.NORMAL.name()));
            sender.sendEvent(AgentSSEEventType.DONE.value(), "[DONE]");
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
                appendTextBlock(DELTA_TYPE_ERROR, NOTICE_INTERRUPTED);
                content = StrUtil.isBlank(responseBuffer)
                        ? NOTICE_INTERRUPTED
                        : responseBuffer + "\n\n" + NOTICE_INTERRUPTED;
            }
            sender.sendEvent(AgentSSEEventType.MESSAGE.value(),
                    new AgentMessageDelta(DELTA_TYPE_ERROR, NOTICE_INTERRUPTED));
            // 出错也落库留痕，否则已执行的工具操作在历史里查不到
            String messageId = persistAssistantMessage(content, AgentMessageStatus.INTERRUPTED);
            // finish 让前端把已流出的内容与工具块定型，口径与落库一致，刷新前后看到的是同一条
            sender.sendEvent(AgentSSEEventType.FINISH.value(),
                    new AgentCompletionPayload(messageId, title, AgentMessageStatus.INTERRUPTED.name()));
            sender.sendEvent(AgentSSEEventType.DONE.value(), "[DONE]");
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
                content = responseBuffer.toString();
                tracked = !thinkingBuffer.isEmpty() || !blocks.isEmpty();
            }
            String messageId = null;
            if (StrUtil.isNotBlank(content) || tracked) {
                messageId = persistAssistantMessage(content, AgentMessageStatus.INTERRUPTED);
            }
            sender.sendEvent(AgentSSEEventType.CANCEL.value(),
                    new AgentCompletionPayload(messageId, title, AgentMessageStatus.INTERRUPTED.name()));
            sender.sendEvent(AgentSSEEventType.DONE.value(), "[DONE]");
        });
    }

    private void onResponseDelta(String delta) {
        if (StrUtil.isEmpty(delta)) {
            return;
        }
        synchronized (stateLock) {
            responseBuffer.append(delta);
            appendTextBlock(DELTA_TYPE_RESPONSE, delta);
        }
        sender.sendEvent(AgentSSEEventType.MESSAGE.value(), new AgentMessageDelta(DELTA_TYPE_RESPONSE, delta));
    }

    private void onThinkingDelta(String delta) {
        if (StrUtil.isEmpty(delta)) {
            return;
        }
        synchronized (stateLock) {
            thinkingBuffer.append(delta);
            appendTextBlock(DELTA_TYPE_THINK, delta);
        }
        sender.sendEvent(AgentSSEEventType.MESSAGE.value(), new AgentMessageDelta(DELTA_TYPE_THINK, delta));
    }

    private void onAgentResult(Msg result) {
        synchronized (stateLock) {
            resultMsg = result;
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
                .kind("confirm")
                .at(LocalDateTime.now().format(BLOCK_TIME))
                .status("pending")
                .calls(calls)
                .build();
        synchronized (stateLock) {
            sealOpenTextBlock();
            // 待确认的工具块还是 running，改成 awaiting 表示等待用户裁决
            for (AgentConfirmCall call : calls) {
                AgentBlock opened = openToolBlocks.get(callKey(call.getToolCallId()));
                if (opened != null && "running".equals(opened.getStatus())) {
                    opened.setStatus("awaiting");
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
            streamed = responseBuffer.toString();
        }
        String messageId = persistAssistantMessage(streamed, AgentMessageStatus.AWAITING_CONFIRM);
        if (messageId == null) {
            settleUnpersistedConfirm();
            return true;
        }
        sender.sendEvent(AgentSSEEventType.CONFIRM.value(), new AgentConfirmPayload(messageId, title, pending));
        sender.sendEvent(AgentSSEEventType.DONE.value(), "[DONE]");
        return true;
    }

    /**
     * 确认消息落库失败：没有 messageId 无法结算，提示用户新建会话
     */
    private void settleUnpersistedConfirm() {
        log.error("待确认消息落库失败, 本轮改为中断收尾, conversationId: {}", conversationId);
        sender.sendEvent(AgentSSEEventType.HINT.value(),
                new AgentHintPayload(HINT_AGENT, "系统繁忙，这一步没有执行；这条会话已无法继续，请新建会话重试"));
        sender.sendEvent(AgentSSEEventType.FINISH.value(),
                new AgentCompletionPayload(null, title, AgentMessageStatus.INTERRUPTED.name()));
        sender.sendEvent(AgentSSEEventType.DONE.value(), "[DONE]");
    }

    private void onToolStart(ToolCallStartEvent event) {
        String toolName = event.getToolCallName();
        if (isInternalTool(toolName)) {
            return;
        }
        AgentBlock block = AgentBlock.builder()
                .kind("tool")
                .at(LocalDateTime.now().format(BLOCK_TIME))
                .name(toolName)
                .displayName(catalog.displayNameOf(toolName))
                .status("running")
                // 记录真实 id，空值不落
                .toolCallId(StrUtil.blankToDefault(event.getToolCallId(), null))
                .build();
        synchronized (stateLock) {
            sealOpenTextBlock();
            blocks.add(block);
            openToolBlocks.put(callKey(event.getToolCallId()), block);
        }
        sender.sendEvent(AgentSSEEventType.TOOL.value(), new AgentToolProgress(block.getToolCallId(),
                toolName, block.getDisplayName(), TOOL_STATUS_START, null, null));
    }

    private void onToolResultDelta(ToolResultTextDeltaEvent event) {
        if (isInternalTool(event.getToolCallName()) || StrUtil.isEmpty(event.getDelta())) {
            return;
        }
        synchronized (stateLock) {
            toolResultBuffers.computeIfAbsent(callKey(event.getToolCallId()), ignored -> new StringBuilder())
                    .append(event.getDelta());
        }
    }

    private void onToolEnd(ToolResultEndEvent event) {
        String toolName = event.getToolCallName();
        if (isInternalTool(toolName)) {
            return;
        }
        boolean ok = event.getState() == ToolResultState.SUCCESS;
        String toolCallId = StrUtil.blankToDefault(event.getToolCallId(), null);
        String result;
        synchronized (stateLock) {
            sealOpenTextBlock();
            String callKey = callKey(event.getToolCallId());
            StringBuilder buffer = toolResultBuffers.remove(callKey);
            result = buffer == null ? null : StrUtil.sub(buffer.toString(), 0, TOOL_RESULT_MAX_CHARS);
            AgentBlock block = openToolBlocks.remove(callKey);
            if (block == null) {
                // 确认后继续执行的工具在上一轮已开头，框架只补发结束事件，这里补建块以便落库
                block = AgentBlock.builder()
                        .kind("tool")
                        .at(LocalDateTime.now().format(BLOCK_TIME))
                        .name(toolName)
                        .displayName(catalog.displayNameOf(toolName))
                        .toolCallId(toolCallId)
                        .build();
                blocks.add(block);
            }
            block.setStatus(ok ? "done" : "failed");
            block.setResult(result);
        }
        sender.sendEvent(AgentSSEEventType.TOOL.value(), new AgentToolProgress(toolCallId,
                toolName, catalog.displayNameOf(toolName), TOOL_STATUS_END, result, ok));
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
     * 调用方需持 stateLock
     */
    private void appendTextBlock(String deltaType, String delta) {
        String kind = switch (deltaType) {
            case DELTA_TYPE_THINK -> "reasoning";
            case DELTA_TYPE_ERROR -> "error";
            default -> "answer";
        };
        if (openTextBlock == null || !kind.equals(openTextBlock.getKind())) {
            sealOpenTextBlock();
            openTextBlock = AgentBlock.builder()
                    .kind(kind)
                    .at(LocalDateTime.now().format(BLOCK_TIME))
                    .build();
            openTextBuffer = new StringBuilder();
            blocks.add(openTextBlock);
        }
        openTextBuffer.append(delta);
    }

    /**
     * 调用方需持 stateLock
     */
    private void sealOpenTextBlock() {
        if (openTextBlock == null) {
            return;
        }
        openTextBlock.setText(openTextBuffer.toString());
        openTextBlock = null;
        openTextBuffer = null;
    }

    private String fallbackContent() {
        Msg result;
        synchronized (stateLock) {
            result = resultMsg;
        }
        return result == null ? "" : StrUtil.emptyIfNull(result.getTextContent());
    }

    private String persistAssistantMessage(String content, AgentMessageStatus status) {
        String thinking;
        List<AgentBlock> settled;
        // 思考文本与块列表在同一个锁内取快照
        synchronized (stateLock) {
            thinking = thinkingBuffer.toString();
            settled = settledBlocks();
        }
        try {
            return conversationService.addAssistantMessage(conversationId, userId, content,
                    thinking, settled, replyToMessageId, status);
        } catch (Exception e) {
            log.error("Agent 终答落库失败, conversationId: {}", conversationId, e);
            return null;
        }
    }

    /**
     * 调用方需持 stateLock：封口文本块、running 改 interrupted、剔除空文本块
     */
    private List<AgentBlock> settledBlocks() {
        sealOpenTextBlock();
        List<AgentBlock> settled = new ArrayList<>(blocks.size());
        for (AgentBlock block : blocks) {
            boolean textual = !"tool".equals(block.getKind()) && !"confirm".equals(block.getKind());
            if (textual && StrUtil.isBlank(block.getText())) {
                continue;
            }
            if ("running".equals(block.getStatus())) {
                block.setStatus("interrupted");
            }
            settled.add(block);
        }
        return settled.isEmpty() ? null : settled;
    }

    @Getter
    @Builder
    public static class Params {

        private final AgentRunHandle runHandle;

        private final AgentConversationService conversationService;

        private final ResolvedCatalog catalog;

        private final String conversationId;

        private final String userId;

        private final String title;

        private final String replyToMessageId;
    }
}
