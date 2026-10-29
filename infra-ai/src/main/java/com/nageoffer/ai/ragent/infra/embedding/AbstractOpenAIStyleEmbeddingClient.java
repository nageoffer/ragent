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

import cn.hutool.core.collection.CollUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.HttpResponseHelper;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OpenAI 兼容协议 EmbeddingClient 抽象基类
 * 封装 /v1/embeddings 协议的通用逻辑，子类只需提供 provider 和覆写钩子方法
 */
@Slf4j
public abstract class AbstractOpenAIStyleEmbeddingClient implements EmbeddingClient {

    protected final OkHttpClient httpClient;

    protected AbstractOpenAIStyleEmbeddingClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // ==================== 子类钩子方法 ====================

    /**
     * 是否要求提供商配置 API Key，默认 true
     */
    protected boolean requiresApiKey() {
        return true;
    }

    /**
     * 子类可覆写此方法添加提供商特有的请求体字段
     * 默认实现：添加 encoding_format=float
     */
    protected void customizeRequestBody(JsonObject body, ModelTarget target) {
        body.addProperty("encoding_format", "float");
    }

    /**
     * 单次请求最大批量大小，0 表示不限制
     */
    protected int maxBatchSize() {
        return 0;
    }

    // ==================== 接口实现 ====================

    @Override
    public List<Float> embed(String text, ModelTarget target) {
        List<List<Float>> result = doEmbed(List.of(text), target);
        return result.get(0);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        if (CollUtil.isEmpty(texts)) {
            return Collections.emptyList();
        }
        int batch = maxBatchSize();
        if (batch <= 0 || texts.size() <= batch) {
            return doEmbed(texts, target);
        }

        List<List<Float>> results = new ArrayList<>(Collections.nCopies(texts.size(), null));
        for (int i = 0, n = texts.size(); i < n; i += batch) {
            int end = Math.min(i + batch, n);
            List<String> slice = texts.subList(i, end);
            List<List<Float>> part = doEmbed(slice, target);
            for (int k = 0; k < part.size(); k++) {
                results.set(i + k, part.get(k));
            }
        }
        return results;
    }

    // ==================== 模板方法：核心请求逻辑 ====================

    /**
     * 构建请求、发送 HTTP、解析 OpenAI 格式响应
     */
    protected List<List<Float>> doEmbed(List<String> texts, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        if (requiresApiKey()) {
            HttpResponseHelper.requireApiKey(provider, provider());
        }

        String url = ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.EMBEDDING);

        String model = HttpResponseHelper.requireModel(target, provider());

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray inputArray = new JsonArray();
        for (String text : texts) {
            inputArray.add(text);
        }
        body.add("input", inputArray);
        body.addProperty("dimensions", target.candidate().getDimension());
        customizeRequestBody(body, target);

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), HttpMediaTypes.JSON));
        if (requiresApiKey()) {
            requestBuilder.addHeader("Authorization", "Bearer " + provider.getApiKey());
        }
        Request request = requestBuilder.build();

        // embedding 是检索链路上唯一的远端同步调用，慢在这里会被上游按「通道无结果」吞掉
        // 成败两路都记耗时：失败路更要记，慢失败才是吃满上游预算的那一种
        long startTime = System.currentTimeMillis();

        JsonObject json;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = HttpResponseHelper.readBody(response.body());
                log.warn("{} embedding 请求失败 - 模型：{}，条数：{}，耗时：{}ms，status={}, body={}",
                        provider(), model, texts.size(), System.currentTimeMillis() - startTime, response.code(), errBody);
                throw new ModelClientException(
                        provider() + " embedding 请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            json = HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            log.warn("{} embedding 请求异常 - 模型：{}，条数：{}，耗时：{}ms，原因：{}",
                    provider(), model, texts.size(), System.currentTimeMillis() - startTime, e.getMessage());
            throw new ModelClientException(
                    provider() + " embedding 请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        if (json.has("error")) {
            JsonObject err = json.getAsJsonObject("error");
            String code = err.has("code") ? err.get("code").getAsString() : "unknown";
            String msg = err.has("message") ? err.get("message").getAsString() : "unknown";
            throw new ModelClientException(
                    provider() + " embedding 错误: " + code + " - " + msg,
                    ModelClientErrorType.PROVIDER_ERROR, null);
        }

        log.info("{} embedding 完成 - 模型：{}，条数：{}，耗时：{}ms",
                provider(), model, texts.size(), System.currentTimeMillis() - startTime);

        JsonArray data = json.getAsJsonArray("data");
        if (data == null || data.isEmpty()) {
            throw new ModelClientException(
                    provider() + " embedding 响应中缺少 data 数组",
                    ModelClientErrorType.INVALID_RESPONSE, null);
        }
        if (data.size() != texts.size()) {
            throw new ModelClientException(
                    provider() + " embedding 响应条数不匹配: expected=" + texts.size() + ", actual=" + data.size(),
                    ModelClientErrorType.INVALID_RESPONSE, null);
        }

        boolean indexed = data.get(0).getAsJsonObject().has("index");
        List<List<Float>> results = new ArrayList<>(Collections.nCopies(texts.size(), null));
        for (int responseIndex = 0; responseIndex < data.size(); responseIndex++) {
            JsonElement el = data.get(responseIndex);
            JsonObject obj = el.getAsJsonObject();
            JsonArray emb = obj.getAsJsonArray("embedding");
            if (emb == null || emb.isEmpty()) {
                throw new ModelClientException(
                        provider() + " embedding 响应中缺少 embedding 字段",
                        ModelClientErrorType.INVALID_RESPONSE, null);
            }
            List<Float> vector = new ArrayList<>(emb.size());
            for (JsonElement v : emb) {
                vector.add(v.getAsFloat());
            }

            boolean hasIndex = obj.has("index") && !obj.get("index").isJsonNull();
            if (hasIndex != indexed) {
                throw new ModelClientException(
                        provider() + " embedding 响应中的 index 字段不一致",
                        ModelClientErrorType.INVALID_RESPONSE, null);
            }
            int resultIndex = indexed ? parseResultIndex(obj, texts.size()) : responseIndex;
            if (results.get(resultIndex) != null) {
                throw new ModelClientException(
                        provider() + " embedding 响应包含重复 index: " + resultIndex,
                        ModelClientErrorType.INVALID_RESPONSE, null);
            }
            results.set(resultIndex, vector);
        }

        return results;
    }

    private int parseResultIndex(JsonObject item, int expectedSize) {
        int index;
        try {
            index = item.get("index").getAsInt();
        } catch (RuntimeException e) {
            throw new ModelClientException(
                    provider() + " embedding 响应包含无效 index",
                    ModelClientErrorType.INVALID_RESPONSE, null, e);
        }
        if (index < 0 || index >= expectedSize) {
            throw new ModelClientException(
                    provider() + " embedding 响应 index 越界: " + index,
                    ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return index;
    }
}
