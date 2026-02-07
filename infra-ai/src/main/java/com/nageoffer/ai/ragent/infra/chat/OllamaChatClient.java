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
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaChatClient implements ChatClient {

    private final OkHttpClient httpClient;
    @Qualifier("modelStreamExecutor")
    private final Executor modelStreamExecutor;

    private final Gson gson = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    @Override
    public String provider() {
        return ModelProvider.OLLAMA.getId();
    }

    @Override
    @RagTraceNode(name = "ollama-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        String url = resolveUrl(provider, target);

        JsonObject body = new JsonObject();
        body.addProperty("model", requireModel(target));
        body.addProperty("stream", false);

        JsonArray messages = buildMessages(request);
        body.add("messages", messages);

        if (request.getTemperature() != null) {
            body.addProperty("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.addProperty("top_p", request.getTopP());
        }
        if (request.getTopK() != null) {
            body.addProperty("top_k", request.getTopK());
        }
        if (request.getMaxTokens() != null) {
            body.addProperty("num_predict", request.getMaxTokens());
        }

        Request requestHttp = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .build();

        JsonObject json;
        try (Response response = httpClient.newCall(requestHttp).execute()) {
            if (!response.isSuccessful()) {
                String errBody = readBody(response.body());
                log.warn("Ollama chat 请求失败: status={}, body={}", response.code(), errBody);
                throw new ModelClientException(
                        "Ollama chat 请求失败: HTTP " + response.code(),
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            json = parseJsonBody(response.body());
        } catch (IOException e) {
            throw new ModelClientException("Ollama chat 请求失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        return extractChatContent(json);
    }

    @Override
    @RagTraceNode(name = "ollama-stream-chat", type = "LLM_PROVIDER")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        Call call = httpClient.newCall(buildStreamRequest(request, target));
        return StreamAsyncExecutor.submit(
                modelStreamExecutor,
                call,
                callback,
                cancelled -> doStream(call, callback, cancelled)
        );
    }

    private void doStream(Call call, StreamCallback callback, AtomicBoolean cancelled) {
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                String body = readBody(response.body());
                throw new ModelClientException(
                        "Ollama 流式请求失败: HTTP " + response.code() + " - " + body,
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new ModelClientException("Ollama 流式响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
            }
            BufferedSource source = body.source();
            boolean completed = false;
            while (!cancelled.get()) {
                String line = source.readUtf8Line();
                if (line == null) {
                    break;
                }
                if (line.trim().isEmpty()) {
                    continue;
                }

                JsonObject obj = gson.fromJson(line, JsonObject.class);

                if (obj.has("done") && obj.get("done").getAsBoolean()) {
                    callback.onComplete();
                    completed = true;
                    break;
                }

                if (obj.has("message")) {
                    JsonObject msg = obj.getAsJsonObject("message");
                    if (msg.has("content")) {
                        String chunk = msg.get("content").getAsString();
                        if (!chunk.isEmpty()) {
                            callback.onContent(chunk);
                        }
                    }
                }
            }
            if (!cancelled.get() && !completed) {
                throw new ModelClientException("Ollama 流式响应异常结束", ModelClientErrorType.INVALID_RESPONSE, null);
            }
        } catch (Exception e) {
            callback.onError(e);
        }
    }

    private Request buildStreamRequest(ChatRequest request, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        String url = resolveUrl(provider, target);

        JsonObject body = new JsonObject();
        body.addProperty("model", requireModel(target));
        body.addProperty("stream", true);

        JsonArray messages = buildMessages(request);
        body.add("messages", messages);

        if (request.getTemperature() != null) {
            body.addProperty("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.addProperty("top_p", request.getTopP());
        }
        if (request.getTopK() != null) {
            body.addProperty("top_k", request.getTopK());
        }
        if (request.getMaxTokens() != null) {
            body.addProperty("num_predict", request.getMaxTokens());
        }

        return new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .build();
    }

    private JsonArray buildMessages(ChatRequest request) {
        JsonArray arr = new JsonArray();

        List<ChatMessage> messages = request.getMessages();
        if (CollUtil.isNotEmpty(messages)) {
            for (ChatMessage m : messages) {
                JsonObject msg = new JsonObject();
                msg.addProperty("role", toOllamaRole(m.getRole()));
                msg.addProperty("content", m.getContent());
                arr.add(msg);
            }
        }

        return arr;
    }

    private String toOllamaRole(ChatMessage.Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }

    private AIModelProperties.ProviderConfig requireProvider(ModelTarget target) {
        if (target == null || target.provider() == null) {
            throw new IllegalStateException("Ollama 提供商配置缺失");
        }
        return target.provider();
    }

    private String requireModel(ModelTarget target) {
        if (target == null || target.candidate() == null || target.candidate().getModel() == null) {
            throw new IllegalStateException("Ollama 模型名称缺失");
        }
        return target.candidate().getModel();
    }

    private String resolveUrl(AIModelProperties.ProviderConfig provider, ModelTarget target) {
        return ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.CHAT);
    }

    private JsonObject parseJsonBody(ResponseBody body) throws IOException {
        if (body == null) {
            throw new ModelClientException("Ollama 响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
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

    private String extractChatContent(JsonObject json) {
        if (json == null || !json.has("message")) {
            throw new ModelClientException("Ollama 响应缺少 message", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject message = json.getAsJsonObject("message");
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new ModelClientException("Ollama 响应缺少 content", ModelClientErrorType.INVALID_RESPONSE, null);
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
