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
     * 模型调用许可：halfOpenToken 标识半开探测所有者，generation 隔离不同熔断周期
     */
    public record CallPermit(String modelId, long halfOpenToken, long generation) {
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
                v.generation++;
                v.halfOpenInFlight = true;
                v.halfOpenToken = probeTokenSeq.incrementAndGet();
                granted.set(new CallPermit(id, v.halfOpenToken, v.generation));
                return v;
            }
            if (v.state == State.HALF_OPEN) {
                if (v.halfOpenInFlight) {
                    return v;
                }
                v.halfOpenInFlight = true;
                v.halfOpenToken = probeTokenSeq.incrementAndGet();
                granted.set(new CallPermit(id, v.halfOpenToken, v.generation));
                return v;
            }
            granted.set(new CallPermit(id, 0L, v.generation));
            return v;
        });
        return granted.get();
    }

    public void markSuccess(CallPermit permit) {
        if (permit == null) {
            return;
        }
        healthById.computeIfPresent(permit.modelId(), (k, v) -> {
            if (!ownsCurrentGeneration(v, permit)) {
                return v;
            }
            boolean halfOpen = v.state == State.HALF_OPEN;
            v.state = State.CLOSED;
            v.consecutiveFailures = 0;
            v.openUntil = 0L;
            v.halfOpenInFlight = false;
            v.halfOpenToken = 0L;
            if (halfOpen) {
                v.generation++;
            }
            return v;
        });
    }

    public void markFailure(CallPermit permit) {
        if (permit == null) {
            return;
        }
        long now = System.currentTimeMillis();
        healthById.computeIfPresent(permit.modelId(), (k, v) -> {
            if (!ownsCurrentGeneration(v, permit)) {
                return v;
            }
            if (v.state == State.HALF_OPEN) {
                v.state = State.OPEN;
                v.openUntil = now + properties.getSelection().getOpenDurationMs();
                v.consecutiveFailures = 0;
                v.halfOpenInFlight = false;
                v.halfOpenToken = 0L;
                v.generation++;
                return v;
            }
            if (v.state != State.CLOSED) {
                return v;
            }
            v.consecutiveFailures++;
            if (v.consecutiveFailures >= properties.getSelection().getFailureThreshold()) {
                v.state = State.OPEN;
                v.openUntil = now + properties.getSelection().getOpenDurationMs();
                v.consecutiveFailures = 0;
                v.generation++;
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
            if (ownsCurrentGeneration(v, permit) && v.state == State.HALF_OPEN && v.halfOpenInFlight) {
                v.halfOpenInFlight = false;
            }
            return v;
        });
    }

    private boolean ownsCurrentGeneration(ModelHealth health, CallPermit permit) {
        if (health.generation != permit.generation()) {
            return false;
        }
        // 半开状态同一周期可能在取消后重新发放探测名额，还需校验具体 token
        return health.state != State.HALF_OPEN || health.halfOpenToken == permit.halfOpenToken();
    }

    private static class ModelHealth {
        private int consecutiveFailures;
        private long openUntil;
        private boolean halfOpenInFlight;
        private long halfOpenToken;
        private long generation;
        private State state;

        private ModelHealth() {
            this.consecutiveFailures = 0;
            this.openUntil = 0L;
            this.halfOpenInFlight = false;
            this.halfOpenToken = 0L;
            this.generation = 0L;
            this.state = State.CLOSED;
        }
    }

    private enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }
}
