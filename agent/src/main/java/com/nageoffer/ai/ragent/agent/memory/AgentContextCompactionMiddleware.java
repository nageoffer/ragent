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

package com.nageoffer.ai.ragent.agent.memory;

import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.memory.AgentContextTrimmer.TrimResult;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 记忆接线点：推理前裁剪/压缩上下文并同步上行列表
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentContextCompactionMiddleware implements MiddlewareBase {

    private final AgentContextTrimmer trimmer;
    private final AgentContextCompactor compactor;
    private final AgentMemoryProperties memoryProperties;

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext context, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> dispatch(agent, context, input, next));
    }

    private Flux<AgentEvent> dispatch(Agent agent, RuntimeContext context, ReasoningInput input,
                                      Function<ReasoningInput, Flux<AgentEvent>> next) {
        AgentState state = RuntimeContext.resolveAgentState(context, agent);
        List<Msg> messages = state.contextMutable();
        if (shouldCompact(messages)) {
            return compact(messages, input, context).flatMapMany(next);
        }
        return next.apply(trimToolResults(messages, input, context));
    }

    /**
     * 摘要启用且末条为用户消息、超过压缩水位时才尝试压缩
     */
    private boolean shouldCompact(List<Msg> messages) {
        if (!memoryProperties.isSummaryEnabled()
                || messages.isEmpty() || messages.get(messages.size() - 1).getRole() != MsgRole.USER) {
            return false;
        }
        return AgentContextChars.total(messages) > memoryProperties.resolveCompactTriggerChars();
    }

    /**
     * 校验消息引用后压缩，同步模型调用切到 boundedElastic，失败退回裁剪
     */
    private Mono<ReasoningInput> compact(List<Msg> messages, ReasoningInput input, RuntimeContext context) {
        List<Msg> prefix = resolvePrefix(input.messages(), messages);
        if (prefix == null) {
            log.warn("上行列表与上下文对不上, 本轮不压缩, 上行: {}, 上下文: {}", input.messages().size(), messages.size());
            return Mono.just(trimToolResults(messages, input, context));
        }
        return Mono.fromCallable(() -> compactor.compactInPlace(messages, context.getUserId(), context.getSessionId())
                        ? rebuild(input, messages, prefix)
                        : trimToolResults(messages, input, context))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("上下文压缩异常, 本轮退回工具结果裁剪, sessionId: {}", context.getSessionId(), e);
                    return Mono.fromCallable(() -> trimToolResults(messages, input, context));
                });
    }

    /**
     * 按裁剪水位清理旧工具结果并同步推理输入，异常时返回原输入
     */
    private ReasoningInput trimToolResults(List<Msg> messages, ReasoningInput input, RuntimeContext context) {
        try {
            TrimResult result = trimmer.trimInPlace(messages);
            if (!result.changed()) {
                return input;
            }
            List<Msg> trimmedMessages = input.messages().stream()
                    .map(msg -> result.replacements().getOrDefault(msg, msg))
                    .toList();
            return new ReasoningInput(trimmedMessages, input.tools(), input.options());
        } catch (Exception e) {
            log.warn("上下文裁剪异常, 本轮按原列表推理, sessionId: {}", context.getSessionId(), e);
            return input;
        }
    }

    /**
     * 按引用逐条比对，取出上行列表头部的框架前缀；失配返回 null
     */
    private List<Msg> resolvePrefix(List<Msg> inputMessages, List<Msg> messages) {
        int offset = inputMessages.size() - messages.size();
        if (offset < 0) {
            return null;
        }
        for (int i = 0; i < messages.size(); i++) {
            if (inputMessages.get(offset + i) != messages.get(i)) {
                return null;
            }
        }
        return List.copyOf(inputMessages.subList(0, offset));
    }

    /**
     * 压缩改了消息条数，需整段重建上行列表
     */
    private ReasoningInput rebuild(ReasoningInput input, List<Msg> messages, List<Msg> prefix) {
        List<Msg> rebuilt = new ArrayList<>(prefix.size() + messages.size());
        rebuilt.addAll(prefix);
        rebuilt.addAll(messages);
        return new ReasoningInput(rebuilt, input.tools(), input.options());
    }
}
