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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次 run 内执行事实的唯一生成者：run 起点、批次号、组内序号、单工具真起止与返回结果、两条中断时刻都只在这里产生一次
 * <p>
 * trace、SSE、PG、前端一律读这里，不各自复算——同一个事实有两个算法，迟早会算出两个值
 */
@Slf4j
public final class AgentToolExecutionFacts {

    /**
     * 放进 RuntimeContext 的键，中间件与工具体都从上下文取同一个实例
     */
    public static final String RUNTIME_CONTEXT_KEY = "ragent_tool_execution_facts";

    /**
     * 短路原因：我方技能遮蔽，detail 是触发遮蔽的 skillCode
     */
    public static final String SHORT_CIRCUIT_MASKED = "masked";

    /**
     * 短路原因：框架在进入工具体之前就否了，事件里拿不到具体原因，不细分
     */
    public static final String SHORT_CIRCUIT_PRE_EXECUTION = "pre_execution";

    private final String runId;
    private final Clock clock;

    /**
     * 本轮起点，构造即定格；轮次耗时从这里起算，不由两条消息的落库时刻相减
     */
    private final long runStartedAt;
    private final AtomicLong runEndedAt = new AtomicLong();

    private final Map<String, ToolFact> toolFacts = new ConcurrentHashMap<>();
    private final AtomicInteger callIndexSeq = new AtomicInteger();
    private final List<ToolBatchFact> batches = new CopyOnWriteArrayList<>();
    private final AtomicReference<ToolBatchFact> openBatch = new AtomicReference<>();

    /**
     * 0 表示未写入：中断时刻取自服务端 epoch millis，不可能落在 1970
     */
    private final AtomicLong interruptedAt = new AtomicLong();
    private final AtomicLong cancelledAt = new AtomicLong();

    public AgentToolExecutionFacts(String runId, Clock clock) {
        this.runId = runId == null || runId.isBlank() ? "run" : runId;
        this.clock = clock;
        this.runStartedAt = clock.millis();
    }

    /**
     * 从框架上下文取本次 run 的事实源，取不到返回 null，调用方按「无追踪」放行
     */
    public static AgentToolExecutionFacts from(RuntimeContext ctx) {
        Object facts = ctx == null ? null : ctx.get(RUNTIME_CONTEXT_KEY);
        return facts instanceof AgentToolExecutionFacts value ? value : null;
    }

    public long now() {
        return clock.millis();
    }

    /**
     * 本轮收口，终点 CAS 一次后返回耗时；落库与 SSE 各问一次拿到的必然是同一个数
     * 挂起等确认的那次就在这里收口，续跑是新的一次 run 重新起算——中间用户想了多久不算进任何一段
     */
    public long settleRun() {
        runEndedAt.compareAndSet(0L, clock.millis());
        return runEndedAt.get() - runStartedAt;
    }

    /**
     * 首个观测者分配序号，之后无论谁再问都返回原值：推理阶段的 pending 块与确认续跑的开头事件都可能是首个
     */
    public int callIndexOf(String toolCallId) {
        return factOf(toolCallId).callIndex;
    }

    public ToolFact toolFact(String toolCallId) {
        return factOf(toolCallId);
    }

    /**
     * 进入 acting 时开一批，已有未收尾的批时返回 null——谁开的谁负责收，避免两层中间件重复收尾
     */
    public ToolBatchFact beginBatch() {
        ToolBatchFact batch = new ToolBatchFact(runId + "-" + batches.size(), clock.millis());
        if (!openBatch.compareAndSet(null, batch)) {
            return null;
        }
        batches.add(batch);
        return batch;
    }

    /**
     * 批终点是 acting 流的真实终止，不取成员最晚结束：权限判定与后处理都算在批里
     */
    public void endBatch(ToolBatchFact batch) {
        if (batch == null) {
            return;
        }
        batch.endedAt.compareAndSet(0L, clock.millis());
        openBatch.compareAndSet(batch, null);
    }

    /**
     * 把本批名册挂上批次，顺序取自 ActingInput；已分配的序号一律沿用，不重排也不重分配
     */
    public void enroll(ToolBatchFact batch, List<String> toolCallIds) {
        if (batch == null || toolCallIds == null) {
            return;
        }
        int previous = Integer.MIN_VALUE;
        boolean ordered = true;
        for (String toolCallId : toolCallIds) {
            ToolFact fact = factOf(toolCallId);
            fact.batchId.compareAndSet(null, batch.batchId);
            batch.enroll(fact.toolCallId);
            ordered &= fact.callIndex > previous;
            previous = fact.callIndex;
        }
        if (!ordered) {
            // 只报不改：序号一经分配就是历史，为了对齐名册去改它等于让 PG 里已落的块对不上
            log.warn("acting 名册顺序与已分配的 callIndex 不一致, batchId: {}, toolCalls: {}",
                    batch.batchId, toolCallIds);
        }
    }

    /**
     * 工具体进入时打起点，CAS 一次，重试或重复订阅都不改已定格的时刻
     */
    public long markStarted(String toolCallId) {
        ToolFact fact = factOf(toolCallId);
        fact.startedAt.compareAndSet(0L, clock.millis());
        ToolBatchFact batch = openBatch.get();
        if (batch != null) {
            fact.batchId.compareAndSet(null, batch.batchId);
        }
        return fact.startedAt.get();
    }

