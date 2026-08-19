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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 音频流首包探测桥接器
 */
final class BinaryProbeStreamBridge implements TtsCallback {

    private enum Disposition {
        PENDING,
        COMMITTED,
        DISCARDED
    }

    private final TtsCallback downstream;
    private final CompletableFuture<ProbeResult> probe = new CompletableFuture<>();
    private final Object lock = new Object();
    private final List<Runnable> buffer = new ArrayList<>();
    private Disposition disposition = Disposition.PENDING;
    private boolean terminated;

    BinaryProbeStreamBridge(TtsCallback downstream) {
        this.downstream = downstream;
    }

    @Override
    public void onAudio(byte[] audio) {
        if (audio.length == 0) {
            return;
        }
        accept(ProbeResult.success(), false, () -> downstream.onAudio(audio));
    }

    @Override
    public void onComplete() {
        accept(ProbeResult.noContent(), true, downstream::onComplete);
    }

    @Override
    public void onError(Throwable throwable) {
        accept(ProbeResult.error(throwable), true, () -> downstream.onError(throwable));
    }

    ProbeResult awaitFirstAudio(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            return probe.get(timeout, unit);
        } catch (TimeoutException exception) {
            return ProbeResult.timeout();
        } catch (ExecutionException exception) {
            return ProbeResult.error(exception.getCause());
        }
    }

    void commit() {
        synchronized (lock) {
            disposition = Disposition.COMMITTED;
            buffer.forEach(Runnable::run);
            buffer.clear();
        }
    }

    void discard() {
        synchronized (lock) {
            disposition = Disposition.DISCARDED;
            buffer.clear();
        }
    }

    private void accept(ProbeResult result, boolean terminal, Runnable action) {
        synchronized (lock) {
            if (terminated || disposition == Disposition.DISCARDED) {
                return;
            }
            terminated = terminal;
            probe.complete(result);
            if (disposition == Disposition.PENDING) {
                buffer.add(action);
                return;
            }
            action.run();
        }
    }

    static final class ProbeResult {

        enum Type {
            SUCCESS,
            ERROR,
            TIMEOUT,
            NO_CONTENT
        }

        private final Type type;
        private final Throwable error;

        private ProbeResult(Type type, Throwable error) {
            this.type = type;
            this.error = error;
        }

        Type getType() {
            return type;
        }

        Throwable getError() {
            return error;
        }

        boolean isSuccess() {
            return type == Type.SUCCESS;
        }

        private static ProbeResult success() {
            return new ProbeResult(Type.SUCCESS, null);
        }

        private static ProbeResult error(Throwable throwable) {
            return new ProbeResult(Type.ERROR, throwable);
        }

        private static ProbeResult timeout() {
            return new ProbeResult(Type.TIMEOUT, null);
        }

        private static ProbeResult noContent() {
            return new ProbeResult(Type.NO_CONTENT, null);
        }
    }
}
