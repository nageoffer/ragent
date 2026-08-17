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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.ANSWER_CITATION_RULES_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;

/**
 * RAG Prompt 编排服务
 * <p>
 * 根据检索结果场景（KB / MCP / Mixed）选择模板，并构造最终发送给 LLM 的消息序列
 */
@Service
@RequiredArgsConstructor
public class RAGPromptService {

    private final PromptTemplateLoader templateLoader;
    private final AgentPromptResolver agentPromptResolver;
    private final RAGConfigProperties ragConfigProperties;

    /**
     * 生成系统提示词，并对模板格式做清理
     */
    public String buildSystemPrompt(PromptContext context) {
        return buildSystemPrompt(context, true);
    }

    /**
     * citationEligible=false 时无条件跳过引用规则拼接（Agent 模式检索门面无来源编号，不读引用开关）
     */
    private String buildSystemPrompt(PromptContext context, boolean citationEligible) {
        PromptBuildPlan plan = plan(context);
        String template = StrUtil.isNotBlank(plan.getBaseTemplate())
                ? plan.getBaseTemplate()
                : defaultTemplate(plan.getScene());
        String systemPrompt = StrUtil.isBlank(template) ? "" : PromptTemplateUtils.cleanupPrompt(template);
        if (!citationEligible || !context.hasKb() || !Boolean.TRUE.equals(ragConfigProperties.getCitationEnabled())) {
            return systemPrompt;
        }

        String citationRules = PromptTemplateUtils.cleanupPrompt(
                templateLoader.load(ANSWER_CITATION_RULES_PROMPT_PATH));
        if (StrUtil.isBlank(systemPrompt)) {
            return citationRules;
        }
        if (StrUtil.isBlank(citationRules)) {
            return systemPrompt;
        }
        return systemPrompt + "\n\n" + citationRules;
    }

    /**
     * 构造发送给 LLM 的完整消息列表（system + evidence + history + user）
     */
    public List<ChatMessage> buildStructuredMessages(PromptContext context,
                                                     List<ChatMessage> history,
                                                     String question,
                                                     List<String> subQuestions) {
        return buildStructuredMessages(context, history, question, subQuestions, true);
    }

    /**
     * citationEligible 透传给系统提示词分支，false 表示调用方不具备角标渲染能力
     */
    public List<ChatMessage> buildStructuredMessages(PromptContext context,
                                                     List<ChatMessage> history,
                                                     String question,
                                                     List<String> subQuestions,
                                                     boolean citationEligible) {
        List<ChatMessage> messages = new ArrayList<>();

        // 1. 系统提示词
        String systemPrompt = buildSystemPrompt(context, citationEligible);
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(ChatMessage.system(systemPrompt));
        }

