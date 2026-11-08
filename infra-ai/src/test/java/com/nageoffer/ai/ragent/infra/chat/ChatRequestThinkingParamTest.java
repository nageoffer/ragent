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

package com.nageoffer.ai.ragent.infra.chat;

import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 线格式回归护栏：enable_thinking 是 DashScope 系私有扩展，只许发给声明认识它的提供商
 * <p>
 * 曾有一次改动让所有提供商无条件带上该字段，OpenAI 系网关判为 unknown_parameter 直接 400，
 * 且因该候选常在档位末位而击穿整条 fallback 链，这里按提供商逐个钉死请求体
 */
class ChatRequestThinkingParamTest {

    private static final String FIELD = "enable_thinking";
    private static final String THINKING_OBJECT = "thinking";

    @Test
    void bailianShouldCarryThinkingFlagWhenEnabled() {
        JsonObject body = buildBody(new BaiLianChatClient(), Boolean.TRUE);
        assertTrue(body.has(FIELD));
        assertTrue(body.get(FIELD).getAsBoolean());
    }

    /**
     * Qwen3 系不显式关会默认开启思考，故关闭时必须显式发 false 而非省略
     */
    @Test
    void bailianShouldExplicitlyDisableThinkingWhenClosed() {
        JsonObject body = buildBody(new BaiLianChatClient(), Boolean.FALSE);
        assertTrue(body.has(FIELD));
        assertFalse(body.get(FIELD).getAsBoolean());
    }

    @Test
    void bailianShouldTreatAbsentFlagAsDisabled() {
        JsonObject body = buildBody(new BaiLianChatClient(), null);
        assertTrue(body.has(FIELD));
        assertFalse(body.get(FIELD).getAsBoolean());
    }

    @Test
    void siliconFlowShouldCarryThinkingFlag() {
        assertTrue(buildBody(new SiliconFlowChatClient(), Boolean.TRUE).has(FIELD));
        assertTrue(buildBody(new SiliconFlowChatClient(), Boolean.FALSE).has(FIELD));
    }

    /**
     * DeepSeek 的方言是 thinking 对象，enable_thinking 发过去会被判为未知参数
     */
    @Test
    void deepSeekShouldNeverCarryThinkingFlag() {
        assertFalse(buildBody(new DeepSeekChatClient(), Boolean.TRUE).has(FIELD));
        assertFalse(buildBody(new DeepSeekChatClient(), Boolean.FALSE).has(FIELD));
        assertFalse(buildBody(new DeepSeekChatClient(), null).has(FIELD));
    }

    /**
     * V4 系思考默认开启，关闭时必须显式发 disabled 而非省略整个对象
     */
    @Test
    void deepSeekShouldCarryThinkingObject() {
        assertEquals("enabled", thinkingType(buildBody(new DeepSeekChatClient(), Boolean.TRUE)));
        assertEquals("disabled", thinkingType(buildBody(new DeepSeekChatClient(), Boolean.FALSE)));
        assertEquals("disabled", thinkingType(buildBody(new DeepSeekChatClient(), null)));
    }

    /**
     * 混元 Hy4 与 DeepSeek 同方言，思考默认开启，关闭时同样必须显式发 disabled
     */
    @Test
    void hunyuanShouldCarryThinkingObjectOnly() {
        assertEquals("enabled", thinkingType(buildBody(new HunyuanChatClient(), Boolean.TRUE)));
        assertEquals("disabled", thinkingType(buildBody(new HunyuanChatClient(), Boolean.FALSE)));
        assertEquals("disabled", thinkingType(buildBody(new HunyuanChatClient(), null)));
        assertFalse(buildBody(new HunyuanChatClient(), Boolean.TRUE).has(FIELD));
    }

    /**
     * Kimi K3 系思考关不掉：不发任何思考开关，不要思考时只把强度降到 low
     */
    @Test
    void kimiShouldOnlyLowerReasoningEffortWhenThinkingOff() {
        JsonObject off = buildBody(new KimiChatClient(), Boolean.FALSE);
        assertEquals("low", off.get("reasoning_effort").getAsString());
        assertEquals("low", buildBody(new KimiChatClient(), null).get("reasoning_effort").getAsString());
        assertFalse(buildBody(new KimiChatClient(), Boolean.TRUE).has("reasoning_effort"));
        assertFalse(off.has(FIELD));
        assertFalse(off.has(THINKING_OBJECT));
    }

