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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.BaiLianChatClient;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实网回放：百炼 qwen-flash（fast 档首选）逐条跑 replay-cases.json，没有 BAILIAN_API_KEY 时跳过
 * <p>
 * expected 为空表示必须放行；非空表示必须命中其中每一类，多命中不算错
 */
@EnabledIfEnvironmentVariable(named = "BAILIAN_API_KEY", matches = ".+")
class LlmInputRiskDetectorReplayTest {

    private record Case(String name, Map<String, String> previous, String question, Set<InputRisk> expected) {
    }

    @Test
    void shouldMatchEveryReplayCase() throws Exception {
        List<Case> cases;
        try (InputStream in = getClass().getResourceAsStream("/input-guard/replay-cases.json")) {
            cases = new ObjectMapper().readValue(in, new TypeReference<>() {
            });
        }
        LlmInputRiskDetector detector = new LlmInputRiskDetector(
                bailianFlash(), new PromptTemplateLoader(new DefaultResourceLoader()));

        List<String> mismatches = new ArrayList<>();
        List<Long> costs = new ArrayList<>();
        for (Case c : cases) {
            Optional<PreviousTurn> previous = Optional.ofNullable(c.previous())
                    .map(turn -> new PreviousTurn(turn.get("user"), turn.get("assistant")));
            long start = System.nanoTime();
            String actual;
            boolean matched;
            try {
                Set<InputRisk> risks = detector.detect(c.question(), () -> previous);
                actual = risks.isEmpty() ? "PASS" : risks.toString();
                matched = c.expected().isEmpty() ? risks.isEmpty() : risks.containsAll(c.expected());
            } catch (RuntimeException e) {
                actual = "FAILED " + e.getMessage();
                matched = false;
            }
            long cost = (System.nanoTime() - start) / 1_000_000;
            costs.add(cost);
            String expected = c.expected().isEmpty() ? "PASS" : c.expected().toString();
            System.out.printf("[replay] %s %-24s expected=%-22s actual=%-22s cost=%dms%n",
                    matched ? "ok  " : "MISS", c.name(), expected, actual, cost);
            if (!matched) {
                mismatches.add(c.name());
            }
        }
        List<Long> sorted = costs.stream().sorted().toList();
        System.out.printf("[replay] cases=%d miss=%d p50=%dms max=%dms%n",
                cases.size(), mismatches.size(), sorted.get(sorted.size() / 2), sorted.get(sorted.size() - 1));
        assertThat(mismatches).isEmpty();
    }

    /**
     * 只走 fast 档首选，不经路由降级：回放要量的是这一个模型的判断
     */
    private static LLMService bailianFlash() {
        BaiLianChatClient client = new BaiLianChatClient();
        ReflectionTestUtils.setField(client, "syncHttpClient", new OkHttpClient());
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("https://dashscope.aliyuncs.com");
        provider.setApiKey(System.getenv("BAILIAN_API_KEY"));
        provider.setEndpoints(Map.of("chat", "/compatible-mode/v1/chat/completions"));
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("qwen-flash");
        candidate.setProvider("bailian");
        candidate.setModel("qwen-flash");
        ModelTarget target = new ModelTarget("qwen-flash", candidate, provider, 5_000L);
        return new LLMService() {
            @Override
            public String chat(ChatRequest request) {
                return client.chat(request, target);
            }

            @Override
            public String chat(ChatRequest request, Tier tier) {
                return client.chat(request, target);
            }

            @Override
            public String chat(ChatRequest request, Tier tier, String preferredModelId) {
                return client.chat(request, target);
            }

            @Override
            public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
