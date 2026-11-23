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

package com.nageoffer.ai.ragent.agent.config;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.agent.model.AnthropicMessagesChatModel;
import com.nageoffer.ai.ragent.agent.model.OpenAIStreamCompletionTransport;
import com.nageoffer.ai.ragent.agent.model.ZenMuxFormatter;
import com.nageoffer.ai.ragent.agent.model.ZenMuxGeminiThoughtSignatureTransport;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.OpenAIClient;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import io.agentscope.extensions.model.openai.compat.kimi.KimiFormatter;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 装配回归：每个 provider 挂到自己的 formatter 与传输链，缺 url 或 endpoints.chat 的配置在起进程时就拒绝
 */
class AgentEngineConfigurationTest {

    @Test
    void shouldAttachZenMuxFormatterForZenMuxProvider() {
        assertThat(formatterOf("zenmux", "openai/gpt-6.1-sol:openai")).isInstanceOf(ZenMuxFormatter.class);
    }

    @Test
    void shouldKeepFormattersOfOtherProviders() {
        assertThat(formatterOf("deepseek", "deepseek-flash")).isInstanceOf(DeepSeekFormatter.class);
        assertThat(formatterOf("kimi", "kimi-k3.1-pro")).isInstanceOf(KimiFormatter.class);
        // 没有专用 formatter 的供应商落到通用实现，不该被 ZenMux 的收敛影响
        assertThat(formatterOf("hunyuan", "hy4"))
                .isInstanceOf(OpenAIChatFormatter.class)
                .isNotInstanceOf(ZenMuxFormatter.class);
    }

    /**
     * 模型名里带 : 选线路（openai/gpt-6.1-sol:openai），原样透传给端点，不在本地拆
     */
    @Test
    void shouldPassZenMuxEndpointAndModelThrough() {
        Model model = build("zenmux", "openai/gpt-6.1-sol:openai");

        assertThat(model.getModelName()).isEqualTo("openai/gpt-6.1-sol:openai");
    }

    @Test
    void shouldBuildAnthropicModelForZenMuxAnthropicProvider() {
        Model model = build("zenmux-anthropic", ProductionModels.OPUS);

        assertThat(model).isInstanceOf(AnthropicMessagesChatModel.class);
        assertThat(model.getModelName()).isEqualTo(ProductionModels.OPUS);
    }

