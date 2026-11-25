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

package com.nageoffer.ai.ragent.agent.trace;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * span 起止要能被摆到已知位置再断言，系统时钟给不出可复现的终点。
 * 同时实现 OTel SDK 的 Clock 并经 SdkTracerProvider#setClock 注入，
 * 使 startSpan()/无显式时间戳的 end() 也取假钟——否则这些时间戳仍是系统实时钟，
 * 「零耗时」类断言在 CI 负载下会被毫秒级真实滑移偶发击穿。
 * nanoTime() 直接复用 epoch 纳秒而非单调钟：测试只关心起止可摆布、可复现，
 * 不依赖纳秒时戳的单调语义。
 */
final class MutableClock extends Clock implements io.opentelemetry.sdk.common.Clock {

    private volatile Instant instant;

    MutableClock(Instant instant) {
        this.instant = instant;
    }

    void advance(long millis) {
        instant = instant.plusMillis(millis);
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.systemDefault();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public long now() {
        Instant current = instant;
        return current.getEpochSecond() * 1_000_000_000L + current.getNano();
    }

    @Override
    public long nanoTime() {
        return now();
    }
}
