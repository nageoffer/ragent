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

package com.nageoffer.ai.ragent.rag.core.guard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.INPUT_RISK_REVIEW_PROMPT_PATH;

/**
 * 大模型输入审核：规则作系统消息，本轮与上一轮由 Jackson 序列化进用户消息
 * <p>
 * 原文只当 JSON 字符串值传，不经模板替换，用户写的标签或引号改不了数据的边界
 */
@RequiredArgsConstructor
public class LlmInputRiskDetector implements InputRiskDetector {

    /**
     * 输出只有 {"risks":[...]}，留余量即可；截断会得到非法 JSON，按故障处理
     */
    private static final int MAX_TOKENS = 100;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;

    @Override
    public Set<InputRisk> detect(String question, Supplier<Optional<PreviousTurn>> previousTurn) {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system(promptTemplateLoader.load(INPUT_RISK_REVIEW_PROMPT_PATH)),
                        ChatMessage.user(toJson(new ReviewInput(previousTurn.get().map(Turn::of).orElse(null), question)))))
                .temperature(0D)
                .maxTokens(MAX_TOKENS)
                .thinking(false)
                .build();
        return parse(llmService.chat(request, Tier.FAST));
    }

    private static Set<InputRisk> parse(String raw) {
        JsonNode risks;
        try {
            risks = MAPPER.readTree(LLMResponseCleaner.stripMarkdownCodeFence(String.valueOf(raw))).path("risks");
        } catch (JsonProcessingException e) {
            // 不挂原异常：Jackson 的报错会带上模型输出片段
            throw new IllegalStateException("审核模型输出不是合法 JSON");
        }
        if (!risks.isArray()) {
            throw new IllegalStateException("审核模型输出缺少 risks 数组");
        }
        Set<InputRisk> result = EnumSet.noneOf(InputRisk.class);
        for (JsonNode risk : risks) {
            result.add(Arrays.stream(InputRisk.values())
                    .filter(candidate -> risk.isTextual() && candidate.name().equals(risk.asText()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("审核模型输出了未知风险类别")));
        }
        return result;
    }

    private static String toJson(ReviewInput input) {
        try {
            return MAPPER.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("审核输入序列化失败", e);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ReviewInput(@JsonProperty("previous_turn") Turn previousTurn,
                               @JsonProperty("current_request") String currentRequest) {
    }

    private record Turn(String user, String assistant) {

        static Turn of(PreviousTurn turn) {
            return new Turn(turn.question(), turn.answer());
        }
    }
}
