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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome.Status;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryFlusher;
import com.nageoffer.ai.ragent.agent.memory.AgentUserMemoryMiddleware;
import com.nageoffer.ai.ragent.agent.trace.AgentToolBodyTracer;
import com.nageoffer.ai.ragent.agent.trace.AgentTraceContextKeys;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * 记忆整理工具：模型只有触发权没有内容写入权，记什么忘什么由服务端仲裁
 * 身份取自 RuntimeContext 而非入参，模型改不了自己是谁
 */
@Slf4j
@RequiredArgsConstructor
public class MemoryFlushTool implements AgentTool {

    public static final String TOOL_NAME = "flush_memory";
    public static final String DISPLAY_NAME = "记忆整理";

    private final String description;
    private final AgentMemoryFlusher memoryFlusher;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return description;
    }

    /**
     * 无参：当前选择只给模型触发权，记什么由服务端读取对话原文后交给 Judge 仲裁，不让模型把自己的转述或猜测直接作为待写内容
     */
    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return AgentToolBodyTracer.trace(this, param, () -> Mono.fromCallable(() -> execute(param))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    private ToolResultBlock execute(ToolCallParam param) {
        ToolUseBlock toolUse = param == null ? null : param.getToolUseBlock();
        RuntimeContext context = param == null ? null : param.getRuntimeContext();
        Object requestId = context == null ? null : context.get(AgentTraceContextKeys.REPLY_TO_MESSAGE_ID);
        if (toolUse == null || StrUtil.isBlank(toolUse.getId()) || context == null
                || StrUtil.isBlank(context.getUserId()) || StrUtil.isBlank(context.getSessionId())
                || !(requestId instanceof String requestMessageId) || StrUtil.isBlank(requestMessageId)) {
            throw new IllegalArgumentException("记忆整理未执行：当前会话的调用信息不完整，本次调用未写入或修改长期记忆。"
                    + "请如实告知用户本次未能保存记忆，不要声称已记住，也不要在本轮重复调用此工具。");
        }
        String toolCallId = toolUse.getId();
        String userId = context.getUserId();
        String conversationId = context.getSessionId();

        try {
            AgentMemoryOutcome outcome = memoryFlusher.flush(userId, requestMessageId);
            refreshSnapshot(context, userId);

            log.info("记忆整理完成, userId={}, conversationId={}, status={}, 内容变更={}",
                    userId, conversationId, outcome.status(), outcome.changes().size());

            return render(toolCallId, outcome);
        } catch (Exception e) {
            log.error("记忆整理工具执行异常, userId={}, conversationId={}",
                    userId, conversationId, e);

            return buildResult(toolCallId,
                    "记忆整理执行异常，无法确认本次内容是否已写入长期记忆。"
                            + "请勿声称已经保存，也不要在本轮重复调用此工具。",
                    true);
        }
    }

    /**
     * 刷新与提交分开：刷新失败只让本轮后续推理看到旧视图，不改变整理结局
     */
    private void refreshSnapshot(RuntimeContext runtimeContext, String userId) {
        try {
            runtimeContext.put(AgentUserMemoryMiddleware.SNAPSHOT_KEY, memoryFlusher.reloadSnapshot(userId));
        } catch (Exception e) {
            log.warn("长期记忆快照刷新失败, 当前会话记忆视图未更新, userId={}", userId, e);
        }
    }

    /**
     * 只有 COMMITTED 返回 SUCCESS；不设置 default，确保新增枚举值时编译器提示未覆盖
     * 只报已提交的变更与判定说明，怎么据此回答用户写在工具声明里，不在每次结果里重复
     */
    private ToolResultBlock render(String toolCallId, AgentMemoryOutcome outcome) {
        Status status = outcome.status();
        String text = switch (status) {
            case COMMITTED -> (outcome.changes().isEmpty()
                    ? "整理完成，这次没有产生记忆变更。"
                    : "整理完成，本次实际发生的记忆变更如下：\n- " + String.join("\n- ", outcome.changes()))
                    + explanationOf(outcome);
            case NOTHING_PENDING, BELOW_THRESHOLD -> throw new IllegalStateException(
                    "记忆整理工具收到只属于后台学习的结局: " + status);
            case BUSY -> "另一次记忆整理正在进行，这次调用没有执行新的记忆变更";
            case CAPACITY_EXCEEDED -> "这次要记下的内容超出了长期记忆的容量上限，这次没有写入任何变更";
            case DISABLED -> "长期记忆功能当前未开启，本次内容不会被记住";
            case FAILED -> "记忆整理没有完成（读取、判断或校验未通过），这次没有写入任何变更";
            case UNKNOWN -> "记忆整理的提交结果无法确认，可能已写入也可能没有";
        };
        return buildResult(toolCallId, text, status != Status.COMMITTED);
    }

    private static String explanationOf(AgentMemoryOutcome outcome) {
        return outcome.explanation().isBlank() ? "" : "\n判定说明：" + outcome.explanation();
    }

    private ToolResultBlock buildResult(String toolCallId, String text, boolean isError) {
        return ToolResultBlock.builder()
                .id(toolCallId)
                .name(TOOL_NAME)
                .output(TextBlock.builder().text(text).build())
                .state(isError ? ToolResultState.ERROR : ToolResultState.SUCCESS)
                .build();
    }
}
