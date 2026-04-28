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

package com.nageoffer.ai.ragent.framework.mq.consumer;

import com.nageoffer.ai.ragent.framework.mq.config.PulsarProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.DeadLetterPolicy;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Pulsar 消费者生命周期管理。
 */
@Slf4j
@Component
@ConditionalOnBean(PulsarClient.class)
public class PulsarConsumerLifecycle implements DisposableBean {

    private final List<Consumer<?>> consumers = new ArrayList<>();

    public PulsarConsumerLifecycle(PulsarClient pulsarClient,
                                   List<PulsarMessageHandler<?>> handlers,
                                   PulsarProperties pulsarProperties) throws Exception {
        for (PulsarMessageHandler<?> handler : handlers) {
            consumers.add(subscribe(pulsarClient, handler, pulsarProperties));
        }
    }

    private <T> Consumer<T> subscribe(PulsarClient pulsarClient,
                                      PulsarMessageHandler<T> handler,
                                      PulsarProperties pulsarProperties) throws Exception {
        String topic = handler.topic();
        ConsumerBuilder<T> builder = pulsarClient.newConsumer(handler.schema())
                .topic(topic)
                .subscriptionName(handler.subscriptionName())
                .subscriptionType(handler.subscriptionType())
                .receiverQueueSize(pulsarProperties.getConsumer().getReceiverQueueSize())
                .ackTimeout(pulsarProperties.getConsumer().getAckTimeoutSeconds(), TimeUnit.SECONDS)
                .negativeAckRedeliveryDelay(
                        pulsarProperties.getConsumer().getNegativeAckDelaySeconds(),
                        TimeUnit.SECONDS)
                .deadLetterPolicy(DeadLetterPolicy.builder()
                        .maxRedeliverCount(pulsarProperties.getConsumer().getMaxRedeliverCount())
                        .deadLetterTopic(topic + "-" + handler.subscriptionName() + "-DLQ")
                        .build())
                .messageListener((consumer, message) -> handleMessage(handler, consumer, message));
        Consumer<T> consumer = builder.subscribe();
        log.info("Pulsar consumer started, topic={}, subscription={}", topic, handler.subscriptionName());
        return consumer;
    }

    private <T> void handleMessage(PulsarMessageHandler<T> handler,
                                   Consumer<T> consumer,
                                   Message<T> message) {
        try {
            handler.onMessage(message);
            consumer.acknowledgeAsync(message);
        } catch (Exception ex) {
            log.error("Pulsar consume failed, topic={}, subscription={}, messageId={}",
                    handler.topic(), handler.subscriptionName(), message.getMessageId(), ex);
            consumer.negativeAcknowledge(message);
        }
    }

    @Override
    public void destroy() throws Exception {
        for (Consumer<?> consumer : consumers) {
            consumer.close();
        }
        consumers.clear();
    }
}
