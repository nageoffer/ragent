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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 摘要调用不经过中间件链，这里钉住它在链路上的样子：挂在本次运行的根下、带会话身份与用量
 * 用量断言按 LangFuse 互斥口径：input 已扣掉缓存，两项相加才是输入总量
 */
class AgentSummaryTracerTest {

    private static final String MODEL = "deepseek-flash";

    private List<SpanData> exported;
    private SdkTracerProvider tracerProvider;
    private RuntimeContext ctx;

    @BeforeEach
    void setUp() {
        GlobalOpenTelemetry.resetForTest();
        exported = Collections.synchronizedList(new ArrayList<>());
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.builder(new CollectingExporter(exported)).build())
                .build();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());
        ctx = RuntimeContext.builder().userId("u-1001").sessionId("c-2002").build();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void shouldRecordGenerationUnderRootWithIdentityAndUsage() {
        Span root = bindRoot();
        AgentRunTracer.nextRound(ctx);
        List<ChatResponse> responses = List.of(response(1200, 300, 200));

        assertThat(AgentSummaryTracer.trace(ctx, MODEL, () -> responses)).isSameAs(responses);
        root.end();

        SpanData summary = summarySpan();
        assertThat(summary.getParentSpanId()).isEqualTo(root.getSpanContext().getSpanId());
        assertThat(summary.getAttributes().get(AgentTraceAttributes.OBSERVATION_TYPE))
                .isEqualTo(AgentTraceAttributes.TYPE_GENERATION);
        assertThat(summary.getAttributes().get(GenAiAttributes.REQUEST_MODEL)).isEqualTo(MODEL);
        assertThat(summary.getAttributes().get(AgentTraceAttributes.SESSION_ID)).isEqualTo("c-2002");
        assertThat(summary.getAttributes().get(AgentTraceAttributes.USER_ID)).isEqualTo("u-1001");
        assertThat(summary.getAttributes().get(RagentAttributes.REACT_ROUND_INDEX)).isEqualTo(1L);
        assertThat(summary.getAttributes().get(AgentTraceAttributes.USAGE_DETAILS))
                .isEqualTo("{\"input\":1000,\"input_cached_tokens\":200,\"output\":300,\"total\":1500}");
    }

    /**
     * 追踪关掉时上下文里没有根，摘要照常执行，也不去碰全局 OTel
     */
    @Test
    void shouldPassThroughWithoutRoot() {
        List<ChatResponse> responses = List.of(response(10, 5, 0));

        assertThat(AgentSummaryTracer.trace(ctx, MODEL, () -> responses)).isSameAs(responses);

        assertThat(exported).isEmpty();
    }

    /**
     * 调用失败也要收口：节点不 end 就不导出，这次失败的调用会从账本里整个消失
     */
    @Test
    void shouldEndWithErrorAndNoUsageWhenCallFails() {
        Span root = bindRoot();
        IllegalStateException failure = new IllegalStateException("timeout");

        assertThatThrownBy(() -> AgentSummaryTracer.trace(ctx, MODEL, () -> {
            throw failure;
        })).isSameAs(failure);
        root.end();

        SpanData summary = summarySpan();
        assertThat(summary.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(summary.getAttributes().get(AgentErrorTypes.KEY)).isEqualTo(IllegalStateException.class.getName());
        assertThat(summary.getAttributes().get(AgentTraceAttributes.USAGE_DETAILS)).isNull();
    }

    private Span bindRoot() {
        Span root = GlobalOpenTelemetry.getTracer(AgentTracingConfiguration.INSTRUMENTATION_SCOPE)
                .spanBuilder("invoke_agent").startSpan();
        AgentRunTracer.bindRoot(ctx, root);
        return root;
    }

    private SpanData summarySpan() {
        return exported.stream().filter(span -> span.getName().equals("context_compaction"))
                .findFirst().orElseThrow();
    }

    private static ChatResponse response(int input, int output, int cached) {
        return ChatResponse.builder()
                .usage(ChatUsage.builder().inputTokens(input).outputTokens(output).cachedTokens(cached).build())
                .build();
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
