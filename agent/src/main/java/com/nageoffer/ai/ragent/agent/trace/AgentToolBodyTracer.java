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

import com.nageoffer.ai.ragent.agent.enums.AgentToolStatus;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts.ToolFact;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 工具执行体的追踪包装：起止时刻在这里写进事实源，span 只是把同一份事实读出来
 * <p>
 * 包住的是工具自己的执行体，框架侧的查表、分组校验、参数合并、排队与重试都不在这段里，
 * 这一点由 ragent.tool.span_scope=body 明说，别让读的人把它当成整次工具调用的耗时
 */
public final class AgentToolBodyTracer {

    /**
     * 名字只留低基数的操作名与工具名，调用 ID 与状态一律进属性
     */
    private static final String SPAN_NAME_PREFIX = "execute_tool ";

    /**
     * 未装配时的兜底：内容一律不采，限额取最小
     * 追踪关掉时本类照样被四把工具调到，缺省值得是「不采」而不是空指针或全量
     */
    private static final AgentTraceSerializer CONTENT_OFF =
            new AgentTraceSerializer(0, 0, false);

    /**
     * 工具体的 span 建在中间件链之外，拿不到注入进来的那份，只能从装配处推过来
     * 与本类已经依赖的 GlobalOpenTelemetry 同属一类全局态，装配点仍只有 AgentTracingConfiguration 一个
     */
    private static volatile AgentTraceSerializer serializer = CONTENT_OFF;

    private AgentToolBodyTracer() {
    }

    /**
     * 传 null 即恢复成不采内容，追踪装配缺席与测试收尾都用它
     */
    static void configure(AgentTraceSerializer configured) {
        serializer = configured == null ? CONTENT_OFF : configured;
    }

    /**
     * 事实源缺失时整段放行：给不出可对账的时刻就不该建这个 span
     */
    public static Mono<ToolResultBlock> trace(AgentTool tool, ToolCallParam param,
                                              Supplier<Mono<ToolResultBlock>> body) {
        return Mono.deferContextual(ctxView -> {
            RuntimeContext runtimeContext = param == null ? null : param.getRuntimeContext();
            AgentToolExecutionFacts facts = AgentToolExecutionFacts.from(runtimeContext);
            if (facts == null) {
                return body.get();
            }
            String toolCallId = toolCallId(param);
            long startedAt = facts.markStarted(toolCallId);
            Span span = openSpan(ctxView, tool, facts, toolCallId, startedAt, runtimeContext, param);
            AtomicBoolean ended = new AtomicBoolean();
            return body.get()
                    .doOnSuccess(result -> {
                        if (ended.compareAndSet(false, true)) {
                            settleFinished(span, facts, toolCallId, result, null);
                        }
                    })
                    .doOnError(error -> {
                        if (ended.compareAndSet(false, true)) {
                            settleFinished(span, facts, toolCallId, null, error);
                        }
                    })
                    .doOnCancel(() -> {
                        if (ended.compareAndSet(false, true)) {
                            settleCancelled(span, facts, toolCallId);
                        }
                    });
        });
    }

    /**
     * 追踪关闭或父亲无效时返回 null：事实照写，只是没有节点可挂
     */
    private static Span openSpan(ContextView ctxView, AgentTool tool, AgentToolExecutionFacts facts,
                                 String toolCallId, long startedAt, RuntimeContext runtimeContext,
                                 ToolCallParam param) {
        Context parent = AgentRunTracer.otelContext(ctxView);
        if (!Span.fromContext(parent).getSpanContext().isValid()) {
            return null;
        }
        String toolName = tool == null ? null : tool.getName();
        Span span = GlobalOpenTelemetry.getTracer(AgentTracingConfiguration.INSTRUMENTATION_SCOPE)
                .spanBuilder(SPAN_NAME_PREFIX + toolName)
                .setParent(parent)
                .setSpanKind(SpanKind.INTERNAL)
                .setStartTimestamp(Instant.ofEpochMilli(startedAt))
                .startSpan();
        span.setAttribute(AgentTraceAttributes.OBSERVATION_TYPE, AgentTraceAttributes.TYPE_TOOL);
        span.setAttribute(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OPERATION_EXECUTE_TOOL);
        span.setAttribute(GenAiAttributes.TOOL_TYPE, GenAiAttributes.TOOL_TYPE_FUNCTION);
        if (toolName != null) {
            span.setAttribute(GenAiAttributes.TOOL_NAME, toolName);
        }
        if (toolCallId != null) {
            span.setAttribute(GenAiAttributes.TOOL_CALL_ID, toolCallId);
        }
        if (tool != null && tool.getDescription() != null) {
            span.setAttribute(GenAiAttributes.TOOL_DESCRIPTION, tool.getDescription());
        }
        span.setAttribute(RagentAttributes.TOOL_SPAN_SCOPE, RagentAttributes.SPAN_SCOPE_BODY);
        // 与根、轮次、批同一组身份键：追踪后端不替子节点继承父节点的用户与会话，漏写这里就查不到 TOOL
        AgentRunTracer.writeIdentity(span, runtimeContext);
        // 工具体挂在批下、批与推理是兄弟，按轮聚合工具耗时只能靠这把键
        AgentRunTracer.writeRoundIndex(span, runtimeContext);
        ToolFact fact = facts.toolFact(toolCallId);
        span.setAttribute(RagentAttributes.TOOL_CALL_INDEX, fact.callIndex());
        if (fact.batchId() != null) {
            span.setAttribute(RagentAttributes.TOOL_BATCH_ID, fact.batchId());
        }
        writeArguments(span, param);
        return span;
    }

