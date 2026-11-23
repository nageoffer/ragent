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

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI 兼容流的完整性校验：SDK 2.0.2 不看结束原因，流断在工具参数中途也会拿半截参数执行工具
 * 带工具调用的只认 tool_calls，且每个调用的参数恰好是一个 JSON 对象；纯文字只认 stop；其余一律按回复不完整报错
 */
public class OpenAIStreamCompletionTransport implements HttpTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 参数串要整段恰好是一个对象，"{...}{..." 这种只读第一个对象就放行会让工具拿错参数
     */
    private static final ObjectReader STRICT = MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final HttpTransport delegate;
    private final String provider;

    public OpenAIStreamCompletionTransport(HttpTransport delegate, String provider) {
        this.delegate = delegate;
        this.provider = provider;
    }

    /**
     * 非流式只有摘要器与 Judge 在用，它们自己只认 stop
     */
    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        return delegate.execute(request);
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        // 流状态跟着单次订阅走，类上不存
        return Flux.defer(() -> {
            StreamState state = new StreamState();
            return delegate.stream(request)
                    .doOnNext(state::accept)
                    .concatWith(Flux.defer(() -> {
                        state.ensureComplete();
                        return Flux.empty();
                    }));
        });
    }

    /**
     * 底层是 SDK 共享的默认传输，不归这里关
     */
    @Override
    public void close() {
    }

    private final class StreamState {

        private final Map<Integer, StringBuilder> arguments = new LinkedHashMap<>();
        private Integer current;
        private String finishReason;
        private boolean refused;

        void accept(String data) {
            JsonNode root = readTree(data);
            if (root == null) {
                return;
            }
            for (JsonNode choice : root.path("choices")) {
                if (choice.hasNonNull("finish_reason")) {
                    finishReason = choice.get("finish_reason").asText();
                }
                JsonNode delta = choice.path("delta");
                refused |= StrUtil.isNotEmpty(delta.path("refusal").asText(null));
                for (JsonNode call : delta.path("tool_calls")) {
                    acceptToolCall(call);
                }
            }
        }

        /**
         * SDK 只把带工具名的分片当成新调用的开头，其余分片一律归给最近开头的那个；分片属于别的调用就会串参，直接判不完整
         */
        private void acceptToolCall(JsonNode call) {
            int index = call.path("index").asInt(current == null ? 0 : current);
            boolean starts = StrUtil.isNotEmpty(call.path("function").path("name").asText(null));
            if (starts) {
                current = index;
                arguments.putIfAbsent(index, new StringBuilder());
            }
            String part = call.path("function").path("arguments").asText("");
            if (part.isEmpty()) {
                return;
            }
            if (current == null || index != current) {
                throw incomplete("工具调用参数分片交错");
            }
            arguments.get(index).append(part);
        }

        void ensureComplete() {
            if (refused) {
                throw incomplete("模型拒答");
            }
            boolean hasToolCalls = !arguments.isEmpty();
            String expected = hasToolCalls ? "tool_calls" : "stop";
            if (!expected.equals(finishReason)) {
                throw incomplete("结束原因 " + finishReason);
            }
            if (hasToolCalls && !arguments.values().stream().allMatch(StreamState::isJsonObject)) {
                throw incomplete("工具调用参数不完整");
            }
        }

        /**
         * 各家无参工具都发 "{}"，没收到任何参数不能当成无参
         */
        private static boolean isJsonObject(StringBuilder raw) {
            if (raw.isEmpty()) {
                return false;
            }
            try {
                return STRICT.readTree(raw.toString()).isObject();
            } catch (IOException e) {
                return false;
            }
        }

        private ModelException incomplete(String reason) {
            return new ModelException(provider + " 回复不完整: " + reason, null, provider);
        }
    }

    /**
     * 解析不了的行交给 SDK 按自己的规则处理
     */
    private static JsonNode readTree(String data) {
        try {
            return MAPPER.readTree(data);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
