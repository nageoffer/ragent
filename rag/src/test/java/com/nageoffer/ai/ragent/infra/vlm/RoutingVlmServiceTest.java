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

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingVlmServiceTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    @Test
    void configuredTimeoutShouldOverrideShortGlobalClientTimeout() {
        server.enqueue(new MockResponse.Builder()
                .setHeader("Content-Type", "application/json")
                .body("{\"choices\":[{\"message\":{\"content\":\"图片描述\"}}]}")
                .bodyDelay(150, TimeUnit.MILLISECONDS)
                .build());

        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl(server.url("/").toString());
        provider.setApiKey("test-key");
        provider.setEndpoints(Map.of("chat", "v1/chat/completions"));

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("qwen-vl-max");
        candidate.setProvider("bailian");
        candidate.setModel("qwen-vl-max");

        ModelSelector selector = mock(ModelSelector.class);
        when(selector.selectVlmCandidates())
                .thenReturn(List.of(new ModelTarget("qwen-vl-max", candidate, provider, 500L)));

        OkHttpClient shortTimeoutClient = new OkHttpClient.Builder()
                .readTimeout(Duration.ofMillis(50))
                .callTimeout(Duration.ofMillis(50))
                .build();
        RoutingVlmService service = new RoutingVlmService(selector, shortTimeoutClient);

        String result = service.describeImage(new byte[]{1, 2, 3}, "image/png", "描述图片", 100);

        assertEquals("图片描述", result);
    }

    @Test
    void missingTimeoutShouldUseGlobalClientTimeout() {
        server.enqueue(new MockResponse.Builder()
                .setHeader("Content-Type", "application/json")
                .body("{\"choices\":[{\"message\":{\"content\":\"图片描述\"}}]}")
                .bodyDelay(150, TimeUnit.MILLISECONDS)
                .build());

        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl(server.url("/").toString());
        provider.setApiKey("test-key");
        provider.setEndpoints(Map.of("chat", "v1/chat/completions"));

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("qwen-vl-max");
        candidate.setProvider("bailian");
        candidate.setModel("qwen-vl-max");

        ModelSelector selector = mock(ModelSelector.class);
        when(selector.selectVlmCandidates())
                .thenReturn(List.of(new ModelTarget("qwen-vl-max", candidate, provider, null)));

        OkHttpClient shortTimeoutClient = new OkHttpClient.Builder()
                .readTimeout(Duration.ofMillis(50))
                .callTimeout(Duration.ofMillis(50))
                .build();
        RoutingVlmService service = new RoutingVlmService(selector, shortTimeoutClient);

        assertThrows(ModelClientException.class,
                () -> service.describeImage(new byte[]{1, 2, 3}, "image/png", "描述图片", 100));
    }
}
