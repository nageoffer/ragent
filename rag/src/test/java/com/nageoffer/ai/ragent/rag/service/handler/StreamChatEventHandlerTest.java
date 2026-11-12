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

package com.nageoffer.ai.ragent.rag.service.handler;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamChatEventHandlerTest {

    private static final String USER_ID = "u-1";

    @BeforeEach
    void setUp() {
        UserContext.set(LoginUser.builder().userId(USER_ID).username("tester").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldPreserveWhitespaceChunksInPersistedAnswerAndReasoning() {
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        StreamTaskManager taskManager = mock(StreamTaskManager.class);
        StreamChatEventHandler handler = new StreamChatEventHandler(StreamChatHandlerParams.builder()
                .emitter(mock(SseEmitter.class))
                .conversationId("c-1")
                .taskId("t-1")
                .modelProperties(new AIModelProperties())
                .memoryService(memoryService)
                .conversationGroupService(mock(ConversationGroupService.class))
                .taskManager(taskManager)
                .build());

        handler.onThinking("推理一");
        handler.onThinking("\n\n");
        handler.onThinking("推理二");
        handler.onContent("正文一");
        handler.onContent("\n\n");
        handler.onContent("正文二");
        handler.onComplete();

        ArgumentCaptor<ChatMessage> message = ArgumentCaptor.forClass(ChatMessage.class);
        verify(memoryService).append(eq("c-1"), eq(USER_ID), message.capture());
        assertThat(message.getValue().getContent()).isEqualTo("正文一\n\n正文二");
        assertThat(message.getValue().getThinkingContent()).isEqualTo("推理一\n\n推理二");
    }

    @Test
    void shouldStillFinishCancelledStreamWhenTitleLookupFails() throws Exception {
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        StreamTaskManager taskManager = mock(StreamTaskManager.class);
        ConversationGroupService conversationGroupService = mock(ConversationGroupService.class);
        // 构造时查一次标题判定是否下发，收尾时再查一次并模拟数据库异常
        when(conversationGroupService.findConversation("c-1", USER_ID))
                .thenReturn(null)
                .thenThrow(new IllegalStateException("标题查询失败"));
        SseEmitter emitter = mock(SseEmitter.class);

        StreamChatEventHandler handler = new StreamChatEventHandler(StreamChatHandlerParams.builder()
                .emitter(emitter)
                .conversationId("c-1")
                .taskId("t-1")
                .modelProperties(new AIModelProperties())
                .memoryService(memoryService)
                .conversationGroupService(conversationGroupService)
                .taskManager(taskManager)
                .build());

        ArgumentCaptor<Runnable> finalizer = ArgumentCaptor.forClass(Runnable.class);
        verify(taskManager).register(eq("t-1"), eq(USER_ID), finalizer.capture());

        handler.onContent("已生成的内容");
        clearInvocations(emitter);

        // 标题查询失败只能降级，不能把 CANCEL/DONE 和断流一起带走
        finalizer.getValue().run();

        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        ArgumentCaptor<ChatMessage> interrupted = ArgumentCaptor.forClass(ChatMessage.class);
        verify(memoryService).append(eq("c-1"), eq(USER_ID), interrupted.capture());
        assertThat(interrupted.getValue().getContent()).isEqualTo("已生成的内容");
        assertThat(interrupted.getValue().getMessageStatus()).isEqualTo(ChatMessage.MessageStatus.INTERRUPTED);
    }
}
