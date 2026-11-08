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
import io.opentelemetry.context.Context;

import java.util.List;
import java.util.function.Supplier;

/**
 * 上下文摘要的模型调用节点：摘要器直接调模型、不经过中间件链，不补这一层 LangFuse 里就没有这次调用的用量
 * <p>
 * 只记模型与用量不记正文，摘要正文已存 t_agent_context_compaction
 */
public final class AgentSummaryTracer {

    /**
     * 与主循环的 chat 节点同为 generation，按名字区分用途
     */
    private static final String SPAN_NAME = "context_compaction";

    /**
     * 只取用量口径，内容一律不采
     */
    private static final AgentTraceSerializer USAGE_ONLY = new AgentTraceSerializer(0, 0, false);

    private AgentSummaryTracer() {
    }

    /**
     * 上下文里没有根 span 即追踪未开启，原样执行；调用抛出时节点照样收口，异常原样抛回
     */
    public static List<ChatResponse> trace(RuntimeContext ctx, String model, Supplier<List<ChatResponse>> call) {
        Span root = AgentRunTracer.rootSpan(ctx);
        if (root == null) {
            return call.get();
        }
        Span span = GlobalOpenTelemetry.getTracer(AgentTracingConfiguration.INSTRUMENTATION_SCOPE)
                .spanBuilder(SPAN_NAME)
                .setParent(root.storeInContext(Context.root()))
                .startSpan();
        span.setAttribute(AgentTraceAttributes.OBSERVATION_TYPE, AgentTraceAttributes.TYPE_GENERATION);
        span.setAttribute(GenAiAttributes.PROVIDER_NAME, GenAiAttributes.PROVIDER_OPENAI);
        if (model != null) {
            span.setAttribute(GenAiAttributes.REQUEST_MODEL, model);
        }
        AgentRunTracer.writeIdentity(span, ctx);
        // 与工具体同理：挂在根下、和轮次是兄弟，归哪一轮只能靠这把键
        AgentRunTracer.writeRoundIndex(span, ctx);
        try {
            List<ChatResponse> responses = call.get();
            writeUsage(span, responses);
            return responses;
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR);
            span.setAttribute(AgentErrorTypes.KEY, AgentErrorTypes.ofThrowable(e));
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * 摘要走非流式，正常只有一份用量；取最后非空的那份，不逐份累加
     */
    private static void writeUsage(Span span, List<ChatResponse> responses) {
        ChatUsage usage = null;
        for (ChatResponse response : responses) {
            if (response.getUsage() != null) {
                usage = response.getUsage();
            }
        }
        if (usage != null) {
            span.setAttribute(AgentTraceAttributes.USAGE_DETAILS, USAGE_ONLY.usageDetails(
                    usage.getInputTokens(), usage.getOutputTokens(), usage.getCachedTokens(), usage.getTotalTokens()));
        }
    }
}
