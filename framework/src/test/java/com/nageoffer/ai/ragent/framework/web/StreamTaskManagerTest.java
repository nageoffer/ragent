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

package com.nageoffer.ai.ragent.framework.web;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamTaskManagerTest {

    private static final String TASK_ID = "t-1001";
    private static final String OWNER_ID = "u-1";
    private static final String OTHER_ID = "u-2";
    private static final String CANCEL_TOPIC = "ragent:stream:cancel";
    private static final String CANCEL_KEY = "ragent:stream:cancel:" + TASK_ID;
    private static final String OWNER_KEY = "ragent:stream:owner:" + TASK_ID;

    private RBucket<String> cancelBucket;
    private RBucket<String> ownerBucket;
    private RTopic topic;
    private StreamTaskManager taskManager;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        cancelBucket = mock(RBucket.class);
        ownerBucket = mock(RBucket.class);
        topic = mock(RTopic.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.<String>getBucket(CANCEL_KEY)).thenReturn(cancelBucket);
        when(redissonClient.<String>getBucket(OWNER_KEY)).thenReturn(ownerBucket);
        when(redissonClient.getTopic(anyString())).thenReturn(topic);
        taskManager = new StreamTaskManager(redissonClient);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldRecordOwnerOnRegister() {
        taskManager.register(TASK_ID, OWNER_ID, () -> {
        });

        // 属主要跨节点可查：停止请求可能落在没跑这条流的节点上
        verify(ownerBucket).set(OWNER_ID, Duration.ofMinutes(30));
    }

    @Test
    void shouldRejectCancelFromNonOwner() {
        when(ownerBucket.get()).thenReturn(OWNER_ID);
        login(OTHER_ID);

        assertThatThrownBy(() -> taskManager.cancelByUser(TASK_ID))
                .isInstanceOf(ClientException.class);

        // taskId 是雪花 ID，时间有序可预测，越权者不该留下任何取消痕迹
        verify(cancelBucket, never()).set(anyString(), any(Duration.class));
        verify(topic, never()).publish(any());
    }

    @Test
    void shouldCancelWhenRequesterIsOwner() {
        when(ownerBucket.get()).thenReturn(OWNER_ID);
        login(OWNER_ID);

        taskManager.cancelByUser(TASK_ID);

        verify(cancelBucket).set(OWNER_ID, Duration.ofMinutes(30));
        // 载荷带上发起方，执行端才有得复核
        verify(topic).publish(TASK_ID + "|" + OWNER_ID);
    }

    @Test
    void shouldIgnoreCancelMarkerPlantedByOthers() {
        // 取消早于注册到达时属主还无从比对，只能在注册这一刻回头验标记
        when(cancelBucket.get()).thenReturn(OTHER_ID);
        AtomicInteger finalized = new AtomicInteger();

        taskManager.register(TASK_ID, OWNER_ID, finalized::incrementAndGet);

        assertThat(finalized.get()).isZero();
    }

    @Test
    void shouldHonorOwnMarkerPlantedBeforeRegister() {
        when(cancelBucket.get()).thenReturn(OWNER_ID);
        AtomicInteger finalized = new AtomicInteger();

        taskManager.register(TASK_ID, OWNER_ID, finalized::incrementAndGet);

        assertThat(finalized.get()).isOne();
    }

    @Test
    void shouldHonorSystemCancelMarker() {
        // SSE 超时与断连由容器回调触发，那条线程上没有登录用户可比对
        when(cancelBucket.get()).thenReturn("__system__");
        AtomicInteger finalized = new AtomicInteger();

        taskManager.register(TASK_ID, OWNER_ID, finalized::incrementAndGet);

        assertThat(finalized.get()).isOne();
    }

    @Test
    void shouldCancelSystemTaskLocallyWithoutRedisRoundTrip() {
        AtomicInteger interrupted = new AtomicInteger();
        AtomicInteger finalized = new AtomicInteger();
        taskManager.register(TASK_ID, OWNER_ID, finalized::incrementAndGet);
        taskManager.bindHandle(TASK_ID, interrupted::incrementAndGet);
        taskManager.cancel(TASK_ID);
        taskManager.cancel(TASK_ID);
        assertThat(interrupted.get()).isOne();
        assertThat(finalized.get()).isOne();
        verify(cancelBucket, never()).set(anyString(), any(Duration.class));
        verify(topic, never()).publish(any());
    }

    @Test
    void shouldDropBroadcastCancelFromNonOwner() {
        AtomicInteger finalized = new AtomicInteger();
        taskManager.register(TASK_ID, OWNER_ID, finalized::incrementAndGet);

        captureListener().onMessage(CANCEL_TOPIC, TASK_ID + "|" + OTHER_ID);

        // 发布端校验挡不住抢在属主落地前的喷射，执行端必须自己再比一次
        assertThat(finalized.get()).isZero();
        // 更要紧的是别置旗标：置了会让 register 的标记复核短路，等于把复核那道门绕开
        assertThat(taskManager.isCancelled(TASK_ID)).isFalse();
    }

    @Test
    void shouldRunBroadcastCancelFromOwner() {
        AtomicInteger finalized = new AtomicInteger();
        taskManager.register(TASK_ID, OWNER_ID, finalized::incrementAndGet);

        captureListener().onMessage(CANCEL_TOPIC, TASK_ID + "|" + OWNER_ID);

        assertThat(finalized.get()).isOne();
        assertThat(taskManager.isCancelled(TASK_ID)).isTrue();
    }

    @Test
    void shouldTreatLegacyPayloadAsSystemCancel() {
        AtomicInteger finalized = new AtomicInteger();
        taskManager.register(TASK_ID, OWNER_ID, finalized::incrementAndGet);

        // 滚动升级期老节点广播的是裸 taskId，它在发布前已做过同样的属主校验
        captureListener().onMessage(CANCEL_TOPIC, TASK_ID);

        assertThat(finalized.get()).isOne();
    }

    @Test
    void shouldNotResurrectTaskAfterUnregister() {
        AtomicInteger cancelled = new AtomicInteger();
        taskManager.register(TASK_ID, OWNER_ID, () -> {
        });
        taskManager.unregister(TASK_ID);

        // 流同步结束时 unregister 先于 bindHandle 执行，这里不该无中生有造一条幽灵任务
        taskManager.bindHandle(TASK_ID, cancelled::incrementAndGet);
        captureListener().onMessage(CANCEL_TOPIC, TASK_ID + "|__system__");

        assertThat(taskManager.isCancelled(TASK_ID)).isFalse();
        assertThat(cancelled.get()).isZero();
    }

    /**
     * 重复执行finalizer收尾逻辑问题复现
     * 会话注册register | 本地取消cancelLocal 可能会导致收尾逻辑重复执行
     */
    @Test
    void shouldRunFinalizerOnlyOnceWhenRegisterRacesWithBroadcastCancel() throws Exception {
        AtomicInteger finalized = new AtomicInteger();

        // register 窗口里唯一耗时的是这次 Redis 读，挂住它就能稳定地把广播插进窗口
        CountDownLatch reviewStarted = new CountDownLatch(1);
        CountDownLatch releaseReview = new CountDownLatch(1);
        when(cancelBucket.get()).thenAnswer(invocation -> {
            reviewStarted.countDown();
            assertThat(releaseReview.await(5, TimeUnit.SECONDS)).isTrue();
            return OWNER_ID;
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> registering = executor.submit(
                () -> taskManager.register(TASK_ID, OWNER_ID, finalized::incrementAndGet));
        try {
            assertThat(reviewStarted.await(5, TimeUnit.SECONDS)).isTrue();
            // 此刻 register 还没落地，广播已经跑完一轮收尾
            captureListener().onMessage(CANCEL_TOPIC, TASK_ID + "|" + OWNER_ID);
            assertThat(finalized.get()).isOne();
            releaseReview.countDown();
            // 必须等注册线程正常结束：子线程异常或超时会在这里显式失败，而不是靠最终计数假绿
            registering.get(5, TimeUnit.SECONDS);
        } finally {
            releaseReview.countDown();
            executor.shutdownNow();
        }

        assertThat(finalized.get()).isOne();
    }

    /**
     * 处理cancelAction抛出异常导致finalizer无法被执行
     * 当前通过捕获异常并且打印错误日志来处理
     */
    @Test
    void shouldRunFinalizerEvenWhenCancelActionThrows() {
        AtomicInteger finalized = new AtomicInteger();
        taskManager.register(TASK_ID, OWNER_ID, finalized::incrementAndGet);
        taskManager.bindHandle(TASK_ID, () -> {
            throw new IllegalStateException("打断上游失败");
        });

        // 两个调用点都是 fire-and-forget，异常只记录不外抛，但收尾不能跟着一起丢
        assertThatCode(() -> taskManager.cancel(TASK_ID)).doesNotThrowAnyException();

        assertThat(finalized.get()).isOne();
    }

    @Test
    void shouldNotThrowWhenFinalizerFails() {
        AtomicInteger interrupted = new AtomicInteger();
        // 1.finalizer执行逻辑抛出异常
        taskManager.register(TASK_ID, OWNER_ID, () -> {
            throw new IllegalStateException("收尾失败");
        });
        // 2.取消逻辑正常执行
        taskManager.bindHandle(TASK_ID, interrupted::incrementAndGet);

        // 3.断言异常是否抛出 -> 被捕获且打印错误日志
        assertThatCode(() -> taskManager.cancel(TASK_ID)).doesNotThrowAnyException();

        // 4.判断取消逻辑是否正确执行
        assertThat(interrupted.get()).isOne();
    }

    @Test
    void shouldLogBothFailuresWhenCancelActionAndFinalizerBothThrow() {
        taskManager.register(TASK_ID, OWNER_ID, () -> {
            throw new IllegalStateException("收尾失败");
        });
        taskManager.bindHandle(TASK_ID, () -> {
            throw new IllegalArgumentException("打断上游失败");
        });

        Logger logger = (Logger) LoggerFactory.getLogger(StreamTaskManager.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // cancelAction抛出的异常保留，收尾逻辑异常挂在 suppressed 上，一起进日志
            assertThatCode(() -> taskManager.cancel(TASK_ID)).doesNotThrowAnyException();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        IThrowableProxy failure = appender.list.stream()
                .filter(event -> event.getThrowableProxy() != null)
                .findFirst()
                .orElseThrow()
                .getThrowableProxy();
        assertThat(failure.getClassName()).isEqualTo(IllegalArgumentException.class.getName());
        assertThat(failure.getMessage()).isEqualTo("打断上游失败");
        assertThat(failure.getSuppressed()).singleElement().satisfies(suppressed -> {
            assertThat(suppressed.getClassName()).isEqualTo(IllegalStateException.class.getName());
            assertThat(suppressed.getMessage()).isEqualTo("收尾失败");
        });
    }

    /**
     * 取回订阅时注册的监听器，用它模拟一条到达本节点的取消广播
     */
    @SuppressWarnings("unchecked")
    private MessageListener<String> captureListener() {
        taskManager.subscribe();
        ArgumentCaptor<MessageListener<String>> captor = ArgumentCaptor.forClass(MessageListener.class);
        verify(topic).addListener(eq(String.class), captor.capture());
        return captor.getValue();
    }

    private void login(String userId) {
        UserContext.set(LoginUser.builder().userId(userId).build());
    }
}
