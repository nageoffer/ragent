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

package com.nageoffer.ai.ragent.rag.trace;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.config.ThreadPoolExecutorConfig;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.retrieval.MultiChannelRetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScope;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScopeResolver;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.VectorSearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.RerankPostProcessor;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用生产线程池跑两个子问题的检索，钉住分阶段 span 跨池后仍挂在各自的父节点下
 */
class KnowledgeSearchSpansTest {

    private final List<SpanData> exported = Collections.synchronizedList(new ArrayList<>());

    private SdkTracerProvider tracerProvider;

    @BeforeEach
    void setUp() {
        GlobalOpenTelemetry.resetForTest();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(new CollectingExporter(exported)))
                .build();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void shouldNestStageSpansUnderToolSpanAcrossPools() {
        RetrievalEngine engine = engine();
        Span tool = tracerProvider.get("test").spanBuilder("execute_tool search_knowledge").startSpan();
        try (Scope ignored = tool.makeCurrent()) {
            engine.retrieve(List.of(
                    new SubQuestionIntent("AirPods Pro 左耳没声音", List.of()),
                    new SubQuestionIntent("AirPods Pro 怎么重置", List.of())));
        } finally {
            tool.end();
        }

        String toolSpanId = tool.getSpanContext().getSpanId();
        for (String retrieveName : List.of("retrieve #1", "retrieve #2")) {
            SpanData retrieve = span(retrieveName);
            assertEquals(toolSpanId, retrieve.getParentSpanId(), retrieveName + " 应挂在工具 span 下");
            List<String> children = exported.stream()
                    .filter(data -> retrieve.getSpanId().equals(data.getParentSpanId()))
                    .map(SpanData::getName)
                    .sorted()
                    .toList();
            assertEquals(List.of("channel vector", "rerank"), children, retrieveName + " 的子节点");
        }
    }

    @Test
    void shouldRecordNothingWithoutParentSpan() {
        engine().retrieve(List.of(new SubQuestionIntent("AirPods Pro 左耳没声音", List.of())));

        assertTrue(exported.isEmpty(), "没有父 span 时不应开出孤立的根 trace，实际: "
                + exported.stream().map(SpanData::getName).toList());
    }

    /**
     * 向量服务出错时通道照样返回空结果，span 得标成失败，不然和「库里没资料」长得一样
     */
    @Test
    void shouldMarkChannelFailedWhenEmbeddingThrows() {
        VectorRetrieverService retrieverService = mock(VectorRetrieverService.class);
        when(retrieverService.embedAndNormalize(anyString())).thenThrow(new IllegalStateException("embedding 服务不可用"));
        RetrievalEngine engine = engine(new VectorSearchChannel(retrieverService, new SearchChannelProperties(), Runnable::run));

        RetrievalContext result;
        Span tool = tracerProvider.get("test").spanBuilder("execute_tool search_knowledge").startSpan();
        try (Scope ignored = tool.makeCurrent()) {
            result = engine.retrieve(List.of(new SubQuestionIntent("AirPods Pro 左耳没声音", List.of())));
        } finally {
            tool.end();
        }

        assertTrue(result.getContextChunks().isEmpty());
        assertEquals(StatusCode.ERROR, span("channel vector").getStatus().getStatusCode());
        assertEquals("embedding 服务不可用", span("channel vector").getStatus().getDescription(), "失败原因要能在追踪里直接看到");
        assertEquals(StatusCode.UNSET, span("retrieve #1").getStatus().getStatusCode(), "兜底成功的上层不应跟着标失败");
    }

    private RetrievalEngine engine() {
        return engine(vectorChannel());
    }

    private RetrievalEngine engine(SearchChannel channel) {
        ThreadPoolExecutorConfig pools = new ThreadPoolExecutorConfig();
        RetrievalScopeResolver scopeResolver = mock(RetrievalScopeResolver.class);
        when(scopeResolver.resolve(anyList())).thenReturn(RetrievalScope.global(0.0, List.of("kb-airpods")));
        MultiChannelRetrievalEngine multiChannel = new MultiChannelRetrievalEngine(
                List.of(channel),
                List.of(rerank()),
                scopeResolver,
                pools.ragRetrievalExecutor(),
                new SearchChannelProperties());
        return new RetrievalEngine(
                new SearchChannelProperties(),
                mock(ContextFormatter.class),
                mock(PromptTemplateLoader.class),
                multiChannel,
                pools.ragContextExecutor());
    }

    private SearchChannel vectorChannel() {
        SearchChannel channel = mock(SearchChannel.class);
        when(channel.getName()).thenReturn("VectorSearch");
        when(channel.getType()).thenReturn(SearchChannelType.VECTOR);
        when(channel.isEnabled(any(SearchContext.class))).thenReturn(true);
        when(channel.search(any(SearchContext.class))).thenReturn(SearchChannelResult.builder()
                .channelType(SearchChannelType.VECTOR)
                .channelName("VectorSearch")
                .chunks(List.of(RetrievedChunk.builder().id("c1").text("左耳无声先清洁滤网").collectionName("kb-airpods").build()))
                .build());
        return channel;
    }

    private RerankPostProcessor rerank() {
        RerankService rerankService = mock(RerankService.class);
        when(rerankService.rerank(anyString(), anyList(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        RAGConfigProperties ragConfig = new RAGConfigProperties();
        ragConfig.setRerankEnabled(true);
        return new RerankPostProcessor(rerankService, ragConfig, new SearchChannelProperties());
    }

    private SpanData span(String name) {
        return exported.stream().filter(data -> name.equals(data.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("未导出 span: " + name + ", 实际: "
                        + exported.stream().map(SpanData::getName).toList()));
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
