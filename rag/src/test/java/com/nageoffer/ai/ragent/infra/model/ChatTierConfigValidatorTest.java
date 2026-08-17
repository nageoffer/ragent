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
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ChatTierConfigValidator 启动期校验单元测试
 */
class ChatTierConfigValidatorTest {

    private static AIModelProperties.ModelCandidate cand(String id) {
        AIModelProperties.ModelCandidate c = new AIModelProperties.ModelCandidate();
        c.setId(id);
        c.setProvider("bailian");
        c.setModel(id);
        c.setSupportsThinking(true);
        return c;
    }

    private static AIModelProperties.TierConfig tier(List<String> candidates) {
        AIModelProperties.TierConfig p = new AIModelProperties.TierConfig();
        p.setCandidates(candidates);
        p.setTimeoutMs(1000L);
        return p;
    }

    private static AIModelProperties validProperties() {
        AIModelProperties props = new AIModelProperties();
        AIModelProperties.ModelGroup chat = new AIModelProperties.ModelGroup();
        chat.setCandidates(List.of(cand("a"), cand("b")));
        Map<String, AIModelProperties.TierConfig> tiers = new HashMap<>();
        tiers.put("fast", tier(List.of("a")));
        tiers.put("standard", tier(List.of("a", "b")));
        tiers.put("deep", tier(List.of("b")));
        chat.setTiers(tiers);
        chat.setDefaultTier("standard");
        chat.setDeepThinkingTier("deep");
        props.setChat(chat);
        return props;
    }

    private static void validate(AIModelProperties props) {
        new ChatTierConfigValidator(props).afterPropertiesSet();
    }

    @Test
    void 合法配置校验通过() {
        assertDoesNotThrow(() -> validate(validProperties()));
    }

    @Test
    void 档位引用未登记的候选id_失败() {
        AIModelProperties props = validProperties();
        props.getChat().getTiers().put("standard", tier(List.of("a", "ghost")));
        assertThrows(IllegalStateException.class, () -> validate(props));
    }

    @Test
    void default_tier_不存在_失败() {
        AIModelProperties props = validProperties();
        props.getChat().setDefaultTier("nope");
        assertThrows(IllegalStateException.class, () -> validate(props));
    }

    @Test
    void 候选id重复_失败() {
        AIModelProperties props = validProperties();
        props.getChat().setCandidates(List.of(cand("a"), cand("a")));
        assertThrows(IllegalStateException.class, () -> validate(props));
    }
}
