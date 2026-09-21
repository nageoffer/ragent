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

import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StreamChatEventHandlerTest {

    private static final String PUBLIC_ERROR_MESSAGE = "生成失败，请稍后重试";

    @Mock
    private SseEmitter emitter;

    @Mock
    private AIModelProperties modelProperties;

    @Mock
    private ConversationMemoryService memoryService;

    @Mock
    private ConversationGroupService conversationGroupService;

    @Mock
    private StreamTaskManager taskManager;

    @Test
    void businessFailureShouldSendSafeErrorEventAndCompleteNormally() throws Exception {
        StreamChatEventHandler handler = new StreamChatEventHandler(StreamChatHandlerParams.builder()
                .emitter(emitter)
                .conversationId("conversation-1")
                .taskId("task-1")
                .modelProperties(modelProperties)
                .memoryService(memoryService)
                .conversationGroupService(conversationGroupService)
                .taskManager(taskManager)
                .build());

        handler.onError(new IllegalStateException("database password leaked"));

        ArgumentCaptor<SseEmitter.SseEventBuilder> eventCaptor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(2)).send(eventCaptor.capture());
        List<Object> errorEventData = eventCaptor.getAllValues().get(1).build().stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .toList();
        assertTrue(errorEventData.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(data -> data.startsWith("event:error\n")));
        Object payload = errorEventData.stream()
                .filter(data -> !(data instanceof String))
                .findFirst()
                .orElseThrow();
        assertEquals(PUBLIC_ERROR_MESSAGE, ReflectionTestUtils.getField(payload, "error"));
        assertFalse(payload.toString().contains("database password leaked"));
        verify(taskManager).unregister("task-1");
        verify(emitter).complete();
        verify(emitter, never()).completeWithError(any());
    }
}
