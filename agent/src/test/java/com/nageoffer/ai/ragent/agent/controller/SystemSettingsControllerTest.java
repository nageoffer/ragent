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

package com.nageoffer.ai.ragent.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.trace.AgentTraceProperties;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.config.GraphProperties;
import com.nageoffer.ai.ragent.rag.config.KeywordProperties;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.RAGRateLimitProperties;
import com.nageoffer.ai.ragent.rag.config.RagStorageProperties;
import com.nageoffer.ai.ragent.rag.config.RagTraceProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SystemSettingsControllerTest {

    private final AgentProperties agent = new AgentProperties();
    private final AgentMemoryProperties memory = new AgentMemoryProperties();
    private final AgentTraceProperties trace = new AgentTraceProperties();
    private final MemoryProperties workflowMemory = new MemoryProperties();
    private final AIModelProperties ai = new AIModelProperties();

    private ApplicationContextRunner context(String mode) {
        OrchestrationProperties engine = new OrchestrationProperties();
        engine.setType(mode);
        return new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getBeanFactory().setConversionService(new ApplicationConversionService()))
                .withPropertyValues("ragent.engine.type=" + mode, "rag.vector.type=pg",
                        "spring.servlet.multipart.max-file-size=20MB",
                        "spring.servlet.multipart.max-request-size=40MB")
                .withUserConfiguration(SystemSettingsController.class)
                .withBean(AgentProperties.class, () -> agent)
                .withBean(AgentMemoryProperties.class, () -> memory)
                .withBean(AgentTraceProperties.class, () -> trace)
                .withBean(OrchestrationProperties.class, () -> engine)
                .withBean(RagStorageProperties.class, RagStorageProperties::new)
                .withBean(KeywordProperties.class, KeywordProperties::new)
                .withBean(GraphProperties.class, GraphProperties::new)
                .withBean(RAGDefaultProperties.class, RAGDefaultProperties::new)
                .withBean(RAGConfigProperties.class, RAGConfigProperties::new)
                .withBean(RagTraceProperties.class, RagTraceProperties::new)
                .withBean(SearchChannelProperties.class, SearchChannelProperties::new)
                .withBean(RAGRateLimitProperties.class, RAGRateLimitProperties::new)
                .withBean(MemoryProperties.class, () -> workflowMemory)
                .withBean(AIModelProperties.class, () -> ai);
    }

    @ParameterizedTest
    @ValueSource(strings = {"agent", "workflow"})
    void shouldExposeSystemEndpointInBothEngineModes(String mode) {
        context(mode).run(ctx -> {
            assertThat(ctx).hasSingleBean(SystemSettingsController.class);
            var controller = ctx.getBean(SystemSettingsController.class);
            var mvc = MockMvcBuilders.standaloneSetup(controller).build();
            mvc.perform(get("/system/settings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.engine.type").value(mode))
                    .andExpect(jsonPath("$.data.backends.vector.type").value("pg"))
                    .andExpect(jsonPath("$.data.upload.maxFileSize").value(20 * 1024 * 1024))
                    .andExpect(jsonPath("$.data.rag.search").isMap());
            assertThat(controller.settings().getData().getAgent() != null).isEqualTo("agent".equals(mode));
        });
    }

    @Test
    void shouldKeepAgentAndWorkflowSettingsSeparateAndResolveCurrentThresholds() {
        agent.getChat().setModel("agent-model");
        agent.getChat().setProvider("agent-provider");
        agent.getChat().setReasoningEffort("high");
        agent.setMaxConcurrentRunsPerUser(3);
        memory.setContextWindowChars(20000);
        memory.setSummaryEnabled(false);
        memory.setLongTermEnabled(false);
        memory.setEvictableTools(List.of("search_knowledge"));
        workflowMemory.setSummaryEnabled(true);
        ai.getChat().setDefaultTier("knowledge-tier");
        trace.setEnabled(true);

        context("agent").run(ctx -> {
            var controller = ctx.getBean(SystemSettingsController.class);
            var result = controller.settings().getData();
            assertThat(result.getAgent().chat().model()).isEqualTo("agent-model");
            assertThat(result.getAgent().chat().reasoningEffort()).isEqualTo("high");
            assertThat(result.getAi().getChat().getDefaultTier()).isEqualTo("knowledge-tier");
            assertThat(result.getRag().getMemory().getSummaryEnabled()).isTrue();
            assertThat(result.getAgent().memory().summaryEnabled()).isFalse();
            assertThat(result.getAgent().memory().longTermEnabled()).isFalse();
            assertThat(result.getAgent().memory().evictableTools()).containsExactly("search_knowledge");
            assertThat(result.getAgent().memory().trimTriggerChars()).isEqualTo(10000);
            assertThat(result.getAgent().memory().compactTriggerChars()).isEqualTo(16000);
            assertThat(result.getAgent().memory().keepRecentChars()).isEqualTo(4000);
            assertThat(result.getAgent().memory().summaryTargetChars()).isEqualTo(2000);
            assertThat(result.getAgent().maxConcurrentRunsPerUser()).isEqualTo(3);
            assertThat(result.getAgent().traceEnabled()).isTrue();

            memory.setContextWindowChars(40000);
            var updated = controller.settings().getData().getAgent().memory();
            assertThat(updated.contextWindowChars()).isEqualTo(40000);
            assertThat(updated.trimTriggerChars()).isEqualTo(20000);
            assertThat(updated.compactTriggerChars()).isEqualTo(32000);
            assertThat(memory.isSummaryEnabled()).isFalse();
        });
    }

    @Test
    void shouldOmitTraceCredentialsAndMaskProviderKey() {
        trace.setPublicKey("test-public-credential");
        trace.setSecretKey("test-private-credential");
        trace.setEndpoint("http://private-observer.example/api");
        var provider = new AIModelProperties.ProviderConfig();
        provider.setApiKey("sk-test-sensitive-provider-key");
        ai.setProviders(Map.of("test", provider));

        context("agent").run(ctx -> {
            var result = ctx.getBean(SystemSettingsController.class).settings().getData();
            String json = new ObjectMapper().writeValueAsString(result);
            assertThat(json).doesNotContain("credential", "publicKey", "secretKey", "private-observer",
                    "sk-test-sensitive-provider-key");
            assertThat(result.getAi().getProviders().get("test").getApiKey()).isEqualTo("sk-tes***-key");
        });
    }
}
