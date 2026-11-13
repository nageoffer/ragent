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

package com.nageoffer.ai.ragent.infra.jev;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.HttpResponseHelper;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Jev 结构化评估客户端（TypeSafe System One 协议）
 * <p>
 * 一次 POST 提交一段 state 与多条 Noul 判断，响应里没有正文；只做传输与鉴权，不认识意图节点
 */
@Service
public class JevClient {

    private static final String LABEL = "jev";

    private final AIModelProperties properties;
    private final OkHttpClient httpClient;

    public JevClient(AIModelProperties properties,
                     @Qualifier("jevHttpClient") OkHttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    /**
     * 取出 Jev 调用目标，由选用 Jev 的一方在启动期调一次
     * <p>
     * 配置完整性已由启动期的 Jev 配置校验器兜住，这里只做解析，不重复报错
     */
    public JevTarget requireTarget() {
        AIModelProperties.Jev jev = properties.getJev();
        AIModelProperties.ModelCandidate candidate = jev.getCandidates().stream()
                .filter(each -> each.getId().equals(jev.getDefaultModel()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("没有可用的 Jev 模型配置"));
        AIModelProperties.ProviderConfig provider = properties.getProviders().get(candidate.getProvider());
        return new JevTarget(
                ModelUrlResolver.resolveUrl(provider, candidate, ModelCapability.JEV),
                provider.getApiKey(),
                candidate.getModel());
    }

    /**
     * 提交一次结构化评估，不做重试：失败原因交给调用方记录并降级
     *
     * @param target 由调用方在启动期用 {@link #requireTarget()} 解析好
     * @throws ModelClientException 超时、网络失败、限流、服务错误或响应不可解析
     */
    public JevResponse evaluate(JevRequest request, JevTarget target) {
        if (request == null || CollUtil.isEmpty(request.questions())) {
            throw new IllegalArgumentException("Jev 请求至少要带一条判断");
        }

        Request httpRequest = new Request.Builder()
                .url(target.url())
                .post(RequestBody.create(buildBody(target.model(), request).toString(), HttpMediaTypes.JSON))
                .addHeader("Authorization", "Bearer " + target.apiKey())
                .build();

        JsonObject respJson;
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                throw new ModelClientException(
                        LABEL + " 请求失败: HTTP " + response.code() + ", body=" + body,
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            respJson = HttpResponseHelper.parseJson(response.body(), LABEL);
        } catch (IOException e) {
            throw new ModelClientException(
                    LABEL + " 请求失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        return parse(respJson, request);
    }

    /**
     * 读取单条 Noul 答案，异常消息只点明哪个 key 坏在哪，不带响应正文
     */
    private double requireNoul(JsonObject answers, String key) {
        JsonElement element = answers.get(key);
        if (element == null || !element.isJsonObject()) {
            throw invalidResponse("缺少判断 " + key + " 的答案");
        }
        JsonObject answer = element.getAsJsonObject();
        if (!answer.has("noul") || !answer.get("noul").isJsonPrimitive()) {
            throw invalidResponse("判断 " + key + " 的答案不是 noul 形态");
        }
        double noul;
        try {
            noul = answer.get("noul").getAsDouble();
        } catch (NumberFormatException e) {
            throw invalidResponse("判断 " + key + " 的 noul 不是数字");
        }
        if (Double.isNaN(noul) || noul < 0D || noul > 1D) {
            throw invalidResponse("判断 " + key + " 的 noul 越界: " + noul);
        }
        return noul;
    }

    private ModelClientException invalidResponse(String detail) {
        return new ModelClientException(
                LABEL + " " + detail, ModelClientErrorType.INVALID_RESPONSE, null);
    }

    private JsonObject buildBody(String model, JevRequest request) {
        JsonObject questions = new JsonObject();
        for (JevNoulQuestion each : request.questions()) {
            JsonObject question = new JsonObject();
            question.addProperty("type", "noul");
            question.addProperty("instructions", each.instructions());
            JsonObject criteria = new JsonObject();
            criteria.addProperty("true", each.trueCriteria());
            criteria.addProperty("false", each.falseCriteria());
            question.add("criteria", criteria);
            questions.add(each.key(), question);
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("state", StrUtil.nullToEmpty(request.state()));
        body.add("questions", questions);
        return body;
    }

    /**
     * 取出 Noul 概率，要求每个请求 key 都有合法答案
     * <p>
     * 缺一条就判整份响应不可信：剩下的高分照样会收窄检索，而漏掉的那个可能才是对的
     * 响应里多出的未知 key 忽略，它不能替代任何请求 key
     */
    private JevResponse parse(JsonObject respJson, JevRequest request) {
        if (respJson == null || !respJson.has("answers") || !respJson.get("answers").isJsonObject()) {
            throw invalidResponse("响应缺少 answers");
        }
        JsonObject answers = respJson.getAsJsonObject("answers");

        Map<String, Double> nouls = new LinkedHashMap<>(request.questions().size());
        for (JevNoulQuestion question : request.questions()) {
            nouls.put(question.key(), requireNoul(answers, question.key()));
        }

        return new JevResponse(nouls);
    }
}
