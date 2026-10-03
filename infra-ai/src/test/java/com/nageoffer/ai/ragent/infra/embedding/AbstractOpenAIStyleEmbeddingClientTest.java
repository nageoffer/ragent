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

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AbstractOpenAIStyleEmbeddingClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void ordersBatchEmbeddingsByResponseIndex() throws IOException {
        serve("{\"data\":["
                + "{\"index\":1,\"embedding\":[2.0,2.1]},"
                + "{\"index\":0,\"embedding\":[1.0,1.1]}]}");

        List<List<Float>> result = client().embedBatch(List.of("first", "second"), target());

        assertEquals(List.of(List.of(1.0F, 1.1F), List.of(2.0F, 2.1F)), result);
    }

    @Test
    void rejectsDuplicateResponseIndexes() throws IOException {
        serve("{\"data\":["
                + "{\"index\":0,\"embedding\":[1.0,1.1]},"
                + "{\"index\":0,\"embedding\":[2.0,2.1]}]}");

        ModelClientException error = assertThrows(
                ModelClientException.class,
                () -> client().embedBatch(List.of("first", "second"), target()));

        assertEquals(ModelClientErrorType.INVALID_RESPONSE, error.getErrorType());
    }

    @Test
    void rejectsResponsesWithMissingEmbeddings() throws IOException {
        serve("{\"data\":[{\"index\":0,\"embedding\":[1.0,1.1]}]}");

        ModelClientException error = assertThrows(
                ModelClientException.class,
                () -> client().embedBatch(List.of("first", "second"), target()));

        assertEquals(ModelClientErrorType.INVALID_RESPONSE, error.getErrorType());
    }

    @Test
    void preservesResponseOrderWhenProviderOmitsIndexes() throws IOException {
        serve("{\"data\":["
                + "{\"embedding\":[1.0,1.1]},"
                + "{\"embedding\":[2.0,2.1]}]}");

        List<List<Float>> result = client().embedBatch(List.of("first", "second"), target());

        assertEquals(List.of(List.of(1.0F, 1.1F), List.of(2.0F, 2.1F)), result);
    }

    private void serve(String responseJson) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    private EmbeddingClient client() {
        return new AbstractOpenAIStyleEmbeddingClient(new OkHttpClient()) {
            @Override
            public String provider() {
                return "test";
            }
        };
    }

    private ModelTarget target() {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        provider.setApiKey("test-key");
        provider.setEndpoints(Map.of("embedding", "/embeddings"));

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setModel("test-embedding");
        candidate.setDimension(2);
        return new ModelTarget("test-embedding", candidate, provider, null);
    }
}
