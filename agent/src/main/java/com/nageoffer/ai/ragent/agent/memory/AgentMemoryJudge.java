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
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
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
 * 记忆仲裁：抽取与取舍合成一次模型调用，解析失败整批抛出重来
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentMemoryJudge {

    /**
     * 单条用户消息进素材的长度上限，长文里可晋升的事实一般落在开头
     */
    private static final int MATERIAL_ITEM_CHARS = 1200;

    private static final String TRUNCATED_SUFFIX = "…（后续 %d 字符省略）";

    private static final String FENCE_TURNS = "recent_turns";
    private static final String FENCE_MEMORIES = "existing_memories";

    private static final String NO_MEMORIES = "（该用户目前没有已沉淀的记忆条目）";

    /**
     * 一批素材可能跨会话，换会话处插这一行；AGENT_MEMORY_EXTRACTION 提示词里原样引用了它，改动要两处一起改
     */
    private static final String CONVERSATION_BREAK = "——（以下换到另一段对话）——";

    private static final String FIELD_ACTION = "action";
    private static final String FIELD_ID = "id";
    private static final String FIELD_CONTENT = "content";

    private final LLMService llmService;
    private final AgentPromptResolver agentPromptResolver;
    private final AgentMemoryProperties memoryProperties;

    /**
     * 判不出东西返回空列表，判失败一律抛出交由调用方结算
     */
    public List<AgentMemoryDecision> judge(List<AgentMemoryItem> existing, List<AgentMessageDO> pending) {
        int maxChars = memoryProperties.resolveMemoryMaxChars();
        String prompt = agentPromptResolver.render(AgentPromptSlot.AGENT_MEMORY_EXTRACTION, Map.of(
                "existing_memories", fence(FENCE_MEMORIES, renderMemories(existing)),
                "recent_turns", fence(FENCE_TURNS, renderTurns(pending)),
                "memory_max_chars", String.valueOf(maxChars)
        ));
        if (StrUtil.isBlank(prompt)) {
            throw new IllegalStateException("长期记忆抽取提示词为空");
        }

        // 素材以数据身份放在用户消息里，「围栏内是数据」的声明由提示词给
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .temperature(0.2D)
                .topP(0.9D)
                .thinking(false)
                .maxTokens(maxChars)
                .build();
        // 不走 FAST 档：5s 是 OkHttp 整段调用预算，素材上千字符必然超时
        String raw = llmService.chat(request, Tier.STANDARD);
        List<AgentMemoryDecision> decisions = parse(raw);
        log.info("长期记忆仲裁完成, 素材消息: {}, 已有条目: {}, 决策: {}",
                pending.size(), existing.size(), decisions.size());
        return decisions;
    }

    /**
     * 数组元素认不全就整批抛出：漏掉半份决策比重跑一次贵得多
     */
    private List<AgentMemoryDecision> parse(String raw) {
        if (StrUtil.isBlank(raw)) {
            throw new IllegalStateException("长期记忆仲裁返回为空");
        }
        String stripped = LLMResponseCleaner.stripMarkdownCodeFence(raw);
        JSONArray array;
        try {
            array = JSONUtil.parseArray(stripped);
        } catch (Exception e) {
            throw new IllegalStateException("长期记忆仲裁输出不是 JSON 数组: " + StrUtil.maxLength(stripped, 200), e);
        }
        List<AgentMemoryDecision> decisions = new ArrayList<>(array.size());
        for (Object item : array) {
            if (!(item instanceof JSONObject json)) {
                throw new IllegalStateException("长期记忆仲裁决策不是对象: " + StrUtil.maxLength(String.valueOf(item), 120));
            }
            AgentMemoryDecision decision = toDecision(json);
            if (decision != null) {
                decisions.add(decision);
            }
        }
        return AgentMemoryDecision.containsClear(decisions) ? checkClearBatch(decisions) : decisions;
    }

    /**
     * 清空批只许带清空之后要记的 ADD；混进替换或撤回说明模型没想清目标状态，整批抛出重判
     * 不能先清空再让它们指不着目标自动丢弃：「清空后只记住我住南京」判成 CLEAR + SUPERSEDE 时，南京会被静默吞掉
     */
    private List<AgentMemoryDecision> checkClearBatch(List<AgentMemoryDecision> decisions) {
        List<AgentMemoryDecision> checked = new ArrayList<>();
        checked.add(AgentMemoryDecision.clear());
        for (AgentMemoryDecision decision : decisions) {
            switch (decision.action()) {
                // 多个 CLEAR 幂等，收成一个
                case CLEAR -> {
                }
                case ADD -> checked.add(decision);
                case SUPERSEDE, RETRACT -> throw new IllegalStateException(
                        "长期记忆仲裁把 CLEAR 与 " + decision.action() + " 放在同一批");
            }
        }
        return checked;
    }

    /**
     * 协议五种动作在这里收成四种：NOOP 只表示「这批没东西可记」，返回 null 就地跳过，不进提交环节
     */
    private AgentMemoryDecision toDecision(JSONObject json) {
        String action = StrUtil.trimToEmpty(json.getStr(FIELD_ACTION)).toUpperCase();
        String targetId = StrUtil.trimToNull(json.getStr(FIELD_ID));
        String content = StrUtil.trimToNull(json.getStr(FIELD_CONTENT));
        return switch (action) {
            case "NOOP" -> null;
            case "ADD" -> AgentMemoryDecision.add(require(content, "ADD 缺少 content"));
            case "SUPERSEDE" -> AgentMemoryDecision.supersede(
                    require(targetId, "SUPERSEDE 缺少 id"), require(content, "SUPERSEDE 缺少 content"));
            case "RETRACT" -> AgentMemoryDecision.retract(require(targetId, "RETRACT 缺少 id"));
            case "CLEAR" -> AgentMemoryDecision.clear();
            default -> throw new IllegalStateException("长期记忆仲裁给出未知动作: " + StrUtil.maxLength(action, 40));
        };
    }

    private String require(String value, String message) {
        if (value == null) {
            throw new IllegalStateException("长期记忆仲裁决策不完整, " + message);
        }
        return value;
    }

    /**
     * 条目带 id 才有得指认，SUPERSEDE 与 RETRACT 全靠它
     */
    private String renderMemories(List<AgentMemoryItem> existing) {
        if (existing == null || existing.isEmpty()) {
            return NO_MEMORIES;
        }
        StringBuilder text = new StringBuilder();
        for (AgentMemoryItem item : existing) {
            text.append("id=").append(item.id()).append(" | ").append(item.content()).append('\n');
        }
        return text.toString().stripTrailing();
    }

    /**
     * 素材只取用户消息，助手与工具结果不进来
     * 换会话处插分隔行：模型看不到助手回答，不隔开会把这段对话里的「它」指到另一段对话说过的东西上
     */
    private String renderTurns(List<AgentMessageDO> pending) {
        StringBuilder text = new StringBuilder();
        String previousConversationId = null;
        for (AgentMessageDO message : pending) {
            String content = StrUtil.trimToEmpty(message.getContent());
            if (content.isEmpty()) {
                continue;
            }
            if (previousConversationId != null && !previousConversationId.equals(message.getConversationId())) {
                text.append(CONVERSATION_BREAK).append('\n');
            }
            previousConversationId = message.getConversationId();
            text.append("- ").append(truncate(content)).append('\n');
        }
        return text.toString().stripTrailing();
    }

    private String truncate(String value) {
        if (value.length() <= MATERIAL_ITEM_CHARS) {
            return value;
        }
        return value.substring(0, MATERIAL_ITEM_CHARS)
                + String.format(TRUNCATED_SUFFIX, value.length() - MATERIAL_ITEM_CHARS);
    }

    private String fence(String name, String body) {
        return "<" + name + ">\n" + body + "\n</" + name + ">";
    }
}
