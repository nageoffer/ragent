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

import com.alibaba.ttl.TransmittableThreadLocal;

import java.time.Instant;

/**
 * 入口审核放行时的起止时刻，审核写、追踪 Middleware 读，补成本轮 trace 根节点下的 input_guard
 * <p>
 * 审核与 onAgent 在同一请求线程上先后同步执行（订阅不切线程），所以写进去的就是这一轮的
 */
public final class AgentInputReviewContext {

    private static final TransmittableThreadLocal<InputReview> CONTEXT = new TransmittableThreadLocal<>();

    private AgentInputReviewContext() {
    }

    public static void set(Instant startedAt, Instant endedAt) {
        CONTEXT.set(new InputReview(startedAt, endedAt));
    }

    /**
     * 取出并清空；每次运行都取，确认续跑取到的是此前残留的，丢弃即可
     */
    public static InputReview take() {
        InputReview review = CONTEXT.get();
        CONTEXT.remove();
        return review;
    }

    public record InputReview(Instant startedAt, Instant endedAt) {
    }
}
