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
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class VoicePlaybackEventHandlerTest {

    private static final String TASK_ID = "task-1";

    @Test
    void sendsAudioAndCompletesThroughSse() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        StreamTaskManager taskManager = mock(StreamTaskManager.class);
        VoicePlaybackEventHandler handler = new VoicePlaybackEventHandler(emitter, TASK_ID, taskManager);

        handler.onAudio(new byte[]{1, 2});
        handler.onComplete();

        verify(taskManager).register(any(String.class), any(Runnable.class));
        verify(emitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));
        verify(taskManager).unregister(TASK_ID);
        verify(emitter).complete();
    }

    @Test
    void reportsOnlyFirstError() {
        SseEmitter emitter = mock(SseEmitter.class);
        StreamTaskManager taskManager = mock(StreamTaskManager.class);
        VoicePlaybackEventHandler handler = new VoicePlaybackEventHandler(emitter, TASK_ID, taskManager);
        RuntimeException failure = new RuntimeException("failed");

        handler.onError(failure);
        handler.onError(new RuntimeException("duplicate"));

        verify(taskManager).unregister(TASK_ID);
        verify(emitter).completeWithError(failure);
    }

    @Test
    void cancelsTaskWhenEmitterCompletes() {
        SseEmitter emitter = mock(SseEmitter.class);
        StreamTaskManager taskManager = mock(StreamTaskManager.class);
        new VoicePlaybackEventHandler(emitter, TASK_ID, taskManager);
        ArgumentCaptor<Runnable> callbacks = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter, times(2)).onCompletion(callbacks.capture());

        List<Runnable> completionCallbacks = callbacks.getAllValues();
        completionCallbacks.get(1).run();

        verify(taskManager).cancel(TASK_ID);
        verify(taskManager, never()).unregister(TASK_ID);
    }

    @Test
    void bindsProviderCancellationHandleToTask() {
        SseEmitter emitter = mock(SseEmitter.class);
        StreamTaskManager taskManager = mock(StreamTaskManager.class);
        StreamCancellationHandle providerHandle = mock(StreamCancellationHandle.class);
        VoicePlaybackEventHandler handler = new VoicePlaybackEventHandler(emitter, TASK_ID, taskManager);
        ArgumentCaptor<Runnable> handle = ArgumentCaptor.forClass(Runnable.class);

        handler.onTaskStarted(providerHandle);
        verify(taskManager).bindHandle(eq(TASK_ID), handle.capture());
        handle.getValue().run();

        verify(providerHandle).cancel();
    }
}
