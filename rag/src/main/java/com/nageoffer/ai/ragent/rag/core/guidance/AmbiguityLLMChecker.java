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

package com.nageoffer.ai.ragent.rag.core.guidance;

import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.trace.KnowledgeSearchSpans;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.GUIDANCE_AMBIGUITY_CHECK_PROMPT_PATH;

/**
 * LLM 歧义确认器
 * 规则层只能发现候选路径重名，是否真的要用户二选一由 LLM 判断
 * 纯 RAG 是只读流程，判不出来时一律放行联合检索，不能因为模型异常反复阻断用户
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AmbiguityLLMChecker {

    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;

    /**
     * 调用 LLM 确认是否存在歧义，响应非法或调用失败时返回 false
     */
    public boolean checkAmbiguity(String question, List<NodeScore> ranked) {
        String candidatesText = buildCandidatesText(ranked);
        String prompt = promptTemplateLoader.render(
                GUIDANCE_AMBIGUITY_CHECK_PROMPT_PATH,
                Map.of(
                        "question", question,
                        "candidates", candidatesText
                )
        );

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.user(prompt)
                ))
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();

        try {
            String raw = llmService.chat(request, Tier.FAST);
            String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
            JsonElement root = JsonParser.parseString(cleaned);

            if (!root.isJsonObject()) {
                log.warn("歧义确认 LLM 返回非 JSON 对象, 降级为跳过澄清: {}", raw);
                return false;
            }

            JsonObject obj = root.getAsJsonObject();
            if (obj.has("ambiguous")) {
                boolean ambiguous = obj.get("ambiguous").getAsBoolean();
                String reason = obj.has("reason") ? obj.get("reason").getAsString() : "";
                log.info("LLM 歧义确认结果: ambiguous={}, reason={}, question={}", ambiguous, reason, question);
                return ambiguous;
            }

            log.warn("歧义确认 LLM 返回缺少 ambiguous 字段, 降级为跳过澄清: {}", raw);
            return false;
        } catch (Exception e) {
            log.warn("歧义确认 LLM 调用失败, 降级为跳过澄清, question={}", question, e);
            KnowledgeSearchSpans.markFailed(e);
            return false;
        }
    }

    private String buildCandidatesText(List<NodeScore> ranked) {
        return ranked.stream()
                .map(ns -> {
                    IntentNode node = ns.getNode();
                    String fullPath = StrUtil.blankToDefault(node.getFullPath(), StrUtil.emptyIfNull(node.getName()));
                    StringBuilder line = new StringBuilder(String.format("- 意图ID: %s, 名称: %s, 完整路径: %s",
                            node.getId(), node.getName(), fullPath));
                    if (StrUtil.isNotBlank(node.getDescription())) {
                        line.append(", 说明: ").append(node.getDescription());
                    }
                    return line.append(String.format(", 匹配分数: %.2f", ns.getScore())).toString();
                })
                .collect(Collectors.joining("\n"));
    }
}