    /**
     * 工具体终止时打终点，成功、异常、取消三条路都收在这里
     */
    public long markEnded(String toolCallId) {
        ToolFact fact = factOf(toolCallId);
        fact.endedAt.compareAndSet(0L, clock.millis());
        return fact.endedAt.get();
    }

    /**
     * 工具体返回时登记结果：框架整批回来才发结束事件，同批有工具没回来就被停止时，已返回的结局只能从这里读
     */
    public void markReturned(String toolCallId, ToolResultBlock result) {
        if (result != null) {
            factOf(toolCallId).result.compareAndSet(null, result);
        }
    }

    /**
     * 断在半路的工具收在中断时刻，没有中断时刻才退回当前时刻
     * 与 markEnded 分开是因为终点来源不同：正常终止取此刻，提前收口取全局那个终点
     */
    public long markTerminated(String toolCallId) {
        ToolFact fact = factOf(toolCallId);
        Long termination = terminationAt();
        fact.endedAt.compareAndSet(0L, termination == null ? clock.millis() : termination);
        return fact.endedAt.get();
    }

    /**
     * 未进入工具体就被否掉的调用，只记原因，不给起止——给了就是凭空造出一段执行
     */
    public void markShortCircuit(String toolCallId, String reason, String detail) {
        ToolFact fact = factOf(toolCallId);
        fact.shortCircuitReason.compareAndSet(null, reason);
        fact.shortCircuitDetail.compareAndSet(null, detail);
    }

    /**
     * 优雅中断的时刻，抢到的那次返回 true
     */
    public boolean markInterrupted() {
        return interruptedAt.compareAndSet(0L, clock.millis());
    }

    /**
     * 强制断流的时刻，抢到的那次返回 true
     */
    public boolean markCancelled() {
        return cancelledAt.compareAndSet(0L, clock.millis());
    }

    public Long interruptedAt() {
        return nullIfUnset(interruptedAt.get());
    }

    public Long cancelledAt() {
        return nullIfUnset(cancelledAt.get());
    }

    /**
     * 所有被提前收口的 span 统一读它：两条中断路径互斥，取先写入的那个
     */
    public Long terminationAt() {
        long interrupted = interruptedAt.get();
        long cancelled = cancelledAt.get();
        if (interrupted == 0L) {
            return nullIfUnset(cancelled);
        }
        if (cancelled == 0L) {
            return interrupted;
        }
        return Math.min(interrupted, cancelled);
    }

    public ToolBatchFact currentBatch() {
        return openBatch.get();
    }

    public ToolBatchFact batchAt(int index) {
        return index >= 0 && index < batches.size() ? batches.get(index) : null;
    }

    public int batchCount() {
        return batches.size();
    }

    private ToolFact factOf(String toolCallId) {
        String key = toolCallId == null || toolCallId.isBlank() ? "unknown" : toolCallId;
        return toolFacts.computeIfAbsent(key, id -> new ToolFact(id, callIndexSeq.getAndIncrement()));
    }

    private static Long nullIfUnset(long value) {
        return value == 0L ? null : value;
    }

    /**
     * 一次工具调用的全部事实，起止与返回结果各自 CAS 一次
     */
    public static final class ToolFact {

        private final String toolCallId;
        private final int callIndex;
        private final AtomicReference<String> batchId = new AtomicReference<>();
        private final AtomicLong startedAt = new AtomicLong();
        private final AtomicLong endedAt = new AtomicLong();
        private final AtomicReference<ToolResultBlock> result = new AtomicReference<>();
        private final AtomicReference<String> shortCircuitReason = new AtomicReference<>();
        private final AtomicReference<String> shortCircuitDetail = new AtomicReference<>();

        private ToolFact(String toolCallId, int callIndex) {
            this.toolCallId = toolCallId;
            this.callIndex = callIndex;
        }

        public int callIndex() {
            return callIndex;
        }

        public String batchId() {
            return batchId.get();
        }

        public Long startedAt() {
            return nullIfUnset(startedAt.get());
        }

        public Long endedAt() {
            return nullIfUnset(endedAt.get());
        }

        /**
         * 任一端缺失就留空：断在半路的那条没有耗时可言
         */
        public Long durationMs() {
            long start = startedAt.get();
            long end = endedAt.get();
            return start == 0L || end == 0L ? null : end - start;
        }

        /**
         * 抛异常或被取消的调用没有返回值，留空
         */
        public ToolResultBlock result() {
            return result.get();
        }

        public String shortCircuitReason() {
            return shortCircuitReason.get();
        }

        public String shortCircuitDetail() {
            return shortCircuitDetail.get();
        }
    }

    /**
     * 一次 acting 的批次事实，名册顺序即模型给出调用的顺序
     */
    public static final class ToolBatchFact {

        private final String batchId;
        private final long startedAt;
        private final AtomicLong endedAt = new AtomicLong();
        private final List<String> members = new CopyOnWriteArrayList<>();

        private ToolBatchFact(String batchId, long startedAt) {
            this.batchId = batchId;
            this.startedAt = startedAt;
        }

        private void enroll(String toolCallId) {
            if (!members.contains(toolCallId)) {
                members.add(toolCallId);
            }
        }

        public String batchId() {
            return batchId;
        }

        public long startedAt() {
            return startedAt;
        }

        public Long endedAt() {
            return nullIfUnset(endedAt.get());
        }

        public List<String> members() {
            return List.copyOf(members);
        }

        public int size() {
            return members.size();
        }
    }
}
