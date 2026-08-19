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

package com.nageoffer.ai.ragent.infra.voice.tts;

import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingTtsServiceTest {

    @Test
    void usesModelHealthStoreForConsecutiveFailures() {
        AIModelProperties properties = singleModelProperties();
        ModelHealthStore healthStore = new ModelHealthStore(properties);
        FailingTtsClient client = new FailingTtsClient("test");
        RoutingTtsService service = service(properties, healthStore, List.of(client));

        assertThrows(RemoteException.class,
                () -> service.synthesize("第一次", new RecordingCallback()));
        assertFalse(healthStore.isUnavailable("tts-model"));

        assertThrows(RemoteException.class,
                () -> service.synthesize("第二次", new RecordingCallback()));
        assertTrue(healthStore.isUnavailable("tts-model"));
        assertEquals(2, client.attempts.get());
        assertEquals(2, client.invalidatingCancellations.get());
    }

    @Test
    void discardsFailedCandidateEventsAndCommitsFirstAudioFromFallback() {
        AIModelProperties properties = fallbackProperties();
        ModelHealthStore healthStore = new ModelHealthStore(properties);
        FailingTtsClient primary = new FailingTtsClient("primary");
        SuccessfulTtsClient backup = new SuccessfulTtsClient("backup", new byte[]{2, 3});
        RoutingTtsService service = service(properties, healthStore, List.of(primary, backup));
        RecordingCallback callback = new RecordingCallback();

        StreamCancellationHandle handle = service.synthesize("你好", callback);

        assertArrayEquals(new byte[]{2, 3}, callback.audioEvents.get(0));
        assertEquals(1, callback.audioEvents.size());
        assertTrue(callback.completed);
        assertEquals(0, callback.errors.get());
        assertEquals(1, primary.attempts.get());
        assertEquals(1, backup.attempts.get());
        handle.cancel();
    }

    @Test
    void releasesHalfOpenPermitWhenStartedTaskIsCancelledBeforeFirstAudio() {
        AIModelProperties properties = fallbackProperties();
        properties.getSelection().setFailureThreshold(1);
        properties.getSelection().setOpenDurationMs(0L);
        ModelHealthStore healthStore = new ModelHealthStore(properties);
        CancelCompletingTtsClient primary = new CancelCompletingTtsClient("primary");
        SuccessfulTtsClient backup = new SuccessfulTtsClient("backup", new byte[]{2, 3});
        RoutingTtsService service = service(properties, healthStore, List.of(primary, backup));
        RecordingCallback callback = new RecordingCallback();
        AtomicBoolean cancelled = new AtomicBoolean();
        healthStore.markFailure("primary-model");

        service.synthesize("立即取消", callback, new TtsTaskObserver() {
            @Override
            public void onTaskStarted(StreamCancellationHandle handle) {
                cancelled.set(true);
                handle.cancel();
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        });

        assertEquals(1, primary.cancellations.get());
        assertEquals(0, backup.attempts.get());
        assertTrue(callback.audioEvents.isEmpty());
        assertFalse(callback.completed);
        assertEquals(0, callback.errors.get());
        assertFalse(healthStore.isUnavailable("primary-model"));
        assertNotNull(healthStore.allowCall("primary-model"));
    }

    @Test
    void releasesHalfOpenPermitWhenConnectionPoolIsExhausted() {
        AIModelProperties properties = singleModelProperties();
        properties.getSelection().setFailureThreshold(1);
        properties.getSelection().setOpenDurationMs(0L);
        ModelHealthStore healthStore = new ModelHealthStore(properties);
        PoolExhaustedTtsClient client = new PoolExhaustedTtsClient("test");
        RoutingTtsService service = service(properties, healthStore, List.of(client));
        healthStore.markFailure("tts-model");

        assertThrows(RemoteException.class,
                () -> service.synthesize("连接池耗尽", new RecordingCallback()));

        assertEquals(1, client.attempts.get());
        assertFalse(healthStore.isUnavailable("tts-model"));
        assertNotNull(healthStore.allowCall("tts-model"));
    }

    private RoutingTtsService service(AIModelProperties properties,
                                      ModelHealthStore healthStore,
                                      List<TtsClient> clients) {
        return new RoutingTtsService(
                new ModelSelector(properties, healthStore),
                healthStore,
                clients
        );
    }

    private AIModelProperties singleModelProperties() {
        AIModelProperties properties = new AIModelProperties();
        properties.getProviders().put("test", new AIModelProperties.ProviderConfig());
        properties.getTts().setDefaultModel("tts-model");
        properties.getTts().setTimeoutMs(1000L);
        properties.getTts().setCandidates(List.of(candidate("tts-model", "test", 1)));
        return properties;
    }

    private AIModelProperties fallbackProperties() {
        AIModelProperties properties = new AIModelProperties();
        properties.getProviders().put("primary", new AIModelProperties.ProviderConfig());
        properties.getProviders().put("backup", new AIModelProperties.ProviderConfig());
        properties.getTts().setDefaultModel("primary-model");
        properties.getTts().setTimeoutMs(1000L);
        properties.getTts().setCandidates(List.of(
                candidate("primary-model", "primary", 1),
                candidate("backup-model", "backup", 2)
        ));
        return properties;
    }

    private AIModelProperties.ModelCandidate candidate(String id, String provider, int priority) {
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId(id);
        candidate.setProvider(provider);
        candidate.setModel(id);
        candidate.setPriority(priority);
        return candidate;
    }

    private static final class FailingTtsClient implements TtsClient {

        private final String provider;
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger invalidatingCancellations = new AtomicInteger();

        private FailingTtsClient(String provider) {
            this.provider = provider;
        }

        @Override
        public String provider() {
            return provider;
        }

        @Override
        public StreamCancellationHandle synthesize(String text, TtsCallback callback, ModelTarget target) {
            attempts.incrementAndGet();
            callback.onError(new IllegalStateException("provider failed"));
            return invalidatingCancellations::incrementAndGet;
        }
    }

    private static final class SuccessfulTtsClient implements TtsClient {

        private final String provider;
        private final byte[] audio;
        private final AtomicInteger attempts = new AtomicInteger();

        private SuccessfulTtsClient(String provider, byte[] audio) {
            this.provider = provider;
            this.audio = audio;
        }

        @Override
        public String provider() {
            return provider;
        }

        @Override
        public StreamCancellationHandle synthesize(String text, TtsCallback callback, ModelTarget target) {
            attempts.incrementAndGet();
            callback.onAudio(audio);
            callback.onComplete();
            return () -> {
            };
        }
    }

    private static final class CancelCompletingTtsClient implements TtsClient {

        private final String provider;
        private final AtomicInteger cancellations = new AtomicInteger();

        private CancelCompletingTtsClient(String provider) {
            this.provider = provider;
        }

        @Override
        public String provider() {
            return provider;
        }

        @Override
        public StreamCancellationHandle synthesize(String text, TtsCallback callback, ModelTarget target) {
            return () -> {
                cancellations.incrementAndGet();
                callback.onComplete();
            };
        }
    }

    private static final class PoolExhaustedTtsClient implements TtsClient {

        private final String provider;
        private final AtomicInteger attempts = new AtomicInteger();

        private PoolExhaustedTtsClient(String provider) {
            this.provider = provider;
        }

        @Override
        public String provider() {
            return provider;
        }

        @Override
        public StreamCancellationHandle synthesize(String text, TtsCallback callback, ModelTarget target) {
            attempts.incrementAndGet();
            throw new ModelClientException("pool exhausted", ModelClientErrorType.RATE_LIMITED, null);
        }
    }

    private static final class RecordingCallback implements TtsCallback {

        private final List<byte[]> audioEvents = new ArrayList<>();
        private final AtomicInteger errors = new AtomicInteger();
        private boolean completed;

        @Override
        public void onAudio(byte[] audio) {
            audioEvents.add(audio);
        }

        @Override
        public void onComplete() {
            completed = true;
        }

        @Override
        public void onError(Throwable throwable) {
            errors.incrementAndGet();
        }
    }
}
