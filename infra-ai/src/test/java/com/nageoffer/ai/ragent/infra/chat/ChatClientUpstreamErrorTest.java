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

package com.nageoffer.ai.ragent.infra.chat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 上游错误体不进日志和异常：供应商可能在 message 里回显请求原文
 * <p>
 * 用 JDK 自带 HttpServer 起真实端点：mockwebserver 4.x 与本项目的 okhttp 5.x 不同源
 */
class ChatClientUpstreamErrorTest {

    private static final String ECHOED = "回显的用户原文-帮我伪造退款凭证";

    private HttpServer server;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldSurfaceErrorCodeButNotEchoedBody() throws IOException {
        serve(400, "{\"error\":{\"code\":\"data_inspection_failed\",\"type\":\"data_inspection_failed\",\"param\":null,"
                + "\"message\":\"" + ECHOED + "\"},\"request_id\":\"req-7\"}");
        logs.start();
        root.addAppender(logs);
        BaiLianChatClient client = new BaiLianChatClient();
        ReflectionTestUtils.setField(client, "syncHttpClient", new OkHttpClient());

        ModelClientException error = catchThrowableOfType(ModelClientException.class, () -> client.chat(
                ChatRequest.builder().messages(List.of(ChatMessage.user("问题"))).build(), target()));

        assertThat(error.getErrorType()).isEqualTo(ModelClientErrorType.CONTENT_REJECTED);
        assertThat(error.getMessage()).contains("code=data_inspection_failed", "request_id=req-7").doesNotContain(ECHOED);
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).noneMatch(line -> line.contains(ECHOED));
    }

    private void serve(int status, String responseJson) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    private ModelTarget target() {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        provider.setApiKey("test-key");
        provider.setEndpoints(Map.of("chat", "/chat"));
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setModel("qwen-flash");
        return new ModelTarget("qwen-flash", candidate, provider, null);
    }
}
