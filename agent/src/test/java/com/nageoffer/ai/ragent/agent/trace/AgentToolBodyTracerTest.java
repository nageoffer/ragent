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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 工具体 span 是唯一给 facts 打起止的生产写入点，这里钉的是「一次执行一个节点、时刻只有一个来源」
 * 断言全部取导出快照：名字的基数、属性分层、七态状态、以及三条收口路各自的终点
 */
class AgentToolBodyTracerTest {

    private static final String RUN_ID = "t-9001";
    private static final String BATCH_SPAN = "tool_batch";
    private static final String TOOL_NAME = "leave_submit";
    private static final String TOOL_DESCRIPTION = "提交请假申请";
    private static final String SPAN_NAME = "execute_tool " + TOOL_NAME;

    private static ContextPropagationOperator reactorHook;

    private List<SpanData> exported;
    private SdkTracerProvider tracerProvider;
    private MutableClock clock;
    private AgentToolExecutionFacts facts;
    private RuntimeContext ctx;

    /**
     * 不装这个 Hook，runWithContext 原样返回，工具体里再建的 span 就找不到父亲
     */
    @BeforeAll
    static void installReactorHook() {
        reactorHook = ContextPropagationOperator.builder().build();
        reactorHook.registerOnEachOperator();
    }

    @AfterAll
    static void resetReactorHook() {
        reactorHook.resetOnEachOperator();
    }

