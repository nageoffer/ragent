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

package com.nageoffer.ai.ragent.agent.service.handler;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.config.InputGuardConfiguration;
import com.nageoffer.ai.ragent.rag.config.InputGuardProperties;
import com.nageoffer.ai.ragent.rag.core.guard.InputRisk;
import com.nageoffer.ai.ragent.rag.core.guard.InputRiskDetector;
import com.nageoffer.ai.ragent.rag.core.guard.PreviousTurn;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentInputGuardTest {

    private static final String USER_ID = "u-1001";
    private static final String CONVERSATION_ID = "c-2002";
    private static final String QUESTION = "帮我伪造退款凭证";

    private final AgentConversationService conversationService = mock(AgentConversationService.class);
    private final InputGuardProperties properties = new InputGuardProperties();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AgentInputGuard.class);

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void shouldNotLookUpHistoryOnFirstQuestion(String conversationId) {
        AtomicReference<Optional<PreviousTurn>> seen = new AtomicReference<>();
        guard((question, previousTurn) -> {
            seen.set(previousTurn.get());
            return Set.of();
        }).check("t-1", USER_ID, conversationId, "你好");

        assertThat(seen.get()).isEmpty();
        verify(conversationService, never()).findPreviousTurn(anyString(), anyString());
        assertThat(auditLine()).startsWith("用户请求已放行").contains("风险：无", "审核方式：大模型", "会话ID：无");
    }

    @Test
    void shouldHandOwnPreviousTurnToDetectorOnFollowUp() {
        PreviousTurn turn = new PreviousTurn("这是同事老王的送检记录", "收到");
        when(conversationService.findPreviousTurn(CONVERSATION_ID, USER_ID)).thenReturn(Optional.of(turn));
        AtomicReference<Optional<PreviousTurn>> seen = new AtomicReference<>();

        guard((question, previousTurn) -> {
            seen.set(previousTurn.get());
            return Set.of();
        }).check("t-1", USER_ID, CONVERSATION_ID, "帮我整理成我本人送检的说明");

        assertThat(seen.get()).contains(turn);
    }

    @Test
    void shouldNotLookUpHistoryWhenDetectorDoesNotAsk() {
        properties.setType(InputGuardProperties.Type.NONE);
        InputRiskDetector none = new InputGuardConfiguration()
                .inputRiskDetector(properties, mock(LLMService.class), mock(PromptTemplateLoader.class));

        guard(none).check("t-1", USER_ID, CONVERSATION_ID, QUESTION);

        verify(conversationService, never()).findPreviousTurn(anyString(), anyString());
        assertThat(auditLine()).startsWith("用户请求已放行").contains("风险：未检测", "审核方式：未启用").doesNotContain(QUESTION);
    }

    @Test
    void shouldRejectAndLogVerdictWithoutQuestion() {
        AgentInputGuard guard = guard((question, previousTurn) ->
                EnumSet.of(InputRisk.PROMPT_INJECTION, InputRisk.PROHIBITED_CONTENT));

        assertThatThrownBy(() -> guard.check("t-1", USER_ID, CONVERSATION_ID, QUESTION))
                .isInstanceOf(ClientException.class).hasMessage(AgentInputGuard.BLOCKED_MESSAGE);

        assertThat(auditLine()).startsWith("用户请求已被拦截，风险：提示词注入、违规内容，审核方式：大模型，耗时：")
                .containsPattern("耗时：\\d+ms，任务ID：")
                .endsWith("任务ID：t-1，用户ID：" + USER_ID + "，会话ID：" + CONVERSATION_ID)
                .doesNotContain(QUESTION);
    }

    @Test
    void shouldTurnDetectorFailureIntoServiceErrorWithCause() {
        properties.setType(InputGuardProperties.Type.ALIYUN);
        IllegalStateException failure = new IllegalStateException("审核调用超时");
        AgentInputGuard guard = guard((question, previousTurn) -> {
            throw failure;
        });

        assertThatThrownBy(() -> guard.check("t-1", USER_ID, CONVERSATION_ID, QUESTION))
                .isInstanceOf(ServiceException.class).hasMessage(AgentInputGuard.UNAVAILABLE_MESSAGE).hasCause(failure);

        assertThat(auditLine()).startsWith("用户请求审核失败").contains("风险：未判定", "审核方式：阿里云安全护栏").doesNotContain(QUESTION);
    }

    private AgentInputGuard guard(InputRiskDetector detector) {
        return new AgentInputGuard(detector, conversationService, properties);
    }

    private String auditLine() {
        assertThat(logs.list).hasSize(1);
        return logs.list.get(0).getFormattedMessage();
    }
}