    /**
     * 这个入口不按型号挑：Sonnet 与 Opus 一样，每次请求都带 beta 头并要求丢弃前缀失配的思考块
     */
    @Test
    void shouldAskEveryAnthropicModelToDropMismatchedThinking() {
        ProductionModels.Gateway gateway = new ProductionModels.Gateway().thenStream(List.of(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"usage\":{\"input_tokens\":9,\"output_tokens\":1}}}",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"好\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":3}}",
                "{\"type\":\"message_stop\"}"));

        ProductionModels.sonnet(gateway).stream(List.of(Msg.builder().role(MsgRole.USER).textContent("问").build()),
                null, GenerateOptions.builder().build()).collectList().block(Duration.ofSeconds(10));

        JSONObject thinking = JSONUtil.parseObj(gateway.lastRequest()).getJSONObject("thinking");
        assertThat(thinking.getStr("type")).isEqualTo("adaptive");
        assertThat(thinking.getJSONObject("block_binding").getStr("prefix_mismatch_behavior")).isEqualTo("drop_block");
        assertThat(gateway.headers.get(0)).containsEntry("anthropic-beta", "thinking-binding-controls-2026-08-01");
    }

    /**
     * 每家都校验流是否完整；签名搬运只给 ZenMux 上的 Gemini，GPT 的加密推理不能被它改写
     */
    @Test
    void shouldChainTransportsPerUpstream() {
        Object deepseek = transportOf("deepseek", "deepseek-flash");
        Object gemini = transportOf("zenmux", "google/gemini-4-hydrogen:google-vertex");
        Object gpt = transportOf("zenmux", "openai/gpt-6.1-sol:openai");

        assertThat(deepseek).isInstanceOf(OpenAIStreamCompletionTransport.class);
        assertThat(field(OpenAIStreamCompletionTransport.class, "delegate", deepseek))
                .isNotInstanceOf(ZenMuxGeminiThoughtSignatureTransport.class);
        assertThat(field(OpenAIStreamCompletionTransport.class, "delegate", gemini))
                .isInstanceOf(ZenMuxGeminiThoughtSignatureTransport.class);
        assertThat(field(OpenAIStreamCompletionTransport.class, "delegate", gpt))
                .isNotInstanceOf(ZenMuxGeminiThoughtSignatureTransport.class);
    }

    /**
     * 按生产装配跑两步：GPT 返回的加密推理是 openai-responses-v1，续调时发出去的请求里不能出现 Gemini 格式
     */
    @Test
    void shouldNotRewriteNonGeminiReasoningIntoGeminiFormat() {
        ProductionModels.Gateway gateway = new ProductionModels.Gateway()
                .thenStream(List.of(
                        "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"reasoning_details\":[{\"type\":"
                                + "\"reasoning.encrypted\",\"id\":\"call_A\",\"data\":\"OPENAI-ENC\",\"format\":\"openai-responses-v1\",\"index\":0}]},"
                                + "\"finish_reason\":null}]}",
                        "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_A\",\"type\":"
                                + "\"function\",\"function\":{\"name\":\"query_order\",\"arguments\":\"{}\"}}]},\"finish_reason\":null}]}",
                        "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}"))
                .thenStream(List.of(
                        "{\"id\":\"c2\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"好的\"},\"finish_reason\":null}]}",
                        "{\"id\":\"c2\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}"));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new Tools());
        ReActAgent.builder().name("RagentAI").sysPrompt("你是售后客服")
                .model(ProductionModels.zenmux("openai/gpt-6.1-sol:openai", gateway)).toolkit(toolkit).maxIters(3).maxRetries(1)
                .stateStore(new InMemoryAgentStateStore()).build()
                .call("查订单", RuntimeContext.builder().userId("u-1").sessionId("c-1").build()).block(Duration.ofSeconds(10));

        assertThat(gateway.requests).hasSize(2);
        assertThat(gateway.lastRequest()).doesNotContain("google-gemini-v1");
    }

    @Test
    void shouldRejectProviderMissingChatEndpoint() {
        AgentProperties agent = new AgentProperties();
        agent.getChat().setProvider("zenmux");
        agent.getChat().setModel("openai/gpt-6.1-sol:openai");
        AIModelProperties ai = new AIModelProperties();
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("https://zenmux.ai");
        ai.setProviders(Map.of("zenmux", provider));

        assertThatThrownBy(() -> new AgentEngineConfiguration(agent, ai).agentChatModel())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("endpoints.chat");
    }

    private Object formatterOf(String providerId, String modelName) {
        return field(OpenAIChatModel.class, "formatter", build(providerId, modelName));
    }

    public static class Tools {

        @Tool(name = "query_order", description = "按订单号查询订单")
        public String queryOrder() {
            return "{\"status\":\"已发货\"}";
        }
    }

    private Object transportOf(String providerId, String modelName) {
        return field(OpenAIClient.class, "transport", field(OpenAIChatModel.class, "client", build(providerId, modelName)));
    }

    private static Object field(Class<?> owner, String name, Object target) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(owner.getSimpleName() + " 的 " + name + " 字段变了，用例要跟着改", e);
        }
    }

    private Model build(String providerId, String modelName) {
        AgentProperties agent = new AgentProperties();
        agent.getChat().setProvider(providerId);
        agent.getChat().setModel(modelName);
        AIModelProperties ai = new AIModelProperties();
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("https://example.invalid");
        provider.setApiKey("sk-test");
        provider.setEndpoints(Map.of("chat", "/api/v1/chat/completions"));
        ai.setProviders(Map.of(providerId, provider));
        return new AgentEngineConfiguration(agent, ai).agentChatModel();
    }
}
