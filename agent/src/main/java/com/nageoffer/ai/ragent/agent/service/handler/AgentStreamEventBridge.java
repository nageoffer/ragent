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
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.dto.AgentCompletionPayload;
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
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentScope 事件流到 SSE 协议的桥：增量转发、轨迹落库与取消收尾
 * 事件线程与取消线程交叠，可变状态在 stateLock 下变更，SSE 发送在锁外
 */
@Slf4j
public class AgentStreamEventBridge {

    private static final String DELTA_TYPE_RESPONSE = "response";
    private static final String DELTA_TYPE_THINK = "think";
    private static final String TOOL_STATUS_START = "start";
    private static final String TOOL_STATUS_END = "end";
    private static final String HINT_AGENT = "AGENT_HINT";
    private static final String HINT_MAX_ITERATIONS = "MAX_ITERATIONS";
    /**
     * 防跑飞护栏，正常不会触发
     */
    private static final int TOOL_RESULT_MAX_CHARS = 64_000;
    private static final String FALLBACK_CALL_KEY = "__anonymous__";    
    /**
     * 跨天回放需全量时刻，不带时区偏移沿用前端约定
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

    private final Object stateLock = new Object();  // 状态锁

    private final StringBuilder responseBuffer = new StringBuilder();
    private final StringBuilder thinkingBuffer = new StringBuilder();
    private Msg resultMsg;

    private final List<AgentBlock> blocks = new ArrayList<>();
    private final Map<String, AgentBlock> openToolBlocks = new HashMap<>();
    private final Map<String, StringBuilder> toolResultBuffers = new HashMap<>();

    /**
     * 当前文本块及缓冲，工具事件到来即封口
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

    public void onEvent(AgentEvent event) { // 事件线程按 event.getType() 分发即可针对每类事件做不同处理：
        switch (event.getType()) {
            case TEXT_BLOCK_DELTA -> onResponseDelta(((TextBlockDeltaEvent) event).getDelta());             //回答增量
            case THINKING_BLOCK_DELTA -> onThinkingDelta(((ThinkingBlockDeltaEvent) event).getDelta());     //思考增量
            case TOOL_CALL_START -> onToolStart((ToolCallStartEvent) event);                                //工具调用开始
            case TOOL_RESULT_TEXT_DELTA -> onToolResultDelta((ToolResultTextDeltaEvent) event);         //工具调用结果增量
            case TOOL_RESULT_END -> onToolEnd((ToolResultEndEvent) event);                               //工具调用结束  
            case HINT_BLOCK -> onHint(((HintBlockEvent) event).getHint());                              //提示块
            // 达到迭代上限后框架仍会生成总结与 AgentResult，只提示不判失败 
            case EXCEED_MAX_ITERS -> sender.sendEvent(AgentSSEEventType.HINT.value(),                   //最大迭代提示
                    new AgentHintPayload(HINT_MAX_ITERATIONS, "已达到最大迭代次数，正在生成当前执行结果的总结"));
            case AGENT_RESULT -> onAgentResult(((AgentResultEvent) event).getResult());                 //AgentResult 事件在流式增量之后才发，保证落库时有完整的增量
            default -> {
            }
        }
    }

    public void onComplete() {
        runHandle.complete(() -> {
            String streamed;
            synchronized (stateLock) {          
                streamed = responseBuffer.toString();
            }   
            // 以流式增量为准，为空时回落终答消息
            String content = StrUtil.isNotBlank(streamed) ? streamed : fallbackContent();
            // 非流式兜底路径没有增量，一次性补发
            if (streamed.isEmpty() && StrUtil.isNotBlank(content)) {
                synchronized (stateLock) {
                    appendTextBlock(DELTA_TYPE_RESPONSE, content);
                }
                sender.sendEvent(AgentSSEEventType.MESSAGE.value(), new AgentMessageDelta(DELTA_TYPE_RESPONSE, content));
            }
            String messageId = persistAssistantMessage(content, AgentMessageStatus.NORMAL);
            sender.sendEvent(AgentSSEEventType.FINISH.value(),
                    new AgentCompletionPayload(messageId, title, AgentMessageStatus.NORMAL.name()));    //完成事件
            sender.sendEvent(AgentSSEEventType.DONE.value(), "[DONE]");
        });
    }

    public void onError(Throwable throwable) {
        // dispose 引发的信号中断不算错误，取消收尾由 finalizer 负责
        if (runHandle.isCancelled()) {
            return;
        }
        // 流已以 text/event-stream 提交后，错误无法经全局异常处理器以 JSON 回写（无 converter）。
        // 失败态与取消态同款协议收尾：error 事件告知前端 → [DONE] → 正常关闭连接，
        // 避免 completeWithError 触发容器 error dispatch，在已提交响应上刷 No converter 噪音。
        runHandle.fail(throwable, () -> {
            log.error("Agent 流式会话异常, taskId: {}", runHandle.getTaskId(), throwable);
            sender.sendEvent(AgentSSEEventType.ERROR.value(), Map.of("error", buildErrorMessage(throwable)));
            sender.sendEvent(AgentSSEEventType.DONE.value(), "[DONE]");
        });
    }

    /**
     * 取消收尾：持久化已生成内容后补发 cancel/done
     */
    public void finishCancelledStream() {
        runHandle.cancel(() -> {
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
                    new AgentCompletionPayload(messageId, title, AgentMessageStatus.INTERRUPTED.name()));   //取消事件
            sender.sendEvent(AgentSSEEventType.DONE.value(), "[DONE]");
        });
    }

    /**
     * 大模型Agent引擎异步回调
    ├─ onResponseDelta → 累积responseBuffer → SSE推送回答增量（前端打字机）
    ├─ onThinkingDelta → 累积thinkingBuffer → SSE推送思考增量
    ├─ onAgentResult → 保存最终完整消息(本地)
    ├─ onToolStart → 创建running工具块、闭合文本块 → SSE通知【工具开始】
    ├─ onToolResultDelta → 内存累积工具输出delta（不推前端）
    └─ onToolEnd → 取出拼接好的工具结果、截断、更新块状态 → SSE推送【工具结束+结果】

     **/


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

    private void onAgentResult(Msg result) {   //保存完整消息到内存
        synchronized (stateLock) {
            resultMsg = result;
        }
    }

    private void onToolStart(ToolCallStartEvent event) {
        String toolName = event.getToolCallName();
        if (isInternalTool(toolName)) {
            return;
        }
        // 构建 AgentBlock：工具调用块，存入会话块列表
        AgentBlock block = AgentBlock.builder()
                .kind("tool")
                .at(LocalDateTime.now().format(BLOCK_TIME))
                .name(toolName)
                .displayName(catalog.displayNameOf(toolName))
                .status("running")
                // 落真实 id 而非 callKey 的兜底值
                .toolCallId(StrUtil.blankToDefault(event.getToolCallId(), null))
                .build();
        synchronized (stateLock) {
            // 工具调用开始即封口文本块，避免工具输出混入文本块
            sealOpenTextBlock();
            blocks.add(block);
            // 保存正在运行中的工具块
            openToolBlocks.put(callKey(event.getToolCallId()), block);
        }
        sender.sendEvent(AgentSSEEventType.TOOL.value(),
                new AgentToolProgress(toolName, block.getDisplayName(), TOOL_STATUS_START, null, null));
    }

    private void onToolResultDelta(ToolResultTextDeltaEvent event) {
        if (isInternalTool(event.getToolCallName()) || StrUtil.isEmpty(event.getDelta())) {
            return;
        }
        synchronized (stateLock) {
            //// 不存在就新建StringBuilder，把工具返回delta累积起来
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
        String result;
        synchronized (stateLock) {
            sealOpenTextBlock();    
            String callKey = callKey(event.getToolCallId());    
            StringBuilder buffer = toolResultBuffers.remove(callKey);   //取出并移除该工具的缓冲区
            result = buffer == null ? null : StrUtil.sub(buffer.toString(), 0, TOOL_RESULT_MAX_CHARS);  //截断工具结果，防止过长
            // 取出正在运行的工具块，修改状态+回填结果
            AgentBlock block = openToolBlocks.remove(callKey);
            if (block != null) {
                block.setStatus(ok ? "done" : "failed");
                block.setResult(result);
            }
        }
        sender.sendEvent(AgentSSEEventType.TOOL.value(),
                new AgentToolProgress(toolName, catalog.displayNameOf(toolName), TOOL_STATUS_END, result, ok));
    }

    /**
     * 端点不回 toolCallId 时退化到单槽兜底
     */
    private String callKey(String toolCallId) {
        return StrUtil.blankToDefault(toolCallId, FALLBACK_CALL_KEY);
    }

    private void onHint(String hint) {
        if (StrUtil.isBlank(hint)) {
            return;
        }
        sender.sendEvent(AgentSSEEventType.HINT.value(), new AgentHintPayload(HINT_AGENT, hint));   //提示
    }

    /**
     * 框架内部工具不暴露给业务
     */
    private boolean isInternalTool(String toolName) {
        return StrUtil.isBlank(toolName) || ReActAgent.STRUCTURED_OUTPUT_TOOL_NAME.equals(toolName);
    }

    /**
     * 调用方需持 stateLock
     */
    private void appendTextBlock(String deltaType, String delta) {
        String kind = DELTA_TYPE_THINK.equals(deltaType) ? "reasoning" : "answer";
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

    /**
     * 前端可展示的失败文案：截断异常 message，为空时给通用文案
     */
    private String buildErrorMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (StrUtil.isNotBlank(message)) {
            return StrUtil.sub(message, 0, 200);
        }
        return "生成失败，请稍后重试";
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
        // 思考文本与轨迹取自同一临界区
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
     * 调用方需持 stateLock：封口文本块，running 置 interrupted，剔除空块
     */
    private List<AgentBlock> settledBlocks() {
        sealOpenTextBlock();
        List<AgentBlock> settled = new ArrayList<>(blocks.size());
        for (AgentBlock block : blocks) {
            if (!"tool".equals(block.getKind()) && StrUtil.isBlank(block.getText())) {
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
