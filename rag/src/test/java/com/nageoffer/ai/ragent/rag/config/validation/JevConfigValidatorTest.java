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

package com.nageoffer.ai.ragent.rag.config.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jev 配置校验契约
 * <p>
 * 只在选用 Jev 时校验：llm 部署允许整块配置缺失
 */
class JevConfigValidatorTest {

    @Test
    @DisplayName("llm 模式不校验 Jev 配置，整块缺失也能启动")
    void skipsValidationWhenStrategyIsLlm() {
        assertThat(JevConfigValidator.validate(Map.of("rag.intent.type", "llm")::get)).isEmpty();
        assertThat(JevConfigValidator.validate(new HashMap<String, String>()::get)).isEmpty();
    }

    @Test
    @DisplayName("配置齐全时无缺口")
    void acceptsCompleteConfiguration() {
        assertThat(JevConfigValidator.validate(complete()::get)).isEmpty();
    }

    @Test
    @DisplayName("逐项缺失都报出来，缺口只说缺什么，配置项路径放在修复建议里")
    void reportsEachMissingPiece() {
        assertProblem(config -> config.remove("ai.jev.default-model"), "没有指定要使用哪个 Jev 模型");
        assertProblem(config -> config.put("ai.jev.default-model", "missing"), "「missing」没有对应的配置");
        assertProblem(config -> config.put("ai.jev.candidates[0].enabled", "false"), "处于停用状态");
        assertProblem(config -> config.remove("ai.jev.candidates[0].model"), "没有填写上游的模型标识");
        assertProblem(config -> config.remove("ai.providers.zenmux.url"), "没有配置服务地址");
        assertProblem(config -> config.remove("ai.providers.zenmux.endpoints.jev"), "没有配置结构化评估接口路径");
        assertProblem(config -> config.remove("ai.providers.zenmux.api-key"), "没有配置访问密钥");
    }

    @Test
    @DisplayName("一次报全部缺口，不是撞一个停一个")
    void reportsAllGapsAtOnce() {
        Map<String, String> config = complete();
        config.remove("ai.providers.zenmux.url");
        config.remove("ai.providers.zenmux.api-key");

        assertThat(JevConfigValidator.validate(config::get)).hasSize(2);
    }

    @Test
    @DisplayName("密钥占位符没被赋值时等同未配置")
    void treatsBlankApiKeyAsMissing() {
        Map<String, String> config = complete();
        config.put("ai.providers.zenmux.api-key", "   ");

        assertThat(JevConfigValidator.validate(config::get))
                .extracting(JevConfigValidator.Violation::problem)
                .anyMatch(problem -> problem.contains("没有配置访问密钥"));
    }

    private void assertProblem(Consumer<Map<String, String>> breaker, String expected) {
        Map<String, String> config = complete();
        breaker.accept(config);
        List<JevConfigValidator.Violation> violations = JevConfigValidator.validate(config::get);

        assertThat(violations).as("改坏后应报出缺口：%s", expected).isNotEmpty();
        assertThat(violations).extracting(JevConfigValidator.Violation::problem)
                .as("期望包含「%s」", expected)
                .anyMatch(problem -> problem.contains(expected));
        assertThat(violations).allSatisfy(violation -> assertThat(violation.problem())
                .as("缺口描述不该出现配置项路径")
                .doesNotContain("ai.jev").doesNotContain("ai.providers"));
    }

    private static Map<String, String> complete() {
        Map<String, String> config = new HashMap<>();
        config.put("rag.intent.type", "jev");
        config.put("ai.jev.default-model", "jev-stable");
        config.put("ai.jev.candidates[0].id", "jev-stable");
        config.put("ai.jev.candidates[0].model", "typesafe/jev-1.14");
        config.put("ai.jev.candidates[0].provider", "zenmux");
        config.put("ai.providers.zenmux.url", "https://zenmux.ai");
        config.put("ai.providers.zenmux.endpoints.jev", "/api/v1/systemone");
        config.put("ai.providers.zenmux.api-key", "sk-test");
        return config;
    }
}
