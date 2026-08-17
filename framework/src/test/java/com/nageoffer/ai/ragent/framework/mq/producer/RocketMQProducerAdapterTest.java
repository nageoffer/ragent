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

import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RocketMQProducerAdapterTest {

    private static final String TOPIC = "document-chunk";

    private RocketMQTemplate rocketMQTemplate;
    private DelegatingTransactionListener transactionListener;
    private RocketMQProducerAdapter producerAdapter;

    @BeforeEach
    void setUp() {
        rocketMQTemplate = mock(RocketMQTemplate.class);
        transactionListener = new DelegatingTransactionListener();
        producerAdapter = new RocketMQProducerAdapter(rocketMQTemplate, transactionListener);

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        ReflectionTestUtils.setField(transactionListener, "transactionManager", transactionManager);
    }

    @Test
    void sendFailureRemovesLocalTransactionCallback() {
        RuntimeException sendFailure = new RuntimeException("broker unavailable");
        AtomicBoolean localTransactionInvoked = new AtomicBoolean();
        AtomicReference<Message<?>> sentMessage = new AtomicReference<>();
        doAnswer(invocation -> {
            sentMessage.set(invocation.getArgument(1));
            throw sendFailure;
        }).when(rocketMQTemplate).sendMessageInTransaction(eq(TOPIC), any(Message.class), isNull());

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> producerAdapter.sendInTransaction(
                        TOPIC, "message-key", "document chunk", "payload",
                        ignored -> localTransactionInvoked.set(true)));

        assertSame(sendFailure, thrown);
        assertEquals(
                RocketMQLocalTransactionState.ROLLBACK,
                transactionListener.executeLocalTransaction(sentMessage.get(), null));
        assertFalse(localTransactionInvoked.get());
    }

    @Test
    void nonOkSendStatusRemovesLocalTransactionCallback() {
        AtomicBoolean localTransactionInvoked = new AtomicBoolean();
        AtomicReference<Message<?>> sentMessage = new AtomicReference<>();
        TransactionSendResult sendResult = mock(TransactionSendResult.class);
        when(sendResult.getSendStatus()).thenReturn(SendStatus.FLUSH_DISK_TIMEOUT);
        doAnswer(invocation -> {
            sentMessage.set(invocation.getArgument(1));
            return sendResult;
        }).when(rocketMQTemplate).sendMessageInTransaction(eq(TOPIC), any(Message.class), isNull());

        producerAdapter.sendInTransaction(
                TOPIC, "message-key", "document chunk", "payload",
                ignored -> localTransactionInvoked.set(true));

        assertEquals(
                RocketMQLocalTransactionState.ROLLBACK,
                transactionListener.executeLocalTransaction(sentMessage.get(), null));
        assertFalse(localTransactionInvoked.get());
    }

    @Test
    void cleanupIsNoOpAfterCallbackIsConsumed() {
        RuntimeException sendFailure = new RuntimeException("end transaction failed");
        AtomicInteger localTransactionInvocations = new AtomicInteger();
        AtomicReference<Message<?>> sentMessage = new AtomicReference<>();
        doAnswer(invocation -> {
            Message<?> message = invocation.getArgument(1);
            sentMessage.set(message);
            assertEquals(
                    RocketMQLocalTransactionState.COMMIT,
                    transactionListener.executeLocalTransaction(message, null));
            throw sendFailure;
        }).when(rocketMQTemplate).sendMessageInTransaction(eq(TOPIC), any(Message.class), isNull());

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> producerAdapter.sendInTransaction(
                        TOPIC, "message-key", "document chunk", "payload",
                        ignored -> localTransactionInvocations.incrementAndGet()));

        assertSame(sendFailure, thrown);
        assertEquals(1, localTransactionInvocations.get());
        assertEquals(
                RocketMQLocalTransactionState.ROLLBACK,
                transactionListener.executeLocalTransaction(sentMessage.get(), null));
    }

    @Test
    void successfulSendConsumesCallbackOnce() {
        AtomicBoolean localTransactionInvoked = new AtomicBoolean();
        AtomicReference<Message<?>> sentMessage = new AtomicReference<>();
        TransactionSendResult sendResult = mock(TransactionSendResult.class);
        when(sendResult.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        doAnswer(invocation -> {
            Message<?> message = invocation.getArgument(1);
            sentMessage.set(message);
            assertEquals(
                    RocketMQLocalTransactionState.COMMIT,
                    transactionListener.executeLocalTransaction(message, null));
            return sendResult;
        }).when(rocketMQTemplate).sendMessageInTransaction(eq(TOPIC), any(Message.class), isNull());

        producerAdapter.sendInTransaction(
                TOPIC, "message-key", "document chunk", "payload",
                ignored -> localTransactionInvoked.set(true));

        assertTrue(localTransactionInvoked.get());
        assertEquals(
                RocketMQLocalTransactionState.ROLLBACK,
                transactionListener.executeLocalTransaction(sentMessage.get(), null));
    }
}
