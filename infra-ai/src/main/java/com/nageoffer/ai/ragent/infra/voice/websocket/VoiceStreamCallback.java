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

package com.nageoffer.ai.ragent.infra.voice.websocket;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Voice 流式回调
 */
public abstract class VoiceStreamCallback<E> {

    private final AtomicBoolean terminated = new AtomicBoolean();

    /**
     * 接收业务数据包
     */
    public final void onPacket(E packet) {
        if (terminated.get()) {
            return;
        }
        onValidPacket(packet);
    }

    /**
     * 任务正常完成
     */
    public final void onComplete() {
        if (terminated.compareAndSet(false, true)) {
            onTaskComplete();
        }
    }

    /**
     * 任务失败
     */
    public final void onError(Throwable throwable) {
        if (terminated.compareAndSet(false, true)) {
            onTaskError(throwable);
        }
    }

    /**
     * 接收有效数据包
     */
    protected void onValidPacket(E packet) {
    }

    /**
     * 处理任务完成
     */
    protected void onTaskComplete() {
    }

    /**
     * 处理任务失败
     */
    protected void onTaskError(Throwable throwable) {
    }
}
