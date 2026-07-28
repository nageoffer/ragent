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

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiQuestionRewriteServiceTest {

    @Mock
    private LLMService llmService;

    @Mock
    private RAGConfigProperties ragConfigProperties;

    @Mock
    private QueryTermMappingService queryTermMappingService;

    @Mock
    private PromptTemplateLoader promptTemplateLoader;

    private MultiQuestionRewriteService rewriteService;

    @BeforeEach
    void setUp() {
        rewriteService = new MultiQuestionRewriteService(
                llmService,
                ragConfigProperties,
                queryTermMappingService,
                promptTemplateLoader
        );
    }

    @Test
    void keepsLastFourDialogueMessagesWhenSummaryIsPresent() {
        when(ragConfigProperties.getQueryRewriteEnabled()).thenReturn(true);
        when(queryTermMappingService.normalize("继续说说它")).thenReturn("继续说说它");
        when(promptTemplateLoader.load(anyString())).thenReturn("rewrite prompt");
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST)))
                .thenReturn("{\"rewrite\":\"继续说明 RAG\",\"sub_questions\":[\"继续说明 RAG\"]}");

        List<ChatMessage> history = List.of(
                ChatMessage.system("conversation summary"),
                ChatMessage.user("user-1"),
                ChatMessage.assistant("assistant-1"),
                ChatMessage.user("user-2"),
                ChatMessage.assistant("assistant-2"),
                ChatMessage.user("user-3"),
                ChatMessage.assistant("assistant-3")
        );

        rewriteService.rewriteWithSplit("继续说说它", history);

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmService).chat(requestCaptor.capture(), eq(Tier.FAST));
        List<ChatMessage> messages = requestCaptor.getValue().getMessages();

        assertEquals(
                List.of("rewrite prompt", "user-2", "assistant-2", "user-3", "assistant-3", "继续说说它"),
                messages.stream().map(ChatMessage::getContent).toList()
        );
        assertEquals(
                List.of(
                        ChatMessage.Role.SYSTEM,
                        ChatMessage.Role.USER,
                        ChatMessage.Role.ASSISTANT,
                        ChatMessage.Role.USER,
                        ChatMessage.Role.ASSISTANT,
                        ChatMessage.Role.USER
                ),
                messages.stream().map(ChatMessage::getRole).toList()
        );
    }
}
