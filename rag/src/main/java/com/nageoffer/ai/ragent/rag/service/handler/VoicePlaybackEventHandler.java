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

import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.infra.voice.tts.TtsCallback;
import com.nageoffer.ai.ragent.infra.voice.tts.TtsTaskObserver;
import com.nageoffer.ai.ragent.rag.dto.AudioFramePayload;
import com.nageoffer.ai.ragent.rag.dto.AudioMetaPayload;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 语音播放 SSE 事件处理器
 */
@Slf4j
public class VoicePlaybackEventHandler implements TtsCallback, TtsTaskObserver {

    private final String taskId;
    private final SseEmitterSender sender;
    private final StreamTaskManager taskManager;
    private final AtomicBoolean terminated = new AtomicBoolean();
    private final AtomicBoolean firstFrameLogged = new AtomicBoolean();

    public VoicePlaybackEventHandler(SseEmitter emitter, String taskId, StreamTaskManager taskManager) {
        this.taskId = taskId;
        this.sender = new SseEmitterSender(emitter);
        this.taskManager = taskManager;
        initialize(emitter);
    }

    private void initialize(SseEmitter emitter) {
        taskManager.register(taskId, () -> {
            sender.sendEvent(SSEEventType.CANCEL.value(), new CompletionPayload(null, null));
            sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
            sender.complete();
        });
        bindEmitterCancellation(emitter);
        sender.sendEvent(SSEEventType.AUDIO_META.value(), new AudioMetaPayload(taskId));
        log.info("播放任务发起，taskId={}", taskId);
    }

    @Override
    public void onTaskStarted(StreamCancellationHandle handle) {
        taskManager.bindHandle(taskId, wrapCancellationHandle(handle)::cancel);
    }

    @Override
    public boolean isCancelled() {
        return taskManager.isCancelled(taskId);
    }

    String taskId() {
        return taskId;
    }

    @Override
    public void onAudio(byte[] audio) {
        if (terminated.get() || isCancelled()) {
            return;
        }
        if (firstFrameLogged.compareAndSet(false, true)) {
            log.info("播放任务首帧下发，taskId={}", taskId);
        }
        sender.sendEvent(SSEEventType.AUDIO.value(), new AudioFramePayload(
                Base64.getEncoder().encodeToString(audio)));
    }

    @Override
    public void onComplete() {
        if (isCancelled() || !terminated.compareAndSet(false, true)) {
            return;
        }
        log.info("播放任务完成，taskId={}", taskId);
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        taskManager.unregister(taskId);
        sender.complete();
    }

    @Override
    public void onError(Throwable throwable) {
        terminateWithError("播放任务失败，taskId={}", throwable);
    }

    public void onStartFailure(Throwable throwable) {
        if (isCancelled()) {
            log.info("播放任务已取消，忽略启动异常，taskId={}", taskId);
            taskManager.unregister(taskId);
            return;
        }
        terminateWithError("播放任务启动失败，taskId={}", throwable);
    }

    public void onRejected(Throwable throwable) {
        terminateWithError("播放任务线程池拒绝，taskId={}", throwable);
    }

    private void terminateWithError(String message, Throwable throwable) {
        if (isCancelled() || !terminated.compareAndSet(false, true)) {
            return;
        }
        log.error(message, taskId, throwable);
        taskManager.unregister(taskId);
        sender.fail(throwable);
    }

    private void bindEmitterCancellation(SseEmitter emitter) {
        Runnable cancel = () -> {
            if (terminated.compareAndSet(false, true) && !isCancelled()) {
                taskManager.cancel(taskId);
            }
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(ignored -> cancel.run());
    }

    private StreamCancellationHandle wrapCancellationHandle(StreamCancellationHandle handle) {
        // 取消指令与已在途的 finish-task 存在竞态，停止后不复用当前连接
        return () -> {
            try {
                handle.cancel();
                log.info("播放任务已取消，taskId={}", taskId);
            } catch (RuntimeException exception) {
                log.warn("播放任务取消失败，taskId={}", taskId, exception);
            }
        };
    }
}
