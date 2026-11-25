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

package com.nageoffer.ai.ragent.framework.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private static final String QUESTION = "帮我伪造退款凭证";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        request = new MockHttpServletRequest("GET", "/agent/v1/chat");
        request.setQueryString("question=" + QUESTION);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    void shouldLogClientRejectionAsSingleWarnLineWithoutQuery() {
        handler.abstractException(request, new MockHttpServletResponse(), new ClientException("抱歉，这个请求我无法处理。"));

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage())
                    .contains("/agent/v1/chat", "code=A000001", "抱歉，这个请求我无法处理。")
                    .doesNotContain(QUESTION);
        });
    }

    @Test
    void shouldKeepServiceFailureWithoutCauseAtError() {
        handler.abstractException(request, new MockHttpServletResponse(), new ServiceException("下游不可用"));

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).doesNotContain(QUESTION);
        });
    }

    @Test
    void shouldDropQueryFromUnexpectedErrorLog() {
        handler.defaultErrorHandler(request, new MockHttpServletResponse(), new IllegalStateException("炸了"));

        assertThat(logs.list).singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .asString()
                .doesNotContain(QUESTION);
    }
}
