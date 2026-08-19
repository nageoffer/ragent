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
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.http.HttpResponseHelper;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import com.nageoffer.ai.ragent.infra.voice.websocket.VoiceConnection;
import jakarta.annotation.PreDestroy;
import okhttp3.Request;
import okhttp3.WebSocket;
import okio.ByteString;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * 阿里云百炼 CosyVoice TTS 客户端
 */
@Component
public class BaiLianTtsClient extends AbstractWebSocketTtsClient<
        BaiLianTtsClient.BaiLianTtsTaskParam,
        BaiLianTtsClient.BaiLianTtsConnection> {

    private final WebSocket.Factory webSocketFactory;
    private final AIModelProperties.WebSocketConfig websocketConfig;

    public BaiLianTtsClient(@Qualifier("streamingHttpClient") WebSocket.Factory webSocketFactory,
                            @Qualifier("webSocketLifecycleExecutor") Executor taskExecutor,
                            AIModelProperties properties) {
        super(taskExecutor, properties.getWebsocket());
        this.webSocketFactory = webSocketFactory;
        this.websocketConfig = properties.getWebsocket();
    }

    @Override
    public String provider() {
        return ModelProvider.BAI_LIAN.getId();
    }

    @Override
    protected BaiLianTtsConnection createConnection(ModelTarget target) {
        return new BaiLianTtsConnection(target, webSocketFactory, websocketConfig);
    }

    @Override
    protected BaiLianTtsTaskParam buildTaskParam(ModelTarget target) {
        return new BaiLianTtsTaskParam(
                HttpResponseHelper.requireModel(target, "TTS"),
                requireVoice(target)
        );
    }

    /**
     * 获取候选模型配置的音色
     */
    private String requireVoice(ModelTarget target) {
        String voice = target.candidate().getVoice();
        if (voice == null || voice.isBlank()) {
            throw new ModelClientException("TTS 未配置默认音色，modelId=" + target.id(),
                    ModelClientErrorType.CLIENT_ERROR, null);
        }
        return voice;
    }

    @PreDestroy
    public void destroy() {
        close();
    }

    record BaiLianTtsTaskParam(
            String model,
            String voice
    ) {
    }

    static final class BaiLianTtsConnection
            extends VoiceConnection<BaiLianTtsTaskParam, String, byte[]> {

        private final Gson gson = new Gson();

        private BaiLianTtsConnection(ModelTarget target,
                                     WebSocket.Factory webSocketFactory,
                                     AIModelProperties.WebSocketConfig websocketConfig) {
            super(target, webSocketFactory, websocketConfig);
        }

        @Override
        protected Request buildWebSocketRequest() {
            AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target(), "TTS");
            HttpResponseHelper.requireApiKey(provider, "TTS");
            String url = toWebSocketUrl(ModelUrlResolver.resolveUrl(provider, target().candidate(), ModelCapability.TTS));
            Request.Builder request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + provider.getApiKey());
            if (provider.getWorkspace() != null && !provider.getWorkspace().isBlank()) {
                request.addHeader("X-DashScope-WorkSpace", provider.getWorkspace());
            }
            return request.build();
        }

        @Override
        protected void doStartTask(String taskId, BaiLianTtsTaskParam param) {
            JsonObject parameters = new JsonObject();
            parameters.addProperty("text_type", "PlainText");
            parameters.addProperty("voice", param.voice());
            parameters.addProperty("format", "mp3");

            JsonObject payload = new JsonObject();
            payload.addProperty("task_group", "audio");
            payload.addProperty("task", "tts");
            payload.addProperty("function", "SpeechSynthesizer");
            payload.addProperty("model", param.model());
            payload.add("parameters", parameters);
            payload.add("input", new JsonObject());

            sendJson(command("run-task", taskId, payload));
        }

        @Override
        protected void doSend(String taskId, String text) {
            JsonObject input = new JsonObject();
            input.addProperty("text", text);
            JsonObject payload = new JsonObject();
            payload.add("input", input);
            sendJson(command("continue-task", taskId, payload));
        }

        @Override
        protected void doFinishTask(String taskId) {
            JsonObject payload = new JsonObject();
            payload.add("input", new JsonObject());
            sendJson(command("finish-task", taskId, payload));
        }

        @Override
        protected void doCancelTask(String taskId) {
            JsonObject input = new JsonObject();
            input.addProperty("directive", "cancel");
            JsonObject payload = new JsonObject();
            payload.add("input", input);
            sendJson(command("finish-task", taskId, payload));
        }

        private JsonObject command(String action, String taskId, JsonObject payload) {
            JsonObject header = new JsonObject();
            header.addProperty("action", action);
            header.addProperty("task_id", taskId);
            header.addProperty("streaming", "duplex");

            JsonObject command = new JsonObject();
            command.add("header", header);
            command.add("payload", payload);
            return command;
        }

        private void sendJson(JsonObject message) {
            WebSocket current = webSocket();
            if (!current.send(gson.toJson(message))) {
                throw new ModelClientException("TTS WebSocket 发送失败，modelId=" + modelId(),
                        ModelClientErrorType.NETWORK_ERROR, null);
            }
        }

        @Override
        protected void handleTextMessage(String text) {
            JsonObject response = gson.fromJson(text, JsonObject.class);
            JsonObject header = response.getAsJsonObject("header");
            if (header == null || !header.has("event")) {
                return;
            }
            String responseTaskId = header.has("task_id") ? header.get("task_id").getAsString() : null;
            validateResponseTaskId(responseTaskId);
            String event = header.get("event").getAsString();
            switch (event) {
                case "task-started" -> markTaskStarted();
                case "task-finished" -> markTaskFinished();
                case "task-failed" -> failTask(header);
                default -> {
                    // 忽略无需处理的元信息事件
                }
            }
        }

        @Override
        protected byte[] decodeBinaryMessage(ByteString bytes) {
            return bytes.toByteArray();
        }

        private void failTask(JsonObject header) {
            String errorCode = header.has("error_code") ? header.get("error_code").getAsString() : "unknown";
            String errorMessage = header.has("error_message") ? header.get("error_message").getAsString() : "unknown";
            markTaskFailed(new ModelClientException(
                    "TTS 任务失败，modelId=" + modelId() + ": " + errorCode + " - " + errorMessage,
                    ModelClientErrorType.SERVER_ERROR,
                    null
            ));
        }

    }
}
