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

import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import com.nageoffer.ai.ragent.agent.trace.AgentRunTracer;
import com.nageoffer.ai.ragent.agent.trace.CollectingSpanExporter;
import com.nageoffer.ai.ragent.agent.trace.RagentAttributes;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import io.agentscope.core.agent.RuntimeContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.Disposable;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunHandleTest {

    private static final String TASK_ID = "t-9001";

    private SseEmitterSender sender;
    private StreamTaskManager taskManager;
    private AgentToolExecutionFacts facts;
    private RuntimeContext runtimeContext;
    private AgentRunHandle handle;
    private List<SpanData> exported;
    private SdkTracerProvider tracerProvider;

    @BeforeEach
    void setUp() {
        sender = mock(SseEmitterSender.class);
        taskManager = mock(StreamTaskManager.class);
        facts = new AgentToolExecutionFacts(TASK_ID, Clock.systemDefaultZone());
        runtimeContext = RuntimeContext.builder().userId("u-1001").sessionId("c-2002").build();
        handle = new AgentRunHandle(TASK_ID, sender, taskManager, facts, runtimeContext);
        exported = Collections.synchronizedList(new ArrayList<>());
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.builder(new CollectingSpanExporter(exported)).build())
                .build();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
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
    void shouldReleaseAndCloseWhenUnregisterThrows() {
        AtomicInteger released = new AtomicInteger();
        handle.onRelease(() -> { throw new IllegalStateException("清缓存失败"); });
        handle.onRelease(released::incrementAndGet);
        doThrow(new IllegalStateException("Redis 不可用")).when(taskManager).unregister(TASK_ID);

        assertThatCode(() -> handle.complete(() -> {})).doesNotThrowAnyException();

        assertThat(released.get()).isOne();
        verify(sender).complete();
    }

    @Test
    void shouldNotStartAfterCancellationSettles() {
        Runnable startup = mock(Runnable.class);
        handle.cancel(() -> {});

        handle.start(startup);

        verify(startup, never()).run();
    }

    @Test
    void shouldKeepGateUntilStartupHandoffCompletes() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch continueStartup = new CountDownLatch(1);
        CountDownLatch cancelEntered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        handle.onRelease(released::countDown);
        try {
            var starting = pool.submit(() -> handle.start(() -> {
                entered.countDown();
                await(continueStartup);
                handle.bindStream(mock(Disposable.class), () -> {});
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var cancelling = pool.submit(() -> {
                cancelEntered.countDown();
                handle.cancel(() -> {});
            });
            assertThat(cancelEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(released.await(100, TimeUnit.MILLISECONDS)).isFalse();
            continueStartup.countDown();
            starting.get(5, TimeUnit.SECONDS);
            cancelling.get(5, TimeUnit.SECONDS);
            assertThat(released.getCount()).isZero();
        } finally {
            continueStartup.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void shouldDisposeSubscriptionWhenStartupFailsAfterSubscribe() {
        Disposable disposable = mock(Disposable.class);

        assertThatCode(() -> handle.start(() -> {
            handle.bindStream(disposable, () -> {});
            throw new IllegalStateException("绑定取消能力失败");
        })).isInstanceOf(IllegalStateException.class);

        // 不掐链的话上游会空跑到 ReAct 迭代上限
        verify(disposable).dispose();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
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
    void shouldNotRequireStateSaveWhenCancelLosesToCompletion() throws Exception {
        assertOnlyWinningExitSettles(false);
    }

    @Test
    void shouldRequireStateSaveOnceWhenCancelWinsAgainstCompletion() throws Exception {
        assertOnlyWinningExitSettles(true);
    }

    private void assertOnlyWinningExitSettles(boolean cancellationWins) throws Exception {
        Runnable completed = mock(Runnable.class);
        Runnable cancelled = mock(Runnable.class);
        Runnable failed = mock(Runnable.class);
        List<Boolean> stateSaveRequiredOnRelease = new ArrayList<>();
        handle.onRelease(() -> stateSaveRequiredOnRelease.add(handle.isStateSaveRequired()));
        CountDownLatch winnerEntered = new CountDownLatch(1);
        CountDownLatch continueWinner = new CountDownLatch(1);
        CountDownLatch loserAttempted = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Runnable winningBody = () -> {
                (cancellationWins ? cancelled : completed).run();
                winnerEntered.countDown();
                await(continueWinner);
            };
            var winner = pool.submit(() -> {
                if (cancellationWins) {
                    handle.cancel(winningBody);
                } else {
                    handle.complete(winningBody);
                }
            });
            assertThat(winnerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var loser = pool.submit(() -> {
                loserAttempted.countDown();
                if (cancellationWins) {
                    handle.complete(completed);
                } else {
                    handle.cancel(cancelled);
                }
            });
            assertThat(loserAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            continueWinner.countDown();
            winner.get(5, TimeUnit.SECONDS);
            loser.get(5, TimeUnit.SECONDS);
            handle.fail(failed);

            verify(completed, times(cancellationWins ? 0 : 1)).run();
            verify(cancelled, times(cancellationWins ? 1 : 0)).run();
            verify(failed, never()).run();
            // 只有取得收尾权的一方能决定补存；释放时已可见，失败入口也不能再改写。
            assertThat(stateSaveRequiredOnRelease).containsExactly(cancellationWins);
            assertThat(handle.isStateSaveRequired()).isEqualTo(cancellationWins);
            verify(taskManager, times(1)).unregister(TASK_ID);
            verify(sender, times(1)).complete();
        } finally {
            continueWinner.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void shouldCloseFailedRunNormally() {
        handle.fail(() -> {
        });

        // 失败事件已在收尾体里从流内发过，再走 completeWithError 只会让容器改写响应体
        // 而响应头早已是 text/event-stream，写不出去，客户端什么终止信号都收不到
        verify(sender, times(1)).complete();
        verify(sender, never()).fail(any());
        assertThat(handle.isStateSaveRequired()).isTrue();
    }

    @Test
    void shouldNotRequireStateSaveWhenFailLosesSettleRace() {
        handle.complete(() -> {
        });

        handle.fail(() -> {
        });

        // 补存盘旗标只能由赢得收尾权的那次置位，否则正常完成也会被补一次无谓的存盘
        assertThat(handle.isStateSaveRequired()).isFalse();
    }

    @Test
    void shouldKeepStateSaveRequiredAfterGracefulInterrupt() {
        Disposable disposable = mock(Disposable.class);
        Runnable interrupt = mock(Runnable.class);
        doAnswer(invocation -> {
            handle.markUpstreamTerminated();
            return null;
        }).when(interrupt).run();
        handle.bindStream(disposable, interrupt);
        handle.fail(() -> {
        });

        handle.interruptUpstream();

        // 优雅中断无条件写这个旗标的话，会把失败出口的补存盘需求冲掉
        assertThat(handle.isStateSaveRequired()).isTrue();
    }

    @Test
    void shouldRunHookRegisteredInsideSettleBodyAfterBodyCompletes() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());

        handle.complete(() -> {
            handle.onRelease(() -> order.add("release"));
            order.add("body");
        });

        // 收尾体里登记的钩子要排在收尾体之后，提前跑会把闸门放在落库之前
        assertThat(order).containsExactly("body", "release");
    }

    @Test
    void shouldInterruptBeforeDisposeAndRequireStateSaveOnGracefulCancellation() {
        Disposable disposable = mock(Disposable.class);
        Runnable interrupt = mock(Runnable.class);
        Runnable cancelled = mock(Runnable.class);
        List<Boolean> stateSaveRequiredOnRelease = new ArrayList<>();
        handle.onRelease(() -> stateSaveRequiredOnRelease.add(handle.isStateSaveRequired()));
        // 中断动作触发上游终止，模拟框架在窗口内自行收尾的优雅路径
        doAnswer(invocation -> {
            handle.markUpstreamTerminated();
            return null;
        }).when(interrupt).run();
        handle.bindStream(disposable, interrupt);

        handle.interruptUpstream();

        // 中断等待只协调上游终止，不能据此断言框架已经存盘成功。
        assertThat(handle.isStateSaveRequired()).isFalse();
        handle.cancel(cancelled);

        // 先请求中断并等待，随后断流，最后才执行取消收尾。
        InOrder order = inOrder(interrupt, disposable, cancelled);
        order.verify(interrupt).run();
        order.verify(disposable).dispose();
        order.verify(cancelled).run();
        assertThat(stateSaveRequiredOnRelease).containsExactly(true);
        assertThat(handle.isStateSaveRequired()).isTrue();
    }

    /**
     * 中断时刻必须在打断动作之前定格，否则未完 span 终点落错
     */
    @Test
    void shouldStampInterruptTimeBeforeInterruptAction() {
        Disposable disposable = mock(Disposable.class);
        AtomicInteger stampedWhenInterrupting = new AtomicInteger();
        handle.bindStream(disposable, () -> {
            stampedWhenInterrupting.set(facts.interruptedAt() == null ? 0 : 1);
            handle.markUpstreamTerminated();
        });

        handle.interruptUpstream();

        assertThat(stampedWhenInterrupting.get()).isOne();
        // 优雅路径没掐链，cancelledAt 应为空
        assertThat(facts.cancelledAt()).isNull();
        assertThat(facts.terminationAt()).isEqualTo(facts.interruptedAt());
    }

    /**
     * 根 span 在框架 onComplete 后就 end 了，结局必须在打断前写入
     */
    @Test
    void shouldWriteInterruptedOutcomeBeforeInterruptAction() {
        Span root = bindRootSpan();
        Disposable disposable = mock(Disposable.class);
        // 模拟框架收尾时官方中间件 end 根 span
        handle.bindStream(disposable, () -> {
            root.end();
            handle.markUpstreamTerminated();
        });

        handle.interruptUpstream();

        assertThat(onlySpan().getAttributes().get(RagentAttributes.RUN_OUTCOME))
                .isEqualTo(RagentAttributes.RUN_OUTCOME_INTERRUPTED);
    }

    /**
     * dispose 后没有线程能读 span，结局必须在掐链前落定
     */
    @Test
    void shouldWriteAbortedOutcomeBeforeDispose() {
        Span root = bindRootSpan();
        Disposable disposable = mock(Disposable.class);
        doAnswer(invocation -> {
            root.end();
            return null;
        }).when(disposable).dispose();
        // 不发终止信号，等满窗口后走强制断流
        handle.bindStream(disposable, () -> {
        });

        handle.interruptUpstream();

        assertThat(onlySpan().getAttributes().get(RagentAttributes.RUN_OUTCOME))
                .isEqualTo(RagentAttributes.RUN_OUTCOME_ABORTED);
    }

    @Test
    void shouldMarkForcedDisposalWhenAwaitTimesOut() {
        Disposable disposable = mock(Disposable.class);
        List<Boolean> stateSaveRequiredOnRelease = new ArrayList<>();
        handle.onRelease(() -> stateSaveRequiredOnRelease.add(handle.isStateSaveRequired()));
        // 中断动作不触发终止信号，等满窗口后必须转强制断流
        handle.bindStream(disposable, () -> {
        });

        handle.interruptUpstream();

        assertThat(handle.isStateSaveRequired()).isTrue();
        verify(disposable).dispose();
        assertThat(facts.cancelledAt()).isNotNull();
        // 两条路径都写过，收口对齐先写入的
        assertThat(facts.terminationAt()).isEqualTo(facts.interruptedAt());

        handle.cancel(() -> {});
        handle.complete(() -> {});
        handle.fail(() -> {});

        assertThat(stateSaveRequiredOnRelease).containsExactly(true);
        verify(taskManager, times(1)).unregister(TASK_ID);
        verify(sender, times(1)).complete();
    }

    @Test
    void shouldDisposeEvenWhenInterruptActionThrows() {
        Disposable disposable = mock(Disposable.class);
        handle.bindStream(disposable, () -> {
            throw new IllegalStateException("打断动作炸了");
        });

        // 异常不能外抛，否则 StreamTaskManager 的取消收尾链会被打断
        assertThatCode(() -> handle.interruptUpstream()).doesNotThrowAnyException();

        assertThat(handle.isStateSaveRequired()).isTrue();
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

        assertThat(handle.isStateSaveRequired()).isTrue();
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
    void shouldTolerateUnboundStream() {
        assertThatCode(() -> handle.interruptUpstream()).doesNotThrowAnyException();
    }

    private Span bindRootSpan() {
        Span root = tracerProvider.get("test").spanBuilder("invoke_agent ragent").startSpan();
        AgentRunTracer.bindRoot(runtimeContext, root);
        return root;
    }

    private SpanData onlySpan() {
        assertThat(exported).hasSize(1);
        return exported.get(0);
    }
}
