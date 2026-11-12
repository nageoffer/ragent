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

package com.nageoffer.ai.ragent.framework.mq.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQMessageConverter;
import org.apache.rocketmq.spring.support.RocketMQUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DelegatingTransactionListenerTest {

    private static final String TOPIC = "document-chunk";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DelegatingTransactionListener listener;

    @BeforeEach
    void setUp() {
        listener = new DelegatingTransactionListener();
        ReflectionTestUtils.setField(listener, "objectMapper", objectMapper);
    }

    @Test
    void brokerCheckDeserializesTypedPayloadAndCommits() {
        assertBrokerCheck(true, RocketMQLocalTransactionState.COMMIT);
    }

    @Test
    void brokerCheckDeserializesTypedPayloadAndRollsBack() {
        assertBrokerCheck(false, RocketMQLocalTransactionState.ROLLBACK);
    }

    private void assertBrokerCheck(boolean committed, RocketMQLocalTransactionState expectedState) {
        listener.registerChecker(TOPIC, new TransactionChecker<CheckEvent>() {
            @Override
            public Class<CheckEvent> bodyType() {
                return CheckEvent.class;
            }

            @Override
            public boolean check(MessageWrapper<CheckEvent> message) {
                assertEquals("message-key", message.getKeys());
                assertEquals("doc-1", assertInstanceOf(CheckEvent.class, message.getBody()).getDocId());
                return committed;
            }
        });

        MessageWrapper<CheckEvent> wrapper = MessageWrapper.<CheckEvent>builder()
                .keys("message-key")
                .body(new CheckEvent("doc-1"))
                .build();
        RocketMQTemplate template = new RocketMQTemplate();
        template.setMessageConverter(new RocketMQMessageConverter().getMessageConverter());
        org.apache.rocketmq.common.message.Message sentMessage = ReflectionTestUtils.invokeMethod(
                template, "createRocketMqMessage", TOPIC,
                MessageBuilder.withPayload(wrapper)
                        .setHeader(DelegatingTransactionListener.HEADER_TOPIC, TOPIC)
                        .build());
        MessageExt brokerMessage = new MessageExt();
        brokerMessage.setBody(sentMessage.getBody());
        brokerMessage.putUserProperty(DelegatingTransactionListener.HEADER_TOPIC, TOPIC);

        assertEquals(expectedState,
                listener.checkLocalTransaction(RocketMQUtil.convertToSpringMessage(brokerMessage)));
    }

    public static class CheckEvent {

        private String docId;

        public CheckEvent() {
        }

        public CheckEvent(String docId) {
            this.docId = docId;
        }

        public String getDocId() {
            return docId;
        }

        public void setDocId(String docId) {
            this.docId = docId;
        }
    }
}
