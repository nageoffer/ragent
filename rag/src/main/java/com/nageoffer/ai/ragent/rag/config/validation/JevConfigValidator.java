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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Jev 意图识别配置完整性校验（纯逻辑，不依赖 Spring，便于单测）
 * 报错只描述缺什么，配置项路径交给 {@link JevConfigFailureAnalyzer} 在修复建议里给
 */
public final class JevConfigValidator {

    private static final String INTENT_TYPE_KEY = "rag.intent.type";

    private static final String JEV_PREFIX = "ai.jev";

    /**
     * 一处配置缺口
     *
     * @param problem 缺什么，自然语言
     * @param action  怎么补，带配置项路径
     */
    public record Violation(String problem, String action) {
    }

    /**
     * @param property 按 key 取配置值，取不到返回 null
     */
    public static List<Violation> validate(Function<String, String> property) {
        if (!"jev".equalsIgnoreCase(trim(property.apply(INTENT_TYPE_KEY)))) {
            return List.of();
        }

        List<Violation> violations = new ArrayList<>();
        String modelId = trim(property.apply(JEV_PREFIX + ".default-model"));
        if (modelId.isEmpty()) {
            violations.add(new Violation("没有指定要使用哪个 Jev 模型",
                    "设 " + JEV_PREFIX + ".default-model，取值为 candidates 里某一项的 id"));
            return violations;
        }

        int index = indexOfCandidate(property, modelId);
        if (index < 0) {
            violations.add(new Violation("指定使用的 Jev 模型「" + modelId + "」没有对应的配置",
                    "在 " + JEV_PREFIX + ".candidates 下补一项 id=" + modelId + " 的候选"));
            return violations;
        }

        String candidate = JEV_PREFIX + ".candidates[" + index + "]";
        if ("false".equalsIgnoreCase(trim(property.apply(candidate + ".enabled")))) {
            violations.add(new Violation("指定使用的 Jev 模型「" + modelId + "」处于停用状态",
                    "把 " + candidate + ".enabled 设为 true，或改用别的模型"));
        }
        if (trim(property.apply(candidate + ".model")).isEmpty()) {
            violations.add(new Violation("Jev 模型「" + modelId + "」没有填写上游的模型标识",
                    "设 " + candidate + ".model，如 typesafe/jev-1.14"));
        }

        String providerName = trim(property.apply(candidate + ".provider"));
        if (providerName.isEmpty()) {
            violations.add(new Violation("Jev 模型「" + modelId + "」没有指定供应商",
                    "设 " + candidate + ".provider，取值为 ai.providers 下的某个名字"));
            return violations;
        }

        String provider = "ai.providers." + providerName;
        if (trim(property.apply(provider + ".url")).isEmpty()) {
            violations.add(new Violation("调用 Jev 的供应商「" + providerName + "」没有配置服务地址",
                    "设 " + provider + ".url"));
        }
        if (trim(property.apply(provider + ".endpoints.jev")).isEmpty()) {
            violations.add(new Violation("调用 Jev 的供应商「" + providerName + "」没有配置结构化评估接口路径",
                    "设 " + provider + ".endpoints.jev，ZenMux 为 /api/v1/systemone"));
        }
        if (trim(property.apply(provider + ".api-key")).isEmpty()) {
            violations.add(new Violation("调用 Jev 的供应商「" + providerName + "」没有配置访问密钥",
                    "设 " + provider + ".api-key，或为它的占位环境变量赋值"));
        }
        return violations;
    }

    /**
     * candidates 是列表，只能按下标逐个试探到没有 id 为止
     */
    private static int indexOfCandidate(Function<String, String> property, String modelId) {
        for (int i = 0; ; i++) {
            String id = property.apply(JEV_PREFIX + ".candidates[" + i + "].id");
            if (id == null) {
                return -1;
            }
            if (modelId.equals(id.trim())) {
                return i;
            }
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
