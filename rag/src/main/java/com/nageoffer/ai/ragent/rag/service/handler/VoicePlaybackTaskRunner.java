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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 在线程池中启动语音播放任务
 */
@Slf4j
@Component
public class VoicePlaybackTaskRunner {

    private final TtsService ttsService;
    private final Executor voicePlaybackExecutor;

    public VoicePlaybackTaskRunner(TtsService ttsService,
                                   @Qualifier("voicePlaybackExecutor") Executor voicePlaybackExecutor) {
        this.ttsService = ttsService;
        this.voicePlaybackExecutor = voicePlaybackExecutor;
    }

    public void run(String text, VoicePlaybackEventHandler callback) {
        try {
            voicePlaybackExecutor.execute(() -> synthesize(text, callback));
        } catch (RejectedExecutionException exception) {
            callback.onRejected(exception);
        }
    }

    private void synthesize(String text, VoicePlaybackEventHandler callback) {
        if (callback.isCancelled()) {
            return;
        }
        try {
            ttsService.synthesize(text, callback, callback);
            log.info("播放任务已启动，taskId={}", callback.taskId());
        } catch (RuntimeException exception) {
            callback.onStartFailure(exception);
        }
    }
}
