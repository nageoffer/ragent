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
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 受限合并：容量顶到上限时把重复、重叠、可抽象的条目并成一条，压到目标水位即停
 * 只产出计划不碰库，落不落、落几组由提交侧在同一事务里裁定
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentMemoryConsolidator {

    /**
     * 一组至少并两条，与 AgentMemoryRepository.MIN_MERGE_GROUP_SIZE 同值，两处要一起改
     */
    private static final int MIN_GROUP_SIZE = 2;

    private static final String FENCE_MEMORIES = "existing_memories";

    private static final String FIELD_IDS = "ids";
    private static final String FIELD_CONTENT = "content";

    /**
     * 每条条目在计划里最多被点名一次，一个 ID 连引号带逗号折下来十来个 token，往宽了取
     */
    private static final int PLAN_TOKENS_PER_ITEM = 16;

    private final LLMService llmService;
    private final AgentPromptResolver agentPromptResolver;
    private final AgentMemoryProperties memoryProperties;

    /**
     * 算不出计划一律回空表：合并是容量的缓解手段，它失败了让反压接着兜
     */
    public List<AgentMemoryMerge> plan(List<AgentMemoryItem> active) {
        if (active == null || active.size() < MIN_GROUP_SIZE) {
            return List.of();
        }
        try {
            List<AgentMemoryMerge> merges = parse(call(active));
            log.info("长期记忆受限合并给出计划, 现有条目: {}, 合并组: {}", active.size(), merges.size());
            return merges;
        } catch (Exception e) {
            log.warn("长期记忆受限合并失败, 本次不合并, 现有条目: {}", active.size(), e);
            return List.of();
        }
    }

    private String call(List<AgentMemoryItem> active) {
        String prompt = agentPromptResolver.render(AgentPromptSlot.AGENT_MEMORY_CONSOLIDATION, Map.of(
                "existing_memories", fence(renderMemories(active)),
                "target_chars", String.valueOf(memoryProperties.resolveConsolidationStopChars())
        ));
        if (StrUtil.isBlank(prompt)) {
            throw new IllegalStateException("长期记忆合并提示词为空");
        }
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .temperature(0.2D)
                .topP(0.9D)
                .thinking(false)
                .maxTokens(planMaxTokens(active.size()))
                .build();
        // 与仲裁同档：素材是全量条目，FAST 档那 5 秒是整段调用预算，必超时
        return llmService.chat(request, Tier.STANDARD);
    }

    /**
     * maxTokens 跟条目数走：拿注入块字符上限当输出上限会把数组截在半途
     */
    private int planMaxTokens(int itemCount) {
        return PLAN_TOKENS_PER_ITEM * itemCount + memoryProperties.resolveConsolidationStopChars();
    }

    /**
     * 认不全就整批抛出：半份合并计划比不合并危险得多，它会把信息留在拆不回来的状态
     */
    private List<AgentMemoryMerge> parse(String raw) {
        if (StrUtil.isBlank(raw)) {
            throw new IllegalStateException("长期记忆合并返回为空");
        }
        JSONArray array;
        String stripped = LLMResponseCleaner.stripMarkdownCodeFence(raw);
        try {
            array = JSONUtil.parseArray(stripped);
        } catch (Exception e) {
            // 输出被 maxTokens 截在半途也走这条，报文头看着像数组、其实收不了尾，别只盯着格式
            throw new IllegalStateException("长期记忆合并输出解析失败, 字符: " + stripped.length()
                    + ", 报文头: " + StrUtil.maxLength(stripped, 200), e);
        }
        List<AgentMemoryMerge> merges = new ArrayList<>(array.size());
        for (Object item : array) {
            if (!(item instanceof JSONObject json)) {
                throw new IllegalStateException("长期记忆合并组不是对象: " + StrUtil.maxLength(String.valueOf(item), 120));
            }
            merges.add(toMerge(json));
        }
        return merges;
    }

    private AgentMemoryMerge toMerge(JSONObject json) {
        JSONArray ids = json.getJSONArray(FIELD_IDS);
        String content = StrUtil.trimToNull(json.getStr(FIELD_CONTENT));
        if (ids == null || content == null) {
            throw new IllegalStateException("长期记忆合并组不完整, 缺 ids 或 content");
        }
        List<String> members = new ArrayList<>(ids.size());
        for (Object id : ids) {
            String member = StrUtil.trimToNull(String.valueOf(id));
            if (member == null) {
                throw new IllegalStateException("长期记忆合并组含空条目ID");
            }
            members.add(member);
        }
        return new AgentMemoryMerge(members, content);
    }

    /**
     * 条目带 id 才有得指认，整组替换全靠它
     */
    private String renderMemories(List<AgentMemoryItem> active) {
        StringBuilder text = new StringBuilder();
        for (AgentMemoryItem item : active) {
            text.append("id=").append(item.id()).append(" | ").append(StrUtil.trimToEmpty(item.content())).append('\n');
        }
        return text.toString().stripTrailing();
    }

    private String fence(String body) {
        return "<" + FENCE_MEMORIES + ">\n" + body + "\n</" + FENCE_MEMORIES + ">";
    }
}
