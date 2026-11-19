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

package com.nageoffer.ai.ragent.rag.core.intent;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.jev.JevClient;
import com.nageoffer.ai.ragent.rag.config.IntentProperties;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * jev 策略的装配到出分全链路
 * <p>
 * 其余用例都把 {@link JevClient} 换成桩，这里用真实客户端打本地端点，验证协议与映射接得上
 */
class JevIntentClassifierWiringTest {

    private final AtomicReference<String> lastRequest = new AtomicReference<>();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("用真 JevClient 走一次分类：协议发出、响应映射回 NodeScore")
    void classifiesThroughRealWiring() throws IOException {
        serve("{\"model\":\"typesafe/jev-1.14\",\"answers\":{"
                + "\"n0\":{\"type\":\"noul\",\"noul\":0.93},"
                + "\"n1\":{\"type\":\"noul\",\"noul\":0.12}},"
                + "\"usage\":{\"input_tokens\":3795,\"output_tokens\":21}}");

        DefaultIntentClassifier registry = mock(DefaultIntentClassifier.class);
        when(registry.listClassifiableNodes()).thenReturn(List.of(
                node("support-aftersale-return", "退换货政策"),
                node("support-presale-spec", "参数与配置")));

        IntentProperties intentProperties = new IntentProperties();
        intentProperties.setType("jev");
        JevClient jevClient = new JevClient(properties(), new OkHttpClient());

        List<NodeScore> scores = new JevIntentClassifier(registry, jevClient, intentProperties)
                .classifyTargets("AirPods Pro 左耳没声音能退吗");

        assertThat(scores).hasSize(1);
        assertThat(scores.get(0).getNode().getId()).isEqualTo("support-aftersale-return");
        assertThat(scores.get(0).getScore()).isEqualTo(0.93D);
        assertThat(lastRequest.get())
                .contains("\"model\":\"typesafe/jev-1.14\"")
                .contains("\"state\":\"AirPods Pro 左耳没声音能退吗\"")
                .contains("\"type\":\"noul\"");
    }

    private AIModelProperties properties() {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        provider.setApiKey("test-key");
        provider.setEndpoints(new HashMap<>(Map.of("jev", "/api/v1/systemone")));

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("jev-stable");
        candidate.setProvider("zenmux");
        candidate.setModel("typesafe/jev-1.14");

        AIModelProperties properties = new AIModelProperties();
        properties.setProviders(new HashMap<>(Map.of("zenmux", provider)));
        properties.getJev().setDefaultModel("jev-stable");
        properties.getJev().setCandidates(List.of(candidate));
        return properties;
    }

    private void serve(String responseJson) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/systemone", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                lastRequest.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    private static IntentNode node(String id, String name) {
        return IntentNode.builder()
                .id(id)
                .name(name)
                .fullPath("产品与服务咨询 > " + name)
                .description("说明-" + name)
                .kind(IntentKind.KB)
                .collectionNames(List.of("bit_policy"))
                .build();
    }
}
