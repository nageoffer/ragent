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

package com.nageoffer.ai.ragent.agent.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/**
 * ZenMux 网关上 Gemini 思考签名的搬运：网关只认 reasoning_details 里的签名，SDK 只认挂在工具调用上的签名
 * 收：流里签名先到、工具调用后到，按 id 补到工具调用上，SDK 把它存成工具调用的元数据，聚合与落库都带得住
 * 发：SDK 写在 function.thought_signature 的签名挪回 reasoning_details，每条消息一份
 */
public class ZenMuxGeminiThoughtSignatureTransport implements HttpTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ENCRYPTED = "reasoning.encrypted";

    /**
     * 只搬 Gemini 的签名；别的上游带回的加密推理形状不同，原样放过
     */
    private static final String GEMINI_FORMAT = "google-gemini-v1";

    private static final String SIGNATURE = "thought_signature";

    private final HttpTransport delegate;

    public ZenMuxGeminiThoughtSignatureTransport(HttpTransport delegate) {
        this.delegate = delegate;
    }

    /**
     * 非流式响应里签名和工具调用在同一条消息，SDK 自己按 id 配得上，只需改出站
     */
    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        return delegate.execute(moveSignaturesToDetails(request));
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        HttpRequest outbound = moveSignaturesToDetails(request);
        // 签名表跟着单次订阅走，类上不存流状态
        return Flux.defer(() -> {
            Map<String, String> signatures = new HashMap<>();
            return delegate.stream(outbound).map(data -> attachSignatures(data, signatures));
        });
    }

    /**
     * 底层是 SDK 共享的默认传输，不归这里关
     */
    @Override
    public void close() {
    }

    static String attachSignatures(String data, Map<String, String> signatures) {
        if (!data.contains(ENCRYPTED) && (signatures.isEmpty() || !data.contains("tool_calls"))) {
            return data;
        }
        JsonNode root = readTree(data);
        if (root == null) {
            return data;
        }
        boolean changed = false;
        for (JsonNode choice : root.path("choices")) {
            JsonNode delta = choice.path("delta");
            for (JsonNode detail : delta.path("reasoning_details")) {
                if (ENCRYPTED.equals(detail.path("type").asText()) && GEMINI_FORMAT.equals(detail.path("format").asText())
                        && detail.hasNonNull("id")) {
                    signatures.put(detail.get("id").asText(), detail.path("data").asText());
                }
            }
            for (JsonNode call : delta.path("tool_calls")) {
                String signature = signatures.get(call.path("id").asText());
                if (signature != null && !call.hasNonNull(SIGNATURE)) {
                    ((ObjectNode) call).put(SIGNATURE, signature);
                    changed = true;
                }
            }
        }
        return changed ? root.toString() : data;
    }

    /**
     * 签名属于这一步的第一个工具调用；SDK 会把它复制给同批其余调用，复制品一并删掉
     */
    static HttpRequest moveSignaturesToDetails(HttpRequest request) {
        String body = request.getBody();
        if (body == null || !body.contains(SIGNATURE)) {
            return request;
        }
        JsonNode root = readTree(body);
        if (root == null) {
            return request;
        }
        for (JsonNode message : root.path("messages")) {
            String signature = null;
            for (JsonNode call : message.path("tool_calls")) {
                JsonNode function = call.path("function");
                if (signature == null && function.hasNonNull(SIGNATURE)) {
                    signature = function.get(SIGNATURE).asText();
                }
                if (function instanceof ObjectNode node) {
                    node.remove(SIGNATURE);
                }
            }
            if (signature != null && !hasEncryptedDetail(message)) {
                ObjectNode detail = MAPPER.createObjectNode()
                        .put("type", ENCRYPTED)
                        .put("id", message.path("tool_calls").path(0).path("id").asText())
                        .put("data", signature)
                        .put("format", GEMINI_FORMAT);
                // 已有的文本推理项留着，只把签名追加进去
                JsonNode existing = message.path("reasoning_details");
                ArrayNode details = existing instanceof ArrayNode array ? array : ((ObjectNode) message).putArray("reasoning_details");
                details.add(detail);
            }
        }
        return HttpRequest.builder()
                .url(request.getUrl())
                .method(request.getMethod())
                .headers(request.getHeaders())
                .body(root.toString())
                .build();
    }

    /**
     * 每条消息只带一份签名，已经有了就不再补
     */
    private static boolean hasEncryptedDetail(JsonNode message) {
        for (JsonNode detail : message.path("reasoning_details")) {
            if (ENCRYPTED.equals(detail.path("type").asText()) && detail.hasNonNull("data")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析不了就原样交给 SDK，由它按自己的规则报错
     */
    private static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
