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

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.agent.enums.AgentToolStatus;
import com.nageoffer.ai.ragent.agent.model.AnthropicMessagesChatModel;
import com.nageoffer.ai.ragent.agent.tool.AgentToolBatchMiddleware;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用真 SDK 建 span、用内存导出器收 span，断言的是最终上报给 LangFuse 的那份快照
 * 工具那条链按生产的洋葱原样搭起来：外层建批 span、最内层开批，批次号与序号才是真从事实源来的
 */
class AgentTraceEnrichmentMiddlewareTest {

    private static final String USER_ID = "u-1001";
    private static final String CONVERSATION_ID = "c-2002";
    private static final String TASK_ID = "t-9001";
    private static final String REPLY_TO_MESSAGE_ID = "m-3003";
    private static final String CONFIRM_MESSAGE_ID = "m-4004";

    /**
     * 官方那层的根 span 名，与本中间件无关，只用来在导出结果里认出它
     */
    private static final String ROOT_SPAN = "invoke_agent";
    private static final String TOOL_BATCH_SPAN = "tool_batch";

    /**
     * 事实源的 runId 在生产里就是任务号，批次号即由它拼出
     */
    private static final String BATCH_ID = TASK_ID + "-0";

    private static ContextPropagationOperator reactorHook;

    private List<SpanData> exported;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;
    private AgentToolExecutionFacts facts;
    private RagentOtelTracingMiddleware tracingMiddleware;
    private AgentToolBatchMiddleware batchMiddleware;
    private AgentTraceEnrichmentMiddleware middleware;

    /**
     * 官方 OtelTracingMiddleware 在构造时装这个 Hook，不装则 runWithContext 直接原样返回，
     * span 根本进不了 Reactor 上下文，本中间件的每个钩子都会走「取不到父亲」的放行分支
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
        exported = Collections.synchronizedList(new ArrayList<>());
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.builder(new CollectingExporter(exported)).build())
                .build();
        tracer = tracerProvider.get("test");
        facts = new AgentToolExecutionFacts(TASK_ID, Clock.systemUTC());
        tracingMiddleware = new RagentOtelTracingMiddleware(tracer);
        batchMiddleware = new AgentToolBatchMiddleware();
        middleware = new AgentTraceEnrichmentMiddleware(tracer, serializer(true));
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    /**
     * 多轮 ReAct：轮次 span 逐轮建，上下文水位逐轮各记各的，运行级的事实只落根上
     */
    @Test
    void shouldOpenOneReasoningSpanPerRoundAndKeepRunFactsOnRoot() {
        RuntimeContext ctx = askContext();

        driveTwoRounds(ctx);

        // 叫 react round 会被当成一整轮 ReAct 读，实际这条 span 的耗时不含工具
        assertThat(spanNames()).noneMatch(name -> name.startsWith("react"));
        assertThat(reasoningSpans()).hasSize(2);
        assertThat(reasoningSpans().get(1).getAttributes().get(RagentAttributes.CONTEXT_MESSAGE_COUNT)).isEqualTo(2L);
        assertThat(reasoningSpans().get(1).getAttributes().get(RagentAttributes.CONTEXT_CHARS)).isPositive();

        SpanData root = span(ROOT_SPAN);
        assertThat(root.getAttributes().get(AgentTraceAttributes.OBSERVATION_TYPE))
                .isEqualTo(AgentTraceAttributes.TYPE_AGENT);
        assertThat(root.getAttributes().get(RagentAttributes.RUN_KIND)).isEqualTo(RagentAttributes.RUN_KIND_ASK);
        // 名字由运行方式拼出，两者必须一直对得上，只钉续跑那一侧会放过首问这条分支
        assertThat(root.getAttributes().get(AgentTraceAttributes.TRACE_NAME)).isEqualTo("ragent.run.ask");
        assertThat(root.getAttributes().get(AgentTraceAttributes.OBSERVATION_INPUT)).isEqualTo("北京明天天气");
        // 跑完也要留结局：只有中断路径写，属性缺失就分不清是没中断还是追踪压根没生效
        assertThat(root.getAttributes().get(RagentAttributes.RUN_OUTCOME))
                .isEqualTo(RagentAttributes.RUN_OUTCOME_COMPLETED);
        // 工具定义只在首轮写一次，逐轮重复几十份 schema 没有意义
        assertThat(root.getAttributes().get(AgentTraceAttributes.META_TOOL_DEFINITIONS))
                .contains("search_knowledge");
    }

    /**
     * 根 output 汇总跨轮业务正文；模型思考与普通 final 留在 generation，根上不重复
     */
    @Test
    void shouldAggregateBusinessAnswerIntoRootOutput() {
        RuntimeContext ctx = askContext();

        driveTwoRounds(ctx);

        JSONObject output = rootOutput();
        assertThat(output.getStr("answer")).isEqualTo("北京明天多云");
        assertThat(output.getStr("generate_reason")).isEqualTo(GenerateReason.MODEL_STOP.name());
        assertThat(output.containsKey("thinking")).isFalse();
        assertThat(output.containsKey("final")).isFalse();
    }

    /**
     * 用量归各 generation 自报，跟到根 span 会被 LangFuse 的 observation 求和重复计入
     */
    @Test
    void shouldNotWriteUsageOnRootSpan() {
        RuntimeContext ctx = askContext();

        driveTwoRounds(ctx);

        assertThat(span(ROOT_SPAN).getAttributes().get(AgentTraceAttributes.USAGE_DETAILS)).isNull();
        assertThat(span("chat").getAttributes().get(AgentTraceAttributes.USAGE_DETAILS))
                .contains("\"input_cached_tokens\":20");
    }

