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

package com.nageoffer.ai.ragent.rag.chat;

import lombok.Getter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 首包等待器 - 用于等待第一个数据包到达的同步工具
 * 支持超时等待，并可区分成功、错误、超时、无内容等不同状态
 */
public class FirstPacketAwaiter {

    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicBoolean hasContent = new AtomicBoolean(false);
    private final AtomicBoolean eventFired = new AtomicBoolean(false);
    private final AtomicReference<Throwable> error = new AtomicReference<>();

    /**
     * 标记收到内容
     */
    public void markContent() {
        hasContent.set(true);
        fireEventOnce();
    }

    /**
     * 标记完成
     */
    public void markComplete() {
        fireEventOnce();
    }

    /**
     * 标记错误
     */
    public void markError(Throwable t) {
        error.set(t);
        fireEventOnce();
    }

    /**
     * 确保只触发一次事件
     */
    private void fireEventOnce() {
        if (eventFired.compareAndSet(false, true)) {
            latch.countDown();
        }
    }

    /**
     * 等待结果
     */
    public Result await(long timeout, TimeUnit unit) throws InterruptedException {
        boolean completed = latch.await(timeout, unit);

        if (error.get() != null) {
            return Result.error(error.get());
        }
        if (!completed) {
            return Result.timeout();
        }
        if (!hasContent.get()) {
            return Result.noContent();
        }
        return Result.success();
    }

    /**
     * 结果封装
     */
    @Getter
    public static class Result {
        public enum Type {SUCCESS, ERROR, TIMEOUT, NO_CONTENT}

        private final Type type;
        private final Throwable error;

        private Result(Type type, Throwable error) {
            this.type = type;
            this.error = error;
        }

        public static Result success() {
            return new Result(Type.SUCCESS, null);
        }

        public static Result error(Throwable t) {
            return new Result(Type.ERROR, t);
        }

        public static Result timeout() {
            return new Result(Type.TIMEOUT, null);
        }

        public static Result noContent() {
            return new Result(Type.NO_CONTENT, null);
        }

        public boolean isSuccess() {
            return type == Type.SUCCESS;
        }
    }
}
