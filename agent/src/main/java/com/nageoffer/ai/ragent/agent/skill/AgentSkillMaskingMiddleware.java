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

import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkill;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 技能工具遮蔽：手册加载前不让模型看到那些不看手册就会办错的工具，避免绕过前置步骤直接动手
 * <p>
 * 技能的 tool_ids 是「加载后才解锁的工具」，不是「正文会用到的工具」，正文按名字引用任何工具都不受影响。
 * 记 G 为所有启用技能 tool_ids 的并集、L 为上下文里已加载技能 tool_ids 的并集，本轮遮蔽 = G − L：
 * 不在任何 tool_ids 里的工具始终可见，一个工具可挂多份手册，任一加载即放行
 * <p>
 * 解锁只认上下文里的 load_skill 结果，手册被压缩带走时工具一并收回；
 * 遮蔽只管工具可见性，不替代操作确认与业务校验
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentSkillMaskingMiddleware implements MiddlewareBase {

    /**
     * 本次推理算出的遮蔽工具集合寄存在调用上下文里，工具执行前复查同一份结论
     */
    public static final String MASKED_TOOLS_ATTRIBUTE = "ragent_masked_tools";

    private final AgentSkillRegistry skillRegistry;

    /**
     * 排在最内层，看到的是压缩与改写之后的最终消息列表
     */
    @Override
    public int order() {
        return 0;
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext runtimeContext, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        Set<String> masked;
        try {
            masked = maskedTools(input.messages());
        } catch (Exception e) {
            log.warn("技能遮蔽计算异常, 本轮按全量工具推理", e);
            return next.apply(input);
        }
        if (runtimeContext != null) {
            runtimeContext.put(MASKED_TOOLS_ATTRIBUTE, masked);
        }
        if (masked.isEmpty()) {
            return next.apply(input);
        }
        List<ToolSchema> tools = input.tools().stream()
                .filter(tool -> !masked.contains(tool.getName()))
                .toList();
        return next.apply(new ReasoningInput(input.messages(), tools, input.options()));
    }

    /**
     * @return 需遮蔽的工具 ID 集合
     */
    private Set<String> maskedTools(List<Msg> messages) {
        List<AgentSkill> skills = skillRegistry.listEnabled();
        if (skills.isEmpty()) {
            return Set.of();
        }
        Set<String> loaded = loadedSkillCodes(messages);
        // 全部解锁工具减去已加载手册解锁的：一个工具挂在多份手册下时，任一手册已加载即放行
        Set<String> masked = skills.stream()
                .flatMap(skill -> skill.toolIds().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        skills.stream()
                .filter(skill -> loaded.contains(skill.skillCode()))
                .forEach(skill -> skill.toolIds().forEach(masked::remove));
        return masked;
    }

    /**
     * 从上下文里捡出已加载的技能，标记由 load_skill 加载成功时写在结果 metadata 上
     */
    private static Set<String> loadedSkillCodes(List<Msg> messages) {
        Set<String> loaded = new HashSet<>();
        for (Msg msg : messages) {
            for (ToolResultBlock result : msg.getContentBlocks(ToolResultBlock.class)) {
                if (result.getMetadata().get(SkillLoadTool.LOADED_SKILL_METADATA_KEY) instanceof String code) {
                    loaded.add(code);
                }
            }
        }
        return loaded;
    }
}