    /**
     * K3 系采样参数固定，传其他值报错
     */
    @Test
    void kimiShouldDropFixedSamplingParams() {
        ChatRequest request = request(Boolean.FALSE);
        request.setTopP(0.8D);
        JsonObject body = new KimiChatClient().buildRequestBody(request, target(), false);
        assertFalse(body.has("temperature"));
        assertFalse(body.has("top_p"));
    }

    /**
     * 聚合网关走 OpenAI 原生协议，带上该字段会被判为未知参数
     */
    @Test
    void aiHubMixShouldNeverCarryThinkingFlag() {
        assertFalse(buildBody(new AIHubMixChatClient(), Boolean.TRUE).has(FIELD));
        assertFalse(buildBody(new AIHubMixChatClient(), Boolean.FALSE).has(FIELD));
        assertFalse(buildBody(new AIHubMixChatClient(), null).has(FIELD));
    }

    @Test
    void ollamaShouldNeverCarryThinkingFlag() {
        assertFalse(buildBody(new OllamaChatClient(), Boolean.TRUE).has(FIELD));
        assertFalse(buildBody(new OllamaChatClient(), Boolean.FALSE).has(FIELD));
    }

    /**
     * thinking 对象同样是私有扩展，只许发给 DeepSeek 和混元
     */
    @Test
    void otherProvidersShouldNeverCarryThinkingObject() {
        assertFalse(buildBody(new BaiLianChatClient(), Boolean.TRUE).has(THINKING_OBJECT));
        assertFalse(buildBody(new AIHubMixChatClient(), Boolean.TRUE).has(THINKING_OBJECT));
        assertFalse(buildBody(new OllamaChatClient(), Boolean.TRUE).has(THINKING_OBJECT));
    }

    /**
     * 新增提供商默认不认识该字段，避免再次无条件外发
     */
    @Test
    void unknownProviderShouldOptOutByDefault() {
        AbstractOpenAIStyleChatClient client = new AbstractOpenAIStyleChatClient() {
            @Override
            public String provider() {
                return "brand-new-gateway";
            }

            @Override
            public String chat(ChatRequest request, ModelTarget target) {
                return doChat(request, target);
            }

            @Override
            public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
                return doStreamChat(request, callback, target);
            }
        };
        assertFalse(buildBody(client, Boolean.TRUE).has(FIELD));
    }

    /**
     * 流式与同步共用请求体构建，思考字段不应因 stream 标志而变化
     */
    @Test
    void streamBodyShouldFollowSameThinkingRule() {
        JsonObject bailian = new BaiLianChatClient().buildRequestBody(request(Boolean.TRUE), target(), true);
        assertTrue(bailian.has(FIELD));
        assertTrue(bailian.get("stream").getAsBoolean());
        assertFalse(new AIHubMixChatClient().buildRequestBody(request(Boolean.TRUE), target(), true).has(FIELD));
    }

    /**
     * 采样参数不受思考闸门影响，防止改动闸门时误伤公共字段
     */
    @Test
    void commonSamplingFieldsShouldStayIntact() {
        JsonObject body = buildBody(new AIHubMixChatClient(), Boolean.FALSE);
        assertEquals("test-model", body.get("model").getAsString());
        assertEquals(0D, body.get("temperature").getAsDouble());
        assertEquals(1, body.getAsJsonArray("messages").size());
    }

    private JsonObject buildBody(AbstractOpenAIStyleChatClient client, Boolean thinking) {
        return client.buildRequestBody(request(thinking), target(), false);
    }

    private String thinkingType(JsonObject body) {
        assertTrue(body.has(THINKING_OBJECT), "请求体缺少 thinking 对象");
        return body.getAsJsonObject(THINKING_OBJECT).get("type").getAsString();
    }

    private ChatRequest request(Boolean thinking) {
        return ChatRequest.builder()
                .messages(List.of(ChatMessage.user("你好")))
                .temperature(0D)
                .thinking(thinking)
                .build();
    }

    private ModelTarget target() {
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setModel("test-model");
        return new ModelTarget("test-id", candidate, new AIModelProperties.ProviderConfig(), null);
    }
}
