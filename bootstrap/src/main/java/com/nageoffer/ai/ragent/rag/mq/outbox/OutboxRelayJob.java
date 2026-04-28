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

package com.nageoffer.ai.ragent.rag.mq.outbox;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.mq.config.PulsarProperties;
import com.nageoffer.ai.ragent.framework.mq.model.MessageEnvelope;
import com.nageoffer.ai.ragent.rag.dao.entity.OutboxEventDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.OutboxEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.CompressionType;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 转发任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayJob {

    private final OutboxEventMapper outboxEventMapper;
    private final PulsarClient pulsarClient;
    private final ObjectMapper objectMapper;
    private final PulsarProperties pulsarProperties;
    private final Map<String, Producer<MessageEnvelope>> producers = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 5000, initialDelay = 10000)
    public void relay() {
        List<OutboxEventDO> events = outboxEventMapper.selectList(
                Wrappers.lambdaQuery(OutboxEventDO.class)
                        .eq(OutboxEventDO::getDeleted, 0)
                        .in(OutboxEventDO::getStatus, OutboxEventStatus.NEW, OutboxEventStatus.FAILED)
                        .le(OutboxEventDO::getNextRetryTime, LocalDateTime.now())
                        .orderByAsc(OutboxEventDO::getCreateTime)
                        .last("limit 50"));
        for (OutboxEventDO event : events) {
            relayOne(event);
        }
    }

    private void relayOne(OutboxEventDO event) {
        try {
            outboxEventMapper.update(OutboxEventDO.builder().status(OutboxEventStatus.SENDING).build(),
                    Wrappers.lambdaUpdate(OutboxEventDO.class)
                            .eq(OutboxEventDO::getId, event.getId())
                            .in(OutboxEventDO::getStatus, OutboxEventStatus.NEW, OutboxEventStatus.FAILED));
            MessageEnvelope envelope = objectMapper.readValue(event.getPayloadJson(), MessageEnvelope.class);
            Producer<MessageEnvelope> producer = producers.computeIfAbsent(event.getTopic(), this::createProducer);
            producer.newMessage()
                    .key(event.getMessageKey())
                    .value(envelope)
                    .send();
            outboxEventMapper.update(OutboxEventDO.builder()
                            .status(OutboxEventStatus.SENT)
                            .lastError(null)
                            .build(),
                    Wrappers.lambdaUpdate(OutboxEventDO.class)
                            .eq(OutboxEventDO::getId, event.getId()));
        } catch (Exception ex) {
            int nextRetryCount = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
            long min = Math.min(300L, nextRetryCount * 30L);
            LocalDateTime nextRetryTime = LocalDateTime.now().plusSeconds(min);
            outboxEventMapper.update(OutboxEventDO.builder()
                            .status(OutboxEventStatus.FAILED)
                            .retryCount(nextRetryCount)
                            .nextRetryTime(nextRetryTime)
                            .lastError(ex.getMessage())
                            .build(),
                    Wrappers.lambdaUpdate(OutboxEventDO.class)
                            .eq(OutboxEventDO::getId, event.getId()));
            log.error("Outbox relay failed, id={}, topic={}", event.getId(), event.getTopic(), ex);
        }
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
        } catch (Exception ex) {
            throw new IllegalStateException("Create outbox producer failed: " + topic, ex);
        }
    }

    private CompressionType resolveCompressionType() {
        try {
            return CompressionType.valueOf(pulsarProperties.getProducer().getCompressionType());
        } catch (Exception ignored) {
            return CompressionType.LZ4;
        }
    }
}
