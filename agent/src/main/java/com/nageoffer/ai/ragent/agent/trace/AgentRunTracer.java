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

package com.nageoffer.ai.ragent.agent.trace;

import cn.hutool.core.util.StrUtil;
import io.agentscope.core.agent.RuntimeContext;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import reactor.util.context.ContextView;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 根 span 的存取口：中间件把它寄存进来，中断链路与轮次回写从这里取
 * 也是 RuntimeContext 上业务身份与轮次落到 span 的唯一写入点，中间件链内外的 span 都走这里
 * <p>
 * 中断发生在响应式链之外的 HTTP 线程上，那里够不着 Reactor 上下文，只能靠 RuntimeContext 这一条路
 */
public final class AgentRunTracer {

    /**
     * 根 span 寄存在调用上下文里，RuntimeContext 由调用方每次新建，天然按 run 隔离
     */
    static final String ROOT_SPAN_ATTRIBUTE = "ragent_trace_root_span";

    /**
     * 轮次计数器与根 span 同住上下文：推理那层递增一次，模型调用、工具批、工具执行体都只读
     */
    static final String ROUND_COUNTER_ATTRIBUTE = "ragent_trace_round_counter";

    private static final String INTERRUPTED_MESSAGE = "用户中止本轮，框架已自行收尾并存盘";

    private static final String ABORTED_MESSAGE = "用户中止本轮，等不到框架收尾，链路被强制断开";

    /**
     * 结局按严重度排序，强制断流可覆盖先发生的 interrupted
     */
    private static final List<String> SEVERITY = List.of(
            RagentAttributes.RUN_OUTCOME_COMPLETED,
            RagentAttributes.RUN_OUTCOME_FAILED,
            RagentAttributes.RUN_OUTCOME_INTERRUPTED,
            RagentAttributes.RUN_OUTCOME_ABORTED);

    /**
     * 先读 Reactor 上下文再退回 ThreadLocal，与官方中间件同一套取法，跨线程后仍能找到父节点
     * 三处建 span 的地方共用这一份，各写各的会在某处漏掉退回路径时让那棵子树变成孤儿
     */
    static Context otelContext(ContextView ctxView) {
        return ContextPropagationOperator.getOpenTelemetryContextFromContextView(ctxView, Context.current());
    }

    public static void bindRoot(RuntimeContext ctx, Span span) {
        if (ctx != null && span != null) {
            ctx.put(ROOT_SPAN_ATTRIBUTE, new RootSpanRef(span, new AtomicReference<>()));
            ctx.put(ROUND_COUNTER_ATTRIBUTE, new AtomicInteger());
        }
    }

    /**
     * 推理那层是唯一的递增者，返回本轮序号
     */
    static int nextRound(RuntimeContext ctx) {
        AtomicInteger counter = counter(ctx);
        return counter == null ? 0 : counter.incrementAndGet();
    }

    /**
     * 读当前轮次，0 表示还没进过推理或追踪没生效，写入方据此跳过不写
     */
    private static int currentRound(RuntimeContext ctx) {
        AtomicInteger counter = counter(ctx);
        return counter == null ? 0 : counter.get();
    }

    /**
     * 轮次号只落有意义的节点，0 一律不写：写了会多出一档假轮次
     */
    static void writeRoundIndex(Span span, RuntimeContext ctx) {
        int round = currentRound(ctx);
        if (span != null && round > 0) {
            span.setAttribute(RagentAttributes.REACT_ROUND_INDEX, round);
        }
    }

    private static AtomicInteger counter(RuntimeContext ctx) {
        Object counter = ctx == null ? null : ctx.get(ROUND_COUNTER_ATTRIBUTE);
        return counter instanceof AtomicInteger value ? value : null;
    }

    /**
     * 用户、会话与三个业务 ID 写在每个 span 上：只写根 span 会让 observation 级的过滤与聚合失准，
     * 追踪后端不会把父节点的这几项回填给子节点，工具体 span 漏了就只能从 trace 逐层下钻
     * 三个业务 ID 是 PG 那侧的主键，链路里带上它们才有从消息记录反查到本条 trace 的路
     */
    static void writeIdentity(Span span, RuntimeContext ctx) {
        if (span == null || ctx == null) {
            return;
        }
        if (StrUtil.isNotBlank(ctx.getUserId())) {
            span.setAttribute(AgentTraceAttributes.USER_ID, ctx.getUserId());
        }
        if (StrUtil.isNotBlank(ctx.getSessionId())) {
            span.setAttribute(AgentTraceAttributes.SESSION_ID, ctx.getSessionId());
        }
        writeContextId(span, ctx, AgentTraceContextKeys.TASK_ID, RagentAttributes.TASK_ID);
        writeContextId(span, ctx, AgentTraceContextKeys.REPLY_TO_MESSAGE_ID, RagentAttributes.REPLY_TO_MESSAGE_ID);
        writeContextId(span, ctx, AgentTraceContextKeys.CONFIRM_MESSAGE_ID, RagentAttributes.CONFIRM_MESSAGE_ID);
    }

