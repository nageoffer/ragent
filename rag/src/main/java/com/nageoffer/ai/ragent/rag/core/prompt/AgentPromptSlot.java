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

package com.nageoffer.ai.ragent.rag.core.prompt;

import com.nageoffer.ai.ragent.rag.config.OrchestrationMode;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 智能体提示词槽位，是槽位元数据的唯一权威源
 * <p>
 * 槽位按功能命名而非按架构命名：生效范围会随 v1/v2 演进变化，不编码进标识符
 */
@Getter
public enum AgentPromptSlot {

    SYSTEM_CHAT("闲聊 / 关于助手", Group.WORKFLOW,
            Set.of(OrchestrationMode.WORKFLOW),
            "Agent 模式下由主 Agent 直接应答",
            Set.of()),

    MCP_ANSWER("MCP 问答", Group.WORKFLOW,
            Set.of(OrchestrationMode.WORKFLOW),
            "Agent 模式下改用原生工具调用，无独立的数据合成环节",
            Set.of()),

    MIXED_ANSWER("混合问答", Group.WORKFLOW,
            Set.of(OrchestrationMode.WORKFLOW),
            "Agent 模式下由主 Agent 综合多个工具的结果",
            Set.of()),

    AGENT_MAIN("Agent 人设", Group.AGENT,
            Set.of(OrchestrationMode.AGENT),
            "WorkFlow 模式不经过 ReAct 架构",
            Set.of()),

    /**
     * 两种架构共用：WorkFlow 下由主链路合成，Agent 下由 RAG Tool 内部合成
     */
    KB_ANSWER("知识库问答", Group.COMMON,
            Set.of(OrchestrationMode.WORKFLOW, OrchestrationMode.AGENT),
            null,
            Set.of()),

    CONVERSATION_SUMMARY("会话压缩", Group.COMMON,
            Set.of(OrchestrationMode.WORKFLOW, OrchestrationMode.AGENT),
            null,
            Set.of("{summary_max_chars}")),

    RECOMMENDED_QUESTIONS("推荐问题", Group.COMMON,
            Set.of(OrchestrationMode.WORKFLOW, OrchestrationMode.AGENT),
            null,
            Set.of("{chunks}", "{count}", "{question}", "{answer}"));

    private final String displayName;

    private final Group group;

    private final Set<OrchestrationMode> effectiveModes;

    /**
     * 未生效时展示给管理员的原因，两种架构都生效的槽位为 null
     */
    private final String inactiveReason;

    /**
     * 必须出现的占位符，缺失会让下游规则静默失效，故在保存时拒绝
     */
    private final Set<String> requiredPlaceholders;

    AgentPromptSlot(String displayName, Group group, Set<OrchestrationMode> effectiveModes,
                    String inactiveReason, Set<String> requiredPlaceholders) {
        this.displayName = displayName;
        this.group = group;
        this.effectiveModes = effectiveModes;
        this.inactiveReason = inactiveReason;
        this.requiredPlaceholders = requiredPlaceholders;
    }

    public boolean isEffectiveIn(OrchestrationMode mode) {
        return effectiveModes.contains(mode);
    }

    /**
     * 当前架构下真正会被读取的槽位，控制台拿它当覆盖率的分母
     */
    public static List<AgentPromptSlot> effectiveIn(OrchestrationMode mode) {
        return Arrays.stream(values())
                .filter(slot -> slot.isEffectiveIn(mode))
                .toList();
    }

    public static Optional<AgentPromptSlot> find(String key) {
        return Arrays.stream(values())
                .filter(slot -> slot.name().equalsIgnoreCase(key))
                .findFirst();
    }

    /**
     * 控制台分栏，按生效范围而非历史归属划分
     */
    @Getter
    public enum Group {

        WORKFLOW("WorkFlow 专属"),
        AGENT("Agent 专属"),
        COMMON("通用");

        private final String displayName;

        Group(String displayName) {
            this.displayName = displayName;
        }
    }
}
