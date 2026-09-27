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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamTaskManagerTest {

    // 雪花 ID 串形状（#150 起用户侧 stop 合同：[1-9][0-9]{0,18}）
    private static final String TASK_ID = "1234567890123456789";
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
    void shouldMarkSystemCancelAsSystem() {
        taskManager.cancel(TASK_ID);

        verify(cancelBucket).set("__system__", Duration.ofMinutes(30));
        verify(topic).publish(TASK_ID + "|__system__");
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

    // ==================== #150：taskId 形状校验 + 待定标记短 TTL（审计 F-5） ====================

    @Test
    void 拒绝非法形状taskId且零副作用() {
        login(OWNER_ID);
        // 字母前缀 / 前导零 / 20 位超长 / 带空白 / 空串 / null：全部不触达 Redis 与广播
        for (String malformed : new String[]{"t-1001", "0123", "12345678901234567890", "123 456", ""}) {
            assertThatThrownBy(() -> taskManager.cancelByUser(malformed))
                    .as("taskId 形状非法：%s", malformed)
                    .isInstanceOf(ClientException.class)
                    .hasMessageContaining("任务标识非法");
        }
        assertThatThrownBy(() -> taskManager.cancelByUser(null))
                .isInstanceOf(ClientException.class);
        verify(cancelBucket, never()).set(anyString(), any(Duration.class));
        verify(topic, never()).publish(any());
    }

    @Test
    void 属主未命中埋秒级待定标记而非长键() {
        // 属主键不存在：注册竞态窗口——标记 TTL 只盖住真实注册窗口（排队 15s 上限+调度余量）
        when(ownerBucket.get()).thenReturn(null);
        login(OWNER_ID);

        taskManager.cancelByUser(TASK_ID);

        verify(cancelBucket).set(OWNER_ID, Duration.ofSeconds(60));
        verify(cancelBucket, never()).set(anyString(), eq(Duration.ofMinutes(30)));
        verify(topic).publish(TASK_ID + "|" + OWNER_ID);
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
