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

import com.nageoffer.ai.ragent.agent.tool.AgentToolBatchMiddleware;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import com.nageoffer.ai.ragent.rag.config.InputGuardProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.model.ToolSchema;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 一次性读回探针：把一棵完整的树打到本机 LangFuse，跑完即删，不属于回归集
 */
class LangfuseReadbackProbe {

    private static final String ENDPOINT = "http://localhost:3000/api/public/otel/v1/traces";
    private static final String SERVICE = "ragent-service-plan-d-probe";
    private static final String SESSION = "probe-conv-planD";

    @Test
    void emit() throws Exception {
        GlobalOpenTelemetry.resetForTest();
        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(ENDPOINT)
                .addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        "pk-lf-ragent-local:sk-lf-ragent-local".getBytes(StandardCharsets.UTF_8)))
                .addHeader("x-langfuse-ingestion-version", "4")
                .build();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(Resource.getDefault().toBuilder()
                        .put(AttributeKey.stringKey("service.name"), SERVICE).build())
                .addSpanProcessor(SimpleSpanProcessor.builder(exporter).build())
                .build();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder().setTracerProvider(provider).build());

        ContextPropagationOperator hook = ContextPropagationOperator.builder().build();
        hook.registerOnEachOperator();

        Tracer tracer = provider.get("probe");
        AgentTraceSerializer serializer = new AgentTraceSerializer(32768, 131072, true);
        AgentToolBodyTracer.configure(serializer);
        AgentTraceEnrichmentMiddleware enrichment = new AgentTraceEnrichmentMiddleware(tracer, serializer, InputGuardProperties.Type.LLM);
        RagentOtelTracingMiddleware tracing = new RagentOtelTracingMiddleware(tracer);
        AgentToolBatchMiddleware batching = new AgentToolBatchMiddleware();

        AgentToolExecutionFacts facts = new AgentToolExecutionFacts("probe-task", Clock.systemUTC());
        RuntimeContext ctx = RuntimeContext.builder().userId("probe-user").sessionId(SESSION).build();
        ctx.put(AgentTraceContextKeys.TASK_ID, "probe-task");
        ctx.put(AgentToolExecutionFacts.RUNTIME_CONTEXT_KEY, facts);

        ToolSchema schema = ToolSchema.builder().name("leave_submit").description("提交请假申请").build();
        List<Msg> round1 = List.of(new UserMessage("帮张三和李四各请一天事假"));
        List<Msg> round2 = List.of(new UserMessage("帮张三和李四各请一天事假"),
                new AssistantMessage("两张单都已提交"));
        ChatUsage usage = ChatUsage.builder().inputTokens(860).outputTokens(120).cachedTokens(64).build();
        Msg finalMsg = AssistantMessage.builder()
                .content(TextBlock.builder().text("张三与李四的事假单都已提交, 单号分别是 LV-8801 与 LV-8802").build())
                .generateReason(GenerateReason.MODEL_STOP)
                .build();

        Call zhang = new Call("call-A", "leave_submit", "张三", "LV-8801", ctx);
        Call li = new Call("call-B", "leave_submit", "李四", "LV-8802", ctx);
        List<ToolUseBlock> calls = List.of(zhang.use(), li.use());

        Span root = tracer.spanBuilder("invoke_agent ragent").startSpan();
        ContextPropagationOperator.runWithContext(
                enrichment.onAgent(null, ctx, new AgentInput(round1), input -> Flux.concat(
                        enrichment.onReasoning(null, ctx, new ReasoningInput(round1, List.of(schema), null),
                                in -> childSpan(tracer, "chat", () -> enrichment.onModelCall(null, ctx,
                                        new ModelCallInput(round1, List.of(), null, null),
                                        mi -> Flux.just(
                                                new ThinkingBlockDeltaEvent("r1", "t1", "两个人各一张单"),
                                                new ThinkingBlockEndEvent("r1", "t1"),
                                                new ModelCallEndEvent("r1", usage))))),
                        tracing.onActing(null, ctx, new ActingInput(calls),
                                outer -> enrichment.onActing(null, ctx, outer,
                                        inner -> batching.onActing(null, ctx, inner,
                                                ignored -> Flux.merge(zhang.body(120), li.body(40))))),
                        enrichment.onReasoning(null, ctx, new ReasoningInput(round2, List.of(schema), null),
                                in -> childSpan(tracer, "chat", () -> enrichment.onModelCall(null, ctx,
                                        new ModelCallInput(round2, List.of(), null, null),
                                        mi -> Flux.just(
                                                new TextBlockDeltaEvent("r2", "b1", "张三与李四的事假单都已提交"),
                                                new TextBlockEndEvent("r2", "b1"),
                                                new ModelCallEndEvent("r2", usage))))),
                        Flux.just(new AgentResultEvent(finalMsg)))),
                root.storeInContext(Context.root())).blockLast();
        root.end();

        provider.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
        provider.close();
        hook.resetOnEachOperator();
        AgentToolBodyTracer.configure(null);
        GlobalOpenTelemetry.resetForTest();
        System.out.println("PROBE_TRACE_ID=" + root.getSpanContext().getTraceId());
    }

    private static Flux<AgentEvent> childSpan(Tracer tracer, String name, Supplier<Flux<AgentEvent>> body) {
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

    private record Call(String id, String name, String employee, String orderNo, RuntimeContext ctx) {

        private ToolUseBlock use() {
            return ToolUseBlock.builder().id(id).name(name)
                    .input(Map.of("employeeName", employee, "day", "2026-09-14")).build();
        }

        private Flux<AgentEvent> body(long millis) {
            return Flux.<AgentEvent>just(new ToolResultStartEvent("r1", id, name))
                    .concatWith(AgentToolBodyTracer.trace(new Fake(name), param(),
                                    () -> Mono.delay(java.time.Duration.ofMillis(millis)).map(t -> block()))
                            .map(block -> new ToolResultEndEvent("r1", id, name, block.getState())));
        }

        private ToolCallParam param() {
            return ToolCallParam.builder().toolUseBlock(use())
                    .input(Map.of("employeeName", employee, "reason", "事假",
                            "auth", Map.of("token", "probe-token")))
                    .runtimeContext(ctx).build();
        }

        private ToolResultBlock block() {
            return ToolResultBlock.builder().id(id).name(name)
                    .output(TextBlock.builder().text("已受理, 单号 " + orderNo).build())
                    .state(ToolResultState.SUCCESS).build();
        }
    }

    private record Fake(String toolName) implements AgentTool {

        @Override
        public String getName() {
            return toolName;
        }

        @Override
        public String getDescription() {
            return "提交请假申请";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            throw new UnsupportedOperationException();
        }
    }

    @SuppressWarnings("unused")
    private static Function<AgentInput, Flux<AgentEvent>> unusedMarker() {
        return input -> Flux.empty();
    }
}
