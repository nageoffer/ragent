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
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 记忆裁决：一次模型调用给出怎么改记忆以及改动原因
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentMemoryJudge {

    private static final Duration JUDGE_TIMEOUT = Duration.ofSeconds(120);
    private static final String FINISH_REASON_STOP = "stop";

    private final Model agentChatModel;
    private final AgentProperties agentProperties;
    private final AgentPromptResolver agentPromptResolver;
    private final AgentMemoryProperties memoryProperties;
    private final ObjectMapper objectMapper;

    /**
     * 主动整理用，只判本轮请求，决策依据只能是这条请求
     *
     * @param existing   当前有效记忆
     * @param request    本轮用户请求
     * @param background 同会话里请求之前的几条消息，用来看懂「这个」「刚才那个」
     * @param later      请求之后用户又说的话，避免把已经改口或清空的内容记回去
     */
    public AgentMemoryJudgement judgeFlush(List<AgentMemoryItem> existing, AgentMessageDO request,
                                           List<AgentMessageDO> background, List<AgentMessageDO> later) {
        FlushInput input = new FlushInput(existing, toViews(background), toView(request), toViews(later));
        List<AgentMessageDO> sourceMessages = List.of(request);
        String raw = ask(input);
        return parse(raw, sourceMessages);
    }

    /**
     * 后台学习用，判断一批还没整理过的用户发言，决策依据只能是这批发言
     *
     * @param existing   当前有效记忆
     * @param messages   本批要判断的用户发言
     * @param background 理解这批发言所需的其他消息，只看不引用
     */
    public AgentMemoryJudgement judgeBackground(List<AgentMemoryItem> existing, List<AgentMessageDO> messages,
                                                List<AgentMessageDO> background) {
        LearnInput input = new LearnInput(existing, toViews(background), toViews(messages));
        String raw = ask(input);
        return parse(raw, messages);
    }

    /**
     * 问模型：规则作系统消息、材料序列化后作用户消息，返回模型最终回答的原文；失败一律抛出，由调用方按失败处理
     * 原文只取正文，思考内容是单独的块，不混进来
     */
    private String ask(Object input) {
        String prompt = agentPromptResolver.render(
                AgentPromptSlot.AGENT_MEMORY_EXTRACTION,
                Map.of("memory_max_chars", String.valueOf(memoryProperties.resolveMemoryMaxChars()))
        );
        if (StrUtil.isBlank(prompt)) {
            throw new IllegalStateException("长期记忆抽取提示词为空");
        }
        String userJson = toJson(input);
        List<Msg> messages = List.of(
                Msg.builder().name("system").role(MsgRole.SYSTEM).textContent(prompt).build(),
                Msg.builder().name("user").role(MsgRole.USER).textContent(userJson).build());

        ChatResponse response = agentChatModel.stream(messages, null, buildOptions())
                .singleOrEmpty()
                .blockOptional()
                .orElseThrow(() -> new IllegalStateException("长期记忆裁决返回为空"));
        String raw = Msg.builder().role(MsgRole.ASSISTANT).content(response.getContent()).build().getTextContent();
        // 解析之前先留原文：结束异常或解析失败时也看得到模型到底返回了什么
        log.info("长期记忆裁决原文:\n{}", prettyForLog(raw));
        if (!FINISH_REASON_STOP.equals(response.getFinishReason())) {
            throw new IllegalStateException("长期记忆裁决未正常结束, 结束原因: " + response.getFinishReason());
        }
        return raw;
    }

    /**
     * 只用于日志：能解析成 JSON 就缩进展开，解析不了（截断、夹了别的文字）就原样输出，不影响后面的业务解析
     */
    private String prettyForLog(String raw) {
        try {
            JsonNode json = objectMapper.readTree(LLMResponseCleaner.stripMarkdownCodeFence(raw));
            // 空正文读出来是 MissingNode，序列化成 null 会误导，照原样输出
            return json == null || json.isMissingNode() ? raw : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (Exception e) {
            return raw;
        }
    }

    private String toJson(Object input) {
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("长期记忆裁决材料序列化失败", e);
        }
    }

    /**
     * 与摘要相同：非流式取完整响应，超时与尝试次数显式指定，字符预算只写入提示词
     * DeepSeek 一律开思考、Kimi 思考常开，两家强度都取 low，其他供应商不发送思考参数；思考模式下 temperature 不生效
     */
    private GenerateOptions buildOptions() {
        GenerateOptions.Builder options = GenerateOptions.builder()
                .stream(false)
                .temperature(0.3D)
                .executionConfig(ExecutionConfig.builder()
                        .timeout(JUDGE_TIMEOUT)
                        .maxAttempts(agentProperties.getMaxRetries())
                        .build());
        if (ModelProvider.DEEP_SEEK.matches(agentProperties.getChat().getProvider())) {
            options.additionalBodyParam("thinking", Map.of("type", "enabled"));
            // 经实际测试，默认思考深度和 low 均能较好处理，为减少耗时做此处理，单次调用耗时平均减少约 15%
            options.reasoningEffort("low");
        } else if (ModelProvider.KIMI.matches(agentProperties.getChat().getProvider())) {
            // Kimi 思考常开，与 DeepSeek 同取 low；temperature 由 KimiFormatter 摘掉
            options.reasoningEffort("low");
        }
        return options.build();
    }

    /**
     * 输出是 {"decisions":[...],"explanation":"..."}；格式不对、缺字段、来源不对都整批抛出，不当成空决策
     */
    private AgentMemoryJudgement parse(String raw, List<AgentMessageDO> sourceMessages) {
        String stripped = LLMResponseCleaner.stripMarkdownCodeFence(raw);
        Output output;
        try {
            output = objectMapper.readValue(stripped, Output.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("长期记忆裁决输出不是约定的 JSON 对象: " + stripped, e);
        }
        String explanation = StrUtil.trimToNull(output.explanation());
        if (output.decisions() == null || explanation == null) {
            throw new IllegalStateException("长期记忆裁决输出缺少 decisions 或 explanation: " + stripped);
        }
        List<AgentMemoryDecision> decisions = new ArrayList<>(output.decisions().size());
        for (DecisionOutput decision : output.decisions()) {
            decisions.add(toDecision(require(decision, "决策为空"), sourceMessages));
        }
        return new AgentMemoryJudgement(decisions, explanation);
    }

    /**
     * 模型只选来源消息 id，表达时间取那条消息的入库时间，不信模型写的时间
     */
    private AgentMemoryDecision toDecision(DecisionOutput output, List<AgentMessageDO> sourceMessages) {
        String action = StrUtil.trimToEmpty(output.action()).toUpperCase();
        String targetId = StrUtil.trimToNull(output.id());
        String content = StrUtil.trimToNull(output.content());
        AgentMessageDO source = requireSource(output.sourceMessageId(), sourceMessages);
        return switch (action) {
            case "ADD" -> new AgentMemoryDecision(AgentMemoryDecision.Action.ADD, null,
                    require(content, "ADD 缺少 content"), source.getId(), source.getCreateTime());
            case "UPDATE" -> new AgentMemoryDecision(AgentMemoryDecision.Action.UPDATE,
                    require(targetId, "UPDATE 缺少 id"), require(content, "UPDATE 缺少 content"),
                    source.getId(), source.getCreateTime());
            case "DELETE" -> new AgentMemoryDecision(AgentMemoryDecision.Action.DELETE,
                    require(targetId, "DELETE 缺少 id"), null, source.getId(), source.getCreateTime());
            default -> throw new IllegalStateException("长期记忆裁决给出未知动作: " + action);
        };
    }

    /**
     * 来源必须是本次允许引用的用户消息之一，按真实 id 原样匹配，不猜、不纠正
     */
    private AgentMessageDO requireSource(String sourceMessageId, List<AgentMessageDO> sourceMessages) {
        for (AgentMessageDO message : sourceMessages) {
            if (message.getId().equals(sourceMessageId)) {
                return message;
            }
        }
        throw new IllegalStateException("长期记忆裁决决策的来源不是本次可引用的用户消息: " + sourceMessageId);
    }

    private static <T> T require(T value, String message) {
        if (value == null) {
            throw new IllegalStateException("长期记忆裁决决策不完整, " + message);
        }
        return value;
    }

    /**
     * 只有工具调用、没有正文的助手消息不进材料
     */
    private static List<MessageView> toViews(List<AgentMessageDO> messages) {
        return messages.stream().filter(message -> StrUtil.isNotBlank(message.getContent())).map(AgentMemoryJudge::toView).toList();
    }

    private static MessageView toView(AgentMessageDO message) {
        return new MessageView(message.getId(), message.getConversationId(), message.getRole(),
                message.getCreateTime(), message.getContent());
    }

    record Output(List<DecisionOutput> decisions, String explanation) {
    }

    record DecisionOutput(String action, String id, String content, String sourceMessageId) {
    }

    /**
     * 主动整理的材料：request 是唯一可引用的发言
     */
    record FlushInput(List<AgentMemoryItem> memories, List<MessageView> background, MessageView request,
                      List<MessageView> later) {
    }

    /**
     * 后台学习的材料：messages 里的每条都可引用
     */
    record LearnInput(List<AgentMemoryItem> memories, List<MessageView> background, List<MessageView> messages) {
    }

    /**
     * 模型需要的消息字段；time 与记忆的 expressedAt 同一格式
     */
    record MessageView(String messageId, String conversationId, String speaker,
                       @JsonFormat(pattern = AgentMemoryItem.TIME_PATTERN, timezone = AgentMemoryItem.TIME_ZONE) Date time,
                       String text) {
    }
}
