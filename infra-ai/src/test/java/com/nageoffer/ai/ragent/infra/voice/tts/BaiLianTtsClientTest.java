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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaiLianTtsClientTest {

    private final Gson gson = new Gson();

    @Test
    void sendsMinimalCosyVoiceProtocolAndReturnsMp3() {
        FakeWebSocketFactory factory = new FakeWebSocketFactory();
        BaiLianTtsClient client = new BaiLianTtsClient(factory, Runnable::run, properties());
        RecordingCallback callback = new RecordingCallback();

        StreamCancellationHandle handle = client.synthesize(
                "你好，世界。",
                callback,
                target()
        );

        JsonObject runTask = factory.messages.get(0);
        JsonObject runPayload = runTask.getAsJsonObject("payload");
        JsonObject parameters = runPayload.getAsJsonObject("parameters");
        assertEquals(Set.of("text_type", "voice", "format"), parameters.keySet());
        assertEquals("PlainText", parameters.get("text_type").getAsString());
        assertEquals("longxiaochun", parameters.get("voice").getAsString());
        assertEquals("mp3", parameters.get("format").getAsString());
        assertEquals("audio", runPayload.get("task_group").getAsString());
        assertEquals("cosyvoice-v3-flash", runPayload.get("model").getAsString());

        assertEquals(List.of("run-task", "continue-task", "finish-task"), factory.actions());
        assertEquals(1, factory.taskIds().stream().distinct().count());
        assertEquals("你好，世界。", factory.messages.get(1)
                .getAsJsonObject("payload")
                .getAsJsonObject("input")
                .get("text")
                .getAsString());
        assertEquals("Bearer test-key", factory.request.header("Authorization"));
        assertEquals("test-workspace", factory.request.header("X-DashScope-WorkSpace"));
        assertTrue(factory.request.url().isHttps());
        assertEquals("dashscope.aliyuncs.com", factory.request.url().host());
        assertEquals("/api-ws/v1/inference", factory.request.url().encodedPath());
        assertArrayEquals(FakeWebSocketFactory.MP3_AUDIO, callback.audio);
        assertTrue(callback.completed);

        handle.cancel();
        assertEquals(0, factory.closeCount.get());
        client.close();
    }

    @Test
    void splitsLongTextIntoSeparateContinueTasks() {
        FakeWebSocketFactory factory = new FakeWebSocketFactory();
        BaiLianTtsClient client = new BaiLianTtsClient(factory, Runnable::run, properties());

        String longText = "测试".repeat(41);
        client.synthesize(longText, new RecordingCallback(), target());

        // run-task + 2 个 continue-task + finish-task
        assertEquals(List.of("run-task", "continue-task", "continue-task", "finish-task"), factory.actions());
        assertEquals(longText.substring(0, 80), factory.messages.get(1)
                .getAsJsonObject("payload").getAsJsonObject("input").get("text").getAsString());
        assertEquals(longText.substring(80), factory.messages.get(2)
                .getAsJsonObject("payload").getAsJsonObject("input").get("text").getAsString());

        client.close();
    }

    @Test
    void waitsForFirstAudioUsingTtsTimeout() {
        FakeWebSocketFactory factory = new FakeWebSocketFactory(true, false, 450L, 0L);
        BaiLianTtsClient client = new BaiLianTtsClient(factory, Runnable::run, properties(300L));
        RecordingCallback callback = new RecordingCallback();

        client.synthesize("延迟首帧", callback, target());

        assertArrayEquals(FakeWebSocketFactory.MP3_AUDIO, callback.audio);
        assertTrue(callback.completed);
        assertNull(callback.error);
        client.close();
    }

    @Test
    void keepsFullFinishGraceWhenAudioArrivesBeforeFinishDirective() {
        FakeWebSocketFactory factory = new FakeWebSocketFactory(
                true, false, 0L, 200L, true, 200L);
        BaiLianTtsClient client = new BaiLianTtsClient(factory, Runnable::run, properties(300L));
        RecordingCallback callback = new RecordingCallback();

        client.synthesize("提前返回音频", callback, target());

        assertArrayEquals(FakeWebSocketFactory.MP3_AUDIO, callback.audio);
        assertTrue(callback.completed);
        assertNull(callback.error);
        client.close();
    }

    @Test
    void renewsFinishWaitForEveryAudioFrame() {
        FakeWebSocketFactory factory = new FakeWebSocketFactory(
                true, false, 0L, 200L, 3, 200L);
        BaiLianTtsClient client = new BaiLianTtsClient(factory, Runnable::run, properties(300L));
        RecordingCallback callback = new RecordingCallback();

        client.synthesize("持续流式返回音频", callback, target());

        assertArrayEquals(FakeWebSocketFactory.MP3_AUDIO, callback.audio);
        assertTrue(callback.completed);
        assertNull(callback.error);
        client.close();
    }

    @Test
    void timesOutWhenAudioFrameGapExceedsConfiguredIdleTimeout() {
        FakeWebSocketFactory factory = new FakeWebSocketFactory(true, false, 0L, 450L);
        BaiLianTtsClient client = new BaiLianTtsClient(factory, Runnable::run, properties(300L));
        RecordingCallback callback = new RecordingCallback();

        client.synthesize("帧间超时", callback, target());

        assertArrayEquals(FakeWebSocketFactory.MP3_AUDIO, callback.audio);
        assertFalse(callback.completed);
        assertNotNull(callback.error);
        client.close();
    }

    @Test
    void sendsCancelDirectiveBeforeFirstAudio() throws Exception {
        FakeWebSocketFactory factory = new FakeWebSocketFactory(false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        BaiLianTtsClient client = new BaiLianTtsClient(factory, executor, properties());
        RecordingCallback callback = new RecordingCallback();

        try {
            StreamCancellationHandle handle = client.synthesize("取消播放", callback, target());
            assertTrue(factory.awaitNormalFinish());

            handle.cancel();

            JsonObject cancel = factory.messages.get(factory.messages.size() - 1);
            assertEquals("finish-task", cancel.getAsJsonObject("header").get("action").getAsString());
            assertEquals("cancel", cancel.getAsJsonObject("payload")
                    .getAsJsonObject("input")
                    .get("directive")
                    .getAsString());
            assertNull(callback.audio);
            assertTrue(callback.completed);
            assertEquals(1, factory.closeCount.get());
        } finally {
            client.close();
            executor.shutdownNow();
        }
    }

    @Test
    void invalidatesConnectionWhenCancelReceivesTaskFailed() throws Exception {
        FakeWebSocketFactory factory = new FakeWebSocketFactory(false, true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        BaiLianTtsClient client = new BaiLianTtsClient(factory, executor, properties());

        try {
            StreamCancellationHandle handle = client.synthesize("取消播放", new RecordingCallback(), target());
            assertTrue(factory.awaitNormalFinish());

            handle.cancel();

            assertEquals(1, factory.closeCount.get());
        } finally {
            client.close();
            executor.shutdownNow();
        }
    }

    private ModelTarget target() {
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("cosyvoice-v3-flash");
        candidate.setProvider("bailian");
        candidate.setModel("cosyvoice-v3-flash");
        candidate.setVoice("longxiaochun");

        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("https://dashscope.aliyuncs.com");
        provider.setApiKey("test-key");
        provider.setWorkspace("test-workspace");
        provider.setEndpoints(Map.of("tts", "/api-ws/v1/inference"));
        return new ModelTarget(candidate.getId(), candidate, provider, 1000L);
    }

    private AIModelProperties properties() {
        return properties(1000L);
    }

    private AIModelProperties properties(long taskPacketIdleTimeoutMs) {
        AIModelProperties.WebSocketConfig config = new AIModelProperties.WebSocketConfig();
        config.setConnectTimeoutMs(1000L);
        config.setTaskStartTimeoutMs(1000L);
        config.setTaskPacketIdleTimeoutMs(taskPacketIdleTimeoutMs);
        config.setMaxTotalPerModel(1);
        config.setMaxIdlePerModel(1);

        AIModelProperties properties = new AIModelProperties();
        properties.setWebsocket(config);
        return properties;
    }

    private static final class RecordingCallback implements TtsCallback {

        private byte[] audio;
        private boolean completed;
        private Throwable error;

        @Override
        public void onAudio(byte[] audio) {
            this.audio = audio;
        }

        @Override
        public void onComplete() {
            completed = true;
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
        }
    }

    private final class FakeWebSocketFactory implements WebSocket.Factory {

        private static final byte[] MP3_AUDIO = {73, 68, 51};

        private final List<JsonObject> messages = new ArrayList<>();
        private final AtomicInteger closeCount = new AtomicInteger();
        private final CountDownLatch normalFinish = new CountDownLatch(1);
        private final boolean autoFinish;
        private final boolean failOnCancel;
        private final long firstAudioDelayMs;
        private final long finishDelayMs;
        private final boolean audioBeforeFinish;
        private final long continueTaskDelayMs;
        private final int audioFrameCount;
        private final long audioFrameIntervalMs;
        private Request request;

        private FakeWebSocketFactory() {
            this(true, false, 0L, 0L);
        }

        private FakeWebSocketFactory(boolean autoFinish) {
            this(autoFinish, false, 0L, 0L);
        }

        private FakeWebSocketFactory(boolean autoFinish, boolean failOnCancel) {
            this(autoFinish, failOnCancel, 0L, 0L);
        }

        private FakeWebSocketFactory(boolean autoFinish, boolean failOnCancel,
                                     long firstAudioDelayMs, long finishDelayMs) {
            this(autoFinish, failOnCancel, firstAudioDelayMs, finishDelayMs, false, 0L, 1, 0L);
        }

        private FakeWebSocketFactory(boolean autoFinish, boolean failOnCancel,
                                     long firstAudioDelayMs, long finishDelayMs,
                                     boolean audioBeforeFinish, long continueTaskDelayMs) {
            this(autoFinish, failOnCancel, firstAudioDelayMs, finishDelayMs,
                    audioBeforeFinish, continueTaskDelayMs, 1, 0L);
        }

        private FakeWebSocketFactory(boolean autoFinish, boolean failOnCancel,
                                     long firstAudioDelayMs, long finishDelayMs,
                                     int audioFrameCount, long audioFrameIntervalMs) {
            this(autoFinish, failOnCancel, firstAudioDelayMs, finishDelayMs,
                    false, 0L, audioFrameCount, audioFrameIntervalMs);
        }

        private FakeWebSocketFactory(boolean autoFinish, boolean failOnCancel,
                                     long firstAudioDelayMs, long finishDelayMs,
                                     boolean audioBeforeFinish, long continueTaskDelayMs,
                                     int audioFrameCount, long audioFrameIntervalMs) {
            this.autoFinish = autoFinish;
            this.failOnCancel = failOnCancel;
            this.firstAudioDelayMs = firstAudioDelayMs;
            this.finishDelayMs = finishDelayMs;
            this.audioBeforeFinish = audioBeforeFinish;
            this.continueTaskDelayMs = continueTaskDelayMs;
            this.audioFrameCount = audioFrameCount;
            this.audioFrameIntervalMs = audioFrameIntervalMs;
        }

        @Override
        public WebSocket newWebSocket(Request request, WebSocketListener listener) {
            this.request = request;
            FakeWebSocket webSocket = new FakeWebSocket(request, listener);
            Response response = new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(101)
                    .message("Switching Protocols")
                    .build();
            listener.onOpen(webSocket, response);
            return webSocket;
        }

        private List<String> actions() {
            return messages.stream()
                    .map(message -> message.getAsJsonObject("header").get("action").getAsString())
                    .toList();
        }

        private List<String> taskIds() {
            return messages.stream()
                    .map(message -> message.getAsJsonObject("header").get("task_id").getAsString())
                    .toList();
        }

        private boolean awaitNormalFinish() throws InterruptedException {
            return normalFinish.await(1, TimeUnit.SECONDS);
        }

        private final class FakeWebSocket implements WebSocket {

            private final Request request;
            private final WebSocketListener listener;

            private FakeWebSocket(Request request, WebSocketListener listener) {
                this.request = request;
                this.listener = listener;
            }

            @Override
            public Request request() {
                return request;
            }

            @Override
            public long queueSize() {
                return 0;
            }

            @Override
            public boolean send(String text) {
                JsonObject message = gson.fromJson(text, JsonObject.class);
                messages.add(message);
                String action = message.getAsJsonObject("header").get("action").getAsString();
                String taskId = message.getAsJsonObject("header").get("task_id").getAsString();
                if ("run-task".equals(action)) {
                    listener.onMessage(this, event(taskId, "task-started"));
                } else if ("continue-task".equals(action) && audioBeforeFinish) {
                    listener.onMessage(this, ByteString.of(MP3_AUDIO));
                    sleep(continueTaskDelayMs);
                } else if ("finish-task".equals(action)) {
                    JsonObject input = message.getAsJsonObject("payload").getAsJsonObject("input");
                    if (input.has("directive")) {
                        listener.onMessage(this, event(taskId, failOnCancel ? "task-failed" : "task-finished"));
                    } else {
                        normalFinish.countDown();
                        if (autoFinish) {
                            respondWithAudio(taskId);
                        }
                    }
                }
                return true;
            }

            private void respondWithAudio(String taskId) {
                Runnable response = () -> {
                    if (!audioBeforeFinish) {
                        sleep(firstAudioDelayMs);
                        for (int frame = 0; frame < audioFrameCount; frame++) {
                            if (frame > 0) {
                                sleep(audioFrameIntervalMs);
                            }
                            listener.onMessage(this, ByteString.of(MP3_AUDIO));
                        }
                    }
                    sleep(finishDelayMs);
                    listener.onMessage(this, event(taskId, "task-finished"));
                };
                if (firstAudioDelayMs == 0L && finishDelayMs == 0L) {
                    response.run();
                    return;
                }
                Thread responseThread = new Thread(response, "fake-tts-response");
                responseThread.setDaemon(true);
                responseThread.start();
            }

            private void sleep(long delayMs) {
                if (delayMs <= 0L) {
                    return;
                }
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public boolean send(ByteString bytes) {
                return true;
            }

            @Override
            public boolean close(int code, String reason) {
                closeCount.incrementAndGet();
                listener.onClosed(this, code, reason == null ? "" : reason);
                return true;
            }

            @Override
            public void cancel() {
            }

            private String event(String taskId, String event) {
                JsonObject header = new JsonObject();
                header.addProperty("task_id", taskId);
                header.addProperty("event", event);
                JsonObject response = new JsonObject();
                response.add("header", header);
                response.add("payload", new JsonObject());
                return gson.toJson(response);
            }
        }
    }
}
