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

package com.nageoffer.ai.ragent.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 线格式回归护栏：GPT 上游收到 temperature 会 400，service_tier 不发则档位与记录的价格对不上
 */
class ZenMuxFormatterTest {

    private static final String GPT = "openai/gpt-6.1-sol:openai";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 摘要器与长期记忆裁决写死 temperature=0.3，这里不摘掉就会把这两次调用打挂
     */
    @Test
    void shouldDropFixedSamplingParamsForOpenAiUpstream() throws Exception {
        JsonNode body = body(new ZenMuxFormatter(), GPT, GenerateOptions.builder()
                .temperature(0.3D).topP(0.9D).frequencyPenalty(0.1D).presencePenalty(0.2D).build());

        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("top_p")).isFalse();
        assertThat(body.has("frequency_penalty")).isFalse();
        assertThat(body.has("presence_penalty")).isFalse();
    }

    @Test
    void shouldSendStandardServiceTierAndKeepReasoningEffort() throws Exception {
        JsonNode body = body(new ZenMuxFormatter(), GPT,
                GenerateOptions.builder().reasoningEffort("high").build());

        assertThat(body.get("service_tier").asText()).isEqualTo("default");
        assertThat(body.get("reasoning_effort").asText()).isEqualTo("high");
    }

    /**
     * 推理模型只认 max_completion_tokens；thinking_budget 是别家方言，ZenMux 不认
     */
    @Test
    void shouldMapMaxTokensAndDropThinkingBudget() throws Exception {
        JsonNode body = body(new ZenMuxFormatter(), GPT,
                GenerateOptions.builder().maxTokens(4096).thinkingBudget(2048).build());

        assertThat(body.has("max_tokens")).isFalse();
        assertThat(body.get("max_completion_tokens").asInt()).isEqualTo(4096);
        assertThat(body.has("thinking_budget")).isFalse();
    }

    /**
     * ZenMux 是聚合网关：非 OpenAI 上游的采样参数照通用实现发，别替它们做决定
     */
    @Test
    void shouldKeepSamplingParamsForOtherUpstreams() throws Exception {
        JsonNode body = body(new ZenMuxFormatter(), "anthropic/claude-x",
                GenerateOptions.builder().temperature(0.3D).build());

        assertThat(body.get("temperature").asDouble()).isEqualTo(0.3D);
        assertThat(body.get("service_tier").asText()).isEqualTo("default");
    }

    /**
     * 回滚 sanitize 的护栏：通用实现会把 temperature 原样发出去
     */
    @Test
    void genericFormatterWouldForwardTemperature() throws Exception {
        JsonNode body = body(new OpenAIChatFormatter(), GPT,
                GenerateOptions.builder().temperature(0.3D).build());

        assertThat(body.get("temperature").asDouble()).isEqualTo(0.3D);
        assertThat(body.has("service_tier")).isFalse();
    }

    private JsonNode body(OpenAIChatFormatter formatter, String model, GenerateOptions options) throws Exception {
        OpenAIRequest request = OpenAIRequest.builder().model(model).messages(List.of()).stream(true).build();
        formatter.applyOptions(request, options, null);
        return MAPPER.readTree(MAPPER.writeValueAsString(request));
    }

    /**
     * extraParams 走 @JsonAnyGetter，断言要看序列化后的线格式而不是 DTO 字段
     */
    @Test
    void shouldNotLeakAdditionalBodyParamsIntoSanitizedFields() throws Exception {
        JsonNode body = body(new ZenMuxFormatter(), GPT, GenerateOptions.builder()
                .temperature(0.3D).additionalBodyParams(Map.of("thinking", Map.of("type", "disabled"))).build());

        assertThat(body.has("temperature")).isFalse();
        assertThat(body.get("thinking").get("type").asText()).isEqualTo("disabled");
    }
}
