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

import com.nageoffer.ai.ragent.agent.model.ZenMuxFormatter;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import io.agentscope.extensions.model.openai.compat.kimi.KimiFormatter;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 装配回归：每个 provider 挂到自己的 formatter，缺 url 或 endpoints.chat 的配置在起进程时就拒绝
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
        OpenAIChatModel model = build("zenmux", "openai/gpt-6.1-sol:openai");

        assertThat(model.getModelName()).isEqualTo("openai/gpt-6.1-sol:openai");
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
        try {
            Field field = OpenAIChatModel.class.getDeclaredField("formatter");
            field.setAccessible(true);
            return field.get(build(providerId, modelName));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("OpenAIChatModel 的 formatter 字段变了，用例要跟着改", e);
        }
    }

    private OpenAIChatModel build(String providerId, String modelName) {
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
