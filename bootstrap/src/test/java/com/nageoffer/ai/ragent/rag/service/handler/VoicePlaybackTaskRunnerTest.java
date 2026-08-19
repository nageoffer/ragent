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

import com.nageoffer.ai.ragent.infra.voice.tts.TtsService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoicePlaybackTaskRunnerTest {

    private static final String TEXT = "你好";

    @Test
    void startsTtsThroughPlaybackExecutor() {
        TtsService ttsService = mock(TtsService.class);
        VoicePlaybackEventHandler callback = mock(VoicePlaybackEventHandler.class);
        VoicePlaybackTaskRunner runner = new VoicePlaybackTaskRunner(ttsService, Runnable::run);

        runner.run(TEXT, callback);

        verify(ttsService).synthesize(TEXT, callback, callback);
    }

    @Test
    void skipsTtsWhenTaskWasCancelled() {
        TtsService ttsService = mock(TtsService.class);
        VoicePlaybackEventHandler callback = mock(VoicePlaybackEventHandler.class);
        when(callback.isCancelled()).thenReturn(true);
        VoicePlaybackTaskRunner runner = new VoicePlaybackTaskRunner(ttsService, Runnable::run);

        runner.run(TEXT, callback);

        verify(ttsService, never()).synthesize(TEXT, callback, callback);
    }

    @Test
    void delegatesStartFailureToCallback() {
        TtsService ttsService = mock(TtsService.class);
        VoicePlaybackEventHandler callback = mock(VoicePlaybackEventHandler.class);
        RuntimeException failure = new RuntimeException("failed");
        doThrow(failure).when(ttsService).synthesize(TEXT, callback, callback);
        VoicePlaybackTaskRunner runner = new VoicePlaybackTaskRunner(ttsService, Runnable::run);

        runner.run(TEXT, callback);

        verify(callback).onStartFailure(failure);
    }

    @Test
    void delegatesExecutorRejectionToCallback() {
        TtsService ttsService = mock(TtsService.class);
        VoicePlaybackEventHandler callback = mock(VoicePlaybackEventHandler.class);
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("busy");
        };
        VoicePlaybackTaskRunner runner = new VoicePlaybackTaskRunner(ttsService, rejectingExecutor);

        runner.run(TEXT, callback);

        verify(callback).onRejected(any(RejectedExecutionException.class));
    }
}
