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

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.function.Supplier;

/**
 * Agent 知识库检索的分阶段 span，挂在当前 OTel span 下
 * 属性只记条数，检索文本是否上报由 Agent 侧工具 span 的采集开关决定
 */
public final class KnowledgeSearchSpans {

    private static final String INSTRUMENTATION_SCOPE = "com.nageoffer.ai.ragent.rag";

    public static final AttributeKey<Long> SUB_QUESTION_COUNT =
            AttributeKey.longKey("ragent.knowledge.sub_question_count");

    public static final AttributeKey<Long> INTENT_COUNT = AttributeKey.longKey("ragent.knowledge.intent_count");

    public static final AttributeKey<Long> INPUT_COUNT = AttributeKey.longKey("ragent.knowledge.input_count");

    public static final AttributeKey<Long> CHUNK_COUNT = AttributeKey.longKey("ragent.knowledge.chunk_count");

    private KnowledgeSearchSpans() {
    }

    /**
     * 当前没有有效 span 时只执行不记录：WorkFlow 问答也走这些方法，不能让它们各自变成一条孤立的根 trace
     */
    public static <T> T trace(String name, Supplier<T> body) {
        Context parent = Context.current();
        if (!Span.fromContext(parent).getSpanContext().isValid()) {
            return body.get();
        }
        Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_SCOPE)
                .spanBuilder(name)
                .setParent(parent)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return body.get();
        } catch (RuntimeException | Error e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * 阶段吞掉异常走兜底时调用，否则 span 只会显示成功、0 条，分不清是服务故障还是库里没资料
     */
    public static void markFailed(Throwable e) {
        Span span = Span.current();
        span.setStatus(StatusCode.ERROR, e.getMessage());
        span.recordException(e);
    }

    /**
     * 写到当前 span 上，须在 {@link #trace} 的 body 里调用；没有 span 时是空操作
     */
    public static void count(AttributeKey<Long> key, long value) {
        Span.current().setAttribute(key, value);
    }
}
