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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.mq.model.MessageEnvelope;
import com.nageoffer.ai.ragent.framework.mq.outbox.OutboxEventPublisher;
import com.nageoffer.ai.ragent.rag.dao.entity.OutboxEventDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.OutboxEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 基于 JDBC 的 Outbox 发布器。
 */
@Component
@RequiredArgsConstructor
public class JdbcOutboxEventPublisher implements OutboxEventPublisher {

    private final OutboxEventMapper outboxEventMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(String topic, String key, String eventType, MessageEnvelope envelope) {
        OutboxEventDO eventDO = OutboxEventDO.builder()
                .topic(topic)
                .messageKey(key)
                .eventType(eventType)
                .payloadJson(toJson(envelope))
                .status(OutboxEventStatus.NEW)
                .retryCount(0)
                .nextRetryTime(new Date())
                .build();
        outboxEventMapper.insert(eventDO);
    }

    private String toJson(MessageEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Serialize outbox envelope failed", ex);
        }
    }
}
