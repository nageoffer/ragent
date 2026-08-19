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

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPool;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket 连接租约
 */
@Slf4j
public final class WebSocketConnectionLease<C extends VoiceConnection<?, ?, ?>> implements AutoCloseable {

    private final GenericObjectPool<C> pool;
    private final C connection;
    private final AtomicBoolean invalidated = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    WebSocketConnectionLease(GenericObjectPool<C> pool, C connection) {
        this.pool = pool;
        this.connection = connection;
    }

    public C connection() {
        return connection;
    }

    public void invalidate() {
        invalidated.set(true);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!invalidated.get()) {
                pool.returnObject(connection);
            } else {
                pool.invalidateObject(connection);
            }
        } catch (Exception exception) {
            log.warn("Voice 连接释放失败，modelId={}", connection.modelId(), exception);
        }
    }
}
