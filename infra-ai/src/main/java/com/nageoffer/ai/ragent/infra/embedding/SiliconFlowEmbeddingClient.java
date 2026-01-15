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

package com.nageoffer.ai.ragent.infra.embedding;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SiliconFlowEmbeddingClient implements EmbeddingClient {

    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    @Override
    public String provider() {
        return ModelProvider.SILICON_FLOW.getId();
    }

    @Override
    public List<Float> embed(String text, ModelTarget target) {
        return embedBatch(List.of(text), target).get(0);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        if (CollectionUtils.isEmpty(texts)) {
            return Collections.emptyList();
        }

        final int maxBatch = 32;
        List<List<Float>> results = new ArrayList<>(Collections.nCopies(texts.size(), null));
        for (int i = 0, n = texts.size(); i < n; i += maxBatch) {
            int end = Math.min(i + maxBatch, n);
            List<String> slice = texts.subList(i, end);
            try {
                List<List<Float>> part = doEmbedOnce(slice, target);
                for (int k = 0; k < part.size(); k++) {
                    results.set(i + k, part.get(k));
                }
            } catch (Exception e) {
                log.error("SiliconFlow embeddings 调用失败", e);
                throw new RuntimeException("调用 SiliconFlow Embedding 失败: " + e.getMessage(), e);
            }
        }

        for (int i = 0; i < results.size(); i++) {
            if (results.get(i) == null) {
                throw new ModelClientException("Embedding 结果缺失，index=" + i, ModelClientErrorType.INVALID_RESPONSE, null);
            }
        }
        return results;
    }

    private List<List<Float>> doEmbedOnce(List<String> slice, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        Map<String, Object> req = new HashMap<>();
        req.put("model", requireModel(target));
        req.put("input", slice);
        if (target.candidate().getDimension() != null) {
            req.put("dimensions", target.candidate().getDimension());
        }
        req.put("encoding_format", "float");

        Request request = new Request.Builder()
                .url(resolveUrl(provider, target))
                .post(RequestBody.create(gson.toJson(req), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();

        JsonObject root;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = readBody(response.body());
                log.error("SiliconFlow embeddings HTTP error: status={}, body={}", response.code(), errBody);
                throw new ModelClientException(
                        "调用 SiliconFlow Embedding 失败: HTTP " + response.code() + " - " + errBody,
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            root = parseJsonBody(response.body());
        } catch (IOException e) {
            throw new ModelClientException("调用 SiliconFlow Embedding 失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        if (root.has("error")) {
            JsonObject err = root.getAsJsonObject("error");
            String code = err.has("code") ? err.get("code").getAsString() : "unknown";
            String msg = err.has("message") ? err.get("message").getAsString() : "unknown";
            throw new ModelClientException("SiliconFlow Embedding 错误: " + code + " - " + msg, ModelClientErrorType.PROVIDER_ERROR, null);
        }

        JsonArray data = root.getAsJsonArray("data");
        if (data == null) {
            throw new ModelClientException("SiliconFlow Embedding 响应中缺少 data 数组", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        List<List<Float>> vectors = new ArrayList<>(data.size());
        for (JsonElement el : data) {
            JsonObject obj = el.getAsJsonObject();
            JsonArray emb = obj.getAsJsonArray("embedding");
            if (emb == null) {
                throw new ModelClientException("SiliconFlow Embedding 响应中缺少 embedding 字段", ModelClientErrorType.INVALID_RESPONSE, null);
            }

            List<Float> v = new ArrayList<>(emb.size());
            for (JsonElement num : emb) v.add(num.getAsFloat());
            vectors.add(v);
        }

        return vectors;
    }

    private AIModelProperties.ProviderConfig requireProvider(ModelTarget target) {
        if (target == null || target.provider() == null) {
            throw new IllegalStateException("SiliconFlow provider config is missing");
        }
        return target.provider();
    }

    private String requireModel(ModelTarget target) {
        if (target == null || target.candidate() == null || target.candidate().getModel() == null) {
            throw new IllegalStateException("SiliconFlow model name is missing");
        }
        return target.candidate().getModel();
    }

    private String resolveUrl(AIModelProperties.ProviderConfig provider, ModelTarget target) {
        return ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.EMBEDDING);
    }

    private JsonObject parseJsonBody(ResponseBody body) throws IOException {
        if (body == null) {
            throw new ModelClientException("SiliconFlow Embedding 响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        String content = body.string();
        return JsonParser.parseString(content).getAsJsonObject();
    }

    private String readBody(ResponseBody body) throws IOException {
        if (body == null) {
            return "";
        }
        return new String(body.bytes(), StandardCharsets.UTF_8);
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
