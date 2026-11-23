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

package com.nageoffer.ai.ragent.agent.config;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 按生产装配造模型：formatter、传输链与开关都走 AgentEngineConfiguration，只把最底层的 HTTP 换成录好的回包
 */
public final class ProductionModels {

    public static final String OPUS = "anthropic/claude-opus-5.5:anthropic";

    public static final String SONNET = "anthropic/claude-sonnet-5.5:anthropic";

    private ProductionModels() {
    }

    public static Model deepseek(Gateway gateway) {
        return build("deepseek", "deepseek-flash", "/v1/chat/completions", gateway);
    }

    public static Model opus(Gateway gateway) {
        return build("zenmux-anthropic", OPUS, "/api/anthropic/v1/messages", gateway);
    }

    public static Model sonnet(Gateway gateway) {
        return build("zenmux-anthropic", SONNET, "/api/anthropic/v1/messages", gateway);
    }

    public static Model zenmux(String model, Gateway gateway) {
        return build("zenmux", model, "/api/v1/chat/completions", gateway);
    }

    /**
     * 模型在构造时就拿走了默认传输，换完立刻还原，不影响别的用例
     */
    static Model build(String provider, String model, String endpoint, Gateway gateway) {
        AgentProperties agent = new AgentProperties();
        agent.getChat().setProvider(provider);
        agent.getChat().setModel(model);
        AIModelProperties ai = new AIModelProperties();
        AIModelProperties.ProviderConfig config = new AIModelProperties.ProviderConfig();
        config.setUrl("https://gateway.test");
        config.setApiKey("sk-test");
        config.setEndpoints(Map.of("chat", endpoint));
        ai.setProviders(Map.of(provider, config));
        HttpTransport previous = HttpTransportFactory.getDefault();
        HttpTransportFactory.setDefault(gateway);
        try {
            return new AgentEngineConfiguration(agent, ai).agentChatModel();
        } finally {
            HttpTransportFactory.setDefault(previous);
        }
    }

    /**
     * 按顺序吐预置回包，记下每次真正发出去的请求体
     */
    public static final class Gateway implements HttpTransport {

        private final Deque<Flux<String>> streams = new ArrayDeque<>();
        private final Deque<String> bodies = new ArrayDeque<>();
        public final List<String> requests = new ArrayList<>();
        public final List<Map<String, String>> headers = new ArrayList<>();

        public Gateway thenStream(List<String> data) {
            streams.add(Flux.fromIterable(data));
            return this;
        }

        public Gateway thenStream(Flux<String> data) {
            streams.add(data);
            return this;
        }

        public Gateway thenBody(String body) {
            bodies.add(body);
            return this;
        }

        @Override
        public synchronized HttpResponse execute(HttpRequest request) {
            requests.add(request.getBody());
            headers.add(request.getHeaders());
            return HttpResponse.builder().statusCode(200).body(bodies.remove()).build();
        }

        @Override
        public synchronized Flux<String> stream(HttpRequest request) {
            requests.add(request.getBody());
            headers.add(request.getHeaders());
            return streams.remove();
        }

        public synchronized String lastRequest() {
            return requests.get(requests.size() - 1);
        }

        @Override
        public void close() {
        }
    }
}
