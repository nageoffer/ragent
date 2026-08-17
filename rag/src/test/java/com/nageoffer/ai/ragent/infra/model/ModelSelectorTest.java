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

package com.nageoffer.ai.ragent.infra.model;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ModelSelector 档位路由单元测试
 */
class ModelSelectorTest {

    private AIModelProperties properties;
    private ModelHealthStore healthStore;
    private ModelSelector selector;

    @BeforeEach
    void setUp() {
        properties = buildProperties();
        healthStore = mock(ModelHealthStore.class);
        when(healthStore.isUnavailable(anyString())).thenReturn(false);
        selector = new ModelSelector(properties, healthStore);
    }

    private static List<String> ids(List<ModelTarget> targets) {
        return targets.stream().map(ModelTarget::id).toList();
    }

    private static AIModelProperties.ModelCandidate cand(String id, String provider, String model, boolean thinking) {
        AIModelProperties.ModelCandidate c = new AIModelProperties.ModelCandidate();
        c.setId(id);
        c.setProvider(provider);
        c.setModel(model);
        c.setSupportsThinking(thinking);
        return c;
    }

    private static AIModelProperties.TierConfig tier(List<String> candidates, long timeoutMs) {
        AIModelProperties.TierConfig p = new AIModelProperties.TierConfig();
        p.setCandidates(candidates);
        p.setTimeoutMs(timeoutMs);
        return p;
    }

    private static AIModelProperties buildProperties() {
        AIModelProperties props = new AIModelProperties();

        Map<String, AIModelProperties.ProviderConfig> providers = new HashMap<>();
        for (String p : List.of("bailian", "ollama", "siliconflow", "aihubmix", "atlascloud")) {
            providers.put(p, new AIModelProperties.ProviderConfig());
        }
        props.setProviders(providers);

        AIModelProperties.ModelGroup chat = new AIModelProperties.ModelGroup();
        chat.setCandidates(List.of(
                cand("qwen-flash", "bailian", "qwen-flash", false),
                cand("qwen-plus", "bailian", "qwen-plus-latest", false),
                cand("qwen3-local", "ollama", "qwen3:8b", false),
                cand("qwen3-max", "bailian", "qwen3-max", true),
                cand("glm-4.7", "siliconflow", "GLM-4.7", true),
                cand("gpt-5.4", "aihubmix", "gpt-5.4", false),
                cand("atlas-deepseek-v4-pro", "atlascloud", "deepseek-ai/deepseek-v4-pro", false),
                cand("atlas-qwen3.5-flash", "atlascloud", "qwen/qwen3.5-flash", false)
        ));
        Map<String, AIModelProperties.TierConfig> tiers = new HashMap<>();
        tiers.put("fast", tier(List.of("qwen-flash", "qwen-plus", "qwen3-local"), 5000L));
        tiers.put("standard", tier(List.of("qwen-plus", "qwen3-local", "gpt-5.4"), 30000L));
        tiers.put("deep", tier(List.of("qwen3-max", "glm-4.7"), 120000L));
        chat.setTiers(tiers);
        chat.setDefaultTier("standard");
        chat.setDeepThinkingTier("deep");
        props.setChat(chat);

        return props;
    }

    @Test
    void 默认档走_standard_且携带该档超时() {
        List<ModelTarget> targets = selector.selectChatCandidates(false);
        assertEquals(List.of("qwen-plus", "qwen3-local", "gpt-5.4"), ids(targets));
        assertEquals(30000L, targets.get(0).timeoutMs());
    }

    @Test
    void 深度思考走_deep_且全部支持思考() {
        List<ModelTarget> targets = selector.selectChatCandidates(true);
        assertEquals(List.of("qwen3-max", "glm-4.7"), ids(targets));
        assertEquals(120000L, targets.get(0).timeoutMs());
        assertTrue(targets.stream().allMatch(t -> Boolean.TRUE.equals(t.candidate().getSupportsThinking())));
    }

    @Test
    void 档位覆盖_fast() {
        List<ModelTarget> targets = selector.selectChatCandidates(false, Tier.FAST);
        assertEquals(List.of("qwen-flash", "qwen-plus", "qwen3-local"), ids(targets));
        assertEquals(5000L, targets.get(0).timeoutMs());
    }

    @Test
    void thinking_覆盖档位覆盖() {
        List<ModelTarget> targets = selector.selectChatCandidates(true, Tier.FAST);
        assertEquals(List.of("qwen3-max", "glm-4.7"), ids(targets));
    }

    @Test
    void preferred_置于队首并去重() {
        List<ModelTarget> targets = selector.selectChatCandidates(false, Tier.STANDARD, "gpt-5.4");
        assertEquals(List.of("gpt-5.4", "qwen-plus", "qwen3-local"), ids(targets));
    }

    @Test
    void preferred_未登记则忽略回退档位候选() {
        List<ModelTarget> targets = selector.selectChatCandidates(false, Tier.STANDARD, "not-exist");
        assertEquals(List.of("qwen-plus", "qwen3-local", "gpt-5.4"), ids(targets));
    }

    @Test
    void 思考请求下不支持思考的_preferred_被丢弃() {
        List<ModelTarget> targets = selector.selectChatCandidates(true, null, "qwen-plus");
        assertEquals(List.of("qwen3-max", "glm-4.7"), ids(targets));
    }

    @Test
    void 思考请求下过滤档位内不支持思考的候选() {
        // deepThinkingTier 指向含非思考候选的档位，思考请求应把非思考候选过滤掉
        properties.getChat().getTiers()
                .put("mixed", tier(List.of("qwen-plus", "qwen3-max"), 120000L));
        properties.getChat().setDeepThinkingTier("mixed");
        List<ModelTarget> targets = selector.selectChatCandidates(true);
        assertEquals(List.of("qwen3-max"), ids(targets));
    }

    @Test
    void 不健康模型被熔断过滤() {
        when(healthStore.isUnavailable("qwen-plus")).thenReturn(true);
        List<ModelTarget> targets = selector.selectChatCandidates(false);
        assertEquals(List.of("qwen3-local", "gpt-5.4"), ids(targets));
    }

    @Test
    void atlasCloud模型可作为preferred候选() {
        List<ModelTarget> targets = selector.selectChatCandidates(false, Tier.STANDARD, "atlas-deepseek-v4-pro");
        assertEquals(List.of("atlas-deepseek-v4-pro", "qwen-plus", "qwen3-local", "gpt-5.4"), ids(targets));
        assertEquals("atlascloud", targets.get(0).candidate().getProvider());
    }
}
