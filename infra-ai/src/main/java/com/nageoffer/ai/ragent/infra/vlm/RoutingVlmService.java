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

package com.nageoffer.ai.ragent.infra.vlm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.HttpResponseHelper;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 路由式 VLM 服务实现类
 * <p>
 * 复用 chat 链路同款基础设施：provider 配置、URL 解析、错误体系、OkHttp 同步客户端
 * 与 chat 的唯一差异是请求体 messages[].content 为多模态数组（text + image_url）
 * 入库期单次同步调用，从 ai.vlm 组取首个可用候选即可，无需 fallback
 */
@Slf4j
@Service
@Primary
public class RoutingVlmService implements VlmService {

    private static final String LABEL = "vlm";

    private final ModelSelector selector;
    private final OkHttpClient syncHttpClient;
    private final Map<Long, OkHttpClient> syncClientByTimeout = new ConcurrentHashMap<>();

    public RoutingVlmService(ModelSelector selector,
                             @Qualifier("syncHttpClient") OkHttpClient syncHttpClient) {
        this.selector = selector;
        this.syncHttpClient = syncHttpClient;
    }

    @Override
    @RagTraceNode(name = "vlm-describe", type = "VLM")
    public String describeImage(byte[] imageBytes, String mime, String prompt, Integer maxOutputTokens) {
        ModelTarget target = resolveTarget();
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, LABEL);
        HttpResponseHelper.requireApiKey(provider, LABEL);

        String url = ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.CHAT);
        JsonObject reqBody = buildMultimodalBody(target, prompt, imageBytes, mime, maxOutputTokens);

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .build();

        JsonObject respJson;
        try (Response response = resolveSyncClient(target.timeoutMs()).newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                log.warn("VLM 请求失败: status={}, body={}", response.code(), body);
                throw new ModelClientException(
                        "VLM 请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code());
            }
            respJson = HttpResponseHelper.parseJson(response.body(), LABEL);
        } catch (IOException e) {
            throw new ModelClientException(
                    "VLM 请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        return extractContent(respJson);
    }

    /**
     * VLM 推理通常显著慢于普通同步请求，按模型组预算覆盖 read/call 超时。
     */
    private OkHttpClient resolveSyncClient(Long timeoutMs) {
        if (timeoutMs == null) {
            return syncHttpClient;
        }
        return syncClientByTimeout.computeIfAbsent(timeoutMs, ms -> syncHttpClient.newBuilder()
                .readTimeout(ms, TimeUnit.MILLISECONDS)
                .callTimeout(ms, TimeUnit.MILLISECONDS)
                .build());
    }

    private ModelTarget resolveTarget() {
        List<ModelTarget> targets = selector.selectVlmCandidates();
        if (targets.isEmpty()) {
            throw new ModelClientException("VLM 模型不可用，请检查 ai.vlm 配置",
                    ModelClientErrorType.PROVIDER_ERROR, null);
        }
        return targets.get(0);
    }

    /**
     * 构造多模态请求体：content 为数组，图片以 base64 data url 内联
     * 百炼 /compatible-mode 端点兼容 OpenAI image_url 协议
     */
    private JsonObject buildMultimodalBody(ModelTarget target, String prompt, byte[] image, String mime, Integer maxOutputTokens) {
        String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(image);

        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", prompt);

        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", dataUrl);
        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        imagePart.add("image_url", imageUrl);

        JsonArray content = new JsonArray();
        content.add(textPart);
        content.add(imagePart);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.add("content", content);

        JsonArray messages = new JsonArray();
        messages.add(userMsg);

        JsonObject body = new JsonObject();
        body.addProperty("model", HttpResponseHelper.requireModel(target, LABEL));
        body.add("messages", messages);
        if (maxOutputTokens != null && maxOutputTokens > 0) {
            body.addProperty("max_tokens", maxOutputTokens);
        }
        return body;
    }

    /**
     * 抽取 OpenAI 兼容响应的 choices[0].message.content
     */
    private String extractContent(JsonObject respJson) {
        if (respJson == null || !respJson.has("choices")) {
            throw new ModelClientException("VLM 响应缺少 choices", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonArray choices = respJson.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new ModelClientException("VLM 响应 choices 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject choice0 = choices.get(0).getAsJsonObject();
        if (choice0 == null || !choice0.has("message")) {
            throw new ModelClientException("VLM 响应缺少 message", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject message = choice0.getAsJsonObject("message");
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new ModelClientException("VLM 响应缺少 content", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return message.get("content").getAsString();
    }
}
