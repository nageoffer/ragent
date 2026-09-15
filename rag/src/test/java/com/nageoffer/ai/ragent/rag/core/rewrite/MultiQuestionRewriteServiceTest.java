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

package com.nageoffer.ai.ragent.rag.core.rewrite;

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultiQuestionRewriteServiceTest {

    private static final String QUESTION = "微服务架构和单体架构有什么区别";

    private LLMService llmService;
    private MultiQuestionRewriteService service;

    @BeforeEach
    void setUp() {
        llmService = mock(LLMService.class);
        RAGConfigProperties ragConfigProperties = mock(RAGConfigProperties.class);
        QueryTermMappingService queryTermMappingService = mock(QueryTermMappingService.class);
        PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);

        when(ragConfigProperties.getQueryRewriteEnabled()).thenReturn(true);
        when(queryTermMappingService.normalize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(promptTemplateLoader.load(anyString())).thenReturn("rewrite prompt");

        service = new MultiQuestionRewriteService(
                llmService, ragConfigProperties, queryTermMappingService, promptTemplateLoader);
    }

    @Test
    void shouldUseRewriteOnlyWhenShouldSplitIsFalse() {
        RewriteResult result = rewriteWithResponse("""
                {
                  "rewrite": "微服务架构和单体架构有什么区别",
                  "should_split": false,
                  "sub_questions": ["什么是微服务架构", "什么是单体架构"]
                }
                """);

        assertEquals(QUESTION, result.rewrittenQuestion());
        assertEquals(List.of(QUESTION), result.subQuestions());
    }

    @Test
    void shouldUseCleanedSubQuestionsWhenShouldSplitIsTrue() {
        RewriteResult result = rewriteWithResponse("""
                {
                  "rewrite": "微服务架构和单体架构有什么区别",
                  "should_split": true,
                  "sub_questions": [" 什么是微服务架构 ", "", 42, "什么是单体架构"]
                }
                """);

        assertEquals(List.of("什么是微服务架构", "什么是单体架构"), result.subQuestions());
    }

    @Test
    void shouldUseRewriteWhenShouldSplitIsTrueButSubQuestionsAreEmpty() {
        RewriteResult result = rewriteWithResponse("""
                {
                  "rewrite": "微服务架构和单体架构有什么区别",
                  "should_split": true,
                  "sub_questions": ["  ", 42]
                }
                """);

        assertEquals(List.of(QUESTION), result.subQuestions());
    }

    @Test
    void shouldKeepExistingBehaviorWhenShouldSplitIsMissing() {
        RewriteResult result = rewriteWithResponse("""
                {
                  "rewrite": "微服务架构和单体架构有什么区别",
                  "sub_questions": ["什么是微服务架构", "什么是单体架构"]
                }
                """);

        assertEquals(List.of("什么是微服务架构", "什么是单体架构"), result.subQuestions());
    }

    @Test
    void shouldKeepExistingBehaviorWhenShouldSplitHasWrongType() {
        RewriteResult result = rewriteWithResponse("""
                {
                  "rewrite": "微服务架构和单体架构有什么区别",
                  "should_split": "false",
                  "sub_questions": ["什么是微服务架构", "什么是单体架构"]
                }
                """);

        assertEquals(List.of("什么是微服务架构", "什么是单体架构"), result.subQuestions());
    }

    private RewriteResult rewriteWithResponse(String response) {
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn(response);
        return service.rewriteWithSplit(QUESTION);
    }
}
