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

package com.nageoffer.ai.ragent.agent.service.handler;

import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AgentRunHandleTest {

    private static final String TASK_ID = "t-9001";

    private SseEmitterSender sender;
    private StreamTaskManager taskManager;
    private AgentRunHandle handle;

    @BeforeEach
    void setUp() {
        sender = mock(SseEmitterSender.class);
        taskManager = mock(StreamTaskManager.class);
        handle = new AgentRunHandle(TASK_ID, sender, taskManager);
    }

    @Test
    void shouldReleaseOnEveryExit() {
        AtomicInteger released = new AtomicInteger();
        handle.onRelease(released::incrementAndGet);

        handle.complete(() -> {
        });

        verify(taskManager).unregister(TASK_ID);
        assertThat(released.get()).isOne();
    }

    @Test
    void shouldCloseChannelWhenBodyThrows() {
        AtomicInteger released = new AtomicInteger();
        handle.onRelease(released::incrementAndGet);

        assertThatCode(() -> handle.complete(() -> {
            throw new IllegalStateException("收尾体炸了");
        })).doesNotThrowAnyException();

        // 收尾体失败不该把闸门和任务登记一起漏掉，否则该用户再也发不出下一轮
        verify(taskManager).unregister(TASK_ID);
        assertThat(released.get()).isOne();
        // 结算旗标已让超时守卫失效，这里再不关通道，SSE 就得挂到 900s 超时
        verify(sender).complete();
    }

    @Test
    void shouldRunHookRegisteredAfterSettleExactlyOnce() {
        AtomicInteger beforeSettle = new AtomicInteger();
        AtomicInteger afterSettle = new AtomicInteger();
        handle.onRelease(beforeSettle::incrementAndGet);
        handle.complete(() -> {
        });

        handle.onRelease(afterSettle::incrementAndGet);

        // 结算后登记的钩子当场补跑，否则后人往 subscribe 之后加资源会被静默泄漏
        assertThat(afterSettle.get()).isOne();
        // 补跑的只是新钩子，先前跑过的不会被再拖一遍
        assertThat(beforeSettle.get()).isOne();
    }

    @Test
    void shouldCloseChannelNormallyOnFail() {
        // 失败态不再 completeWithError（避免全局异常处理器在已提交的 event-stream 上刷 No converter），
        // 改为与 complete/cancel 一致正常关闭，失败告知由调用方以 SSE error 事件发出
        handle.fail(new IllegalStateException("上游炸了"), () -> {
        });

        verify(taskManager).unregister(TASK_ID);
        verify(sender).complete();
        verify(sender, never()).fail(any());
    }

    @Test
    void shouldSettleOnlyOnceAcrossThreeExits() {
        AtomicInteger released = new AtomicInteger();
        handle.onRelease(released::incrementAndGet);

        handle.cancel(() -> {
        });
        handle.complete(() -> {
        });
        handle.fail(new IllegalStateException("上游炸了"), () -> {
        });

        verify(taskManager, times(1)).unregister(TASK_ID);
        verify(sender, times(1)).complete();
        verify(sender, never()).fail(any());
        assertThat(released.get()).isOne();
    }

    @Test
    void shouldDisposeBeforeInterrupt() {
        Disposable disposable = mock(Disposable.class);
        Runnable interrupt = mock(Runnable.class);
        handle.bindStream(disposable, interrupt);

        handle.interruptUpstream();

        // 顺序反了会让 ReAct 循环在断流后继续空转到迭代上限
        InOrder order = inOrder(disposable, interrupt);
        order.verify(disposable).dispose();
        order.verify(interrupt).run();
    }

    @Test
    void shouldTolerateUnboundStream() {
        assertThatCode(() -> handle.interruptUpstream()).doesNotThrowAnyException();
    }
}
