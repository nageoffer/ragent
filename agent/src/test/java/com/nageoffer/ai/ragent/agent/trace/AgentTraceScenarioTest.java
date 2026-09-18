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
import com.nageoffer.ai.ragent.agent.tool.AgentToolBatchMiddleware;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts.ToolFact;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场景验收：三层中间件按生产层序串起来，工具体走真的 body tracer，时钟与工具结果都由用例摆布
 * 单点行为各自的用例已经覆盖过，这里钉的是跨层不变量——每条用例末尾都过同一关
 */
class AgentTraceScenarioTest {

    private static final String ROOT_SPAN = "invoke_agent";
    private static final String BATCH_SPAN = "tool_batch";
    private static final String REPLY_ID = "r-1";
    private static final String SUBMIT = "leave_submit";
    private static final String QUERY = "leave_query";
    private static final String SUBMIT_SPAN = "execute_tool " + SUBMIT;
    private static final String QUERY_SPAN = "execute_tool " + QUERY;

    /**
     * 生产侧写得出的 gen_ai 键就这些，多一把即是自造伪标准
     * 出入参那两把是内容键，只在 capture-content 打开时出现，白名单管的是「能不能写」不是「一定写」
     */
    private static final Set<String> ALLOWED_GEN_AI_KEYS = Set.of(
            "gen_ai.operation.name", "gen_ai.tool.name", "gen_ai.tool.call.id", "gen_ai.tool.description",
            "gen_ai.tool.type", "gen_ai.provider.name", "gen_ai.request.stream", "gen_ai.conversation.id",
            "gen_ai.tool.call.arguments", "gen_ai.tool.call.result");

    private static final Set<String> ALLOWED_ERROR_TYPES = Stream.of(AgentErrorTypes.values())
            .map(AgentErrorTypes::value).collect(Collectors.toUnmodifiableSet());

    private static ContextPropagationOperator reactorHook;

    private List<SpanData> exported;
    private SdkTracerProvider tracerProvider;
    private MutableClock clock;
    private AgentToolExecutionFacts facts;
    private RuntimeContext ctx;
    private RagentOtelTracingMiddleware tracing;
    private AgentTraceEnrichmentMiddleware enrichment;
    private AgentToolBatchMiddleware batching;
    private Span root;

    /**
     * 不装这个 Hook，runWithContext 原样返回，批与工具体都找不到父亲，整棵树的形状不成立
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
        // 工具体那层的 tracer 取自全局实例，先把全局位置腾出来再放自己的 SDK
        GlobalOpenTelemetry.resetForTest();
        exported = Collections.synchronizedList(new ArrayList<>());
        clock = new MutableClock(Instant.now());
        tracerProvider = SdkTracerProvider.builder()
                .setClock(clock)
                .addSpanProcessor(SimpleSpanProcessor.builder(new CollectingSpanExporter(exported)).build())
                .build();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());
        Tracer tracer = tracerProvider.get("test");
        tracing = new RagentOtelTracingMiddleware(tracer);
        batching = new AgentToolBatchMiddleware();
        captureContent(true);
        newRun("t-9001");
    }

    @AfterEach
    void tearDown() {
        // 工具体的序列化器是静态位，不还原会把开关漏给下一个用例
        AgentToolBodyTracer.configure(null);
        tracerProvider.close();
        GlobalOpenTelemetry.resetForTest();
    }

    /**
     * 批与执行体必须拿同一份：分别配两套的话，同一次调用在两层上会读出两种口径
     */
    private void captureContent(boolean enabled) {
        AgentTraceSerializer serializer = new AgentTraceSerializer(4096, 16_384, enabled);
        enrichment = new AgentTraceEnrichmentMiddleware(tracerProvider.get("test"), serializer);
        AgentToolBodyTracer.configure(serializer);
    }

