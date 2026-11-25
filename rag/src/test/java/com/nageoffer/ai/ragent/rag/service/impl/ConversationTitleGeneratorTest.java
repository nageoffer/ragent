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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationTitleGeneratorTest {

    private LLMService llmService;
    private PromptTemplateLoader promptTemplateLoader;
    private ConversationTitleGenerator generator;

    @BeforeEach
    void setUp() {
        llmService = mock(LLMService.class);
        promptTemplateLoader = mock(PromptTemplateLoader.class);
        when(promptTemplateLoader.render(anyString(), anyMap())).thenReturn("prompt");
        generator = new ConversationTitleGenerator(new MemoryProperties(), promptTemplateLoader, llmService);
    }

    @Test
    void shouldReturnLlmTitleWhenGenerationSucceeds() {
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn("年假计算和入职时长");

        assertThat(generator.generate("年假怎么计算？")).isEqualTo("年假计算和入职时长");
    }

    @Test
    void shouldReturnNullInsteadOfFallbackTitleWhenLlmFails() {
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenThrow(new IllegalStateException("LLM 不可用"));

        // 失败时由调用方保留占位标题，生成器不再返回"新对话"兜底值
        assertThat(generator.generate("年假怎么计算？")).isNull();
    }
}
