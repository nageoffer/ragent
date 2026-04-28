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

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.mq.config.PulsarProperties;
import com.nageoffer.ai.ragent.framework.mq.model.MessageEnvelope;
import com.nageoffer.ai.ragent.framework.mq.model.MqSendReceipt;
import com.nageoffer.ai.ragent.framework.mq.outbox.OutboxEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.CompressionType;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Pulsar 的消息生产者。
 */
@Slf4j
@RequiredArgsConstructor
public class PulsarProducerAdapter implements MessageQueueProducer {

    private final PulsarClient pulsarClient;
    private final ObjectMapper objectMapper;
    private final OutboxEventPublisher outboxEventPublisher;
    private final PulsarProperties pulsarProperties;
    private final Map<String, Producer<MessageEnvelope>> producers = new ConcurrentHashMap<>();

    @Override
    public MqSendReceipt send(String topic, String keys, String bizDesc, Object body) {
        String resolvedKey = StrUtil.blankToDefault(keys, UUID.randomUUID().toString());
        MessageEnvelope envelope = buildEnvelope(resolvedKey, body);
        try {
            Producer<MessageEnvelope> producer = producers.computeIfAbsent(topic, this::createProducer);
            MessageId messageId = producer.newMessage()
                    .key(resolvedKey)
                    .value(envelope)
                    .send();
            log.info("[Pulsar 生产者] {} 发送成功, topic={}, key={}, messageId={}",
                    bizDesc, topic, resolvedKey, messageId);
            return MqSendReceipt.builder()
                    .messageId(messageId.toString())
                    .topic(topic)
                    .key(resolvedKey)
                    .publishTime(envelope.getTimestamp())
                    .build();
        } catch (Exception ex) {
            log.error("[Pulsar 生产者] {} 发送失败, topic={}, key={}", bizDesc, topic, resolvedKey, ex);
            throw new RuntimeException("Pulsar send failed", ex);
        }
    }

    @Override
    public void publishReliable(String topic, String keys, String bizDesc, Object body) {
        String resolvedKey = StrUtil.blankToDefault(keys, UUID.randomUUID().toString());
        MessageEnvelope envelope = buildEnvelope(resolvedKey, body);
        outboxEventPublisher.publish(topic, resolvedKey, resolveEventType(body), envelope);
        log.info("[Pulsar Outbox] {} 已写入 outbox, topic={}, key={}", bizDesc, topic, resolvedKey);
    }

    private MessageEnvelope buildEnvelope(String key, Object body) {
        return MessageEnvelope.builder()
                .key(key)
                .eventType(resolveEventType(body))
                .payloadJson(toJson(body))
                .traceId(UserContext.getUserId())
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private String resolveEventType(Object body) {
        return body == null ? "unknown" : body.getClass().getSimpleName();
    }

    private Producer<MessageEnvelope> createProducer(String topic) {
        try {
            return pulsarClient.newProducer(Schema.JSON(MessageEnvelope.class))
                    .topic(topic)
                    .compressionType(resolveCompressionType())
                    .enableBatching(pulsarProperties.getProducer().isBatchingEnabled())
                    .batchingMaxMessages(pulsarProperties.getProducer().getBatchingMaxMessages())
                    .batchingMaxPublishDelay(
                            pulsarProperties.getProducer().getBatchingMaxPublishDelayMs(),
                            TimeUnit.MILLISECONDS)
                    .sendTimeout(pulsarProperties.getProducer().getSendTimeoutMs(), TimeUnit.MILLISECONDS)
                    .blockIfQueueFull(pulsarProperties.getProducer().isBlockIfQueueFull())
                    .create();
        } catch (PulsarClientException ex) {
            throw new IllegalStateException("Create Pulsar producer failed: " + topic, ex);
        }
    }

    private CompressionType resolveCompressionType() {
        try {
            return CompressionType.valueOf(pulsarProperties.getProducer().getCompressionType());
        } catch (Exception ignored) {
            return CompressionType.LZ4;
        }
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Serialize MQ payload failed", ex);
        }
    }
}
