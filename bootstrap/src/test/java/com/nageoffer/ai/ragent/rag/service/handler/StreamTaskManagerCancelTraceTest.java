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

package com.nageoffer.ai.ragent.rag.service.handler;

import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;

import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户主动取消时，对应的 trace run 必须收尾为 CANCELLED
 * <p>
 * 取消信号在 provider client 层被 {@code ForwardingStreamCallback.finishExternally} 刻意拦截（不透传 delegate，
 * 否则流式 failover 切换候选时会终止用户 SSE），因此 run 级终态只能由本类上报 ——
 * 全链路只有这里能区分「用户主动取消」与「failover 内部取消」
 * </p>
 */
class StreamTaskManagerCancelTraceTest {

    private static final String TASK_ID = "task-1";

    private RedissonClient redissonClient;
    private RTopic topic;
    private RagTraceRecordService traceRecordService;
    private StreamTaskManager taskManager;
    private StreamCancellationHandle handle;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        topic = mock(RTopic.class);
        traceRecordService = mock(RagTraceRecordService.class);
        handle = mock(StreamCancellationHandle.class);
        when(redissonClient.getTopic(any(String.class))).thenReturn(topic);

        taskManager = new StreamTaskManager(redissonClient, traceRecordService);
    }

    @Test
    void reportsCancelledTraceRunOnUserCancel() {
        MessageListener<String> listener = subscribeAndCaptureListener();
        taskManager.bindHandle(TASK_ID, handle);

        listener.onMessage("channel", TASK_ID);

        verify(handle).cancel();
        verify(traceRecordService).cancelRunByTaskId(eq(TASK_ID), any(Date.class));
    }

    @Test
    void reportsCancelledTraceRunOnlyOnce() {
        MessageListener<String> listener = subscribeAndCaptureListener();
        taskManager.bindHandle(TASK_ID, handle);

        listener.onMessage("channel", TASK_ID);
        listener.onMessage("channel", TASK_ID);

        verify(traceRecordService, times(1)).cancelRunByTaskId(eq(TASK_ID), any(Date.class));
    }

    @Test
    void skipsTraceReportForUnknownTask() {
        MessageListener<String> listener = subscribeAndCaptureListener();

        listener.onMessage("channel", "not-registered");

        verify(traceRecordService, never()).cancelRunByTaskId(any(), any());
    }

    @Test
    void stillCancelsStreamWhenTraceReportFails() {
        MessageListener<String> listener = subscribeAndCaptureListener();
        taskManager.bindHandle(TASK_ID, handle);
        doThrow(new RuntimeException("trace 库不可用"))
                .when(traceRecordService).cancelRunByTaskId(any(), any());

        listener.onMessage("channel", TASK_ID);

        verify(handle).cancel();
    }

    @SuppressWarnings("unchecked")
    private MessageListener<String> subscribeAndCaptureListener() {
        taskManager.subscribe();
        ArgumentCaptor<MessageListener<String>> captor = ArgumentCaptor.forClass(MessageListener.class);
        verify(topic).addListener(eq(String.class), captor.capture());
        return captor.getValue();
    }
}
