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

import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.time.Duration;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 按 modelId 管理 Voice WebSocket 连接池
 */
public final class WebSocketExecutor<C extends VoiceConnection<?, ?, ?>> implements AutoCloseable {

    private final Function<ModelTarget, C> connectionFactory;
    private final AIModelProperties.WebSocketConfig config;
    private final Map<String, GenericObjectPool<C>> poolsByModelId = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public WebSocketExecutor(Function<ModelTarget, C> connectionFactory,
                      AIModelProperties.WebSocketConfig config) {
        this.connectionFactory = connectionFactory;
        this.config = config;
    }

    /**
     * 从模型连接池借用空闲 WebSocket
     */
    public WebSocketConnectionLease<C> acquire(ModelTarget target) {
        if (closed.get()) {
            throw new IllegalStateException("Voice 连接池已关闭");
        }
        String modelId = target.id();

        GenericObjectPool<C> pool = poolsByModelId.computeIfAbsent(modelId, ignored -> createPool(target));
        try {
            C connection = pool.borrowObject();
            return new WebSocketConnectionLease<>(pool, connection);
        } catch (NoSuchElementException exception) {
            Throwable cause = exception.getCause();
            // Commons Pool 仅在创建连接失败时保留 cause
            if (cause == null) {
                throw new ModelClientException("Voice 连接池无可用连接，modelId=" + modelId,
                        ModelClientErrorType.RATE_LIMITED, null, exception);
            }
            if (cause instanceof ModelClientException modelClientException) {
                throw modelClientException;
            }
            throw new ModelClientException("Voice 连接创建失败，modelId=" + modelId,
                    ModelClientErrorType.NETWORK_ERROR, null, cause);
        } catch (Exception exception) {
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RemoteException("Voice 连接借用失败，modelId=" + modelId,
                    exception, BaseErrorCode.REMOTE_ERROR);
        }
    }

    private GenericObjectPool<C> createPool(ModelTarget target) {
        GenericObjectPoolConfig<C> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(config.getMaxTotalPerModel());
        poolConfig.setMaxIdle(config.getMaxIdlePerModel());
        poolConfig.setBlockWhenExhausted(false);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        if (config.getIdleTimeoutMs() > 0) {
            poolConfig.setMinEvictableIdleDuration(Duration.ofMillis(config.getIdleTimeoutMs()));
            poolConfig.setTimeBetweenEvictionRuns(Duration.ofMillis(config.getEvictionIntervalMs()));
            // -1 表示每轮检查全部空闲连接
            poolConfig.setNumTestsPerEvictionRun(-1);
        }

        return new GenericObjectPool<>(new BasePooledObjectFactory<>() {
            @Override
            public C create() throws Exception {
                C connection = connectionFactory.apply(target);
                try {
                    connection.connect();
                    return connection;
                } catch (Exception exception) {
                    closeQuietly(connection);
                    throw exception;
                }
            }

            @Override
            public PooledObject<C> wrap(C connection) {
                return new DefaultPooledObject<>(connection);
            }

            @Override
            public boolean validateObject(PooledObject<C> pooledObject) {
                return pooledObject.getObject().isReusable();
            }

            @Override
            public void destroyObject(PooledObject<C> pooledObject) {
                closeQuietly(pooledObject.getObject());
            }
        }, poolConfig);
    }

    private void closeQuietly(C connection) {
        try {
            connection.close();
        } catch (RuntimeException ignored) {
            // 销毁失败不应阻塞连接池关闭
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        poolsByModelId.values().forEach(GenericObjectPool::close);
        poolsByModelId.clear();
    }
}
