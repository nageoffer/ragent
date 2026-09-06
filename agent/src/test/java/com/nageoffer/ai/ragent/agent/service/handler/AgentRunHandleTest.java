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
import static org.mockito.Mockito.doAnswer;
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
    void shouldSettleOnlyOnceAcrossThreeExits() {
        AtomicInteger released = new AtomicInteger();
        handle.onRelease(released::incrementAndGet);

        handle.cancel(() -> {
        });
        handle.complete(() -> {
        });
        handle.fail(() -> {
        });

        verify(taskManager, times(1)).unregister(TASK_ID);
        verify(sender, times(1)).complete();
        assertThat(released.get()).isOne();
    }

    @Test
    void shouldCloseFailedRunNormally() {
        handle.fail(() -> {
        });

        // 失败事件已在收尾体里从流内发过，再走 completeWithError 只会让容器改写响应体
        // 而响应头早已是 text/event-stream，写不出去，客户端什么终止信号都收不到
        verify(sender, times(1)).complete();
        verify(sender, never()).fail(any());
        assertThat(handle.isFailed()).isTrue();
    }

    @Test
    void shouldInterruptBeforeDispose() {
        Disposable disposable = mock(Disposable.class);
        Runnable interrupt = mock(Runnable.class);
        // 中断动作触发上游终止，模拟框架在窗口内自行收尾的优雅路径
        doAnswer(invocation -> {
            handle.markUpstreamTerminated();
            return null;
        }).when(interrupt).run();
        handle.bindStream(disposable, interrupt);

        handle.interruptUpstream();

        // 先 dispose 会掐断响应式链，框架的 handleInterrupt → saveStateToSession 永远跑不到
        // 代价是已执行的工具结果不落库，确认后立刻停止这条路径尤其明显
        InOrder order = inOrder(interrupt, disposable);
        order.verify(interrupt).run();
        order.verify(disposable).dispose();
        // 优雅收尾框架已存盘，释放钩子不该再补
        assertThat(handle.isForcedDisposal()).isFalse();
    }

    @Test
    void shouldMarkForcedDisposalWhenAwaitTimesOut() {
        Disposable disposable = mock(Disposable.class);
        // 中断动作不触发终止信号，等满窗口后必须转强制断流
        handle.bindStream(disposable, () -> {
        });

        handle.interruptUpstream();

        assertThat(handle.isForcedDisposal()).isTrue();
        verify(disposable).dispose();
    }

    @Test
    void shouldDisposeEvenWhenInterruptActionThrows() {
        Disposable disposable = mock(Disposable.class);
        handle.bindStream(disposable, () -> {
            throw new IllegalStateException("打断动作炸了");
        });

        // 异常不能外抛，否则 StreamTaskManager 的取消收尾链会被打断
        assertThatCode(() -> handle.interruptUpstream()).doesNotThrowAnyException();

        assertThat(handle.isForcedDisposal()).isTrue();
        verify(disposable).dispose();
    }

    @Test
    void shouldMarkForcedDisposalWhenAwaitInterrupted() {
        Disposable disposable = mock(Disposable.class);
        handle.bindStream(disposable, () -> {
        });

        Thread.currentThread().interrupt();
        try {
            handle.interruptUpstream();
        } finally {
            // 清掉测试线程的中断旗标，免得污染后续用例
            assertThat(Thread.interrupted()).isTrue();
        }

        assertThat(handle.isForcedDisposal()).isTrue();
        verify(disposable).dispose();
    }

    @Test
    void shouldInterruptOnlyOnceWhenCalledTwice() {
        Disposable disposable = mock(Disposable.class);
        Runnable interrupt = mock(Runnable.class);
        doAnswer(invocation -> {
            handle.markUpstreamTerminated();
            return null;
        }).when(interrupt).run();
        handle.bindStream(disposable, interrupt);

        // 取消广播线程与补掐路径可能各调一次，第二次必须是空操作
        handle.interruptUpstream();
        handle.interruptUpstream();

        verify(interrupt, times(1)).run();
        verify(disposable, times(1)).dispose();
    }

    @Test
    void shouldMarkCancelledExitOnCancel() {
        handle.cancel(() -> {
        });

        // 取消抢在句柄绑定前结算时，调用方靠这个旗标补掐上游
        assertThat(handle.isCancelledExit()).isTrue();
    }

    @Test
    void shouldNotMarkCancelledExitOnComplete() {
        handle.complete(() -> {
        });

        // 完成出口上游已自行终止，补掐会对结束的运行多调一次打断
        assertThat(handle.isCancelledExit()).isFalse();
    }

    @Test
    void shouldTolerateUnboundStream() {
        assertThatCode(() -> handle.interruptUpstream()).doesNotThrowAnyException();
    }
}
