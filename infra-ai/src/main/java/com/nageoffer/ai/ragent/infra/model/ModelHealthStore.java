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

package com.nageoffer.ai.ragent.infra.model;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型健康状态存储器
 * 用于管理和跟踪各个 AI 模型的健康状况，实现断路器模式
 */
@Component
@RequiredArgsConstructor
public class ModelHealthStore {

    private final AIModelProperties properties;

    private final Map<String, ModelHealth> healthById = new ConcurrentHashMap<>();

    private final AtomicLong probeTokenSeq = new AtomicLong();

    /**
     * 模型调用许可，halfOpenToken 为 0 时不持有半开探测名额
     */
    public record CallPermit(String modelId, long halfOpenToken) {
    }

    public boolean isUnavailable(String id) {
        ModelHealth health = healthById.get(id);
        if (health == null) {
            return false;
        }
        if (health.state == State.OPEN && health.openUntil > System.currentTimeMillis()) {
            return true;
        }
        return health.state == State.HALF_OPEN && health.halfOpenInFlight;
    }

    /**
     * 返回 null 表示拒绝调用
     */
    public CallPermit allowCall(String id) {
        if (id == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        AtomicReference<CallPermit> granted = new AtomicReference<>();
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                v = new ModelHealth();
            }
            if (v.state == State.OPEN) {
                if (v.openUntil > now) {
                    return v;
                }
                v.state = State.HALF_OPEN;
                v.halfOpenInFlight = true;
                v.halfOpenToken = probeTokenSeq.incrementAndGet();
                granted.set(new CallPermit(id, v.halfOpenToken));
                return v;
            }
            if (v.state == State.HALF_OPEN) {
                if (v.halfOpenInFlight) {
                    return v;
                }
                v.halfOpenInFlight = true;
                v.halfOpenToken = probeTokenSeq.incrementAndGet();
                granted.set(new CallPermit(id, v.halfOpenToken));
                return v;
            }
            granted.set(new CallPermit(id, 0L));
            return v;
        });
        return granted.get();
    }

    public void markSuccess(String id) {
        if (id == null) {
            return;
        }
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                return new ModelHealth();
            }
            v.state = State.CLOSED;
            v.consecutiveFailures = 0;
            v.openUntil = 0L;
            v.halfOpenInFlight = false;
            return v;
        });
    }

    public void markFailure(String id) {
        if (id == null) {
            return;
        }
        long now = System.currentTimeMillis();
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                v = new ModelHealth();
            }
            if (v.state == State.HALF_OPEN) {
                v.state = State.OPEN;
                v.openUntil = now + properties.getSelection().getOpenDurationMs();
                v.consecutiveFailures = 0;
                v.halfOpenInFlight = false;
                return v;
            }
            v.consecutiveFailures++;
            if (v.consecutiveFailures >= properties.getSelection().getFailureThreshold()) {
                v.state = State.OPEN;
                v.openUntil = now + properties.getSelection().getOpenDurationMs();
                v.consecutiveFailures = 0;
            }
            return v;
        });
    }

    /**
     * 仅释放当前凭证持有的半开探测名额
     */
    public void releaseHalfOpenPermit(CallPermit permit) {
        if (permit == null || permit.halfOpenToken() <= 0L) {
            return;
        }
        healthById.computeIfPresent(permit.modelId(), (k, v) -> {
            if (v.state == State.HALF_OPEN && v.halfOpenInFlight && v.halfOpenToken == permit.halfOpenToken()) {
                v.halfOpenInFlight = false;
            }
            return v;
        });
    }

    private static class ModelHealth {
        private int consecutiveFailures;
        private long openUntil;
        private boolean halfOpenInFlight;
        private long halfOpenToken;
        private State state;

        private ModelHealth() {
            this.consecutiveFailures = 0;
            this.openUntil = 0L;
            this.halfOpenInFlight = false;
            this.halfOpenToken = 0L;
            this.state = State.CLOSED;
        }
    }

    private enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }
}
