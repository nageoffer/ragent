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

package com.nageoffer.ai.ragent.rag.service.ratelimit;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.config.RAGRateLimitProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatQueueLimiterTest {

    private static final String USER_ID = "user-1";

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RPermitExpirableSemaphore semaphore;

    @Mock
    private RScoredSortedSet<String> queue;

    @Mock
    private RAtomicLong queueSequence;

    @Mock
    private RBucket<String> entryMarker;

    @Mock
    private RScript script;

    @Mock
    private RTopic topic;

    @Mock
    private ConversationMemoryService memoryService;

    @Mock
    private ConversationGroupService conversationGroupService;

    private FairDistributedRateLimiter rateLimiter;
    private ChatQueueLimiter chatQueueLimiter;

    @BeforeEach
    void setUp() throws Exception {
        when(redissonClient.getPermitExpirableSemaphore(anyString())).thenReturn(semaphore);
        doReturn(queue).when(redissonClient).getScoredSortedSet(anyString(), eq(StringCodec.INSTANCE));
        when(redissonClient.getAtomicLong(anyString())).thenReturn(queueSequence);
        doReturn(entryMarker).when(redissonClient).getBucket(anyString(), eq(StringCodec.INSTANCE));
        when(redissonClient.getTopic(anyString())).thenReturn(topic);
        when(queueSequence.incrementAndGet()).thenReturn(1L);

        rateLimiter = new FairDistributedRateLimiter("test:chat", redissonClient, () -> 1, () -> 30, () -> 50);
        rateLimiter.start();
        prestartSchedulerThreads(rateLimiter);

        RAGRateLimitProperties rateLimitProperties = new RAGRateLimitProperties();
        rateLimitProperties.setGlobalEnabled(true);
        rateLimitProperties.setGlobalMaxWaitSeconds(1);

        MemoryProperties memoryProperties = new MemoryProperties();
        memoryProperties.setTitleMaxLength(30);

        chatQueueLimiter = new ChatQueueLimiter(
                rateLimiter,
                Runnable::run,
                rateLimitProperties,
                memoryService,
                conversationGroupService,
                memoryProperties);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        rateLimiter.stop();
    }

    @Test
    void shouldKeepUserContextWhenQueuedRequestIsAcquiredByScheduler() throws Exception {
        when(semaphore.availablePermits()).thenReturn(0, 1);
        when(semaphore.tryAcquire(anyLong(), anyLong(), any())).thenReturn("permit-1");
        when(redissonClient.getScript(eq(StringCodec.INSTANCE))).thenReturn(script);
        doReturn(List.of(1L, 1L)).when(script).eval(
                eq(RScript.Mode.READ_WRITE),
                anyString(),
                eq(RScript.ReturnType.LIST),
                anyList(),
                any(),
                any(),
                any());

        AtomicReference<String> actualUserId = new AtomicReference<>();
        CountDownLatch acquired = new CountDownLatch(1);
        setUserContext();

        chatQueueLimiter.enqueue("排队问题", "conversation-1", new SseEmitter(), () -> {
            actualUserId.set(UserContext.getUserId());
            acquired.countDown();
        });
        UserContext.clear();

        assertTrue(acquired.await(3, TimeUnit.SECONDS));
        assertEquals(USER_ID, actualUserId.get());
    }

    @Test
    void shouldKeepUserContextWhenQueuedRequestTimesOutOnScheduler() throws Exception {
        when(semaphore.availablePermits()).thenReturn(0);
        CountDownLatch memoryWritten = new CountDownLatch(2);
        doAnswer(invocation -> {
            memoryWritten.countDown();
            return "message-1";
        }).when(memoryService).append(anyString(), eq(USER_ID), any());
        setUserContext();

        chatQueueLimiter.enqueue("排队问题", null, new SseEmitter(), () -> {
        });
        UserContext.clear();

        assertTrue(memoryWritten.await(3, TimeUnit.SECONDS));
        verify(memoryService, times(2)).append(anyString(), eq(USER_ID), any());
    }

    private static void setUserContext() {
        UserContext.set(LoginUser.builder().userId(USER_ID).username("tester").role("user").build());
    }

    private static void prestartSchedulerThreads(FairDistributedRateLimiter limiter) throws Exception {
        Field schedulerField = FairDistributedRateLimiter.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        ScheduledThreadPoolExecutor scheduler = (ScheduledThreadPoolExecutor) schedulerField.get(limiter);
        scheduler.prestartAllCoreThreads();
    }
}
