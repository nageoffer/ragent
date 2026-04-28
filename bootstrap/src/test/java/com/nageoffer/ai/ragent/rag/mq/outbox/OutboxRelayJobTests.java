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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.mq.config.PulsarProperties;
import com.nageoffer.ai.ragent.framework.mq.model.MessageEnvelope;
import com.nageoffer.ai.ragent.rag.dao.entity.OutboxEventDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.OutboxEventMapper;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayJobTests {

    @Test
    void shouldRelayEventAndMarkSent() throws Exception {
        OutboxEventMapper outboxEventMapper = mock(OutboxEventMapper.class);
        PulsarClient pulsarClient = mock(PulsarClient.class);
        @SuppressWarnings("rawtypes")
        ProducerBuilder producerBuilder = mock(ProducerBuilder.class);
        @SuppressWarnings("unchecked")
        Producer<MessageEnvelope> producer = mock(Producer.class);
        @SuppressWarnings("unchecked")
        TypedMessageBuilder<MessageEnvelope> messageBuilder = mock(TypedMessageBuilder.class);

        PulsarProperties pulsarProperties = new PulsarProperties();
        pulsarProperties.getProducer().setCompressionType("LZ4");
        pulsarProperties.getProducer().setBatchingEnabled(true);
        pulsarProperties.getProducer().setBatchingMaxMessages(100);
        pulsarProperties.getProducer().setBatchingMaxPublishDelayMs(50);
        pulsarProperties.getProducer().setSendTimeoutMs(3000);
        pulsarProperties.getProducer().setBlockIfQueueFull(true);

        MessageEnvelope envelope = MessageEnvelope.builder()
                .key("doc-1")
                .eventType("knowledge.chunk")
                .payloadJson("{\"docId\":\"doc-1\"}")
                .timestamp(System.currentTimeMillis())
                .build();
        OutboxEventDO event = OutboxEventDO.builder()
                .id("evt-1")
                .topic("persistent://ragent/ai/knowledge-document-chunk")
                .messageKey("doc-1")
                .payloadJson(new ObjectMapper().writeValueAsString(envelope))
                .status(OutboxEventStatus.NEW)
                .retryCount(0)
                .nextRetryTime(new Date(System.currentTimeMillis() - 1000))
                .deleted(0)
                .build();

        when(outboxEventMapper.selectList(any())).thenReturn(List.of(event));
        when(pulsarClient.newProducer(any())).thenReturn(producerBuilder);
        when(producerBuilder.topic(anyString())).thenReturn(producerBuilder);
        when(producerBuilder.compressionType(any())).thenReturn(producerBuilder);
        when(producerBuilder.enableBatching(true)).thenReturn(producerBuilder);
        when(producerBuilder.batchingMaxMessages(100)).thenReturn(producerBuilder);
        when(producerBuilder.batchingMaxPublishDelay(anyLong(), any())).thenReturn(producerBuilder);
        when(producerBuilder.sendTimeout(anyInt(), any())).thenReturn(producerBuilder);
        when(producerBuilder.blockIfQueueFull(true)).thenReturn(producerBuilder);
        when(producerBuilder.create()).thenReturn(producer);
        when(producer.newMessage()).thenReturn(messageBuilder);
        when(messageBuilder.key("doc-1")).thenReturn(messageBuilder);
        when(messageBuilder.value(any())).thenReturn(messageBuilder);

        OutboxRelayJob job = new OutboxRelayJob(
                outboxEventMapper, pulsarClient, new ObjectMapper(), pulsarProperties
        );

        job.relay();

        verify(messageBuilder).send();
        ArgumentCaptor<OutboxEventDO> updateCaptor = ArgumentCaptor.forClass(OutboxEventDO.class);
        verify(outboxEventMapper, times(2)).update(updateCaptor.capture(), any());
        List<OutboxEventDO> updates = updateCaptor.getAllValues();
        Assertions.assertEquals(OutboxEventStatus.SENDING, updates.get(0).getStatus());
        Assertions.assertEquals(OutboxEventStatus.SENT, updates.get(1).getStatus());
    }

    @Test
    void shouldMarkFailedWhenProducerSendThrows() throws Exception {
        OutboxEventMapper outboxEventMapper = mock(OutboxEventMapper.class);
        PulsarClient pulsarClient = mock(PulsarClient.class);
        @SuppressWarnings("rawtypes")
        ProducerBuilder producerBuilder = mock(ProducerBuilder.class);
        @SuppressWarnings("unchecked")
        Producer<MessageEnvelope> producer = mock(Producer.class);
        @SuppressWarnings("unchecked")
        TypedMessageBuilder<MessageEnvelope> messageBuilder = mock(TypedMessageBuilder.class);

        PulsarProperties pulsarProperties = new PulsarProperties();
        MessageEnvelope envelope = MessageEnvelope.builder()
                .key("message-1")
                .eventType("message.feedback")
                .payloadJson("{\"messageId\":\"message-1\"}")
                .timestamp(System.currentTimeMillis())
                .build();
        OutboxEventDO event = OutboxEventDO.builder()
                .id("evt-2")
                .topic("persistent://ragent/ai/message-feedback")
                .messageKey("message-1")
                .payloadJson(new ObjectMapper().writeValueAsString(envelope))
                .status(OutboxEventStatus.NEW)
                .retryCount(1)
                .nextRetryTime(new Date(System.currentTimeMillis() - 1000))
                .deleted(0)
                .build();

        when(outboxEventMapper.selectList(any())).thenReturn(List.of(event));
        when(pulsarClient.newProducer(any())).thenReturn(producerBuilder);
        when(producerBuilder.topic(anyString())).thenReturn(producerBuilder);
        when(producerBuilder.compressionType(any())).thenReturn(producerBuilder);
        when(producerBuilder.enableBatching(anyBoolean())).thenReturn(producerBuilder);
        when(producerBuilder.batchingMaxMessages(anyInt())).thenReturn(producerBuilder);
        when(producerBuilder.batchingMaxPublishDelay(anyLong(), any())).thenReturn(producerBuilder);
        when(producerBuilder.sendTimeout(anyInt(), any())).thenReturn(producerBuilder);
        when(producerBuilder.blockIfQueueFull(anyBoolean())).thenReturn(producerBuilder);
        when(producerBuilder.create()).thenReturn(producer);
        when(producer.newMessage()).thenReturn(messageBuilder);
        when(messageBuilder.key("message-1")).thenReturn(messageBuilder);
        when(messageBuilder.value(any())).thenReturn(messageBuilder);
        doThrow(new RuntimeException("send failed")).when(messageBuilder).send();

        OutboxRelayJob job = new OutboxRelayJob(
                outboxEventMapper, pulsarClient, new ObjectMapper(), pulsarProperties
        );

        job.relay();

        ArgumentCaptor<OutboxEventDO> updateCaptor = ArgumentCaptor.forClass(OutboxEventDO.class);
        verify(outboxEventMapper, times(2)).update(updateCaptor.capture(), any());
        List<OutboxEventDO> updates = updateCaptor.getAllValues();
        Assertions.assertEquals(OutboxEventStatus.SENDING, updates.get(0).getStatus());
        Assertions.assertEquals(OutboxEventStatus.FAILED, updates.get(1).getStatus());
        Assertions.assertEquals(2, updates.get(1).getRetryCount());
        Assertions.assertTrue(updates.get(1).getLastError().contains("send failed"));
    }
}