    /**
     * 首问没有确认消息号，缺哪个跳哪个，不落空串免得过滤时多出一档假值
     */
    private static void writeContextId(Span span, RuntimeContext ctx, String key, AttributeKey<String> attribute) {
        Object value = ctx.get(key);
        if (value instanceof String id && StrUtil.isNotBlank(id)) {
            span.setAttribute(attribute, id);
        }
    }

    public static void markCompleted(RuntimeContext ctx) {
        writeOutcome(ctx, RagentAttributes.RUN_OUTCOME_COMPLETED, null);
    }

    /**
     * 级别与报错原文由 onAgent 的错误回调写，这里只补结局，免得两处互相盖掉
     */
    public static void markFailed(RuntimeContext ctx) {
        writeOutcome(ctx, RagentAttributes.RUN_OUTCOME_FAILED, null);
    }

    /**
     * 框架正常完成中断时会写入 OK，此处只记录 interrupted，不设置 error.type
     */
    public static void markInterrupted(RuntimeContext ctx) {
        writeOutcome(ctx, RagentAttributes.RUN_OUTCOME_INTERRUPTED, span -> {
            span.setAttribute(AgentTraceAttributes.LEVEL, AgentTraceAttributes.LEVEL_WARNING);
            span.setAttribute(AgentTraceAttributes.STATUS_MESSAGE, INTERRUPTED_MESSAGE);
        });
    }

    /**
     * 强制断流的 ERROR 状态由官方 onAgent 取消回调写入，此处只补充终止原因
     */
    public static void markAborted(RuntimeContext ctx) {
        writeOutcome(ctx, RagentAttributes.RUN_OUTCOME_ABORTED, span -> {
            span.setAttribute(AgentErrorTypes.KEY, AgentErrorTypes.CANCELLED.value());
            span.setAttribute(AgentTraceAttributes.LEVEL, AgentTraceAttributes.LEVEL_ERROR);
            span.setAttribute(AgentTraceAttributes.STATUS_MESSAGE, ABORTED_MESSAGE);
        });
    }

    /**
     * 只有更严重的结局才落笔，附带属性也一并跳过：级别降不回来，文案更不该被后到的轻结局改写
     * extra 传 null 表示这个结局只有结局本身，级别与文案另有人写
     */
    private static void writeOutcome(RuntimeContext ctx, String outcome, Consumer<Span> extra) {
        RootSpanRef ref = rootRef(ctx);
        if (ref == null || !ref.claim(outcome)) {
            return;
        }
        ref.span.setAttribute(RagentAttributes.RUN_OUTCOME, outcome);
        if (extra != null) {
            extra.accept(ref.span);
        }
    }

    /**
     * 轮数与工具定义都属于整次调用，写在根 span 上；挂进轮次 span 会随轮数翻倍
     */
    static void writeRootAttribute(RuntimeContext ctx, Consumer<Span> writer) {
        RootSpanRef ref = rootRef(ctx);
        if (ref != null) {
            writer.accept(ref.span);
        }
    }

    /**
     * 摘要调用挂在根下，与工具体 span 一样靠 round_index 归轮，不必把 OTel 上下文传进记忆层
     */
    static Span rootSpan(RuntimeContext ctx) {
        RootSpanRef ref = rootRef(ctx);
        return ref == null ? null : ref.span;
    }

    /**
     * 追踪关掉时上下文里没有根 span，各调用点原样跳过
     */
    private static RootSpanRef rootRef(RuntimeContext ctx) {
        Object root = ctx == null ? null : ctx.get(ROOT_SPAN_ATTRIBUTE);
        return root instanceof RootSpanRef ref ? ref : null;
    }

    /**
     * 根 span 与它已经落定的结局，两者同生共死才不会被别的 run 串味
     */
    private record RootSpanRef(Span span, AtomicReference<String> outcome) {

        /**
         * CAS 抢占：两条中断路径可能来自不同线程，比大小的读改写必须是原子的
         */
        private boolean claim(String candidate) {
            int rank = SEVERITY.indexOf(candidate);
            while (true) {
                String current = outcome.get();
                if (current != null && SEVERITY.indexOf(current) >= rank) {
                    return false;
                }
                if (outcome.compareAndSet(current, candidate)) {
                    return true;
                }
            }
        }
    }

    private AgentRunTracer() {
    }
}
