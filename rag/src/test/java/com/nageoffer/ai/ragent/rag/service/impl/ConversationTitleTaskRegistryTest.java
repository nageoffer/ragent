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

package com.nageoffer.ai.ragent.rag.service.impl;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ConversationTitleTaskRegistryTest {

    private static final String USER_ID = "u-1";
    private static final String CONVERSATION_ID = "c-1";

    private final ConversationTitleTaskRegistry registry = new ConversationTitleTaskRegistry();

    @Test
    void shouldReturnImmediatelyWhenNoTaskRegistered() {
        long start = System.nanoTime();

        registry.await(USER_ID, CONVERSATION_ID, 1000L);

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(500L);
    }

    @Test
    void shouldReturnOnceTaskCompletesBeforeTimeout() throws Exception {
        CompletableFuture<Void> future = new CompletableFuture<>();
        registry.register(USER_ID, CONVERSATION_ID, future);
        CountDownLatch awaited = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            registry.await(USER_ID, CONVERSATION_ID, 5000L);
            awaited.countDown();
        });
        waiter.start();

        assertThat(awaited.await(200, TimeUnit.MILLISECONDS)).isFalse();
        future.complete(null);

        assertThat(awaited.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldGiveUpAfterTimeoutWhenTaskNeverCompletes() {
        registry.register(USER_ID, CONVERSATION_ID, new CompletableFuture<Void>());

        long start = System.nanoTime();
        registry.await(USER_ID, CONVERSATION_ID, 100L);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMillis).isBetween(80L, 1500L);
    }

    @Test
    void shouldNotThrowWhenTaskFailed() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        registry.register(USER_ID, CONVERSATION_ID, future);
        future.completeExceptionally(new IllegalStateException("标题生成失败"));

        assertThatCode(() -> registry.await(USER_ID, CONVERSATION_ID, 100L)).doesNotThrowAnyException();
    }

    @Test
    void shouldIsolateTasksByUserAndConversation() {
        registry.register(USER_ID, CONVERSATION_ID, new CompletableFuture<Void>());

        long start = System.nanoTime();
        registry.await("u-2", CONVERSATION_ID, 1000L);
        registry.await(USER_ID, "c-2", 1000L);

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(500L);
    }
}
