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

package com.nageoffer.ai.ragent.infra.model;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRoutingExecutorTest {

    @Test
    void shouldStopFallbackAndKeepHealthUnchangedWhenThreadIsInterrupted() {
        ModelHealthStore healthStore = mock(ModelHealthStore.class);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(healthStore);
        ModelTarget primary = target("primary", "bailian");
        ModelTarget fallback = target("fallback", "deepseek");
        ModelHealthStore.CallPermit permit = new ModelHealthStore.CallPermit(primary.id(), 7L);
        when(healthStore.allowCall(primary.id())).thenReturn(permit);

        AtomicInteger calls = new AtomicInteger();
        try {
            assertThatThrownBy(() -> executor.executeWithFallback(
                    ModelCapability.CHAT,
                    List.of(primary, fallback),
                    ModelTarget::id,
                    (client, target) -> {
                        calls.incrementAndGet();
                        Thread.currentThread().interrupt();
                        throw new ModelClientException(
                                "request interrupted",
                                ModelClientErrorType.NETWORK_ERROR,
                                null,
                                new InterruptedIOException("interrupted"));
                    }))
                    .isInstanceOf(CancellationException.class);
        } finally {
            // 清除测试线程的中断标记，避免污染其他用例
            Thread.interrupted();
        }

        assertThat(calls).hasValue(1);
        verify(healthStore).releaseHalfOpenPermit(permit);
        verify(healthStore, never()).markFailure(primary.id());
        verify(healthStore, never()).allowCall(fallback.id());
    }

    @Test
    void shouldRecognizeWrappedInterruptedExceptionAndRestoreInterruptFlag() {
        ModelHealthStore healthStore = mock(ModelHealthStore.class);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(healthStore);
        ModelTarget target = target("primary", "deepseek");
        ModelHealthStore.CallPermit permit = new ModelHealthStore.CallPermit(target.id(), 0L);
        when(healthStore.allowCall(target.id())).thenReturn(permit);

        try {
            assertThatThrownBy(() -> executor.executeWithFallback(
                    ModelCapability.CHAT,
                    List.of(target),
                    ModelTarget::id,
                    (client, ignored) -> {
                        throw new UndeclaredThrowableException(new InterruptedException("interrupted"));
                    }))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        verify(healthStore).releaseHalfOpenPermit(permit);
        verify(healthStore, never()).markFailure(target.id());
    }

    @Test
    void shouldPropagateDirectCancellationWithoutFallbackOrFailureMark() {
        ModelHealthStore healthStore = mock(ModelHealthStore.class);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(healthStore);
        ModelTarget primary = target("primary", "bailian");
        ModelTarget fallback = target("fallback", "deepseek");
        ModelHealthStore.CallPermit permit = new ModelHealthStore.CallPermit(primary.id(), 9L);
        when(healthStore.allowCall(primary.id())).thenReturn(permit);

        assertThatThrownBy(() -> executor.executeWithFallback(
                ModelCapability.CHAT,
                List.of(primary, fallback),
                ModelTarget::id,
                (client, ignored) -> {
                    throw new CancellationException("cancelled by client");
                }))
                .isInstanceOf(CancellationException.class);

        verify(healthStore).releaseHalfOpenPermit(permit);
        verify(healthStore, never()).markFailure(primary.id());
        verify(healthStore, never()).allowCall(fallback.id());
    }

    @Test
    void shouldStillFallbackForRealModelFailure() {
        ModelHealthStore healthStore = mock(ModelHealthStore.class);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(healthStore);
        ModelTarget primary = target("primary", "bailian");
        ModelTarget fallback = target("fallback", "deepseek");
        when(healthStore.allowCall(primary.id()))
                .thenReturn(new ModelHealthStore.CallPermit(primary.id(), 0L));
        when(healthStore.allowCall(fallback.id()))
                .thenReturn(new ModelHealthStore.CallPermit(fallback.id(), 0L));

        String result = executor.executeWithFallback(
                ModelCapability.CHAT,
                List.of(primary, fallback),
                ModelTarget::id,
                (client, target) -> {
                    if (target == primary) {
                        throw new IllegalStateException("provider unavailable");
                    }
                    return "ok";
                });

        assertThat(result).isEqualTo("ok");
        verify(healthStore).markFailure(primary.id());
        verify(healthStore).markSuccess(fallback.id());
    }

    /**
     * 审查拒收由请求内容触发，计入熔断的话同一类输入连发两次就能让候选停用
     */
    @Test
    void shouldTryNextCandidateWithoutMarkingFailureWhenContentRejected() {
        ModelHealthStore healthStore = mock(ModelHealthStore.class);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(healthStore);
        ModelTarget primary = target("qwen-flash", "bailian");
        ModelTarget fallback = target("qwen3-local", "ollama");
        ModelHealthStore.CallPermit probe = new ModelHealthStore.CallPermit(primary.id(), 7L);
        when(healthStore.allowCall(primary.id())).thenReturn(probe);
        when(healthStore.allowCall(fallback.id()))
                .thenReturn(new ModelHealthStore.CallPermit(fallback.id(), 0L));

        String result = executor.executeWithFallback(
                ModelCapability.CHAT,
                List.of(primary, fallback),
                ModelTarget::id,
                (client, target) -> {
                    if (target == primary) {
                        throw new ModelClientException("bailian 同步请求失败: HTTP 400 code=data_inspection_failed",
                                ModelClientErrorType.CONTENT_REJECTED, 400);
                    }
                    return "ok";
                });

        assertThat(result).isEqualTo("ok");
        verify(healthStore, never()).markFailure(primary.id());
        verify(healthStore).releaseHalfOpenPermit(probe);
        verify(healthStore).markSuccess(fallback.id());
    }

    /**
     * 首选失败、次选成功时阶段 span 仍是成功，但要能看出换了模型、首选为什么失败
     */
    @Test
    void shouldMarkFallbackOnCurrentSpan() {
        List<SpanData> exported = new ArrayList<>();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(new SpanExporter() {
                    @Override
                    public CompletableResultCode export(Collection<SpanData> spans) {
                        exported.addAll(spans);
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
                }))
                .build();
        ModelHealthStore healthStore = mock(ModelHealthStore.class);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(healthStore);
        ModelTarget primary = target("qwen3-rerank", "bailian");
        ModelTarget fallback = target("rerank-noop", "noop");
        when(healthStore.allowCall(primary.id()))
                .thenReturn(new ModelHealthStore.CallPermit(primary.id(), 0L));
        when(healthStore.allowCall(fallback.id()))
                .thenReturn(new ModelHealthStore.CallPermit(fallback.id(), 0L));

        Span stage = tracerProvider.get("test").spanBuilder("rerank").startSpan();
        try (Scope ignored = stage.makeCurrent()) {
            executor.executeWithFallback(
                    ModelCapability.RERANK,
                    List.of(primary, fallback),
                    ModelTarget::id,
                    (client, target) -> {
                        if (target == primary) {
                            throw new IllegalStateException("Read timed out");
                        }
                        return "ok";
                    });
        } finally {
            stage.end();
            tracerProvider.close();
        }

        Attributes attributes = exported.get(0).getAttributes();
        assertThat(attributes.get(AttributeKey.stringKey("ragent.model.id"))).isEqualTo("rerank-noop");
        assertThat(attributes.get(AttributeKey.booleanKey("ragent.model.fallback"))).isTrue();
        assertThat(attributes.get(AttributeKey.stringKey("ragent.model.failed"))).isEqualTo("qwen3-rerank: Read timed out");
        assertThat(attributes.get(AttributeKey.stringKey("langfuse.observation.level"))).isEqualTo("WARNING");
        assertThat(exported.get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
    }

    private static ModelTarget target(String id, String provider) {
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId(id);
        candidate.setProvider(provider);
        candidate.setModel(id);
        return new ModelTarget(id, candidate, new AIModelProperties.ProviderConfig(), 30_000L);
    }
}