    /**
     * 单工具：一次执行一个工具节点，起止两端与事实源同值
     */
    @Test
    void shouldTraceSingleToolCall() {
        Call call = call("call-1", SUBMIT);

        start(List.of(call.use()), call.body());
        clock.advance(12);
        call.succeed();
        endRoot();

        SpanData tool = toolSpan("call-1");
        assertThat(tool.getParentSpanId()).isEqualTo(span(BATCH_SPAN).getSpanId());
        assertThat(tool.getAttributes().get(RagentAttributes.TOOL_STATUS)).isEqualTo(AgentToolStatus.DONE.value());
        assertThat(batchSpan().getAttributes().get(RagentAttributes.TOOL_BATCH_OUTCOME))
                .isEqualTo(RagentAttributes.OUTCOME_EXECUTED);
        assertThat(durationMillis(tool)).isEqualTo(12);

        assertSpanNames(ROOT_SPAN, BATCH_SPAN, SUBMIT_SPAN);
        assertInvariants(List.of("call-1"), List.of());
    }

    /**
     * 两个耗时不同的并行工具：慢的那条区间必须真包住快的，串行执行给不出这种交叠
     */
    @Test
    void shouldTraceParallelToolsWithDistinctDurations() {
        Call slow = call("call-1", SUBMIT);
        Call fast = call("call-2", QUERY);

        start(List.of(slow.use(), fast.use()), Flux.merge(slow.body(), fast.body()));
        clock.advance(20);
        fast.succeed();
        clock.advance(80);
        slow.succeed();
        endRoot();

        SpanData slowSpan = toolSpan("call-1");
        SpanData fastSpan = toolSpan("call-2");
        assertThat(slowSpan.getStartEpochNanos()).isEqualTo(fastSpan.getStartEpochNanos());
        assertThat(durationMillis(fastSpan)).isEqualTo(20);
        assertThat(durationMillis(slowSpan)).isEqualTo(100);
        // 并行的判据是区间交叠而不是两条都在同一个批下：串行跑完也共父
        assertThat(fastSpan.getEndEpochNanos()).isLessThan(slowSpan.getEndEpochNanos());
        assertThat(batchSpan().getAttributes().get(RagentAttributes.TOOL_BATCH_SIZE)).isEqualTo(2L);

        assertSpanNames(ROOT_SPAN, BATCH_SPAN, SUBMIT_SPAN, QUERY_SPAN);
        assertInvariants(List.of("call-1", "call-2"), List.of());
    }

    /**
     * 同名并行：两条 span 名字一模一样，区分只能靠调用 ID 与序号，把名字写成带参数的就是这一条炸开的
     */
    @Test
    void shouldKeepIdenticalNamesDistinguishableByCallIdAndIndex() {
        Call first = call("call-1", SUBMIT);
        Call second = call("call-2", SUBMIT);

        // 名册与执行顺序故意相反：序号若跟着开跑或返回的先后走，这里两条都会对调
        start(List.of(first.use(), second.use()), Flux.merge(second.body(), first.body()));
        clock.advance(15);
        second.succeed();
        clock.advance(15);
        first.succeed();
        endRoot();

        assertThat(exported.stream().filter(data -> SUBMIT_SPAN.equals(data.getName()))).hasSize(2);
        // 序号跟名册走，不跟事件到达顺序走：先回来的那条不许把序号抢到 0
        assertThat(toolSpan("call-1").getAttributes().get(RagentAttributes.TOOL_CALL_INDEX)).isEqualTo(0L);
        assertThat(toolSpan("call-2").getAttributes().get(RagentAttributes.TOOL_CALL_INDEX)).isEqualTo(1L);
        assertThat(toolSpan("call-1").getAttributes().get(RagentAttributes.TOOL_BATCH_ID))
                .isEqualTo(toolSpan("call-2").getAttributes().get(RagentAttributes.TOOL_BATCH_ID));

        assertSpanNames(ROOT_SPAN, BATCH_SPAN, SUBMIT_SPAN, SUBMIT_SPAN);
        assertInvariants(List.of("call-1", "call-2"), List.of());
    }

