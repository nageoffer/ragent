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

package com.nageoffer.ai.ragent.infra.voice.tts;

import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import com.nageoffer.ai.ragent.infra.voice.websocket.VoiceConnection;
import com.nageoffer.ai.ragent.infra.voice.websocket.VoiceStreamCallback;
import com.nageoffer.ai.ragent.infra.voice.websocket.WebSocketTaskExecutor;
import com.nageoffer.ai.ragent.infra.voice.websocket.WebSocketTaskSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 WebSocket 的 TTS 客户端模板
 */
public abstract class AbstractWebSocketTtsClient<P, C extends VoiceConnection<P, String, byte[]>>
        implements TtsClient, AutoCloseable {

    /**
     * continue-task 单次发送的文本长度上限
     */
    private static final int CHUNK_MAX_LEN = 80;

    private final WebSocketTaskExecutor<P, String, byte[], C> taskExecutor;

    protected AbstractWebSocketTtsClient(Executor executor, AIModelProperties.WebSocketConfig poolConfig) {
        this.taskExecutor = new WebSocketTaskExecutor<>(this::createConnection, poolConfig, executor);
    }

    @Override
    public final StreamCancellationHandle synthesize(String text, TtsCallback callback, ModelTarget target) {
        AtomicBoolean audioReceived = new AtomicBoolean();
        VoiceStreamCallback<byte[]> streamCallback = adaptCallback(callback, audioReceived);
        WebSocketTaskSession<String> session = taskExecutor.openTask(
                target,
                buildTaskParam(target),
                streamCallback,
                () -> !audioReceived.get()
        );
        try {
            // 按协议限制分块发送
            for (String chunk : splitChunks(text)) {
                session.send(chunk);
            }
            session.finish().whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    streamCallback.onError(throwable);
                }
            });
            return session::cancel;
        } catch (RuntimeException exception) {
            session.cancel();
            throw exception;
        }
    }

    /**
     * 按长度上限分块
     */
    private List<String> splitChunks(String text) {
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < text.length(); start += CHUNK_MAX_LEN) {
            String chunk = text.substring(start, Math.min(start + CHUNK_MAX_LEN, text.length()));
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    protected abstract P buildTaskParam(ModelTarget target);

    protected abstract C createConnection(ModelTarget target);

    private VoiceStreamCallback<byte[]> adaptCallback(TtsCallback callback,
                                                       AtomicBoolean audioReceived) {
        return new VoiceStreamCallback<>() {
            @Override
            protected void onValidPacket(byte[] packet) {
                if (packet.length > 0) {
                    audioReceived.set(true);
                }
                callback.onAudio(packet);
            }

            @Override
            protected void onTaskComplete() {
                callback.onComplete();
            }

            @Override
            protected void onTaskError(Throwable throwable) {
                callback.onError(throwable);
            }
        };
    }

    @Override
    public final void close() {
        taskExecutor.close();
    }
}
