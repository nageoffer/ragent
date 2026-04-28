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

package com.nageoffer.ai.ragent.rag.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.mq.config.PulsarProperties;
import com.nageoffer.ai.ragent.framework.mq.consumer.PulsarMessageHandler;
import com.nageoffer.ai.ragent.framework.mq.model.MessageEnvelope;
import com.nageoffer.ai.ragent.rag.mq.event.MessageFeedbackEvent;
import com.nageoffer.ai.ragent.rag.service.MessageFeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 消息反馈 Pulsar 消费者。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageFeedbackConsumer implements PulsarMessageHandler<MessageEnvelope> {

    private final MessageFeedbackService feedbackService;
    private final ObjectMapper objectMapper;
    private final PulsarProperties pulsarProperties;

    @Value("${unique-name:}")
    private String uniqueName;

    @Override
    public String topic() {
        return pulsarProperties.getTopics().getMessageFeedback();
    }

    @Override
    public String subscriptionName() {
        return "message-feedback-sub" + uniqueName;
    }

    @Override
    public SubscriptionType subscriptionType() {
        return SubscriptionType.Key_Shared;
    }

    @Override
    public Schema<MessageEnvelope> schema() {
        return Schema.JSON(MessageEnvelope.class);
    }

    @Override
    public void onMessage(Message<MessageEnvelope> message) throws Exception {
        MessageEnvelope envelope = message.getValue();
        MessageFeedbackEvent event = objectMapper.readValue(
                envelope.getPayloadJson(),
                MessageFeedbackEvent.class
        );
        log.info("[Pulsar 消费者] 开始处理消息反馈, messageId={}, userId={}, vote={}, key={}",
                event.getMessageId(), event.getUserId(), event.getVote(), envelope.getKey());
        feedbackService.submitFeedbackByEvent(event);
    }
}