    @BeforeEach
    void setUp() {
        // 生产侧 tracer 取自全局实例，测试要先把全局位置腾出来再放自己的 SDK
        GlobalOpenTelemetry.resetForTest();
        exported = Collections.synchronizedList(new ArrayList<>());
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.builder(new CollectingExporter(exported)).build())
                .build();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());
        clock = new MutableClock(Instant.now());
        facts = new AgentToolExecutionFacts(RUN_ID, clock);
        ctx = RuntimeContext.builder().userId("u-1001").sessionId("c-2002").build();
        ctx.put(AgentToolExecutionFacts.RUNTIME_CONTEXT_KEY, facts);
    }

    @AfterEach
    void tearDown() {
        // 序列化器是静态位，不还原会把上一条用例的内容开关漏给下一条
        AgentToolBodyTracer.configure(null);
        tracerProvider.close();
        GlobalOpenTelemetry.resetForTest();
    }

    /**
     * 没装配序列化器时按不采内容走：追踪关掉后四把工具照样会调到这里，缺省值得是「不采」
     */
    @Test
    void shouldWriteNoContentWhenSerializerAbsent() {
        runUnderBatch(() -> trace("call-1", () -> Mono.just(success("call-1"))));

        SpanData tool = toolSpan();
        assertThat(tool.getAttributes().get(GenAiAttributes.TOOL_CALL_ARGUMENTS)).isNull();
        assertThat(tool.getAttributes().get(GenAiAttributes.TOOL_CALL_RESULT)).isNull();
        assertThat(tool.getAttributes().get(AgentTraceAttributes.OBSERVATION_INPUT)).isNull();
        assertThat(tool.getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT)).isNull();
    }

    /**
     * 超长出入参照样受属性总预算约束：工具体是后补的出口，绕开那道预算就等于开了条无界通道
     * 预算内保留原样、超限整段退成合法 JSON，两条都由同一个序列化器管，这里不另立一套
     */
    @Test
    void shouldKeepOversizedContentWithinAttributeBudget() {
        AgentToolBodyTracer.configure(new AgentTraceSerializer(8000, 200, true));
        String huge = "长".repeat(5000);

        runUnderBatch(() -> AgentToolBodyTracer.trace(tool(),
                param("call-1", ctx, Map.of("note", huge, "password", "p@ss")),
                () -> Mono.just(result("call-1", ToolResultState.SUCCESS, huge))));

        SpanData tool = toolSpan();
        String arguments = tool.getAttributes().get(GenAiAttributes.TOOL_CALL_ARGUMENTS);
        assertThat(arguments).hasSizeLessThanOrEqualTo(200).contains("_truncated");
        assertThat(tool.getAttributes().get(GenAiAttributes.TOOL_CALL_RESULT))
                .hasSizeLessThanOrEqualTo(200).contains("_truncated");
        assertThat(tool.getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT))
                .hasSizeLessThanOrEqualTo(200).contains("_truncated");
    }

    /**
     * 工具体参数不再按键名过滤，嵌套值保持原样
     */
    @Test
    void shouldKeepNestedValuesInToolArguments() {
        AgentToolBodyTracer.configure(new AgentTraceSerializer(4096, 16_384, true));

        runUnderBatch(() -> AgentToolBodyTracer.trace(tool(),
                param("call-1", ctx, Map.of("employeeName", "张三",
                        "profile", Map.of("password", "p@ss", "nested", Map.of("apiKey", "sk-1")))),
                () -> Mono.just(success("call-1"))));

        String arguments = toolSpan().getAttributes().get(GenAiAttributes.TOOL_CALL_ARGUMENTS);
        assertThat(arguments).contains("张三", "p@ss", "sk-1");
    }

    /**
     * 断在半路的没有返回值，写这一份是为了让「有入参、没输出」不再等价于一次静默成功
     */
    @Test
    void shouldWriteInterruptedOutcomeWhenCancelled() {
        AgentToolBodyTracer.configure(new AgentTraceSerializer(4096, 16_384, true));
        Sinks.One<ToolResultBlock> pending = Sinks.one();

        Span batch = startBatchSpan();
        try (Scope ignored = batch.makeCurrent()) {
            Disposable subscription = trace("call-1", pending::asMono).subscribe();
            clock.advance(7);
            subscription.dispose();
        } finally {
            batch.end();
        }

        SpanData tool = toolSpan();
        assertThat(tool.getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT))
                .contains("\"state\":\"" + AgentToolStatus.INTERRUPTED.value() + "\"");
        assertThat(tool.getAttributes().get(GenAiAttributes.TOOL_CALL_RESULT)).isNull();
        assertThat(tool.getAttributes().get(GenAiAttributes.TOOL_CALL_ARGUMENTS)).contains("张三");
    }

    /**
     * 单工具跑完：一次执行一个节点，标准键、厂商键、我方扩展键各在各的区里
     */
    @Test
    void shouldOpenOneToolSpanPerExecutedCall() {
        runUnderBatch(() -> trace("call-1", () -> Mono.just(success("call-1"))));

        SpanData tool = toolSpan();
        assertThat(exported.stream().filter(data -> data.getName().startsWith("execute_tool"))).hasSize(1);
        assertThat(tool.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(tool.getParentSpanId()).isEqualTo(span(BATCH_SPAN).getSpanId());
        assertThat(tool.getAttributes().get(GenAiAttributes.OPERATION_NAME))
                .isEqualTo(GenAiAttributes.OPERATION_EXECUTE_TOOL);
        assertThat(tool.getAttributes().get(GenAiAttributes.TOOL_NAME)).isEqualTo(TOOL_NAME);
        assertThat(tool.getAttributes().get(GenAiAttributes.TOOL_CALL_ID)).isEqualTo("call-1");
        assertThat(tool.getAttributes().get(GenAiAttributes.TOOL_DESCRIPTION)).isEqualTo(TOOL_DESCRIPTION);
        assertThat(tool.getAttributes().get(GenAiAttributes.TOOL_TYPE))
                .isEqualTo(GenAiAttributes.TOOL_TYPE_FUNCTION);
        assertThat(tool.getAttributes().get(RagentAttributes.TOOL_STATUS))
                .isEqualTo(AgentToolStatus.DONE.value());
        assertThat(tool.getAttributes().get(RagentAttributes.TOOL_SPAN_SCOPE))
                .isEqualTo(RagentAttributes.SPAN_SCOPE_BODY);
        // 跑顺了不落 OK：OTel 约定成功留空，补一句 OK 会覆盖别处已判定的失败
        assertThat(tool.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(tool.getAttributes().get(AgentErrorTypes.KEY)).isNull();
        assertThat(tool.getAttributes().get(RagentAttributes.TOOL_ERROR_SOURCE)).isNull();
    }

    /**
     * span 名只带低基数的操作名与工具名，调用 ID 一律进属性，否则后端按名聚合当场炸开
     */
    @Test
    void shouldKeepSpanNameFreeOfDynamicIdentifiers() {
        runUnderBatch(() -> trace("call-77f3", () -> Mono.just(success("call-77f3"))));

        assertThat(toolSpan().getName()).isEqualTo(SPAN_NAME).doesNotContain("call-77f3");
    }

    /**
     * 批次号与组内序号回读事实源，PG 块、SSE 帧、trace 上是同一个值
     */
    @Test
    void shouldReadBatchIdAndCallIndexFromFacts() {
        var batch = facts.beginBatch();
        facts.enroll(batch, List.of("call-1", "call-2"));

        runUnderBatch(() -> trace("call-2", () -> Mono.just(success("call-2"))));

        SpanData tool = toolSpan();
        assertThat(tool.getAttributes().get(RagentAttributes.TOOL_BATCH_ID)).isEqualTo(RUN_ID + "-0");
        assertThat(tool.getAttributes().get(RagentAttributes.TOOL_CALL_INDEX)).isEqualTo(1L);
        assertThat(tool.getAttributes().get(RagentAttributes.TOOL_CALL_INDEX))
                .isEqualTo((long) facts.callIndexOf("call-2"));
    }

    /**
     * 工具体 span 与根、轮次、批同一套身份键：LangFuse 不会把父节点的用户与会话回填给子节点，
     * 缺了这几把键就只能从 trace 逐层下钻，按消息或任务直接过滤 TOOL 一条都查不到
     * 确认消息号首问没有，缺哪个跳哪个，不落空串
     */
    @Test
    void shouldCarryIdentityKeysLikeEveryOtherSpan() {
        ctx.put(AgentTraceContextKeys.TASK_ID, "t-9001");
        ctx.put(AgentTraceContextKeys.REPLY_TO_MESSAGE_ID, "m-3003");

        runUnderBatch(() -> trace("call-1", () -> Mono.just(success("call-1"))));

        SpanData tool = toolSpan();
        assertThat(tool.getAttributes().get(AgentTraceAttributes.USER_ID)).isEqualTo("u-1001");
        assertThat(tool.getAttributes().get(AgentTraceAttributes.SESSION_ID)).isEqualTo("c-2002");
        assertThat(tool.getAttributes().get(RagentAttributes.TASK_ID)).isEqualTo("t-9001");
        assertThat(tool.getAttributes().get(RagentAttributes.REPLY_TO_MESSAGE_ID)).isEqualTo("m-3003");
        assertThat(attributeKeys(tool)).doesNotContain(RagentAttributes.CONFIRM_MESSAGE_ID.getKey());
    }

    /**
     * span 的起止直接取事实源写下的那两个时刻，四方一致靠的是同一个来源而不是三处各读一次时钟
     */
    @Test
    void shouldStampSpanTimesFromTheSameFactsAsPersistence() {
        runUnderBatch(() -> trace("call-1", () -> Mono.fromSupplier(() -> {
            clock.advance(120);
            return success("call-1");
        })));

        SpanData tool = toolSpan();
        assertThat(facts.toolFact("call-1").durationMs()).isEqualTo(120L);
        assertThat(tool.getStartEpochNanos())
                .isEqualTo(TimeUnit.MILLISECONDS.toNanos(facts.toolFact("call-1").startedAt()));
        assertThat(tool.getEndEpochNanos())
                .isEqualTo(TimeUnit.MILLISECONDS.toNanos(facts.toolFact("call-1").endedAt()));
    }

    /**
     * 未装配序列化器时不上报内容：这是追踪关闭时的兜底，不代表产品的内容采集默认值
     */
    @Test
    void shouldNotCaptureArgumentsOrResultWhenSerializerAbsent() {
        runUnderBatch(() -> trace("call-1", () -> Mono.just(success("call-1"))));

        assertThat(attributeKeys(toolSpan()))
                .doesNotContain("gen_ai.tool.call.arguments", "gen_ai.tool.call.result")
                // 规范的 execute_tool 表里没有会话 ID 这一项，写了就是自造标准键
                .doesNotContain("gen_ai.conversation.id");
    }

    /**
     * 同名并行两条：工具名相同，调用 ID 与组内序号必须分得开，区间也各算各的
     */
    @Test
    void shouldGiveParallelSameNameCallsTheirOwnIdentityAndWindow() {
        Sinks.One<ToolResultBlock> first = Sinks.one();
        Sinks.One<ToolResultBlock> second = Sinks.one();
        Span batch = startBatchSpan();
        try (Scope ignored = batch.makeCurrent()) {
            trace("call-1", first::asMono).subscribe();
            clock.advance(10);
            trace("call-2", second::asMono).subscribe();
            clock.advance(20);
            first.tryEmitValue(success("call-1"));
            clock.advance(5);
            second.tryEmitValue(success("call-2"));
        } finally {
            batch.end();
        }

        List<SpanData> tools = toolSpans();
        assertThat(tools).hasSize(2);
        assertThat(tools).allSatisfy(data ->
                assertThat(data.getAttributes().get(GenAiAttributes.TOOL_NAME)).isEqualTo(TOOL_NAME));
        assertThat(tools).extracting(data -> data.getAttributes().get(GenAiAttributes.TOOL_CALL_ID))
                .containsExactlyInAnyOrder("call-1", "call-2");
        assertThat(tools).extracting(data -> data.getAttributes().get(RagentAttributes.TOOL_CALL_INDEX))
                .containsExactlyInAnyOrder(0L, 1L);
        // 后一条的起点早于前一条的终点，区间真重叠；共用一个区间就看不出谁卡住了
        assertThat(facts.toolFact("call-2").startedAt()).isLessThan(facts.toolFact("call-1").endedAt());
        assertThat(facts.toolFact("call-1").durationMs()).isEqualTo(30L);
        assertThat(facts.toolFact("call-2").durationMs()).isEqualTo(25L);
    }

    /**
     * 工具自报失败：error.type 取闭集里的 tool_error，细分留给 error.source
     */
    @Test
    void shouldMarkFailedWhenToolReturnsErrorState() {
        runUnderBatch(() -> trace("call-1", () -> Mono.just(error("call-1"))));

        SpanData tool = toolSpan();
        assertThat(tool.getAttributes().get(RagentAttributes.TOOL_STATUS))
                .isEqualTo(AgentToolStatus.FAILED.value());
        assertThat(tool.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(tool.getAttributes().get(AgentErrorTypes.KEY)).isEqualTo(AgentErrorTypes.TOOL_ERROR.value());
        assertThat(tool.getAttributes().get(RagentAttributes.TOOL_ERROR_SOURCE))
                .isEqualTo(RagentAttributes.ERROR_SOURCE_TOOL_RESULT);
    }

    /**
     * 工具体抛异常：error.type 取全限定类名，异常正文只进 exception 事件——文案里带姓名和单号
     */
    @Test
    void shouldRecordExceptionClassNameRatherThanMessage() {
        assertThatCode(() -> runUnderBatch(() -> trace("call-1",
                () -> Mono.error(new IllegalStateException("张三的请假单 L-77 提交失败")))))
                .isInstanceOf(IllegalStateException.class);

        SpanData tool = toolSpan();
        assertThat(tool.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(tool.getAttributes().get(AgentErrorTypes.KEY))
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(tool.getAttributes().get(RagentAttributes.TOOL_ERROR_SOURCE))
                .isEqualTo(RagentAttributes.ERROR_SOURCE_EXCEPTION);
        assertThat(tool.getStatus().getDescription()).isEmpty();
        assertThat(tool.getEvents()).extracting(EventData::getName).contains("exception");
    }

    /**
     * 强制断流：未完的工具收在先写入的那个中断时刻，事实源与 span 必须落在同一纳秒
     */
    @Test
    void shouldEndCancelledToolAtTerminationInstant() {
        Span batch = startBatchSpan();
        Disposable subscription;
        try (Scope ignored = batch.makeCurrent()) {
            subscription = trace("call-1", Mono::never).subscribe();
        }
        clock.advance(40);
        facts.markInterrupted();
        clock.advance(2000);
        facts.markCancelled();
        subscription.dispose();
        batch.end();

        SpanData tool = toolSpan();
        assertThat(tool.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(tool.getAttributes().get(AgentErrorTypes.KEY)).isEqualTo(AgentErrorTypes.CANCELLED.value());
        assertThat(tool.getAttributes().get(RagentAttributes.TOOL_STATUS))
                .isEqualTo(AgentToolStatus.INTERRUPTED.value());
        // 取消不是工具的错，不给它安一个 error.source
        assertThat(tool.getAttributes().get(RagentAttributes.TOOL_ERROR_SOURCE)).isNull();
        assertThat(tool.getEndEpochNanos()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(facts.terminationAt()));
        assertThat(facts.toolFact("call-1").endedAt()).isEqualTo(facts.terminationAt());
    }

    /**
     * 没有任何中断时刻的取消只可能来自上游超时，按结构判定，不去异常文案里找 timeout 字样
     */
    @Test
    void shouldFallBackToTimeoutWhenCancelledWithoutInterruptStamp() {
        Span batch = startBatchSpan();
        try (Scope ignored = batch.makeCurrent()) {
            trace("call-1", Mono::never).subscribe().dispose();
        } finally {
            batch.end();
        }

        assertThat(toolSpan().getAttributes().get(AgentErrorTypes.KEY))
                .isEqualTo(AgentErrorTypes.TIMEOUT.value());
    }

    /**
     * 关掉追踪时没有父 span，事实源照样要有起止：归批与耗时是业务事实，不能被可观测性开关改形状
     */
    @Test
    void shouldStillRecordFactsWhenNoParentSpanExists() {
        trace("call-1", () -> Mono.fromSupplier(() -> {
            clock.advance(15);
            return success("call-1");
        })).block();

        assertThat(exported).isEmpty();
        assertThat(facts.toolFact("call-1").durationMs()).isEqualTo(15L);
        // 停止时没等到结束事件的块靠它收口，关掉追踪也得有
        assertThat(facts.toolFact("call-1").result()).isNotNull()
                .extracting(ToolResultBlock::getState).isEqualTo(ToolResultState.SUCCESS);
    }

    /**
     * 取不到事实源就整段放行：没有事实源就给不出可对账的时刻，宁可不建这个 span
     */
    @Test
    void shouldPassThroughWhenFactsAbsent() {
        RuntimeContext bare = RuntimeContext.builder().userId("u-1001").sessionId("c-2002").build();

        ToolResultBlock result = runUnderBatch(() -> AgentToolBodyTracer.trace(tool(),
                param("call-1", bare), () -> Mono.just(success("call-1"))));

        assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
        assertThat(toolSpans()).isEmpty();
    }

    /**
     * 工具体空完成属于工具违约，按已有的状态映射记 failed，但不去猜一个没观测到的 error.source
     */
    @Test
    void shouldTreatEmptyCompletionAsFailureWithoutInventingSource() {
        runUnderBatch(() -> trace("call-1", Mono::empty));

        SpanData tool = toolSpan();
        assertThat(tool.getAttributes().get(RagentAttributes.TOOL_STATUS))
                .isEqualTo(AgentToolStatus.FAILED.value());
        assertThat(tool.getAttributes().get(AgentErrorTypes.KEY)).isEqualTo(AgentErrorTypes.TOOL_ERROR.value());
        assertThat(tool.getAttributes().get(RagentAttributes.TOOL_ERROR_SOURCE)).isNull();
    }

    /**
     * 工具语义只落在这一个节点上：批节点不写、这里也不额外挂第二个带 tool 语义的 span
     */
    @Test
    void shouldCarryTheOnlyToolTypedObservationForOneExecution() {
        runUnderBatch(() -> trace("call-1", () -> Mono.just(success("call-1"))));

        assertThat(exported.stream()
                .filter(data -> data.getAttributes().asMap().entrySet().stream()
                        .anyMatch(entry -> "langfuse.observation.type".equals(entry.getKey().getKey())
                                && "tool".equals(entry.getValue()))))
                .hasSize(1);
        assertThat(attributeKeys(span(BATCH_SPAN))).noneMatch(key -> key.startsWith("gen_ai."));
    }

    /**
     * 工具体与推理在树上是兄弟，按轮聚合工具耗时只能靠这把键，四类节点上必须是同一个数
     */
    @Test
    void shouldCarryCurrentRoundIndexOnToolSpan() {
        Span root = tracerProvider.get("test").spanBuilder("invoke_agent").startSpan();
        AgentRunTracer.bindRoot(ctx, root);
        AgentRunTracer.nextRound(ctx);
        AgentRunTracer.nextRound(ctx);

        runUnderBatch(() -> trace("call-1", () -> Mono.just(success("call-1"))));
        root.end();

        assertThat(toolSpan().getAttributes().get(RagentAttributes.REACT_ROUND_INDEX)).isEqualTo(2L);
    }

    /**
     * 确认续跑先执行工具后推理，此刻一轮都没进过：写个 0 会在按轮聚合时多出一档假轮次
     */
    @Test
    void shouldLeaveRoundIndexUnwrittenWhenNoRoundStarted() {
        runUnderBatch(() -> trace("call-1", () -> Mono.just(success("call-1"))));

        assertThat(toolSpan().getAttributes().get(RagentAttributes.REACT_ROUND_INDEX)).isNull();
    }

    private Mono<ToolResultBlock> trace(String toolCallId, Supplier<Mono<ToolResultBlock>> body) {
        return AgentToolBodyTracer.trace(tool(), param(toolCallId, ctx), body);
    }

    private ToolResultBlock runUnderBatch(Supplier<Mono<ToolResultBlock>> body) {
        Span batch = startBatchSpan();
        try (Scope ignored = batch.makeCurrent()) {
            return body.get().block();
        } finally {
            batch.end();
        }
    }

    private Span startBatchSpan() {
        return tracerProvider.get("test").spanBuilder(BATCH_SPAN).startSpan();
    }

    private ToolCallParam param(String toolCallId, RuntimeContext runtimeContext) {
        return param(toolCallId, runtimeContext, Map.of("employeeName", "张三"));
    }

    private ToolCallParam param(String toolCallId, RuntimeContext runtimeContext, Map<String, Object> input) {
        return ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder().id(toolCallId).name(TOOL_NAME).build())
                .input(input)
                .runtimeContext(runtimeContext)
                .build();
    }

    private ToolResultBlock success(String toolCallId) {
        return result(toolCallId, ToolResultState.SUCCESS);
    }

    private ToolResultBlock error(String toolCallId) {
        return result(toolCallId, ToolResultState.ERROR);
    }

    private ToolResultBlock result(String toolCallId, ToolResultState state) {
        return result(toolCallId, state, "已提交");
    }

    private ToolResultBlock result(String toolCallId, ToolResultState state, String text) {
        return ToolResultBlock.builder()
                .id(toolCallId)
                .name(TOOL_NAME)
                .output(TextBlock.builder().text(text).build())
                .state(state)
                .build();
    }

    private List<String> attributeKeys(SpanData data) {
        return data.getAttributes().asMap().keySet().stream().map(AttributeKey::getKey).toList();
    }

    private List<SpanData> toolSpans() {
        return exported.stream().filter(data -> data.getName().startsWith("execute_tool")).toList();
    }

    private SpanData toolSpan() {
        return span(SPAN_NAME);
    }

    private SpanData span(String name) {
        return exported.stream().filter(data -> name.equals(data.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("未导出 span: " + name + ", 实际: "
                        + exported.stream().map(SpanData::getName).toList()));
    }

    /**
     * 只提供工具身份，执行体由每条用例自己给：这一层测的是包装而不是某把工具的业务
     */
    private AgentTool tool() {
        return new AgentTool() {

            @Override
            public String getName() {
                return TOOL_NAME;
            }

            @Override
            public String getDescription() {
                return TOOL_DESCRIPTION;
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                throw new UnsupportedOperationException("用例直接调 tracer，不走这条入口");
            }
        };
    }

    private record CollectingExporter(List<SpanData> sink) implements SpanExporter {

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            sink.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
