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

package com.nageoffer.ai.ragent.infra.chat;

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingLLMServiceHalfOpenRecoveryTest {

    private static final String MODEL_ID = "mock-model";

    private ModelHealthStore healthStore;
    private RoutingLLMService service;
    private StreamCallback callback;
    private ChatClient client;
    private LlmFirstPacketProbe probe;

    private void initService(int failureThreshold, long openDurationMs) throws InterruptedException {
        AIModelProperties properties = new AIModelProperties();
        properties.getSelection().setFailureThreshold(failureThreshold);
        properties.getSelection().setOpenDurationMs(openDurationMs);
        healthStore = new ModelHealthStore(properties);

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId(MODEL_ID);
        candidate.setProvider("mock");
        ModelTarget target = new ModelTarget(MODEL_ID, candidate, new AIModelProperties.ProviderConfig(), 1000L);

        ModelSelector selector = mock(ModelSelector.class);
        when(selector.selectChatCandidates(anyBoolean())).thenReturn(List.of(target));

        client = mock(ChatClient.class);
        when(client.provider()).thenReturn("mock");
        when(client.streamChat(any(), any(), any())).thenReturn(() -> {
        });

        probe = mock(LlmFirstPacketProbe.class);
        when(probe.awaitFirstPacket(any(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new InterruptedException("simulated interrupt"));

        callback = mock(StreamCallback.class);
        service = new RoutingLLMService(selector, healthStore,
                mock(ModelRoutingExecutor.class), probe, List.of(client));
    }

    private void streamChatExpectingInterrupt() {
        assertThrows(RemoteException.class, () -> service.streamChat(new ChatRequest(), callback));
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void interruptedHalfOpenProbeReleasesPermit() throws InterruptedException {
        initService(1, 0L);
        healthStore.markFailure(MODEL_ID);
        streamChatExpectingInterrupt();
        assertNotNull(healthStore.allowCall(MODEL_ID));
    }

    @Test
    void interruptedHalfOpenProbeKeepsModelAvailable() throws InterruptedException {
        initService(1, 0L);
        healthStore.markFailure(MODEL_ID);

        streamChatExpectingInterrupt();
        assertFalse(healthStore.isUnavailable(MODEL_ID));
    }

    @Test
    void closedStateInterruptDoesNotCountAsFailure() throws InterruptedException {
        initService(2, 60_000L);

        streamChatExpectingInterrupt();
        healthStore.markFailure(MODEL_ID);
        assertNotNull(healthStore.allowCall(MODEL_ID));
    }

    @Test
    void closedStateInterruptDoesNotOpenCircuit() throws InterruptedException {
        initService(1, 60_000L);

        streamChatExpectingInterrupt();

        assertNotNull(healthStore.allowCall(MODEL_ID));
        assertFalse(healthStore.isUnavailable(MODEL_ID));
    }

    @Test
    void staleClosedCallCannotReleaseAnotherProbePermit() throws InterruptedException {
        initService(1, 0L);
        when(probe.awaitFirstPacket(any(), anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
            healthStore.markFailure(MODEL_ID);
            healthStore.allowCall(MODEL_ID);
            throw new InterruptedException("stale closed caller interrupted");
        });
        streamChatExpectingInterrupt();

        assertNull(healthStore.allowCall(MODEL_ID));
        assertTrue(healthStore.isUnavailable(MODEL_ID));
    }

    @Test
    void staleHalfOpenPermitCannotReleaseCurrentPermit() throws InterruptedException {
        initService(1, 0L);
        healthStore.markFailure(MODEL_ID);
        ModelHealthStore.CallPermit permit1 = healthStore.allowCall(MODEL_ID);
        assertNotNull(permit1);
        assertTrue(permit1.halfOpenToken() > 0);
        healthStore.markFailure(MODEL_ID);
        ModelHealthStore.CallPermit permit2 = healthStore.allowCall(MODEL_ID);
        assertNotNull(permit2);
        assertTrue(permit2.halfOpenToken() > 0);
        healthStore.releaseHalfOpenPermit(permit1);

        assertNull(healthStore.allowCall(MODEL_ID));
        assertTrue(healthStore.isUnavailable(MODEL_ID));
    }

    @Test
    void interruptCancelsProbeBeforeReleasingPermit() throws InterruptedException {
        initService(1, 0L);
        healthStore.markFailure(MODEL_ID);
        AtomicBoolean heldAtCancelTime = new AtomicBoolean(false);
        when(client.streamChat(any(), any(), any())).thenReturn(
                () -> heldAtCancelTime.set(healthStore.isUnavailable(MODEL_ID)));

        streamChatExpectingInterrupt();

        assertTrue(heldAtCancelTime.get());
        assertFalse(healthStore.isUnavailable(MODEL_ID));
    }

    @Test
    void cancelFailureStillReleasesPermit() throws InterruptedException {
        initService(1, 0L);
        healthStore.markFailure(MODEL_ID);
        when(client.streamChat(any(), any(), any())).thenReturn(() -> {
            throw new IllegalStateException("cancel failed");
        });

        assertThrows(RuntimeException.class, () -> service.streamChat(new ChatRequest(), callback));

        assertFalse(healthStore.isUnavailable(MODEL_ID));
    }
}