        // 2. 对话历史（含摘要，摘要作为 history[0] 的 system message 自然紧跟系统提示词）
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history);
        }

        // 3. 证据 + 问题（合并为一条 user message）
        String evidenceBody = buildEvidenceBody(context);
        String userQuestion = buildUserQuestion(question, subQuestions);
        String userContent = mergeEvidenceAndQuestion(evidenceBody, userQuestion);
        if (StrUtil.isNotBlank(userContent)) {
            messages.add(ChatMessage.user(userContent));
        }

        return messages;
    }

    private PromptPlan planPrompt(List<NodeScore> intents, Set<String> eligibleIntentIds) {
        List<NodeScore> safeIntents = intents == null ? Collections.emptyList() : intents;
        Map<String, NodeScore> eligibleById = new LinkedHashMap<>();
        for (NodeScore intent : safeIntents) {
            if (intent == null || intent.getNode() == null) {
                continue;
            }
            String intentId = intent.getNode().getId();
            if (!eligibleIntentIds.contains(intentId)) {
                continue;
            }
            eligibleById.putIfAbsent(intentId, intent);
        }
        List<NodeScore> eligibleIntents = new ArrayList<>(eligibleById.values());

        if (eligibleIntents.isEmpty()) {
            return new PromptPlan(Collections.emptyList(), null);
        }

        if (eligibleIntents.size() == 1) {
            IntentNode only = eligibleIntents.get(0).getNode();
            String tpl = StrUtil.emptyIfNull(only.getPromptTemplate()).trim();
            if (StrUtil.isNotBlank(tpl)) {
                return new PromptPlan(eligibleIntents, tpl);
            }
        }
        return new PromptPlan(eligibleIntents, null);
    }

    private PromptBuildPlan plan(PromptContext context) {
        if (context.hasMcp() && !context.hasKb()) {
            return planMcpOnly(context);
        }
        if (!context.hasMcp() && context.hasKb()) {
            return planKbOnly(context);
        }
        if (context.hasMcp() && context.hasKb()) {
            return planMixed(context);
        }
        throw new IllegalStateException("PromptContext requires MCP or KB context.");
    }

    private PromptBuildPlan planKbOnly(PromptContext context) {
        PromptPlan plan = planPrompt(context.getKbIntents(), context.getEligibleIntentIds());
        return PromptBuildPlan.builder()
                .scene(PromptScene.KB_ONLY)
                .baseTemplate(plan.getBaseTemplate())
                .mcpContext(context.getMcpContext())
                .kbContext(context.getKbContext())
                .question(context.getQuestion())
                .build();
    }

    private PromptBuildPlan planMcpOnly(PromptContext context) {
        List<NodeScore> intents = context.getMcpIntents();
        String baseTemplate = null;
        if (CollUtil.isNotEmpty(intents) && intents.size() == 1) {
            IntentNode node = intents.get(0).getNode();
            String tpl = StrUtil.emptyIfNull(node.getPromptTemplate()).trim();
            if (StrUtil.isNotBlank(tpl)) {
                baseTemplate = tpl;
            }
        }

        return PromptBuildPlan.builder()
                .scene(PromptScene.MCP_ONLY)
                .baseTemplate(baseTemplate)
                .mcpContext(context.getMcpContext())
                .kbContext(context.getKbContext())
                .question(context.getQuestion())
                .build();
    }

    private PromptBuildPlan planMixed(PromptContext context) {
        return PromptBuildPlan.builder()
                .scene(PromptScene.MIXED)
                .mcpContext(context.getMcpContext())
                .kbContext(context.getKbContext())
                .question(context.getQuestion())
                .build();
    }

    private String defaultTemplate(PromptScene scene) {
        return switch (scene) {
            case KB_ONLY -> agentPromptResolver.resolve(AgentPromptSlot.KB_ANSWER);
            case MCP_ONLY -> agentPromptResolver.resolve(AgentPromptSlot.MCP_ANSWER);
            case MIXED -> agentPromptResolver.resolve(AgentPromptSlot.MIXED_ANSWER);
            case EMPTY -> "";
        };
    }

    private String buildUserQuestion(String question, List<String> subQuestions) {
        if (CollUtil.isNotEmpty(subQuestions) && subQuestions.size() > 1) {
            String numbered = IntStream.range(0, subQuestions.size())
                    .mapToObj(i -> (i + 1) + ". " + subQuestions.get(i))
                    .collect(Collectors.joining("\n"));
            return renderSection("multi-questions", Map.of("questions", numbered));
        }
        if (StrUtil.isBlank(question)) {
            return "";
        }
        return renderSection("single-question", Map.of("question", question));
    }

    private String mergeEvidenceAndQuestion(String evidenceBody, String question) {
        if (StrUtil.isBlank(evidenceBody)) {
            return question;
        }
        if (StrUtil.isBlank(question)) {
            return evidenceBody;
        }
        return evidenceBody + "\n\n" + question;
    }

    /**
     * 将 MCP 和 KB 证据合并为一个文本块，各自有值时用对应 section 渲染
     */
    private String buildEvidenceBody(PromptContext context) {
        StringBuilder sb = new StringBuilder();
        if (StrUtil.isNotBlank(context.getMcpContext())) {
            sb.append(renderSection("mcp-evidence", Map.of("body", context.getMcpContext().trim())));
        }
        if (StrUtil.isNotBlank(context.getKbContext())) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(renderSection("kb-evidence", Map.of("body", context.getKbContext().trim())));
        }
        return sb.toString().trim();
    }

    private String renderSection(String section, Map<String, String> slots) {
        return templateLoader.renderSection(CONTEXT_FORMAT_PATH, section, slots);
    }
}