    /**
     * 规范键给按 GenAI 语义读的一方，LangFuse 键给 UI，同一份内容写两处，别让其中一边看到的是另一种口径
     */
    private static void writeArguments(Span span, ToolCallParam param) {
        String arguments = serializer.toolArguments(param == null ? null : param.getInput());
        if (arguments == null) {
            return;
        }
        span.setAttribute(GenAiAttributes.TOOL_CALL_ARGUMENTS, arguments);
        span.setAttribute(AgentTraceAttributes.OBSERVATION_INPUT, arguments);
    }

    /**
     * 成功与失败共用一个终点来源：都取事实源刚写下的那个时刻，不各读一次时钟
     */
    private static void settleFinished(Span span, AgentToolExecutionFacts facts, String toolCallId,
                                       ToolResultBlock result, Throwable error) {
        // 协作式取消会返回 INTERRUPTED 而不是发出 Reactor cancel 信号，终点仍要对齐用户中断时刻
        AgentToolStatus status = AgentToolStatus.of(result == null ? null : result.getState());
        long endedAt = status == AgentToolStatus.INTERRUPTED
                ? facts.markTerminated(toolCallId)
                : facts.markEnded(toolCallId);
        // 落库读它，必须在没有 span 的早退之前
        facts.markReturned(toolCallId, result);
        if (span == null) {
            return;
        }
        // 空完成是工具违约，交给同一处状态映射判成失败，不在这里另立一种状态
        span.setAttribute(RagentAttributes.TOOL_STATUS, status.value());
        if (error != null) {
            span.setStatus(StatusCode.ERROR);
            span.setAttribute(AgentErrorTypes.KEY, AgentErrorTypes.ofThrowable(error));
            span.setAttribute(RagentAttributes.TOOL_ERROR_SOURCE, RagentAttributes.ERROR_SOURCE_EXCEPTION);
            span.recordException(error);
        } else if (status == AgentToolStatus.FAILED) {
            span.setStatus(StatusCode.ERROR);
            span.setAttribute(AgentErrorTypes.KEY, AgentErrorTypes.TOOL_ERROR.value());
            if (result != null) {
                // 没结果就没观测到来源，宁可空着也不猜一个
                span.setAttribute(RagentAttributes.TOOL_ERROR_SOURCE, RagentAttributes.ERROR_SOURCE_TOOL_RESULT);
            }
        } else if (status == AgentToolStatus.INTERRUPTED) {
            span.setStatus(StatusCode.ERROR);
            span.setAttribute(AgentErrorTypes.KEY, AgentErrorTypes.ofCancelledSpan(facts.terminationAt()));
        }
        writeOutcome(span, status, result, error);
        span.end(Instant.ofEpochMilli(endedAt));
    }

    /**
     * 结局要能读：只标 ERROR 不给正文等于把排查推回日志
     * 规范键只在成功时写，它表示的是工具返回值，拿失败正文顶上去会让按它统计的一方读到假成功
     */
    private static void writeOutcome(Span span, AgentToolStatus status, ToolResultBlock result, Throwable error) {
        String outcome = serializer.toolOutcome(status.value(), result, error);
        if (outcome == null) {
            return;
        }
        span.setAttribute(AgentTraceAttributes.OBSERVATION_OUTPUT, outcome);
        if (error != null || status == AgentToolStatus.FAILED) {
            return;
        }
        String returned = serializer.toolResult(result);
        if (returned != null) {
            span.setAttribute(GenAiAttributes.TOOL_CALL_RESULT, returned);
        }
    }

    /**
     * 断在半路的那条对齐中断时刻，用断流当时的墙上时间会把中断后的等待窗口算进耗时
     */
    private static void settleCancelled(Span span, AgentToolExecutionFacts facts, String toolCallId) {
        long endedAt = facts.markTerminated(toolCallId);
        if (span == null) {
            return;
        }
        span.setAttribute(RagentAttributes.TOOL_STATUS, AgentToolStatus.INTERRUPTED.value());
        span.setStatus(StatusCode.ERROR);
        span.setAttribute(AgentErrorTypes.KEY, AgentErrorTypes.ofCancelledSpan(facts.terminationAt()));
        // 断在半路的没有返回值，写这一份是为了让「有入参、没输出」不再等价于一次静默成功
        writeOutcome(span, AgentToolStatus.INTERRUPTED, null, null);
        span.end(Instant.ofEpochMilli(endedAt));
    }

    private static String toolCallId(ToolCallParam param) {
        return param == null || param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId();
    }
}