    /**
     * 同名并行的两条各写各的出入参：串一次就等于把另一条的业务当成了自己的
     * 名字一模一样，读的人认哪份属于哪条只能靠 span 自带的 toolCallId 与序号
     */
    @Test
    void shouldWriteOwnArgumentsAndResultOnEachToolSpan() {
        Call first = call("call-1", SUBMIT);
        Call second = call("call-2", SUBMIT);

        start(List.of(first.use(), second.use()), Flux.merge(second.body(), first.body()));
        clock.advance(15);
        second.succeed();
        clock.advance(15);
        first.succeed();
        endRoot();

        SpanData one = toolSpan("call-1");
        SpanData two = toolSpan("call-2");
        assertThat(one.getAttributes().get(GenAiAttributes.TOOL_CALL_ARGUMENTS))
                .contains("事假 call-1").doesNotContain("call-2");
        assertThat(two.getAttributes().get(GenAiAttributes.TOOL_CALL_ARGUMENTS))
                .contains("事假 call-2").doesNotContain("call-1");
        assertThat(one.getAttributes().get(GenAiAttributes.TOOL_CALL_RESULT))
                .contains("已受理 call-1").doesNotContain("call-2");
        assertThat(two.getAttributes().get(GenAiAttributes.TOOL_CALL_RESULT)).contains("已受理 call-2");
        // 工具体与批节点都保留完整参数，不再按键名过滤
        assertThat(one.getAttributes().get(GenAiAttributes.TOOL_CALL_ARGUMENTS))
                .contains("p@ss");

        // 给 UI 读的那两把与规范键同源，只是结局那份额外带上状态
        assertThat(one.getAttributes().get(AgentTraceAttributes.OBSERVATION_INPUT))
                .isEqualTo(one.getAttributes().get(GenAiAttributes.TOOL_CALL_ARGUMENTS));
        assertThat(one.getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT))
                .contains("\"state\":\"" + AgentToolStatus.DONE.value() + "\"").contains("已受理 call-1");

