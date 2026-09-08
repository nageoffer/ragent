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

package com.nageoffer.ai.ragent.infra.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 模型提供商枚举
 * 统一管理提供商名称，避免散落的字符串常量
 */
@Getter
@RequiredArgsConstructor
public enum ModelProvider {

    /**
     * Ollama 本地模型服务
     */
    OLLAMA("ollama"),

    /**
     * 阿里云百炼大模型平台
     */
    BAI_LIAN("bailian"),

    /**
     * 硅基流动 AI 模型服务
     */
    SILICON_FLOW("siliconflow"),

    /**
     * 推理时代 AI 模型服务
     */
    AI_HUB_MIX("aihubmix"),

    /**
     * LiteLLM gateway (OpenAI-compatible proxy for 100+ providers)
     */
    LITE_LLM("litellm"),

    /**
     * 空实现，用于测试或占位
     */
    NOOP("noop");

    private final String id;

    public boolean matches(String provider) {
        return provider != null && provider.equalsIgnoreCase(id);
    }
}
