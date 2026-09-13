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
 * <p>
 * 两层按水位分工：50% 裁工具结果，80% 压缩摘要；实例被单例 Agent 共享，不持有 per-call 字段
 * 裁剪层：把过老的工具结果换成等长占位说明（带原入参），不碰 IO、不改列表长度、只替换 tool_result 不动 tool_use，
 * 因此不会产生孤儿结果块。按工具循环切分，保护本轮和最近 N 个循环。
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
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext runtimeContext, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> dispatch(agent, runtimeContext, input, next));//调度，派发
    }

    private Flux<AgentEvent> dispatch(Agent agent, RuntimeContext runtimeContext, ReasoningInput input,
                                      Function<ReasoningInput, Flux<AgentEvent>> next) {
        List<Msg> context;
        try {
            //读取AgentState（智能体会话状态）
            AgentState state = RuntimeContext.resolveAgentState(runtimeContext, agent);
            context = state == null ? null : state.contextMutable();//获取可变上下文消息列表
        } catch (Exception e) {
            log.warn("会话状态取不到, 本轮按原列表推理, sessionId: {}", sessionId(runtimeContext), e);
            return next.apply(input);//降级
        }
        if (context == null) {
            return next.apply(input);
        }
        if (!memoryProperties.isSummaryEnabled() || !shouldCompact(context)) {
            return next.apply(trim(context, input, runtimeContext));
        }
        // 先验上行列表与 context 的引用关系，再动手压缩
        List<Msg> prefix = resolvePrefix(input.messages(), context);
        if (prefix == null) {
            log.warn("上行列表与上下文对不上, 本轮不压缩, 上行: {}, 上下文: {}", input.messages().size(), context.size());
            return next.apply(trim(context, input, runtimeContext));
        }
        return compact(context, input, prefix, runtimeContext).flatMapMany(next::apply);
    }

    /**
     * 末条是用户消息才压缩，保证工具循环已闭合
     */
    private boolean shouldCompact(List<Msg> context) {
        if (context.isEmpty() || context.get(context.size() - 1).getRole() != MsgRole.USER) {
            return false;
        }
        return AgentContextChars.total(context) > memoryProperties.resolveCompactTriggerChars();
    }

    /**
     * 压缩含同步模型调用，切到 boundedElastic 避免占推理线程
     */
    private Mono<ReasoningInput> compact(List<Msg> context, ReasoningInput input, List<Msg> prefix,
                                         RuntimeContext runtimeContext) {
        return Mono.fromCallable(() -> compactor.compactInPlace(context, userId(runtimeContext), sessionId(runtimeContext))
                        ? rebuild(input, context, prefix)
                        : trim(context, input, runtimeContext))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("上下文压缩异常, 本轮退回工具结果裁剪, sessionId: {}", sessionId(runtimeContext), e);
                    return Mono.fromCallable(() -> trim(context, input, runtimeContext));
                });
    }

    /**
     * 裁剪失败走原列表
     */
    private ReasoningInput trim(List<Msg> context, ReasoningInput input, RuntimeContext runtimeContext) {
        try {
            TrimResult result = trimmer.trimInPlace(context);
            if (!result.changed()) {
                return input;
            }
            List<Msg> messages = input.messages().stream()
                    .map(msg -> result.replacements().getOrDefault(msg, msg))
                    .toList();
            return new ReasoningInput(messages, input.tools(), input.options());
        } catch (Exception e) {
            log.warn("上下文裁剪异常, 本轮按原列表推理, sessionId: {}", sessionId(runtimeContext), e);
            return input;
        }
    }

    /**
     * 按引用逐条比对，取出上行列表头部的框架前缀；失配返回 null
     */
    private List<Msg> resolvePrefix(List<Msg> messages, List<Msg> context) {
        int offset = messages.size() - context.size();
        if (offset < 0) {
            return null;
        }
        for (int i = 0; i < context.size(); i++) {
            if (messages.get(offset + i) != context.get(i)) {
                return null;
            }
        }
        return List.copyOf(messages.subList(0, offset));
    }

    /**
     * 压缩改了消息条数，需整段重建上行列表
     */
    private ReasoningInput rebuild(ReasoningInput input, List<Msg> context, List<Msg> prefix) {
        List<Msg> rebuilt = new ArrayList<>(prefix.size() + context.size());
        rebuilt.addAll(prefix);
        rebuilt.addAll(context);
        return new ReasoningInput(rebuilt, input.tools(), input.options());
    }

    private String sessionId(RuntimeContext runtimeContext) {
        return runtimeContext == null ? null : runtimeContext.getSessionId();
    }

    private String userId(RuntimeContext runtimeContext) {
        return runtimeContext == null ? null : runtimeContext.getUserId();
    }
}
