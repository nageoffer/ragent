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

package com.nageoffer.ai.ragent.agent.service.handler;

import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.misc.CompletableFutureWrapper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AgentRunGateTest {
    private static final String USER = "u-1001";
    private static final String TASK = "9001";
    private final Map<String, RLock> locks = new HashMap<>();
    private AgentProperties properties;
    private RedissonClient redisson;
    private AgentRunGate gate;

    @BeforeEach
    void setUp() {
        locks.clear();
        properties = new AgentProperties();
        redisson = mock(RedissonClient.class);
        when(redisson.getLock(anyString())).thenAnswer(call -> lock(call.getArgument(0)));
        gate = new AgentRunGate(redisson, properties);
    }

    private RLock lock(String key) {
        return locks.computeIfAbsent(key, ignored -> {
            RLock lock = mock(RLock.class);
            when(lock.getName()).thenReturn(key);
            when(lock.tryLockAsync(anyLong()))
                    .thenReturn(new CompletableFutureWrapper<>(true));
            when(lock.unlockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<>((Void) null));
            return lock;
        });
    }

    @Test
    void shouldUseConversationKeyAndLogicalOwnerWithWatchdog() {
        gate.acquire(USER, TASK, "c-1");
        verify(lock("ragent:agent:run-lock:" + USER + ":c-1"))
                .tryLockAsync(9001);
        verify(lock("ragent:agent:run-permit:" + USER + ":0"))
                .tryLockAsync(9001);
    }

    @Test
    void shouldRejectBusyConversationBeforeRequestingPermit() {
        when(lock("ragent:agent:run-lock:" + USER + ":c-1")
                .tryLockAsync(9001))
                .thenReturn(new CompletableFutureWrapper<>(false));
        assertThatThrownBy(() -> gate.acquire(USER, TASK, "c-1"))
                .isInstanceOf(ClientException.class).hasMessageContaining("当前会话正在处理中");
        verify(redisson, never()).getLock("ragent:agent:run-permit:" + USER + ":0");
    }

    @Test
    void shouldReleaseConversationWhenConfiguredPermitsAreFull() {
        properties.setMaxConcurrentRunsPerUser(2);
        for (int slot = 0; slot < 2; slot++) {
            when(lock("ragent:agent:run-permit:" + USER + ":" + slot)
                    .tryLockAsync(9001))
                    .thenReturn(new CompletableFutureWrapper<>(false));
        }
        assertThatThrownBy(() -> gate.acquire(USER, TASK, "c-1"))
                .isInstanceOf(ClientException.class).hasMessage("并发会话已达上限，请稍后重试");
        verify(lock("ragent:agent:run-lock:" + USER + ":c-1")).unlockAsync(9001);
        verify(redisson, never()).getLock("ragent:agent:run-permit:" + USER + ":2");
    }

    @Test
    void shouldAllowRepeatedReleaseAcrossThreads() throws Exception {
        Runnable release = gate.acquire(USER, TASK, "c-1");
        RLock conversation = lock("ragent:agent:run-lock:" + USER + ":c-1");
        RLock permit = lock("ragent:agent:run-permit:" + USER + ":0");
        when(conversation.unlockAsync(9001)).thenReturn(
                new CompletableFutureWrapper<>((Void) null),
                new CompletableFutureWrapper<>(new CompletionException(new IllegalMonitorStateException())));
        when(permit.unlockAsync(9001)).thenReturn(
                new CompletableFutureWrapper<>((Void) null),
                new CompletableFutureWrapper<>(new IllegalMonitorStateException()));
        Thread callback = new Thread(release);
        callback.start();
        callback.join();
        release.run();
        verify(conversation, times(2)).unlockAsync(9001);
        verify(permit, times(2)).unlockAsync(9001);
    }

    @Test
    void shouldWaitForBothUnlockResultsBeforeReturning() throws Exception {
        Runnable release = gate.acquire(USER, TASK, "c-1");
        CompletableFuture<Void> conversationUnlocked = new CompletableFuture<>();
        CompletableFuture<Void> permitUnlocked = new CompletableFuture<>();
        CountDownLatch conversationRequested = new CountDownLatch(1);
        CountDownLatch permitRequested = new CountDownLatch(1);
        when(lock("ragent:agent:run-lock:" + USER + ":c-1").unlockAsync(9001)).thenAnswer(call -> {
            conversationRequested.countDown();
            return new CompletableFutureWrapper<>(conversationUnlocked);
        });
        when(lock("ragent:agent:run-permit:" + USER + ":0").unlockAsync(9001)).thenAnswer(call -> {
            permitRequested.countDown();
            return new CompletableFutureWrapper<>(permitUnlocked);
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var releasing = executor.submit(release);
            assertThat(permitRequested.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(releasing.isDone()).isFalse();
            assertThat(conversationRequested.getCount()).isEqualTo(1);
            permitUnlocked.complete(null);
            assertThat(conversationRequested.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(releasing.isDone()).isFalse();
            conversationUnlocked.complete(null);
            releasing.get(5, TimeUnit.SECONDS);
        } finally {
            conversationUnlocked.complete(null);
            permitUnlocked.complete(null);
            executor.shutdownNow();
        }
    }

    @Test
    void shouldReleaseConversationWhenPermitAcquisitionFails() {
        when(lock("ragent:agent:run-permit:" + USER + ":0")
                .tryLockAsync(9001))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        assertThatThrownBy(() -> gate.acquire(USER, TASK, "c-1")).hasMessage("Redis unavailable");
        verify(lock("ragent:agent:run-lock:" + USER + ":c-1")).unlockAsync(9001);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldAttemptConversationReleaseEvenWhenPermitUnlockFails(boolean invocationFails) {
        Runnable release = gate.acquire(USER, TASK, "c-1");
        IllegalStateException failure = new IllegalStateException("Redis unavailable");
        if (invocationFails) {
            when(lock("ragent:agent:run-permit:" + USER + ":0").unlockAsync(9001)).thenThrow(failure);
        } else {
            when(lock("ragent:agent:run-permit:" + USER + ":0").unlockAsync(9001))
                    .thenReturn(new CompletableFutureWrapper<>(failure));
        }
        release.run();
        verify(lock("ragent:agent:run-lock:" + USER + ":c-1")).unlockAsync(9001);
    }

    @Test
    void shouldAcquireOnlyConversationLockWithDistinctOwners() {
        Runnable firstRelease = gate.acquireConversation(USER, "c-1");
        firstRelease.run();
        Runnable secondRelease = gate.acquireConversation(USER, "c-1");
        secondRelease.run();

        RLock conversation = lock("ragent:agent:run-lock:" + USER + ":c-1");
        ArgumentCaptor<Long> owners = ArgumentCaptor.forClass(Long.class);
        verify(conversation, times(2)).tryLockAsync(owners.capture());
        assertThat(owners.getAllValues().get(0)).isNotEqualTo(owners.getAllValues().get(1));
        owners.getAllValues().forEach(owner -> verify(conversation).unlockAsync(owner));
        verify(redisson, times(2)).getLock("ragent:agent:run-lock:" + USER + ":c-1");
        // 不申请名额，因此其他会话占满全部名额也不会阻止删除
        verifyNoMoreInteractions(redisson);
    }

    @Test
    void shouldRejectConversationAcquisitionWhenLockIsHeld() {
        RLock conversation = lock("ragent:agent:run-lock:" + USER + ":c-1");
        when(conversation.tryLockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<>(false));

        assertThatThrownBy(() -> gate.acquireConversation(USER, "c-1"))
                .isInstanceOf(ClientException.class).hasMessageContaining("当前会话正在处理中");

        verify(conversation, never()).unlockAsync(anyLong());
        verify(redisson).getLock("ragent:agent:run-lock:" + USER + ":c-1");
        verifyNoMoreInteractions(redisson);
    }

    @Test
    void shouldValidateTimeoutAgainstTaskRetention() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(properties)).isEmpty();
            properties.setSseTimeoutMs(1_800_000L);
            assertThat(validator.validate(properties)).singleElement()
                    .satisfies(violation -> assertThat(violation.getMessage()).contains("30 分钟"));
            properties.setSseTimeoutMs(900_000L);
            properties.setMaxConcurrentRunsPerUser(0);
            assertThat(validator.validate(properties)).singleElement()
                    .satisfies(violation -> assertThat(violation.getPropertyPath().toString())
                            .isEqualTo("maxConcurrentRunsPerUser"));
            properties.setMaxConcurrentRunsPerUser(5);
            properties.setSseTimeoutMs(0L);
            assertThat(validator.validate(properties)).singleElement()
                    .satisfies(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("sseTimeoutMs"));
            properties.setSseTimeoutMs(null);
            properties.setMaxConcurrentRunsPerUser(null);
            assertThat(validator.validate(properties)).hasSize(2);
        }
    }
}
