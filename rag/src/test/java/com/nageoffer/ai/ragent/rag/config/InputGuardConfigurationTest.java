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

package com.nageoffer.ai.ragent.rag.config;

import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.jev.JevClient;
import com.nageoffer.ai.ragent.infra.jev.JevTarget;
import com.nageoffer.ai.ragent.rag.core.guard.InputRiskDetector;
import com.nageoffer.ai.ragent.rag.core.guard.JevInputRiskDetector;
import com.nageoffer.ai.ragent.rag.core.guard.LlmInputRiskDetector;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InputGuardConfigurationTest {

    private final InputGuardConfiguration configuration = new InputGuardConfiguration();

    @Test
    void shouldDefaultToLlm() {
        InputRiskDetector detector = configuration.inputRiskDetector(
                bind(Map.of()), mock(LLMService.class), mock(PromptTemplateLoader.class), mock(JevClient.class));

        assertThat(detector).isInstanceOf(LlmInputRiskDetector.class);
    }

    @Test
    void shouldAssembleJevWithResolvedTarget() {
        JevClient jevClient = mock(JevClient.class);
        when(jevClient.requireTarget()).thenReturn(new JevTarget("http://jev", "key", "typesafe/jev-1.14"));

        InputRiskDetector detector = configuration.inputRiskDetector(
                bind(Map.of("ragent.input-guard.type", "jev")), mock(LLMService.class), mock(PromptTemplateLoader.class), jevClient);

        assertThat(detector).isInstanceOf(JevInputRiskDetector.class);
        verify(jevClient).requireTarget();
    }

    @Test
    void shouldRequireCredentialsOnlyWhenAliyunSelected() {
        assertThatThrownBy(() -> configuration.inputRiskDetector(
                bind(Map.of("ragent.input-guard.type", "aliyun")), mock(LLMService.class), mock(PromptTemplateLoader.class), mock(JevClient.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("access-key-id");
    }

    /**
     * workflow 部署没有接审核，不能因为 yaml 里留着 aliyun 就要求配凭据
     */
    @Test
    void shouldNotAssembleOutsideAgentEngine() {
        aliyunWithoutCredentials()
                .withPropertyValues("ragent.engine.type=workflow")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(InputRiskDetector.class);
                });
        aliyunWithoutCredentials()
                .withPropertyValues("ragent.engine.type=agent")
                .run(context -> assertThat(context).hasFailed());
    }

    private static ApplicationContextRunner aliyunWithoutCredentials() {
        return new ApplicationContextRunner()
                .withUserConfiguration(InputGuardConfiguration.class)
                .withBean(InputGuardProperties.class, () -> bind(Map.of("ragent.input-guard.type", "aliyun")))
                .withBean(LLMService.class, () -> mock(LLMService.class))
                .withBean(PromptTemplateLoader.class, () -> mock(PromptTemplateLoader.class))
                .withBean(JevClient.class, () -> mock(JevClient.class));
    }

    private static InputGuardProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("ragent.input-guard", InputGuardProperties.class);
    }
}