        assertSpanNames(ROOT_SPAN, BATCH_SPAN, SUBMIT_SPAN, SUBMIT_SPAN);
        assertInvariants(List.of("call-1", "call-2"), List.of());
    }

    /**
     * 关掉内容采集后连工具体也不许留业务原文：批那层早有闸门，执行体是后补的出口，漏在这里等于没关
     */
    @Test
    void shouldWriteNoContentOnToolSpanWhenCaptureDisabled() {
        captureContent(false);
        Call call = call("call-1", SUBMIT);

        start(List.of(call.use()), call.body());
        clock.advance(12);
        call.succeed();
        endRoot();

        SpanData tool = toolSpan("call-1");
        assertThat(tool.getAttributes().get(GenAiAttributes.TOOL_CALL_ARGUMENTS)).isNull();
        assertThat(tool.getAttributes().get(GenAiAttributes.TOOL_CALL_RESULT)).isNull();
        assertThat(tool.getAttributes().get(AgentTraceAttributes.OBSERVATION_INPUT)).isNull();
        assertThat(tool.getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT)).isNull();
        assertThat(batchSpan().getAttributes().get(AgentTraceAttributes.OBSERVATION_INPUT)).isNull();
        // 姓名、事由与回执任何一段都不许从别的键上渗出来
        assertThat(attributeValues()).allSatisfy(value ->
                assertThat(value).doesNotContain("张三", "事假", "已受理", "p@ss"));

        // 结构与状态照旧：连这些一起没了就不叫关内容，叫关观测
        assertThat(tool.getAttributes().get(RagentAttributes.TOOL_STATUS)).isEqualTo(AgentToolStatus.DONE.value());
        assertThat(tool.getAttributes().get(GenAiAttributes.TOOL_CALL_ID)).isEqualTo("call-1");
        assertInvariants(List.of("call-1"), List.of());
    }

    /**
     * 失败那条的结果也要能读：只标 ERROR 不给正文等于把排查推回日志
     * 规范键留空是有意的，它表示工具返回值，拿失败正文顶上去会让按它统计的一方读到假成功
     */
    @Test
    void shouldKeepFailedToolResultReadable() {
        Call call = call("call-1", SUBMIT);

        start(List.of(call.use()), call.body());
        clock.advance(9);
        call.fail();
        endRoot();

        SpanData tool = toolSpan("call-1");
        assertThat(tool.getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT))
                .contains("\"state\":\"" + AgentToolStatus.FAILED.value() + "\"").contains("已受理 call-1");
        assertThat(tool.getAttributes().get(GenAiAttributes.TOOL_CALL_RESULT)).isNull();
        assertThat(tool.getAttributes().get(GenAiAttributes.TOOL_CALL_ARGUMENTS)).contains("事假 call-1");
        assertInvariants(List.of("call-1"), List.of());
    }

    /**
     * 跑成功的那批只有结构化名册，没有拿来当展示摘要的状态说明
     * status_message 在 LangFuse 里与级别同区，成功批占着它会让「有话说」等于「有问题」
     */
    @Test
    void shouldLeaveStatusMessageUnwrittenOnSuccessfulBatch() {
        Call call = call("call-1", SUBMIT);

        start(List.of(call.use()), call.body());
        clock.advance(12);
        call.succeed();
        endRoot();

        assertThat(batchSpan().getAttributes().get(AgentTraceAttributes.STATUS_MESSAGE)).isNull();
        assertThat(batchSpan().getAttributes().get(AgentTraceAttributes.LEVEL)).isNull();
        assertThat(batchSpan().getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT))
                .contains(SUBMIT).contains("\"callIndex\":0");
        assertInvariants(List.of("call-1"), List.of());
    }

    /**
     * 工具失败：错在工具不在链路，两层都要标出来，级别不能被同批的成功盖掉
     */
    @Test
    void shouldMarkFailedToolOnBothToolAndBatch() {
        Call failing = call("call-1", SUBMIT);
        Call passing = call("call-2", QUERY);

        start(List.of(failing.use(), passing.use()), Flux.merge(failing.body(), passing.body()));
        clock.advance(10);
        passing.succeed();
        clock.advance(10);
        failing.fail();
        endRoot();

        SpanData tool = toolSpan("call-1");
        assertThat(tool.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(tool.getAttributes().get(AgentErrorTypes.KEY)).isEqualTo(AgentErrorTypes.TOOL_ERROR.value());
        assertThat(tool.getAttributes().get(RagentAttributes.TOOL_ERROR_SOURCE))
                .isEqualTo(RagentAttributes.ERROR_SOURCE_TOOL_RESULT);
        assertThat(toolSpan("call-2").getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(batchSpan().getAttributes().get(AgentTraceAttributes.LEVEL))
                .isEqualTo(AgentTraceAttributes.LEVEL_ERROR);

        assertSpanNames(ROOT_SPAN, BATCH_SPAN, SUBMIT_SPAN, QUERY_SPAN);
        assertInvariants(List.of("call-1", "call-2"), List.of());
    }

    /**
     * 等确认：人类等多久都不该变成工具耗时，故一个工具节点都不建
     */
    @Test
    void shouldEmitNoToolSpanWhileAwaitingConfirmation() {
        ToolUseBlock pending = call("call-1", SUBMIT).use();

        start(List.of(pending), Flux.just(new RequireUserConfirmEvent(REPLY_ID, List.of(pending))));
        clock.advance(300_000);
        endRoot();

        assertThat(batchSpan().getAttributes().get(RagentAttributes.TOOL_BATCH_OUTCOME))
                .isEqualTo(RagentAttributes.OUTCOME_AWAITING);
        assertThat(batchSpan().getAttributes().get(AgentTraceAttributes.LEVEL))
                .isEqualTo(AgentTraceAttributes.LEVEL_WARNING);
        // 批 span 也不许跨等待：它在事件落完那刻就收了，之后那五分钟与本次运行的耗时无关
        assertThat(durationMillis(batchSpan())).isZero();

        assertSpanNames(ROOT_SPAN, BATCH_SPAN);
        assertInvariants(List.of(), List.of("call-1"));
    }

    /**
     * 用户拒绝：一步没跑，同样零工具节点，且不能与「跑完了没输出」同形
     */
    @Test
    void shouldEmitNoToolSpanWhenAllToolsDenied() {
        ToolUseBlock denied = call("call-1", SUBMIT).use();

        start(List.of(denied), Flux.just(new AllToolsDeniedEvent(List.of(denied))));
        endRoot();

        assertThat(batchSpan().getAttributes().get(RagentAttributes.TOOL_BATCH_OUTCOME))
                .isEqualTo(RagentAttributes.OUTCOME_DENIED);
        assertThat(batchSpan().getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT))
                .contains(AgentToolStatus.DENIED.value());

        assertSpanNames(ROOT_SPAN, BATCH_SPAN);
        assertInvariants(List.of(), List.of("call-1"));
    }

    /**
     * 批准续跑：等待与执行分属两次运行，同一个 toolCallId 跨两棵树也只该有一个工具节点
     */
    @Test
    void shouldProduceExactlyOneToolSpanAcrossAwaitAndResume() {
        ToolUseBlock pending = call("call-1", SUBMIT).use();
        start(List.of(pending), Flux.just(new RequireUserConfirmEvent(REPLY_ID, List.of(pending))));
        clock.advance(120_000);
        endRoot();

        // 确认续跑是另一次运行：新 runId、新事实源，批号因此不会与上一次撞
        newRun("t-9002");
        Call resumed = call("call-1", SUBMIT);
        start(List.of(resumed.use()), resumed.body());
        clock.advance(18);
        resumed.succeed();
        endRoot();

        List<SpanData> batches = exported.stream().filter(data -> BATCH_SPAN.equals(data.getName())).toList();
        assertThat(batches).extracting(data -> data.getAttributes().get(RagentAttributes.TOOL_BATCH_OUTCOME))
                .containsExactly(RagentAttributes.OUTCOME_AWAITING, RagentAttributes.OUTCOME_EXECUTED);
        assertThat(batches.get(0).getAttributes().get(RagentAttributes.TOOL_BATCH_ID))
                .isNotEqualTo(batches.get(1).getAttributes().get(RagentAttributes.TOOL_BATCH_ID));
        assertThat(durationMillis(toolSpan("call-1"))).isEqualTo(18);

        assertSpanNames(ROOT_SPAN, BATCH_SPAN, ROOT_SPAN, BATCH_SPAN, SUBMIT_SPAN);
        assertInvariants(List.of("call-1"), List.of());
    }

    /**
     * 执行中取消：两层都得收口且对齐中断时刻，留一个跑着的 span 比没有这个 span 更糟
     */
    @Test
    void shouldSettleBothLayersWhenCancelledMidExecution() {
        Call running = call("call-1", SUBMIT);

        Disposable subscription = start(List.of(running.use()), running.body());
        clock.advance(40);
        facts.markInterrupted();
        // 中断到断流之间是收尾等待，把它算进耗时就等于把中断成本记到工具头上
        clock.advance(2_000);
        facts.markCancelled();
        subscription.dispose();
        endRoot();

        SpanData tool = toolSpan("call-1");
        assertThat(tool.getAttributes().get(RagentAttributes.TOOL_STATUS))
                .isEqualTo(AgentToolStatus.INTERRUPTED.value());
        assertThat(tool.getAttributes().get(AgentErrorTypes.KEY)).isEqualTo(AgentErrorTypes.CANCELLED.value());
        assertThat(tool.getEndEpochNanos()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(facts.terminationAt()));
        assertThat(durationMillis(tool)).isEqualTo(40);
        assertThat(batchSpan().getAttributes().get(RagentAttributes.TOOL_BATCH_OUTCOME))
                .isEqualTo(RagentAttributes.OUTCOME_INTERRUPTED);
        assertThat(batchSpan().getEndEpochNanos()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(facts.terminationAt()));
        // 断在半路的一律不许留成功相：状态、级别、error.type 三处任缺一处就会被读成跑完了
        assertThat(exported).noneMatch(data -> AgentToolStatus.DONE.value()
                .equals(data.getAttributes().get(RagentAttributes.TOOL_STATUS)));

        assertSpanNames(ROOT_SPAN, BATCH_SPAN, SUBMIT_SPAN);
        assertInvariants(List.of(), List.of());
        assertThat(toolObservations("call-1")).hasSize(1);
    }

    /**
     * 跨层不变量：与具体场景无关的那几条，每条用例末尾都走一遍
     * 单独成方法而不是抄进各用例，是为了让「某条不变量根本没人验」不可能发生
     */
    private void assertInvariants(List<String> executed, List<String> unexecuted) {
        for (String toolCallId : executed) {
            assertThat(toolObservations(toolCallId))
                    .as("%s 应有且仅有一个 tool observation", toolCallId).hasSize(1);
            SpanData tool = toolObservations(toolCallId).get(0);
            ToolFact fact = facts.toolFact(toolCallId);
            // span 不自己读时钟，两端都从事实源抄：不同源就意味着 PG、SSE、Langfuse 会各说各的
            assertThat(tool.getStartEpochNanos()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(fact.startedAt()));
            assertThat(tool.getEndEpochNanos()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(fact.endedAt()));
            assertThat(tool.getAttributes().get(RagentAttributes.TOOL_CALL_INDEX))
                    .isEqualTo((long) fact.callIndex());
        }
        for (String toolCallId : unexecuted) {
            assertThat(toolObservations(toolCallId))
                    .as("%s 没跑过就不该有 observation", toolCallId).isEmpty();
            assertThat(facts.toolFact(toolCallId).durationMs()).isNull();
        }

        assertThat(exported).allSatisfy(data -> {
            // 名字里带调用 ID、批号或运行号，后端按名聚合当场炸成一个 span 一类
            assertThat(data.getName()).doesNotContain("call-", "t-900");
            data.getAttributes().forEach((key, value) -> {
                if (key.getKey().startsWith("gen_ai.")) {
                    assertThat(ALLOWED_GEN_AI_KEYS)
                            .as("%s 上的 %s 不在标准键清单里", data.getName(), key.getKey())
                            .contains(key.getKey());
                }
            });
            String errorType = data.getAttributes().get(AgentErrorTypes.KEY);
            // 异常那档记的是全限定类名，本身就是低基数的，其余必须落在封闭取值里
            if (errorType != null && !errorType.contains(".")) {
                assertThat(ALLOWED_ERROR_TYPES).contains(errorType);
            }
        });
    }

    /**
     * 全树属性值摊平，供内容闸门做穷举扫描：只挑几个点验，漏掉的正是没人想到的那条通道
     */
    private List<String> attributeValues() {
        List<String> values = new ArrayList<>();
        for (SpanData data : exported) {
            data.getAttributes().forEach((key, value) -> values.add(String.valueOf(value)));
        }
        return values;
    }

    /**
     * SimpleSpanProcessor 只导出已收口的 span，故名单齐了就等于没有一个悬着的
     */
    private void assertSpanNames(String... names) {
        assertThat(exported).extracting(SpanData::getName).containsExactlyInAnyOrder(names);
    }

    private Flux<AgentEvent> acting(List<ToolUseBlock> calls, Flux<AgentEvent> events) {
        return tracing.onActing(null, ctx, new ActingInput(calls),
                outer -> enrichment.onActing(null, ctx, outer,
                        inner -> batching.onActing(null, ctx, inner, ignored -> events)));
    }

    /**
     * 不 block：并行与取消都要在流跑着的时候拨时钟、投结果，阻塞式驱动给不出这些时刻
     */
    private Disposable start(List<ToolUseBlock> calls, Flux<AgentEvent> events) {
        root = tracerProvider.get("test").spanBuilder(ROOT_SPAN).startSpan();
        AgentRunTracer.bindRoot(ctx, root);
        // 本批属于刚推理完的那一轮，计数器由推理那层递增，这里补上一轮免得轮次号整个不写
        AgentRunTracer.nextRound(ctx);
        return ContextPropagationOperator.runWithContext(acting(calls, events),
                root.storeInContext(Context.root())).subscribe();
    }

    private void endRoot() {
        root.end();
    }

    /**
     * 一次运行一份事实源，确认续跑是第二次运行，两次之间必须换新
     */
    private void newRun(String runId) {
        facts = new AgentToolExecutionFacts(runId, clock);
        ctx = RuntimeContext.builder().userId("u-1001").sessionId("c-2002").build();
        ctx.put(AgentToolExecutionFacts.RUNTIME_CONTEXT_KEY, facts);
    }

    private Call call(String toolCallId, String toolName) {
        return new Call(toolCallId, toolName);
    }

    private List<SpanData> toolObservations(String toolCallId) {
        return exported.stream()
                .filter(data -> AgentTraceAttributes.TYPE_TOOL
                        .equals(data.getAttributes().get(AgentTraceAttributes.OBSERVATION_TYPE)))
                .filter(data -> toolCallId.equals(data.getAttributes().get(GenAiAttributes.TOOL_CALL_ID)))
                .toList();
    }

    private SpanData toolSpan(String toolCallId) {
        List<SpanData> spans = toolObservations(toolCallId);
        if (spans.size() != 1) {
            throw new AssertionError(toolCallId + " 的工具 span 有 " + spans.size() + " 个, 实际导出: "
                    + exported.stream().map(SpanData::getName).toList());
        }
        return spans.get(0);
    }

    private SpanData batchSpan() {
        return span(BATCH_SPAN);
    }

    private SpanData span(String name) {
        return exported.stream().filter(data -> name.equals(data.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("未导出 span: " + name + ", 实际: "
                        + exported.stream().map(SpanData::getName).toList()));
    }

    private static long durationMillis(SpanData data) {
        return TimeUnit.NANOSECONDS.toMillis(data.getEndEpochNanos() - data.getStartEpochNanos());
    }

    /**
     * 一次假工具调用：订阅即开跑，结果由用例挑时刻投进去，起止窗口因此完全可控
     */
    private final class Call {

        private final String toolCallId;
        private final String toolName;
        private final Sinks.One<ToolResultBlock> result = Sinks.one();
        private final RuntimeContext runtimeContext = ctx;

        private Call(String toolCallId, String toolName) {
            this.toolCallId = toolCallId;
            this.toolName = toolName;
        }

        private ToolUseBlock use() {
            return ToolUseBlock.builder()
                    .id(toolCallId)
                    .name(toolName)
                    .input(Map.of("employeeName", "张三", "day", "2026-09-14"))
                    .build();
        }

        /**
         * 事件与工具体同源：真跑过才有 start/end 两个事件，也才有 span，两者不会各说各的
         */
        private Flux<AgentEvent> body() {
            return Flux.<AgentEvent>just(new ToolResultStartEvent(REPLY_ID, toolCallId, toolName))
                    .concatWith(AgentToolBodyTracer.trace(new FakeTool(toolName), param(), result::asMono)
                            .map(block -> new ToolResultEndEvent(REPLY_ID, toolCallId, toolName, block.getState())));
        }

        private void succeed() {
            result.tryEmitValue(block(ToolResultState.SUCCESS));
        }

        private void fail() {
            result.tryEmitValue(block(ToolResultState.ERROR));
        }

        /**
         * 入参与结果都带上调用 ID：同名并行时这是唯一能看出「哪份属于哪条」的凭据
         */
        private ToolCallParam param() {
            return ToolCallParam.builder()
                    .toolUseBlock(use())
                    .input(Map.of("employeeName", "张三", "reason", "事假 " + toolCallId,
                            "profile", Map.of("password", "p@ss")))
                    .runtimeContext(runtimeContext)
                    .build();
        }

        private ToolResultBlock block(ToolResultState state) {
            return ToolResultBlock.builder()
                    .id(toolCallId)
                    .name(toolName)
                    .output(TextBlock.builder().text("已受理 " + toolCallId).build())
                    .state(state)
                    .build();
        }
    }

    /**
     * 只提供工具身份，执行体由用例的 sink 决定：这一层测的是链路形状不是某把工具的业务
     */
    private record FakeTool(String toolName) implements AgentTool {

        @Override
        public String getName() {
            return toolName;
        }

        @Override
        public String getDescription() {
            return "假工具 " + toolName;
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            throw new UnsupportedOperationException("用例直接驱动 sink，不走这条入口");
        }
    }
}
