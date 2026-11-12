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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 长期记忆注入点：把用户跨会话沉淀的事实挂进上行副本，只读不写
 * <p>
 * 快照存 RuntimeContext 而非中间件字段，实例被单例 Agent 共享
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentUserMemoryMiddleware implements MiddlewareBase {

    /**
     * 调用级快照的存放键，flush 工具写入成功后就地覆盖这一格
     */
    public static final String SNAPSHOT_KEY = "__user_memory_snapshot__";

    private final AgentMemoryRepository memoryRepository;

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext runtimeContext, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> dispatch(runtimeContext, input, next));
    }

    private Flux<AgentEvent> dispatch(RuntimeContext runtimeContext, ReasoningInput input,
                                      Function<ReasoningInput, Flux<AgentEvent>> next) {
        AgentMemorySnapshot cached = runtimeContext == null ? null : runtimeContext.get(SNAPSHOT_KEY);
        if (cached != null) {
            return next.apply(inject(input, cached));
        }
        // 首轮才读库，读的是阻塞 JDBC，不占推理线程
        return Mono.fromCallable(() -> load(runtimeContext))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(snapshot -> next.apply(inject(input, snapshot)));
    }

    private AgentMemorySnapshot load(RuntimeContext runtimeContext) {
        String userId = runtimeContext == null ? null : runtimeContext.getUserId();
        if (StrUtil.isBlank(userId)) {
            return AgentMemorySnapshot.empty();
        }
        AgentMemorySnapshot snapshot = memoryRepository.loadSnapshot(userId);
        runtimeContext.put(SNAPSHOT_KEY, snapshot);
        return snapshot;
    }

    /**
     * 先按 name 滤净旧块再插一块，重复挂载中间件也只会有一块
     */
    private ReasoningInput inject(ReasoningInput input, AgentMemorySnapshot snapshot) {
        List<Msg> messages = input.messages();
        boolean stale = messages.stream().anyMatch(AgentUserMemoryMiddleware::isMemoryBlock);
        if (!stale && !snapshot.hasItems()) {
            return input;
        }
        List<Msg> rebuilt = new ArrayList<>(messages.size() + 1);
        for (Msg msg : messages) {
            if (!isMemoryBlock(msg)) {
                rebuilt.add(msg);
            }
        }
        if (snapshot.hasItems()) {
            rebuilt.add(personaBoundary(rebuilt), buildMemoryMsg(AgentMemoryBlock.render(snapshot.contents())));
        }
        return new ReasoningInput(rebuilt, input.tools(), input.options());
    }

    /**
     * 插在人设之后、会话首条之前：尾插会让压缩中间件的引用比对首格失配，从此永久跳过压缩
     */
    private int personaBoundary(List<Msg> messages) {
        int index = 0;
        while (index < messages.size() && messages.get(index).getRole() == MsgRole.SYSTEM) {
            index++;
        }
        return index;
    }

    /**
     * 用 USER 不用 SYSTEM：形制与压缩摘要同源，供应商对中途 SYSTEM 的容忍度不一
     */
    private Msg buildMemoryMsg(String block) {
        return Msg.builder()
                .id("user-memory-" + UUID.randomUUID().toString().replace("-", ""))
                .name(AgentMemoryBlock.NAME)
                .role(MsgRole.USER)
                .textContent(block)
                .build();
    }

    private static boolean isMemoryBlock(Msg msg) {
        return AgentMemoryBlock.NAME.equals(msg.getName());
    }
}