    /**
     * 缓存写入量只在 Anthropic 模型的响应元数据里，事件流带不出来；漏了它，Opus 每笔写缓存都按普通输入价少算
     */
    @Test
    void shouldRecordCacheWriteAndProviderForAnthropicModel() {
        RuntimeContext ctx = askContext();
        List<Msg> msgs = List.of(new UserMessage("问题"));
        AnthropicMessagesChatModel model = new AnthropicMessagesChatModel("https://zenmux.ai/api/anthropic/v1/messages",
                "sk-test", "anthropic/claude-opus-5.5", new ScriptedStream(List.of(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"usage\":{\"input_tokens\":11,"
                        + "\"cache_creation_input_tokens\":2896,\"cache_read_input_tokens\":0,\"output_tokens\":1}}}",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"好\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":3}}",
                "{\"type\":\"message_stop\"}")));

        Span root = startRoot();
        underRoot(root, ctx, msgs, input -> reasoningRound(ctx, msgs, List.of(), childSpan("chat",
                () -> middleware.onModelCall(null, ctx, new ModelCallInput(msgs, List.of(), null, model),
                        call -> call.model().stream(call.messages(), call.tools(), call.options())
                                .last()
                                .flatMapMany(last -> Flux.just(new ModelCallEndEvent("r1", last.getUsage()))))))).blockLast();
        root.end();

        assertThat(span("chat").getAttributes().get(GenAiAttributes.PROVIDER_NAME)).isEqualTo("anthropic");
        assertThat(span("chat").getAttributes().get(AgentTraceAttributes.USAGE_DETAILS))
                .contains("\"input\":11", "\"input_cached_tokens\":0", "\"cache_creation_input_tokens\":2896");
    }

    /**
     * 轮次号贯穿三类节点：推理与工具在树上是兄弟，同一轮只能靠这把键认出来
     */
    @Test
    void shouldCarrySameRoundIndexAcrossReasoningChatAndToolBatch() {
        RuntimeContext ctx = askContext();

        driveTwoRounds(ctx);

        assertThat(roundIndexes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "reasoning#0", 1L, "chat#0", 1L, TOOL_BATCH_SPAN + "#0", 1L,
                "reasoning#1", 2L, "chat#1", 2L));
        // 根上是总轮数，不是某一轮的序号，两把键不能混用
        assertThat(span(ROOT_SPAN).getAttributes().get(RagentAttributes.REACT_ROUNDS)).isEqualTo(2L);
        assertThat(span(ROOT_SPAN).getAttributes().get(RagentAttributes.REACT_ROUND_INDEX)).isNull();
    }

    /**
     * 轮次号进了属性，就不许再留在 span 名里：名字带序号会让后端按名聚合时每轮各成一类
     */
    @Test
    void shouldKeepReasoningSpanNameFreeOfRoundNumber() {
        RuntimeContext ctx = askContext();

        driveTwoRounds(ctx);

        assertThat(spanNames()).filteredOn(name -> name.startsWith("reasoning"))
                .containsExactly("reasoning", "reasoning");
    }

    /**
     * 一轮推理自己的结局：span 状态只有 OK/ERROR 两档，分不出「被用户停掉」和「真报错」
     */
    @Test
    void shouldWriteRoundOutcomeOnReasoningSpan() {
        RuntimeContext ctx = askContext();

        driveTwoRounds(ctx);

        assertThat(reasoningSpans()).map(data -> data.getAttributes().get(RagentAttributes.ROUND_OUTCOME))
                .containsExactly(RagentAttributes.RUN_OUTCOME_COMPLETED, RagentAttributes.RUN_OUTCOME_COMPLETED);
    }

    /**
     * 契约版本写在根上，换代之后查询侧凭它区分两代数据，不必靠属性有没有来猜
     */
    @Test
    void shouldStampContractVersionOnRootSpan() {
        RuntimeContext ctx = askContext();

        driveTwoRounds(ctx);

        assertThat(span(ROOT_SPAN).getAttributes().get(RagentAttributes.CONTRACT_VERSION))
                .isEqualTo(RagentAttributes.CONTRACT_VERSION_VALUE);
    }

    /**
     * chat span 的两个规范属性：provider 是必填项，stream 不写会被下游读成非流式而我们全程流式
     */
    @Test
    void shouldWriteRequiredGenAiAttributesOnChatSpan() {
        RuntimeContext ctx = askContext();

        driveTwoRounds(ctx);

        SpanData chat = span("chat");
        assertThat(chat.getAttributes().get(GenAiAttributes.PROVIDER_NAME)).isEqualTo(GenAiAttributes.PROVIDER_OPENAI);
        assertThat(chat.getAttributes().get(GenAiAttributes.REQUEST_STREAM)).isTrue();
    }

    /**
     * 会话身份只有 langfuse.session.id 一把键，且必须落在每个 span 上，否则 observation 级过滤会漏
     */
    @Test
    void shouldWriteSessionIdOnEverySpan() {
        RuntimeContext ctx = askContext();

        driveTwoRounds(ctx);

        assertThat(exported).isNotEmpty().allSatisfy(data ->
                assertThat(data.getAttributes().get(AgentTraceAttributes.SESSION_ID)).isEqualTo(CONVERSATION_ID));
        // 根 span 是 internal 变体，规范只把 operation.name 列为必填，provider 不属于它
        assertThat(span(ROOT_SPAN).getAttributes().get(GenAiAttributes.PROVIDER_NAME)).isNull();
    }

    /**
     * 业务 ID 三件套：首问没有确认消息号，缺的那个不落空串，免得过滤时多出一档假值
     */
    @Test
    void shouldCopyBusinessIdsFromRuntimeContextToEverySpan() {
        RuntimeContext ctx = askContext();

        driveTwoRounds(ctx);

        SpanData root = span(ROOT_SPAN);
        assertThat(root.getAttributes().get(RagentAttributes.TASK_ID)).isEqualTo(TASK_ID);
        assertThat(root.getAttributes().get(RagentAttributes.REPLY_TO_MESSAGE_ID)).isEqualTo(REPLY_TO_MESSAGE_ID);
        assertThat(root.getAttributes().get(RagentAttributes.CONFIRM_MESSAGE_ID)).isNull();
        // 只写根 span 会让 observation 级的过滤与聚合失准
        assertThat(span("reasoning").getAttributes().get(RagentAttributes.TASK_ID)).isEqualTo(TASK_ID);
        assertThat(span(ROOT_SPAN).getAttributes().get(AgentTraceAttributes.USER_ID)).isEqualTo(USER_ID);
        assertThat(span(ROOT_SPAN).getAttributes().get(AgentTraceAttributes.SESSION_ID)).isEqualTo(CONVERSATION_ID);
    }

    /**
     * 权限暂停：停在 ASKING 的工具块与终止原因都得留在根 output 上，只取正文会把「在等哪个工具」整段丢掉
     */
    @Test
    void shouldKeepAskingToolBlockAndReasonWhenPausedForPermission() {
        RuntimeContext ctx = askContext();
        ToolUseBlock asking = leaveSubmit().withState(ToolCallState.ASKING);
        Msg paused = AssistantMessage.builder()
                .content(asking)
                .generateReason(GenerateReason.PERMISSION_ASKING)
                .build();

        Span root = startRoot();
        underRoot(root, ctx, List.of(new UserMessage("帮我请 9 月 14 日的假")),
                input -> Flux.just(new AgentResultEvent(paused))).blockLast();
        root.end();

        JSONObject output = rootOutput();
        assertThat(output.getStr("generate_reason")).isEqualTo(GenerateReason.PERMISSION_ASKING.name());
        JSONObject block = output.getJSONObject("final").getJSONArray("blocks").getJSONObject(0);
        assertThat(block.getStr("type")).isEqualTo("tool_use");
        assertThat(block.getStr("state")).isEqualTo(ToolCallState.ASKING.name());
        assertThat(block.getJSONObject("input").getStr("employeeName")).isEqualTo("张三");
        // final 不摊原始 metadata，终止原因走专键，framework 内部键不该漏进来
        assertThat(output.getJSONObject("final").containsKey("metadata")).isFalse();
        assertThat(span(ROOT_SPAN).getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT))
                .doesNotContain(Msg.METADATA_GENERATE_REASON);
    }

    /**
     * 挂起等确认的那批一个都没跑，批 span 却停在「入参齐全、输出为空、级别正常」
     * 那正是一次静默成功的写操作的样子，与被拒批要防的是同一种误读
     */
    @Test
    void shouldRecordAwaitingToolCallsWhenPausedForPermission() {
        RuntimeContext ctx = askContext();
        // 事件里这批是标 ASKING 之前的旧实例：框架走 withState 复制回上下文，不改手上这几个
        ToolUseBlock pending = leaveSubmit();

        Span root = startRoot();
        underRoot(root, ctx, List.of(new UserMessage("帮我请 9 月 14 日的假")), input -> Flux.concat(
                acting(ctx, List.of(pending), Flux.just(
                        new RequireUserConfirmEvent("r1", List.of(pending)),
                        new RequestStopEvent("permission asking", GenerateReason.PERMISSION_ASKING))),
                Flux.just(new AgentResultEvent(AssistantMessage.builder()
                        .content(leaveSubmit().withState(ToolCallState.ASKING))
                        .generateReason(GenerateReason.PERMISSION_ASKING)
                        .build())))).blockLast();
        root.end();

        SpanData batch = span(TOOL_BATCH_SPAN);
        assertThat(batch.getAttributes().get(AgentTraceAttributes.LEVEL))
                .isEqualTo(AgentTraceAttributes.LEVEL_WARNING);
        // 挂起不是被拒，措辞得分开：这批还等着人点，不是已经被否掉
        assertThat(batch.getAttributes().get(AgentTraceAttributes.STATUS_MESSAGE))
                .isEqualTo("等待人工确认，工具尚未执行");
        String output = batch.getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT);
        assertThat(output).isNotEqualTo("[]");
        JSONObject item = JSONUtil.parseArray(output).getJSONObject(0);
        assertThat(item.getStr("name")).isEqualTo("leave_submit");
        assertThat(item.getBool("executed")).isFalse();
        // 取值与 SSE、落库块同一套，追踪另造一档措辞就成了第二种状态解释
        assertThat(item.getStr("state")).isEqualTo(AgentToolStatus.AWAITING.value());
        // 入参有几个、输出就得有几个，少一个就是「这次到底跑没跑」说不清
        assertThat(JSONUtil.parseArray(batch.getAttributes().get(AgentTraceAttributes.OBSERVATION_INPUT)))
                .hasSameSizeAs(JSONUtil.parseArray(output));
    }

    /**
     * 确认续跑：本次调用先执行工具后推理，计数器还停在 0，标记这批是上一次挂起留下的
     */
    @Test
    void shouldMarkConfirmResumeRunAndCarryConfirmMessageId() {
        RuntimeContext ctx = askContext();
        ctx.put(AgentTraceContextKeys.CONFIRM_MESSAGE_ID, CONFIRM_MESSAGE_ID);
        ToolUseBlock approved = leaveSubmit().withState(ToolCallState.ALLOWED);
        // 确认续跑由一条带确认结果元数据的空正文消息驱动
        Msg resume = UserMessage.builder().metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.of())).build();

        Span root = startRoot();
        underRoot(root, ctx, List.of(resume), input -> Flux.concat(
                acting(ctx, List.of(approved), Flux.just(
                        new ToolResultStartEvent("r1", "call-1", "leave_submit"),
                        new ToolResultTextDeltaEvent("r1", "call-1", "leave_submit", "已提交, 单号 L-77"),
                        new ToolResultEndEvent("r1", "call-1", "leave_submit", ToolResultState.SUCCESS))),
                Flux.just(new AgentResultEvent(AssistantMessage.builder()
                        .content(TextBlock.builder().text("已提交").build())
                        .build())))).blockLast();
        root.end();

        SpanData rootData = span(ROOT_SPAN);
        // 字面量而非常量拼接：名字是查询侧的聚合维度，改了值就该让这里变红
        assertThat(rootData.getAttributes().get(AgentTraceAttributes.TRACE_NAME))
                .isEqualTo("ragent.run.confirm_resume");
        assertThat(rootData.getAttributes().get(RagentAttributes.RUN_KIND))
                .isEqualTo(RagentAttributes.RUN_KIND_CONFIRM_RESUME);
        assertThat(rootData.getAttributes().get(RagentAttributes.CONFIRM_MESSAGE_ID)).isEqualTo(CONFIRM_MESSAGE_ID);
        // 续跑那条消息正文为空，退回结构化输出才看得见确认结果
        assertThat(rootData.getAttributes().get(AgentTraceAttributes.OBSERVATION_INPUT))
                .contains(Msg.METADATA_CONFIRM_RESULTS);

        SpanData batch = span(TOOL_BATCH_SPAN);
        // 这批是上一次挂起留下的，不属于本次运行的任何一轮；写个 0 会在按轮聚合时多出一档假轮次
        assertThat(batch.getAttributes().get(RagentAttributes.REACT_ROUND_INDEX)).isNull();
        assertThat(batch.getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT)).contains("已提交, 单号 L-77");
        assertThat(batch.getAttributes().get(AgentTraceAttributes.LEVEL)).isNull();
    }

    /**
     * 整批被拒：不写就停在「入参齐全、输出为空、级别正常」，看上去正是一次静默成功的写操作
     */
    @Test
    void shouldRecordDeniedToolCallsInsteadOfEmptyOutput() {
        RuntimeContext ctx = askContext();
        ToolUseBlock denied = leaveSubmit().withState(ToolCallState.ASKING);

        Span root = startRoot();
        underRoot(root, ctx, List.of(new UserMessage("帮我请假")), input -> Flux.concat(
                acting(ctx, List.of(denied), Flux.just(new AllToolsDeniedEvent(List.of(denied)))),
                Flux.just(new AgentResultEvent(AssistantMessage.builder()
                        .content(TextBlock.builder().text("已取消").build())
                        .generateReason(GenerateReason.ALL_TOOLS_DENIED)
                        .build())))).blockLast();
        root.end();

        SpanData batch = span(TOOL_BATCH_SPAN);
        assertThat(batch.getAttributes().get(AgentTraceAttributes.LEVEL))
                .isEqualTo(AgentTraceAttributes.LEVEL_WARNING);
        // 拒因可能是用户点了取消，也可能是权限策略，措辞不替框架认定
        assertThat(batch.getAttributes().get(AgentTraceAttributes.STATUS_MESSAGE))
                .isEqualTo("全部工具调用被拒绝，均未执行");
        String output = batch.getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT);
        assertThat(output).isNotEqualTo("[]");
        JSONObject item = JSONUtil.parseArray(output).getJSONObject(0);
        assertThat(item.getStr("name")).isEqualTo("leave_submit");
        assertThat(item.getBool("executed")).isFalse();
        // 拒绝的结局是 denied，照抄块上那个 ASKING 只说得出「当时在等确认」，说不出后来被否了
        assertThat(item.getStr("state")).isEqualTo(AgentToolStatus.DENIED.value());
    }

    /**
     * 并发那批同起同止，只该占一个批次号；条目顺序恒取模型吐出的先后，
     * 同名同参的两条只剩 toolCallId 与 callIndex 分得开，按到达顺序排就会把出入参对错人
     */
    @Test
    void shouldOrderToolCallsByModelOrderRegardlessOfEventArrival() {
        RuntimeContext ctx = askContext();
        ToolUseBlock first = leaveSubmit("call-1", "张三");
        ToolUseBlock second = leaveSubmit("call-2", "李四");

        Span root = startRoot();
        underRoot(root, ctx, List.of(new UserMessage("帮我和李四一起请假")), input -> Flux.concat(
                acting(ctx, List.of(first, second), Flux.just(
                        // 整批并发执行，谁先回全看各自快慢，与模型吐出的先后无关
                        new ToolResultStartEvent("r1", "call-2", "leave_submit"),
                        new ToolResultStartEvent("r1", "call-1", "leave_submit"),
                        new ToolResultTextDeltaEvent("r1", "call-2", "leave_submit", "已提交, 单号 L-88"),
                        new ToolResultTextDeltaEvent("r1", "call-1", "leave_submit", "已提交, 单号 L-77"),
                        new ToolResultEndEvent("r1", "call-2", "leave_submit", ToolResultState.SUCCESS),
                        new ToolResultEndEvent("r1", "call-1", "leave_submit", ToolResultState.SUCCESS))),
                Flux.just(new AgentResultEvent(AssistantMessage.builder()
                        .content(TextBlock.builder().text("两张单都提交了").build())
                        .build())))).blockLast();
        root.end();

        SpanData batch = span(TOOL_BATCH_SPAN);
        JSONArray inputs = JSONUtil.parseArray(batch.getAttributes().get(AgentTraceAttributes.OBSERVATION_INPUT));
        assertThat(inputs.getJSONObject(0).getInt("callIndex")).isZero();
        assertThat(inputs.getJSONObject(0).getStr("toolCallId")).isEqualTo("call-1");
        assertThat(inputs.getJSONObject(1).getStr("toolCallId")).isEqualTo("call-2");

        JSONArray outputs = JSONUtil.parseArray(batch.getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT));
        assertThat(outputs.getJSONObject(0).getStr("toolCallId")).isEqualTo("call-1");
        assertThat(outputs.getJSONObject(0).getStr("result")).isEqualTo("已提交, 单号 L-77");
        assertThat(outputs.getJSONObject(1).getStr("toolCallId")).isEqualTo("call-2");
        assertThat(outputs.getJSONObject(1).getStr("result")).isEqualTo("已提交, 单号 L-88");
        // 名字一样，出入参靠位置对齐就得靠这两个字段自证没错位
        assertThat(outputs.getJSONObject(0).getStr("name")).isEqualTo(outputs.getJSONObject(1).getStr("name"));
        // 一次 acting 就是一批，批次号取自事实源，前端那侧的批耗时才对得上这一个 span 的区间
        assertThat(batch.getAttributes().get(RagentAttributes.TOOL_BATCH_ID)).isEqualTo(BATCH_ID);
        assertThat(outputs.getJSONObject(0).getStr("batchId")).isEqualTo(BATCH_ID);
        assertThat(outputs.getJSONObject(1).getStr("batchId")).isEqualTo(BATCH_ID);
    }

    /**
     * 确认卡上只列要确认的那几条，同批其余工具一样一步没跑：漏掉一条就成了「那条已经成功」
     */
    @Test
    void shouldMarkWholeBatchAwaitingEvenWhenConfirmEventListsOnlyOne() {
        RuntimeContext ctx = askContext();
        ToolUseBlock write = leaveSubmit("call-1", "张三");
        ToolUseBlock read = searchCall("call-2");

        Span root = startRoot();
        underRoot(root, ctx, List.of(new UserMessage("查下余额再帮我请假")), input -> Flux.concat(
                acting(ctx, List.of(write, read), Flux.just(
                        new RequireUserConfirmEvent("r1", List.of(write)),
                        new RequestStopEvent("permission asking", GenerateReason.PERMISSION_ASKING))),
                Flux.just(new AgentResultEvent(AssistantMessage.builder()
                        .content(leaveSubmit().withState(ToolCallState.ASKING))
                        .generateReason(GenerateReason.PERMISSION_ASKING)
                        .build())))).blockLast();
        root.end();

        SpanData batch = span(TOOL_BATCH_SPAN);
        JSONArray outputs = JSONUtil.parseArray(batch.getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT));
        assertThat(outputs).hasSize(2);
        assertThat(outputs.stream().map(item -> ((JSONObject) item).getStr("state")))
                .containsOnly(AgentToolStatus.AWAITING.value());
        assertThat(outputs.stream().map(item -> ((JSONObject) item).getBool("executed"))).containsOnly(false);
        // 归批只说明这两条同属一次 acting，跑没跑由 executed 与缺失的起止说了算
        assertThat(outputs.stream().map(item -> ((JSONObject) item).getStr("batchId"))).containsOnly(BATCH_ID);
        assertThat(batch.getAttributes().get(RagentAttributes.TOOL_BATCH_OUTCOME))
                .isEqualTo(RagentAttributes.OUTCOME_AWAITING);
    }

    /**
     * 规则直接拒掉的那条与同批真跑的那条共用一个批次号：它们本来就是一次 acting 里的同一批
     * 分不清的是「跑没跑」，那由 executed 回答，不该靠拆批次号来表达
     */
    @Test
    void shouldNotGiveRuleDeniedToolAnExecutionWindow() {
        RuntimeContext ctx = askContext();
        ToolUseBlock refused = leaveSubmit("call-1", "张三");
        ToolUseBlock allowed = searchCall("call-2");

        Span root = startRoot();
        underRoot(root, ctx, List.of(new UserMessage("查下余额再帮我请假")), input -> Flux.concat(
                acting(ctx, List.of(refused, allowed), Flux.concat(
                        // 拒绝也会发开头事件，只凭它判执行就会把一步没跑的那条算成跑过
                        Flux.just(new ToolResultStartEvent("r1", "call-1", "leave_submit"),
                                new ToolResultEndEvent("r1", "call-1", "leave_submit", ToolResultState.DENIED)),
                        executed("call-2",
                                new ToolResultStartEvent("r1", "call-2", "search_knowledge"),
                                new ToolResultTextDeltaEvent("r1", "call-2", "search_knowledge", "余额 5 天"),
                                new ToolResultEndEvent("r1", "call-2", "search_knowledge", ToolResultState.SUCCESS)))),
                Flux.just(new AgentResultEvent(AssistantMessage.builder()
                        .content(TextBlock.builder().text("请假被拒了").build())
                        .build())))).blockLast();
        root.end();

        SpanData batch = span(TOOL_BATCH_SPAN);
        assertThat(batch.getAttributes().get(RagentAttributes.TOOL_BATCH_ID)).isEqualTo(BATCH_ID);
        // 一条真跑过，批级结局就不能报「整批被拒」，否则已经落地的写操作会被读成没发生
        assertThat(batch.getAttributes().get(RagentAttributes.TOOL_BATCH_OUTCOME))
                .isEqualTo(RagentAttributes.OUTCOME_EXECUTED);
        JSONArray outputs = JSONUtil.parseArray(batch.getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT));
        assertThat(outputs.getJSONObject(0).getStr("batchId")).isEqualTo(BATCH_ID);
        assertThat(outputs.getJSONObject(0).getStr("state")).isEqualTo(AgentToolStatus.DENIED.value());
        // 拒绝是框架合成的结果，工具本体一步没跑，开头事件发过也不算执行
        assertThat(outputs.getJSONObject(0).getBool("executed")).isFalse();
        assertThat(outputs.getJSONObject(1).getStr("batchId")).isEqualTo(BATCH_ID);
        assertThat(outputs.getJSONObject(1).getBool("executed")).isTrue();
        // 结局混着，只报一句「全被拒」会把真跑过的那条也算进去
        assertThat(batch.getAttributes().get(AgentTraceAttributes.STATUS_MESSAGE)).isEqualTo("leave_submit#0=denied");
    }

    /**
     * 框架在进入工具体之前就否掉的那条：开头事件照发、结果也有，但工具本体一步没跑
     * 光凭事件判执行，它会在批产出里报成跑过，与批级结局各执一词
     */
    @Test
    void shouldNotCountShortCircuitedCallAsExecuted() {
        RuntimeContext ctx = askContext();

        Span root = startRoot();
        underRoot(root, ctx, List.of(new UserMessage("帮我请假")), input -> Flux.concat(
                acting(ctx, List.of(leaveSubmit()), Flux.just(
                        new ToolResultStartEvent("r1", "call-1", "leave_submit"),
                        new ToolResultEndEvent("r1", "call-1", "leave_submit", ToolResultState.ERROR))),
                Flux.just(new AgentResultEvent(AssistantMessage.builder()
                        .content(TextBlock.builder().text("这个工具没能调起来").build())
                        .build())))).blockLast();
        root.end();

        SpanData batch = span(TOOL_BATCH_SPAN);
        JSONObject item = JSONUtil.parseArray(batch.getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT))
                .getJSONObject(0);
        assertThat(item.getStr("state")).isEqualTo(AgentToolStatus.FAILED.value());
        // 没有工具体记录就不算跑过，写操作根本没发出去，记成跑过会让人以为单据已经提交
        assertThat(item.getBool("executed")).isFalse();
    }

    /**
     * 强制断流时官方那层先收走批 span，之后的补写全是空操作：故每次状态变化都当场落一遍，
     * 留下的是最后一次落地的状态而非空输出，「入参齐全、输出为空」才是最容易被读成静默成功的那种
     */
    @Test
    void shouldKeepLastLandedToolStateWhenCancelled() {
        RuntimeContext ctx = askContext();

        Span root = startRoot();
        underRoot(root, ctx, List.of(new UserMessage("帮我请假")), input -> acting(ctx, List.of(leaveSubmit()),
                executed("call-1", new ToolResultStartEvent("r1", "call-1", "leave_submit"),
                        new ToolResultTextDeltaEvent("r1", "call-1", "leave_submit", "提交中"))
                        .concatWith(Flux.never())))
                .doOnCancel(root::end)
                .take(2)
                .blockLast();

        SpanData batch = span(TOOL_BATCH_SPAN);
        JSONObject item = JSONUtil.parseArray(batch.getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT))
                .getJSONObject(0);
        // 停在开跑那一刻的状态，不是 done：断流处没有任何结果，判成成功就是凭空编一个结局
        assertThat(item.getStr("state")).isEqualTo(AgentToolStatus.RUNNING.value());
        // 已经开跑，写操作可能已经发出去了，记成没执行就是漏账
        assertThat(item.getBool("executed")).isTrue();
        assertThat(item.getStr("batchId")).isEqualTo(BATCH_ID);
    }

    /**
     * 失败路径根本不发 AgentResultEvent，正文只能靠 error 回调补完，否则整条 trace 只剩一句报错
     */
    @Test
    void shouldFlushPartialAnswerWhenStreamFails() {
        RuntimeContext ctx = askContext();

        Span root = startRoot();
        underRoot(root, ctx, List.of(new UserMessage("北京明天天气")), input -> Flux.concat(
                        Flux.just(new TextBlockDeltaEvent("r1", "b1", "北京明天")),
                        Flux.error(new IllegalStateException("模型返回 402"))))
                .onErrorComplete()
                .blockLast();
        root.end();

        SpanData rootData = span(ROOT_SPAN);
        assertThat(rootData.getAttributes().get(AgentTraceAttributes.LEVEL))
                .isEqualTo(AgentTraceAttributes.LEVEL_ERROR);
        assertThat(rootData.getAttributes().get(AgentTraceAttributes.STATUS_MESSAGE)).isEqualTo("模型返回 402");
        // 失败与中断在 Langfuse 上都是一条红 trace，结局属性是唯一能把两者分开的字段
        assertThat(rootData.getAttributes().get(RagentAttributes.RUN_OUTCOME))
                .isEqualTo(RagentAttributes.RUN_OUTCOME_FAILED);
        // 没到刷新阈值也没有块结束事件，这段正文只可能是 error 回调补上的
        assertThat(rootOutput().getStr("answer")).isEqualTo("北京明天");
        assertThat(rootOutput().getStr("generate_reason")).isNull();
    }

    /**
     * 强制断流：取消自外向内传，官方那层先收根 span，正文最多落后一个刷新阈值
     */
    @Test
    void shouldLoseAtMostOneFlushWindowWhenCancelled() {
        RuntimeContext ctx = askContext();
        String chunk = "答".repeat(512);

        Span root = startRoot();
        underRoot(root, ctx, List.of(new UserMessage("写一篇长文")), input -> Flux
                        .<AgentEvent>just(new TextBlockDeltaEvent("r1", "b1", chunk),
                                new TextBlockDeltaEvent("r1", "b1", chunk),
                                new TextBlockDeltaEvent("r1", "b1", chunk))
                        .concatWith(Flux.never()))
                // 官方中间件在我们下游，取消时它先 end 掉根 span，之后的补写全是空操作
                .doOnCancel(root::end)
                .take(3)
                .blockLast();

        String answer = rootOutput().getStr("answer");
        // 前两段攒够 1024 触发过一次刷新，第三段还没攒够就被取消掐断
        assertThat(answer).hasSize(chunk.length() * 2);
        assertThat(chunk.length() * 3 - answer.length()).isLessThan(1024);
    }

    /**
     * 内层还有几个中间件的 onReasoning 是即时执行的，它们抛出时本层的收尾回调一个都还没挂上
     * 不当场收口这条 span 就永不 end，也就永不导出：那一轮在链路上整个消失，子节点跟着变孤儿
     */
    @Test
    void shouldEndReasoningSpanWhenInnerMiddlewareThrowsAtAssembly() {
        RuntimeContext ctx = askContext();
        List<Msg> msgs = List.of(new UserMessage("问题"));

        Span root = startRoot();
        assertThatThrownBy(() -> underRoot(root, ctx, msgs, agentInput ->
                middleware.onReasoning(null, ctx, new ReasoningInput(msgs, List.of(), null), ignored -> {
                    throw new IllegalStateException("内层中间件装配期抛出");
                })).blockLast())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("内层中间件装配期抛出");
        root.end();

        // 只有 end 过的 span 才进得了导出器，能查到就说明它没被漏在半开状态
        assertThat(reasoningSpans()).hasSize(1);
        assertThat(span("reasoning").getAttributes().get(RagentAttributes.ROUND_OUTCOME))
                .isEqualTo(RagentAttributes.RUN_OUTCOME_FAILED);
        // 异常照旧往外抛，本层只负责收 span，不替业务把错吞掉
        assertThat(span(ROOT_SPAN).getAttributes().get(AgentTraceAttributes.LEVEL))
                .isEqualTo(AgentTraceAttributes.LEVEL_ERROR);
    }

    /**
     * 打满迭代上限是个结局而不是异常，不标级别在 LangFuse 里与正常收尾长得一样
     */
    @Test
    void shouldMarkWarningWhenIterationsExhausted() {
        RuntimeContext ctx = askContext();

        Span root = startRoot();
        underRoot(root, ctx, List.of(new UserMessage("循环任务")),
                input -> Flux.just(new ExceedMaxItersEvent("r1", 12, 12))).blockLast();
        root.end();

        assertThat(span(ROOT_SPAN).getAttributes().get(AgentTraceAttributes.LEVEL))
                .isEqualTo(AgentTraceAttributes.LEVEL_WARNING);
        assertThat(span(ROOT_SPAN).getAttributes().get(AgentTraceAttributes.STATUS_MESSAGE))
                .isEqualTo("Agent ReAct循环达到迭代上限，迭代次数：12");
    }

    /**
     * 官方那层若换实现、不再把 span 写进 Reactor 上下文，各钩子必须原样放行
     */
    @Test
    void shouldPassThroughWhenRootSpanMissing() {
        RuntimeContext ctx = askContext();

        List<AgentEvent> events = middleware
                .onAgent(null, ctx, new AgentInput(List.of(new UserMessage("问题"))),
                        input -> Flux.just(new TextBlockDeltaEvent("r1", "b1", "答")))
                .collectList()
                .block();

        assertThat(events).hasSize(1);
        // 没有父亲就不建轮次 span，孤儿 span 到了 LangFuse 是另一条 trace
        assertThat(exported).isEmpty();
    }

    /**
     * 入参是逐片流过来的，收尾事件只带 id 与名字：不拼这几片，chat 上就只剩一份「调过谁」的清单，
     * 读不出模型究竟请求了什么，而这段生成实打实计进了本次 completion 的耗时与 output_tokens
     */
    @Test
    void shouldAccumulateStreamedToolArgumentsOnChatSpan() {
        RuntimeContext ctx = askContext();

        driveModelCall(ctx, Flux.just(
                new ToolCallDeltaEvent("r1", "call-1", "search_knowledge", "{\"query\":"),
                new ToolCallDeltaEvent("r1", "call-1", "search_knowledge", "\"半天年假\"}"),
                new ToolCallEndEvent("r1", "call-1", "search_knowledge")));

        JSONObject call = chatToolCalls().getJSONObject(0);
        assertThat(call.getStr("name")).isEqualTo("search_knowledge");
        // 两片必须首尾相接：ToolCallDeltaEvent 给的是增量，覆盖式写入只会剩后半截
        assertThat(call.getJSONObject("arguments").getStr("query")).isEqualTo("半天年假");
    }

    /**
     * 同名工具并行调两次，靠 toolCallId 各攒各的；混在一起会拼出一段谁也解析不出的 JSON
     */
    @Test
    void shouldKeepArgumentsApartForParallelCallsOfSameTool() {
        RuntimeContext ctx = askContext();

        driveModelCall(ctx, Flux.just(
                new ToolCallDeltaEvent("r1", "call-1", "load_skill", "{\"skill_code\":"),
                new ToolCallDeltaEvent("r1", "call-2", "load_skill", "{\"skill_code\":"),
                new ToolCallDeltaEvent("r1", "call-1", "load_skill", "\"leave_apply\"}"),
                new ToolCallDeltaEvent("r1", "call-2", "load_skill", "\"meeting_room_booking\"}"),
                new ToolCallEndEvent("r1", "call-1", "load_skill"),
                new ToolCallEndEvent("r1", "call-2", "load_skill")));

        JSONArray calls = chatToolCalls();
        assertThat(calls.getJSONObject(0).getJSONObject("arguments").getStr("skill_code"))
                .isEqualTo("leave_apply");
        assertThat(calls.getJSONObject(1).getJSONObject("arguments").getStr("skill_code"))
                .isEqualTo("meeting_room_booking");
    }

    /**
     * 拼出来的是 JSON 原文，完成后要恢复成结构化参数，并保留原始字段值
     */
    @Test
    void shouldKeepValuesInStreamedToolArguments() {
        RuntimeContext ctx = askContext();

        driveModelCall(ctx, Flux.just(
                new ToolCallDeltaEvent("r1", "call-1", "login", "{\"user\":\"tom\",\"password\":\"p@ss\"}"),
                new ToolCallEndEvent("r1", "call-1", "login")));

        JSONObject arguments = chatToolCalls().getJSONObject(0).getJSONObject("arguments");
        assertThat(arguments.getStr("user")).isEqualTo("tom");
        assertThat(arguments.getStr("password")).isEqualTo("p@ss");
    }

    /**
     * 流断在半路时用明确占位表示参数不完整，避免把半截 JSON 当成正常对象
     */
    @Test
    void shouldMarkArgumentsMalformedWhenStreamBreaksMidJson() {
        RuntimeContext ctx = askContext();

        driveModelCall(ctx, Flux.just(
                new ToolCallDeltaEvent("r1", "call-1", "login", "{\"password\":\"p@ss"),
                new ToolCallEndEvent("r1", "call-1", "login")));

        assertThat(chatToolCalls().getJSONObject(0).getStr("arguments")).isEqualTo("<malformed-json>");
        assertThat(attributeValues()).allSatisfy(value -> assertThat(value).doesNotContain("p@ss"));
    }

    /**
     * 打开内容采集后每层都得有自己那份，缺一层在 UI 上就是一个空节点
     * 执行体那层的 span 建在中间件链之外，由 AgentTraceScenarioTest 盯着
     */
    @Test
    void shouldWriteOwnContentOnRootChatAndBatchWhenCaptureEnabled() {
        RuntimeContext ctx = askContext();

        driveTwoRounds(ctx);

        assertThat(span(ROOT_SPAN).getAttributes().get(AgentTraceAttributes.OBSERVATION_INPUT))
                .contains("北京明天天气");
        assertThat(span(ROOT_SPAN).getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT))
                .contains("北京明天多云");
        assertThat(span("chat").getAttributes().get(AgentTraceAttributes.OBSERVATION_INPUT))
                .contains("北京明天天气");
        assertThat(span("chat").getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT))
                .contains("先查天气");
        assertThat(span(TOOL_BATCH_SPAN).getAttributes().get(AgentTraceAttributes.OBSERVATION_INPUT))
                .contains("search_knowledge").contains("\"callIndex\":0");
        assertThat(span(TOOL_BATCH_SPAN).getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT))
                .contains("多云");
    }

    /**
     * 关掉内容采集后逐 span 扫全树：一段业务原文都不许出现在任何一个属性上
     * 只挑几个点验没意义，这道闸门要防的正是「某个节点漏了一条通道」
     */
    @Test
    void shouldWriteNoContentAttributeAnywhereWhenCaptureDisabled() {
        middleware = new AgentTraceEnrichmentMiddleware(tracer, serializer(false));
        RuntimeContext ctx = askContext();

        driveTwoRounds(ctx);

        for (SpanData data : exported) {
            assertThat(data.getAttributes().get(AgentTraceAttributes.OBSERVATION_INPUT))
                    .as("%s 不该带 input", data.getName()).isNull();
            assertThat(data.getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT))
                    .as("%s 不该带 output", data.getName()).isNull();
        }
        // 提问、正文、思考、工具结果，任何一段都不许从别的键上渗出来
        assertThat(attributeValues()).allSatisfy(value -> assertThat(value)
                .doesNotContain("北京明天天气", "北京明天多云", "先查天气", "多云"));

        // 结构、轮次与用量照旧：连这些一起没了就不叫关内容，叫关观测
        assertThat(span("chat").getAttributes().get(AgentTraceAttributes.USAGE_DETAILS))
                .contains("\"input_cached_tokens\":20");
        assertThat(span(ROOT_SPAN).getAttributes().get(RagentAttributes.REACT_ROUNDS)).isEqualTo(2L);
        assertThat(span(ROOT_SPAN).getAttributes().get(AgentTraceAttributes.META_TOOL_DEFINITIONS))
                .contains("search_knowledge");
        assertThat(span(TOOL_BATCH_SPAN).getAttributes().get(RagentAttributes.REACT_ROUND_INDEX)).isEqualTo(1L);
    }

    /**
     * 内容采集开关由同一个序列化器控制
     */
    private AgentTraceSerializer serializer(boolean captureContent) {
        return new AgentTraceSerializer(4096, 16_384, captureContent);
    }

    /**
     * 全树属性值摊平成字符串，供内容闸门做穷举扫描
     */
    private List<String> attributeValues() {
        List<String> values = new ArrayList<>();
        for (SpanData data : exported) {
            data.getAttributes().forEach((key, value) -> values.add(String.valueOf(value)));
        }
        return values;
    }

    /**
     * 两轮 ReAct：首轮推理后调一次工具，次轮出正文并给出最终消息
     */
    private void driveTwoRounds(RuntimeContext ctx) {
        ToolSchema tool = ToolSchema.builder().name("search_knowledge").description("检索知识库").build();
        List<Msg> firstRound = List.of(new UserMessage("北京明天天气"));
        List<Msg> secondRound = List.of(new UserMessage("北京明天天气"), new AssistantMessage("已查到天气"));
        ChatUsage usage = ChatUsage.builder().inputTokens(120).outputTokens(30).cachedTokens(20).build();
        Msg finalMsg = AssistantMessage.builder()
                .content(TextBlock.builder().text("北京明天多云").build())
                .generateReason(GenerateReason.MODEL_STOP)
                .build();

        Span root = startRoot();
        underRoot(root, ctx, firstRound, input -> Flux.concat(
                reasoningRound(ctx, firstRound, List.of(tool), modelCall(ctx, firstRound, Flux.just(
                        new ThinkingBlockDeltaEvent("r1", "t1", "先查"),
                        new ThinkingBlockDeltaEvent("r1", "t1", "天气"),
                        new ThinkingBlockEndEvent("r1", "t1"),
                        new ModelCallEndEvent("r1", usage)))),
                acting(ctx, List.of(searchCall()), Flux.just(
                        new ToolResultStartEvent("r1", "call-1", "search_knowledge"),
                        new ToolResultTextDeltaEvent("r1", "call-1", "search_knowledge", "多云"),
                        new ToolResultEndEvent("r1", "call-1", "search_knowledge", ToolResultState.SUCCESS))),
                reasoningRound(ctx, secondRound, List.of(tool), modelCall(ctx, secondRound, Flux.just(
                        new TextBlockDeltaEvent("r2", "b1", "北京"),
                        new TextBlockDeltaEvent("r2", "b1", "明天多云"),
                        new TextBlockEndEvent("r2", "b1")))),
                Flux.just(new AgentResultEvent(finalMsg)))).blockLast();
        root.end();
    }

    /**
     * 只跑一轮模型调用：入参那几条断言不关心工具执行与后续轮次，多铺一层反而看不清是谁写的属性
     */
    private void driveModelCall(RuntimeContext ctx, Flux<AgentEvent> events) {
        List<Msg> msgs = List.of(new UserMessage("问题"));
        Span root = startRoot();
        underRoot(root, ctx, msgs, input ->
                reasoningRound(ctx, msgs, List.of(), modelCall(ctx, msgs, events))).blockLast();
        root.end();
    }

    /**
     * chat span 上那份名册，入参断言一律从这里取：换成整串包含匹配就分不清值落在哪个键上
     */
    private JSONArray chatToolCalls() {
        JSONObject output = JSONUtil.parseObj(
                span("chat").getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT));
        return output.getJSONArray("tool_calls");
    }

    private Span startRoot() {
        return tracer.spanBuilder(ROOT_SPAN).startSpan();
    }

    /**
     * 复刻官方那层：建根 span 后用 runWithContext 写进 Reactor 上下文，内层才取得到
     */
    private Flux<AgentEvent> underRoot(Span root, RuntimeContext ctx, List<Msg> msgs,
                                       Function<AgentInput, Flux<AgentEvent>> next) {
        return ContextPropagationOperator.runWithContext(
                middleware.onAgent(null, ctx, new AgentInput(msgs), next), root.storeInContext(Context.root()));
    }

    private Flux<AgentEvent> reasoningRound(RuntimeContext ctx, List<Msg> messages, List<ToolSchema> tools,
                                            Flux<AgentEvent> events) {
        return middleware.onReasoning(null, ctx, new ReasoningInput(messages, tools, null), input -> events);
    }

    /**
     * 官方在轮次里建 chat span，模型调用的属性落在它身上而非轮次 span
     */
    private Flux<AgentEvent> modelCall(RuntimeContext ctx, List<Msg> messages, Flux<AgentEvent> events) {
        return childSpan("chat", () -> middleware.onModelCall(null, ctx,
                new ModelCallInput(messages, List.of(), null, null), input -> events));
    }

    /**
     * 生产的三层洋葱：外层建批 span（父亲是根而非轮次）、本中间件补内容、最内层开批发号
     */
    private Flux<AgentEvent> acting(RuntimeContext ctx, List<ToolUseBlock> calls, Flux<AgentEvent> events) {
        return tracingMiddleware.onActing(null, ctx, new ActingInput(calls),
                outer -> middleware.onActing(null, ctx, outer,
                        inner -> batchMiddleware.onActing(null, ctx, inner, ignored -> events)));
    }

    /**
     * 复刻真进过工具体的那条：起始时刻由 AgentToolBodyTracer 写进事实源，事件流本身给不出这个凭据
     */
    private Flux<AgentEvent> executed(String toolCallId, AgentEvent... events) {
        return Flux.defer(() -> {
            facts.markStarted(toolCallId);
            return Flux.just(events);
        });
    }

    /**
     * 收尾回调挂在中间件返回的流之后，与官方那层同一处：故完成时本中间件先写、取消时官方先收 span
     * 用 doFinally 会把这个次序反过来，取消路径上的断言就成了永远为真
     */
    private Flux<AgentEvent> childSpan(String name, Supplier<Flux<AgentEvent>> body) {
        return Flux.deferContextual(view -> {
            Context parent = ContextPropagationOperator.getOpenTelemetryContextFromContextView(view, Context.current());
            Span span = tracer.spanBuilder(name).setParent(parent).startSpan();
            AtomicBoolean ended = new AtomicBoolean();
            Runnable end = () -> {
                if (ended.compareAndSet(false, true)) {
                    span.end();
                }
            };
            return ContextPropagationOperator.runWithContext(
                    body.get().doOnComplete(end).doOnError(e -> end.run()).doOnCancel(end),
                    span.storeInContext(parent));
        });
    }

    private RuntimeContext askContext() {
        RuntimeContext ctx = RuntimeContext.builder().userId(USER_ID).sessionId(CONVERSATION_ID).build();
        ctx.put(AgentTraceContextKeys.TASK_ID, TASK_ID);
        ctx.put(AgentTraceContextKeys.REPLY_TO_MESSAGE_ID, REPLY_TO_MESSAGE_ID);
        ctx.put(AgentToolExecutionFacts.RUNTIME_CONTEXT_KEY, facts);
        return ctx;
    }

    private static ToolUseBlock searchCall() {
        return searchCall("call-1");
    }

    private static ToolUseBlock searchCall(String id) {
        return ToolUseBlock.builder().id(id).name("search_knowledge")
                .input(Map.of("query", "北京明天天气")).build();
    }

    private static ToolUseBlock leaveSubmit() {
        return leaveSubmit("call-1", "张三");
    }

    /**
     * 同名同工具、只有请假人不同，是并发批里最难对齐的那种：位置错一位就把两个人的单据说反
     */
    private static ToolUseBlock leaveSubmit(String id, String employeeName) {
        return ToolUseBlock.builder().id(id).name("leave_submit")
                .input(Map.of("employeeName", employeeName, "day", "2026-09-14")).build();
    }

    private JSONObject rootOutput() {
        return JSONUtil.parseObj(span(ROOT_SPAN).getAttributes().get(AgentTraceAttributes.OBSERVATION_OUTPUT));
    }

    private List<String> spanNames() {
        return exported.stream().map(SpanData::getName).toList();
    }

    /**
     * 轮次 span 已是同名的一族，按导出顺序取才分得出第几轮
     */
    private List<SpanData> reasoningSpans() {
        return exported.stream().filter(data -> "reasoning".equals(data.getName())).toList();
    }

    /**
     * 同名 span 有好几个，按名字加出现序号做键，才能断言「第几个 reasoning 是第几轮」
     */
    private Map<String, Long> roundIndexes() {
        Map<String, Long> indexes = new LinkedHashMap<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (SpanData data : exported) {
            Long round = data.getAttributes().get(RagentAttributes.REACT_ROUND_INDEX);
            if (round != null) {
                int ordinal = seen.merge(data.getName(), 0, (old, ignored) -> old + 1);
                indexes.put(data.getName() + "#" + ordinal, round);
            }
        }
        return indexes;
    }

    private SpanData span(String name) {
        return exported.stream()
                .filter(data -> name.equals(data.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未导出名为 " + name + " 的 span, 实际: " + spanNames()));
    }

    /**
     * 只收 span 不发网络，属性快照在 end 那一刻定格，与真实上报看到的是同一份
     */
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

    private record ScriptedStream(List<String> data) implements HttpTransport {

        @Override
        public HttpResponse execute(HttpRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<String> stream(HttpRequest request) {
            return Flux.fromIterable(data);
        }

        @Override
        public void close() {
        }
    }
}
