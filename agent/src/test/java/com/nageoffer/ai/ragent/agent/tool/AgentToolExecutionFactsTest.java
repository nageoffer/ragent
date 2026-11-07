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

package com.nageoffer.ai.ragent.agent.tool;

import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts.ToolBatchFact;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts.ToolFact;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事实源是 ID、时刻与批次的唯一生成者，这些用例钉住的是「只生成一次」
 */
class AgentToolExecutionFactsTest {

    /**
     * 可控时钟：每读一次前进 10ms，起止不会撞成同一个时刻
     */
    private static final class SteppingClock extends Clock {

        private final AtomicInteger ticks = new AtomicInteger();

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(1_000L + ticks.getAndIncrement() * 10L);
        }
    }

    private final SteppingClock clock = new SteppingClock();
    private final AgentToolExecutionFacts facts = new AgentToolExecutionFacts("task-1", clock);

    @Test
    void shouldAllocateCallIndexOncePerToolCallId() {
        assertThat(facts.callIndexOf("a")).isZero();
        assertThat(facts.callIndexOf("b")).isEqualTo(1);
        assertThat(facts.callIndexOf("a")).isZero();
        assertThat(facts.callIndexOf("c")).isEqualTo(2);
    }

    /**
     * 第二轮的批不从 0 起算：调用序号按整条回复（per-reply）单调递增，不是 bug
     */
    @Test
    void shouldKeepCallIndexMonotonicAcrossBatches() {
        ToolBatchFact first = facts.beginBatch();
        facts.enroll(first, List.of("a", "b"));
        facts.endBatch(first);

        ToolBatchFact second = facts.beginBatch();
        facts.enroll(second, List.of("c", "d"));

        assertThat(facts.callIndexOf("c")).isEqualTo(2);
        assertThat(facts.callIndexOf("d")).isEqualTo(3);
        assertThat(second.members()).containsExactly("c", "d");
    }

    @Test
    void shouldOpenOneBatchAtATime() {
        ToolBatchFact first = facts.beginBatch();
        assertThat(first).isNotNull();
        assertThat(first.batchId()).isEqualTo("task-1-0");
        assertThat(facts.beginBatch()).isNull();
        assertThat(facts.currentBatch()).isSameAs(first);

        facts.endBatch(first);
        assertThat(facts.currentBatch()).isNull();

        ToolBatchFact second = facts.beginBatch();
        assertThat(second.batchId()).isEqualTo("task-1-1");
        assertThat(facts.batchAt(0)).isSameAs(first);
        assertThat(facts.batchAt(1)).isSameAs(second);
        assertThat(facts.batchCount()).isEqualTo(2);
    }

    @Test
    void shouldRecordBatchBoundaryFromClock() {
        ToolBatchFact batch = facts.beginBatch();
        long started = batch.startedAt();
        facts.endBatch(batch);

        assertThat(batch.endedAt()).isNotNull().isGreaterThan(started);
        Long settled = batch.endedAt();
        facts.endBatch(batch);
        assertThat(batch.endedAt()).isEqualTo(settled);
    }

    /**
     * 轮次耗时定格一次：落库与 SSE 各问一次，中间时钟又走了也得答同一个数
     */
    @Test
    void shouldSettleRunDurationOnce() {
        long settled = facts.settleRun();

        assertThat(settled).isPositive();
        assertThat(facts.settleRun()).isEqualTo(settled);
        assertThat(facts.settleRun()).isEqualTo(settled);
    }

    @Test
    void shouldStampToolBodyTimesOnce() {
        ToolBatchFact batch = facts.beginBatch();
        facts.enroll(batch, List.of("a"));

        long started = facts.markStarted("a");
        facts.markStarted("a");
        long ended = facts.markEnded("a");
        facts.markEnded("a");

        ToolFact fact = facts.toolFact("a");
        assertThat(fact.startedAt()).isEqualTo(started);
        assertThat(fact.endedAt()).isEqualTo(ended);
        assertThat(fact.durationMs()).isEqualTo(ended - started);
        assertThat(fact.batchId()).isEqualTo(batch.batchId());
    }

    /**
     * 断在半路的那条只有起点，宁可没耗时也不编一个
     */
    @Test
    void shouldLeaveDurationEmptyWhenOneEndMissing() {
        facts.markStarted("a");

        ToolFact fact = facts.toolFact("a");
        assertThat(fact.startedAt()).isNotNull();
        assertThat(fact.endedAt()).isNull();
        assertThat(fact.durationMs()).isNull();
    }

    /**
     * 并行两条各记各的，晚开始的那条不能把先开始的覆盖掉
     */
    @Test
    void shouldKeepParallelToolTimesIndependent() {
        facts.markStarted("a");
        facts.markStarted("b");
        facts.markEnded("b");
        facts.markEnded("a");

        ToolFact slow = facts.toolFact("a");
        ToolFact fast = facts.toolFact("b");
        assertThat(slow.startedAt()).isLessThan(fast.startedAt());
        assertThat(slow.endedAt()).isGreaterThan(fast.endedAt());
        assertThat(slow.durationMs()).isGreaterThan(fast.durationMs());
    }

    @Test
    void shouldRecordShortCircuitWithoutExecutionTime() {
        facts.markShortCircuit("a", AgentToolExecutionFacts.SHORT_CIRCUIT_MASKED, "skill-1");

        ToolFact fact = facts.toolFact("a");
        assertThat(fact.shortCircuitReason()).isEqualTo(AgentToolExecutionFacts.SHORT_CIRCUIT_MASKED);
        assertThat(fact.shortCircuitDetail()).isEqualTo("skill-1");
        assertThat(fact.startedAt()).isNull();
        assertThat(fact.endedAt()).isNull();
    }

    /**
     * 两条中断路径互斥：terminationAt 只认先写入的那一个
     */
    @Test
    void shouldKeepInterruptAndCancelExclusive() {
        assertThat(facts.terminationAt()).isNull();
        assertThat(facts.markInterrupted()).isTrue();
        long interrupted = facts.interruptedAt();

        assertThat(facts.markCancelled()).isTrue();
        assertThat(facts.cancelledAt()).isGreaterThan(interrupted);
        assertThat(facts.terminationAt()).isEqualTo(interrupted);
    }

    @Test
    void shouldTakeCancelAsTerminationWhenItComesFirst() {
        assertThat(facts.markCancelled()).isTrue();
        long cancelled = facts.cancelledAt();
        facts.markInterrupted();

        assertThat(facts.terminationAt()).isEqualTo(cancelled);
    }

    @Test
    void shouldWriteInterruptOnlyOnceUnderConcurrency() throws Exception {
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    if (facts.markInterrupted()) {
                        winners.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(winners.get()).isEqualTo(1);
    }

    @Test
    void shouldAllocateCallIndexOnceUnderConcurrency() throws Exception {
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    return facts.callIndexOf("same");
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(facts.callIndexOf("same")).isZero();
        assertThat(facts.callIndexOf("next")).isEqualTo(1);
    }
}
