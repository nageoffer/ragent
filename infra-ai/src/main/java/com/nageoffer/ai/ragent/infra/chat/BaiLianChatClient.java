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

import cn.hutool.core.collection.CollUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class BaiLianChatClient implements ChatClient {

    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();
    private final Executor modelStreamExecutor;

    @Autowired
    public BaiLianChatClient(OkHttpClient httpClient,
                             @Qualifier("modelStreamExecutor") Executor modelStreamExecutor) {
        this.httpClient = httpClient;
        this.modelStreamExecutor = modelStreamExecutor;
    }

    @Override
    public String provider() {
        return ModelProvider.BAI_LIAN.getId();
    }

    @Override
    public String chat(ChatRequest request, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProvider(target);

        JsonObject reqBody = buildRequestBody(request, target, false);
        Request requestHttp = new Request.Builder()
                .url(resolveUrl(provider, target))
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();

        JsonObject respJson;
        try (Response response = httpClient.newCall(requestHttp).execute()) {
            if (!response.isSuccessful()) {
                String body = readBody(response.body());
                log.warn("百炼同步请求失败: status={}, body={}", response.code(), body);
                throw new ModelClientException(
                        "百炼同步请求失败: HTTP " + response.code(),
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            respJson = parseJsonBody(response.body());
        } catch (IOException e) {
            throw new ModelClientException("百炼同步请求失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        return extractChatContent(respJson);
    }

    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Call call = httpClient.newCall(buildStreamRequest(request, target));
        try {
            CompletableFuture.runAsync(() -> doStream(call, callback, cancelled), modelStreamExecutor);
        } catch (RejectedExecutionException ex) {
            call.cancel();
            callback.onError(new ModelClientException("流式线程池繁忙", ModelClientErrorType.SERVER_ERROR, null, ex));
            return () -> {
            };
        }
        return () -> {
            cancelled.set(true);
            call.cancel();
        };
    }

    private void doStream(Call call, StreamCallback callback, AtomicBoolean cancelled) {
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                String body = readBody(response.body());
                throw new ModelClientException(
                        "百炼流式请求失败: HTTP " + response.code() + " - " + body,
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new ModelClientException("百炼流式响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
            }
            BufferedSource source = body.source();
            while (!cancelled.get()) {
                String line = source.readUtf8Line();
                if (line == null) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }

                String payload = line.trim();
                if (payload.startsWith("data:")) {
                    payload = payload.substring("data:".length()).trim();
                }

                if ("[DONE]".equalsIgnoreCase(payload)) {
                    callback.onComplete();
                    break;
                }

                try {
                    JsonObject obj = gson.fromJson(payload, JsonObject.class);
                    JsonArray choices = obj.getAsJsonArray("choices");
                    if (choices == null || choices.isEmpty()) {
                        continue;
                    }

                    JsonObject choice0 = choices.get(0).getAsJsonObject();
                    String chunk = null;

                    if (choice0.has("delta") && choice0.get("delta").isJsonObject()) {
                        JsonObject delta = choice0.getAsJsonObject("delta");
                        if (delta.has("content")) {
                            JsonElement ce = delta.get("content");
                            if (ce != null && !ce.isJsonNull()) {
                                chunk = ce.getAsString();
                            }
                        }
                    }

                    if (chunk == null && choice0.has("message") && choice0.get("message").isJsonObject()) {
                        JsonObject msg = choice0.getAsJsonObject("message");
                        if (msg.has("content")) {
                            JsonElement ce = msg.get("content");
                            if (ce != null && !ce.isJsonNull()) {
                                chunk = ce.getAsString();
                            }
                        }
                    }

                    if (chunk != null && !chunk.isEmpty()) {
                        callback.onContent(chunk);
                    }

                    if (choice0.has("finish_reason")) {
                        JsonElement fr = choice0.get("finish_reason");
                        if (fr != null && !fr.isJsonNull()) {
                            callback.onComplete();
                            break;
                        }
                    }

                } catch (Exception parseEx) {
                    log.warn("百炼流式响应解析失败: payload={}", payload, parseEx);
                }
            }
        } catch (Exception e) {
            callback.onError(e);
        }
    }

    private JsonObject buildRequestBody(ChatRequest request, ModelTarget target, boolean stream) {
        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", requireModel(target));
        if (stream) {
            reqBody.addProperty("stream", true);
        }

        JsonArray messages = buildMessages(request);
        reqBody.add("messages", messages);

        if (request.getTemperature() != null) {
            reqBody.addProperty("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            reqBody.addProperty("top_p", request.getTopP());
        }
        if (request.getTopK() != null) {
            reqBody.addProperty("top_k", request.getTopK());
        }
        if (request.getMaxTokens() != null) {
            reqBody.addProperty("max_tokens", request.getMaxTokens());
        }
        if (stream && request.getThinking() != null && request.getThinking()) {
            reqBody.addProperty("enable_thinking", true);
        }
        return reqBody;
    }

    private JsonArray buildMessages(ChatRequest request) {
        JsonArray arr = new JsonArray();

        List<com.nageoffer.ai.ragent.framework.convention.ChatMessage> messages = request.getMessages();
        if (CollUtil.isNotEmpty(messages)) {
            for (com.nageoffer.ai.ragent.framework.convention.ChatMessage m : messages) {
                JsonObject msg = new JsonObject();
                msg.addProperty("role", toOpenAiRole(m.getRole()));
                msg.addProperty("content", m.getContent());
                arr.add(msg);
            }
        }

        return arr;
    }

    private String toOpenAiRole(com.nageoffer.ai.ragent.framework.convention.ChatMessage.Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }

    private AIModelProperties.ProviderConfig requireProvider(ModelTarget target) {
        if (target == null || target.provider() == null) {
            throw new IllegalStateException("百炼提供商配置缺失");
        }
        if (target.provider().getApiKey() == null || target.provider().getApiKey().isBlank()) {
            throw new IllegalStateException("百炼API密钥缺失");
        }
        return target.provider();
    }

    private String requireModel(ModelTarget target) {
        if (target == null || target.candidate() == null || target.candidate().getModel() == null) {
            throw new IllegalStateException("百炼模型名称缺失");
        }
        return target.candidate().getModel();
    }

    private Request buildStreamRequest(ChatRequest request, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        JsonObject reqBody = buildRequestBody(request, target, true);
        return new Request.Builder()
                .url(resolveUrl(provider, target))
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .addHeader("Accept", "text/event-stream")
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();
    }

    private JsonObject parseJsonBody(ResponseBody body) throws IOException {
        if (body == null) {
            throw new ModelClientException("百炼响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        String content = body.string();
        return gson.fromJson(content, JsonObject.class);
    }

    private String readBody(ResponseBody body) throws IOException {
        if (body == null) {
            return "";
        }
        return new String(body.bytes(), StandardCharsets.UTF_8);
    }

    private String resolveUrl(AIModelProperties.ProviderConfig provider, ModelTarget target) {
        return ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.CHAT);
    }

    private String extractChatContent(JsonObject respJson) {
        if (respJson == null || !respJson.has("choices")) {
            throw new ModelClientException("百炼响应缺少 choices", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonArray choices = respJson.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new ModelClientException("百炼响应 choices 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject choice0 = choices.get(0).getAsJsonObject();
        if (choice0 == null || !choice0.has("message")) {
            throw new ModelClientException("百炼响应缺少 message", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject message = choice0.getAsJsonObject("message");
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new ModelClientException("百炼响应缺少 content", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return message.get("content").getAsString();
    }

    private ModelClientErrorType classifyStatus(int status) {
        if (status == 401 || status == 403) {
            return ModelClientErrorType.UNAUTHORIZED;
        }
        if (status == 429) {
            return ModelClientErrorType.RATE_LIMITED;
        }
        if (status >= 500) {
            return ModelClientErrorType.SERVER_ERROR;
        }
        return ModelClientErrorType.CLIENT_ERROR;
    }
}
