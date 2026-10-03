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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VlmConfigValidatorTest {

    @Test
    void 未配置候选时跳过校验() {
        assertDoesNotThrow(() -> validate(new AIModelProperties()));
    }

    @Test
    void 正数超时校验通过() {
        AIModelProperties properties = configuredProperties();
        properties.getVlm().setTimeoutMs(120000L);

        assertDoesNotThrow(() -> validate(properties));
    }

    @Test
    void 未配置超时时兼容全局客户端() {
        assertDoesNotThrow(() -> validate(configuredProperties()));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void 非正数超时时校验失败(long timeoutMs) {
        AIModelProperties properties = configuredProperties();
        properties.getVlm().setTimeoutMs(timeoutMs);

        assertThrows(IllegalStateException.class, () -> validate(properties));
    }

    private static AIModelProperties configuredProperties() {
        AIModelProperties properties = new AIModelProperties();
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("qwen-vl-max");
        properties.getVlm().setCandidates(List.of(candidate));
        return properties;
    }

    private static void validate(AIModelProperties properties) {
        new VlmConfigValidator(properties).afterPropertiesSet();
    }
}
