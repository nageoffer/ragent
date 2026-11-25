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
import com.nageoffer.ai.ragent.infra.jev.JevClient;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import com.nageoffer.ai.ragent.rag.config.HttpClientConfig;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实网回放：逐条跑 replay-cases.json，命中的类别必须与 expected 完全一致，多报漏报都算错
 * <p>
 * LLM 直连百炼 qwen-flash（fast 档首选），Jev 直连 ZenMux；各自缺密钥时跳过
 */
class InputRiskDetectorReplayTest {

    private record Case(String name, Map<String, String> previous, String question, Set<InputRisk> expected) {
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BAILIAN_API_KEY", matches = ".+")
    void llmShouldMatchEveryReplayCase() throws Exception {
        replay("llm", new LlmInputRiskDetector(bailianFlash(), new PromptTemplateLoader(new DefaultResourceLoader())));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ZENMUX_API_KEY", matches = ".+")
    void jevShouldMatchEveryReplayCase() throws Exception {
        replay("jev", new JevInputRiskDetector(zenmuxJev(), new PromptTemplateLoader(new DefaultResourceLoader())));
    }

    private void replay(String label, InputRiskDetector detector) throws Exception {
        List<Case> cases;
        try (InputStream in = getClass().getResourceAsStream("/input-guard/replay-cases.json")) {
            cases = new ObjectMapper().readValue(in, new TypeReference<>() {
            });
        }
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
                matched = risks.equals(c.expected());
            } catch (RuntimeException e) {
                actual = "FAILED " + e.getMessage();
                matched = false;
            }
            long cost = (System.nanoTime() - start) / 1_000_000;
            costs.add(cost);
            String expected = c.expected().isEmpty() ? "PASS" : c.expected().toString();
            System.out.printf("[replay:%s] %s %-24s expected=%-40s actual=%-40s cost=%dms%n",
                    label, matched ? "ok  " : "MISS", c.name(), expected, actual, cost);
            if (!matched) {
                mismatches.add(c.name());
            }
        }
        List<Long> sorted = costs.stream().sorted().toList();
        System.out.printf("[replay:%s] cases=%d miss=%d p50=%dms max=%dms%n",
                label, cases.size(), mismatches.size(), sorted.get(sorted.size() / 2), sorted.get(sorted.size() - 1));
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

    /**
     * 模型取值同 application.yaml 的 ai.jev，HTTP 客户端直接用生产的 jevHttpClient
     */
    private static JevClient zenmuxJev() {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("https://zenmux.ai");
        provider.setApiKey(System.getenv("ZENMUX_API_KEY"));
        provider.setEndpoints(new HashMap<>(Map.of("jev", "/api/v1/systemone")));
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("jev-stable");
        candidate.setProvider("zenmux");
        candidate.setModel("typesafe/jev-1.14");
        AIModelProperties properties = new AIModelProperties();
        properties.setProviders(new HashMap<>(Map.of("zenmux", provider)));
        properties.getJev().setDefaultModel("jev-stable");
        properties.getJev().setCandidates(new ArrayList<>(List.of(candidate)));
        return new JevClient(properties, new HttpClientConfig().jevHttpClient());
    }
}
