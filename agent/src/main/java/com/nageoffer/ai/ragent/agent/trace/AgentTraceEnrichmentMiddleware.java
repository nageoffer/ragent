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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.enums.AgentToolStatus;
import com.nageoffer.ai.ragent.agent.memory.AgentContextChars;
import com.nageoffer.ai.ragent.agent.model.AnthropicMessagesChatModel;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import com.nageoffer.ai.ragent.rag.config.InputGuardProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.util.context.ContextView;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 官方 OtelTracingMiddleware 只建 span 骨架与计数，这里补上内容：问题、上下文、工具出入参、轮次
 * <p>
 * 它必须紧贴官方中间件的内层：官方用 runWithContext（本质是 contextWrite）把 span 写进 Reactor 上下文，
 * 内层才能在 deferContextual 里把它取出来补属性；内层的终止回调也先于外层 span.end() 触发，晚写的属性不会丢
 * <p>
 * 官方那层若换实现、不再把 span 写进 Reactor 上下文，这里取到的 span 无效，各钩子原样放行，不建没有父亲的轮次 span
 */
@Slf4j
@RequiredArgsConstructor
public class AgentTraceEnrichmentMiddleware implements MiddlewareBase {

    /**
     * 框架只保证「这批全被拒」，拒因可能是用户点了取消，也可能是权限策略，措辞不替它认定
     */
    private static final String ALL_TOOLS_DENIED_MESSAGE = "全部工具调用被拒绝，均未执行";

    /**
     * 挂起与被拒是两种结局，措辞得分开：这批还等着人点，不是已经被否掉
     */
    private static final String AWAITING_PERMISSION_MESSAGE = "等待人工确认，工具尚未执行";

    private static final String INPUT_GUARD_SPAN = "input_guard";

    private final Tracer tracer;
    private final AgentTraceSerializer serializer;

    /**
     * 入口审核方式，进程级常量，写在 input_guard 节点上
     */
    private final InputGuardProperties.Type inputGuardType;

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(ctxView -> {
            AgentInputReviewContext.InputReview inputReview = AgentInputReviewContext.take();
            Span span = currentSpan(ctxView);
            if (!span.getSpanContext().isValid()) {
                return next.apply(input);
            }
            // trace 名与运行方式同源：各写一个三元表达式迟早有一处漏改，读的人也就分不清该信哪个
            String runKind = isConfirmResume(input.msgs())
                    ? RagentAttributes.RUN_KIND_CONFIRM_RESUME : RagentAttributes.RUN_KIND_ASK;
            span.setAttribute(AgentTraceAttributes.TRACE_NAME, RagentAttributes.TRACE_NAME_PREFIX + runKind);
            span.setAttribute(AgentTraceAttributes.OBSERVATION_TYPE, AgentTraceAttributes.TYPE_AGENT);
            span.setAttribute(RagentAttributes.RUN_KIND, runKind);
            // 契约版本只写根 span：语义换代后查询侧凭它区分两代数据，不必靠某个属性有没有来猜
            span.setAttribute(RagentAttributes.CONTRACT_VERSION, RagentAttributes.CONTRACT_VERSION_VALUE);
            span.setAttribute(AgentTraceAttributes.OBSERVATION_INPUT, describeQuestion(input.msgs()));
            AgentRunTracer.writeIdentity(span, ctx);

            // 中断发生在响应式链之外的 HTTP 线程上，那里只够得着 RuntimeContext，根 span 与轮次计数器都寄存进去
            // 轮数由 onReasoning 逐轮回写，不留到终止回调：取消信号自外向内传，外层先 end span 会把补写吞掉
            AgentRunTracer.bindRoot(ctx, span);
            if (RagentAttributes.RUN_KIND_ASK.equals(runKind) && inputReview != null) {
                traceInputReview(span, ctx, inputReview);
            }

            RootOutput output = new RootOutput(span);
            return next.apply(input)
                    .doOnNext(event -> {
                        if (event instanceof TextBlockDeltaEvent delta) {
                            output.appendAnswer(delta.getDelta());
                        } else if (event instanceof TextBlockEndEvent) {
                            output.flush();
                        } else if (event instanceof AgentResultEvent result) {
                            output.finish(result.getResult());
                        } else if (event instanceof ExceedMaxItersEvent exceed) {
                            span.setAttribute(AgentTraceAttributes.LEVEL, AgentTraceAttributes.LEVEL_WARNING);
                            span.setAttribute(AgentTraceAttributes.STATUS_MESSAGE,
                                    "Agent ReAct循环达到迭代上限，迭代次数：" + exceed.getMaxIters());
                        }
                    })
                    // 中断走的也是完成信号，结局落不落得下由严重度决定，这里只管报「流正常走完了」
                    .doOnComplete(() -> AgentRunTracer.markCompleted(ctx))
                    // 失败路径根本不发 AgentResultEvent，正文只能靠这里补完，否则整条 trace 只剩一句报错
                    .doOnError(e -> {
                        output.flush();
                        AgentRunTracer.markFailed(ctx);
                        span.setAttribute(AgentTraceAttributes.LEVEL, AgentTraceAttributes.LEVEL_ERROR);
                        span.setAttribute(AgentTraceAttributes.STATUS_MESSAGE, String.valueOf(e.getMessage()));
                    })
                    // 强制断流时取消信号自外向内传，官方那层先 end 掉根 span，这里的补写多半已是空操作
                    // 故正文最多落后一个刷新阈值即 1023 字符，再准就得逐 delta 重建整串 JSON，不划算
                    // 取消跑在 Redisson 取消监听线程上，与仍在 append 的产出线程并发读同一个 answer，故要包一层
                    .doOnCancel(cancelQuietly("root_output", output::flush));
        });
    }

    /**
     * 入口审核跑在 Agent 启动之前，这里按它真实的起止时刻补成根节点的子节点，所以它排在根节点开始之前
     */
    private void traceInputReview(Span root, RuntimeContext ctx, AgentInputReviewContext.InputReview review) {
        Span span = tracer.spanBuilder(INPUT_GUARD_SPAN)
                .setParent(Context.root().with(root))
                .setStartTimestamp(review.startedAt())
                .startSpan();
        span.setAttribute(AgentTraceAttributes.OBSERVATION_TYPE, AgentTraceAttributes.TYPE_GUARDRAIL);
        span.setAttribute(RagentAttributes.INPUT_GUARD_TYPE, inputGuardType.name().toLowerCase(Locale.ROOT));
        AgentRunTracer.writeIdentity(span, ctx);
        span.end(review.endedAt());
    }

    /**
     * 官方没有轮次这一层，由这里补建，官方随后建的 chat 会认它作父
     * execute_tool 认不了：官方在我们外层，它建 span 时父亲已定，contextWrite 只覆盖 reasoning 那条流
     * 故工具与轮次在树上是兄弟，靠 onActing 补写的 round_index 归轮，本 span 耗时也就只含推理不含工具
     */
    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(ctxView -> {
            Context parent = AgentRunTracer.otelContext(ctxView);
            if (!Span.fromContext(parent).getSpanContext().isValid()) {
                return next.apply(input);
            }
            // 全程唯一的递增者，模型调用、工具批、工具执行体都只读这个数
            int round = AgentRunTracer.nextRound(ctx);
            // 序号进属性，名字保持低基数：带上序号会让后端按名聚合时每一轮各成一类
            Span span = tracer.spanBuilder("reasoning").setParent(parent).startSpan();
            AtomicBoolean ended = new AtomicBoolean();
            // span 已经开了，终止回调却要到本段末尾才挂上，这中间抛出就没人再收它，故整段兜住
            try {
                span.setAttribute(AgentTraceAttributes.OBSERVATION_TYPE, AgentTraceAttributes.TYPE_CHAIN);
                span.setAttribute(RagentAttributes.REACT_ROUND_INDEX, round);
                span.setAttribute(RagentAttributes.CONTEXT_MESSAGE_COUNT, input.messages().size());
                span.setAttribute(RagentAttributes.CONTEXT_CHARS, AgentContextChars.total(input.messages()));
                AgentRunTracer.writeIdentity(span, ctx);
                // 轮数每轮覆写一次，后写覆盖前写，中断在任何一轮都留得下当时的真实轮数
                // 工具定义只在首轮写，逐轮重复几十份 schema 没有意义，序列化也只在这个分支里发生
                AgentRunTracer.writeRootAttribute(ctx, root -> {
                    root.setAttribute(RagentAttributes.REACT_ROUNDS, round);
                    if (round == 1) {
                        root.setAttribute(AgentTraceAttributes.META_TOOL_DEFINITIONS,
                                serializer.toolDefinitions(input.tools()));
                    }
                });

                return ContextPropagationOperator.runWithContext(
                        next.apply(input)
                                .doOnComplete(() -> endSpan(span, ended, StatusCode.OK, null,
                                        RagentAttributes.RUN_OUTCOME_COMPLETED))
                                .doOnCancel(() -> endSpan(span, ended, StatusCode.ERROR, null,
                                        RagentAttributes.RUN_OUTCOME_INTERRUPTED))
                                .doOnError(e -> endSpan(span, ended, StatusCode.ERROR, e,
                                        RagentAttributes.RUN_OUTCOME_FAILED)),
                        span.storeInContext(parent));
            } catch (RuntimeException e) {
                // next.apply 也在这段里：内层有中间件的 onReasoning 是即时执行的，它抛出时回调一个都还没挂上
                // 不在这里收口，这条 span 永不 end，也就永不导出，那一轮在链路上整个消失，子节点跟着变孤儿
                endSpan(span, ended, StatusCode.ERROR, e, RagentAttributes.RUN_OUTCOME_FAILED);
                throw e;
            }
        });
    }

    /**
     * 模型实际看到什么的权威记录点：业务中间件都只挂 onReasoning，走到这里的 messages 已是压缩、裁剪、
     * 记忆注入、技能遮蔽全部做完的最终列表，与本中间件在洋葱里的层序无关
     */
    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(ctxView -> {
            Span span = currentSpan(ctxView);
            if (!span.getSpanContext().isValid()) {
                return next.apply(input);
            }
            span.setAttribute(AgentTraceAttributes.OBSERVATION_TYPE, AgentTraceAttributes.TYPE_GENERATION);
            span.setAttribute(AgentTraceAttributes.OBSERVATION_INPUT, serializer.messages(input.messages()));
            span.setAttribute(AgentTraceAttributes.MODEL_PARAMETERS, serializer.modelParameters(input.options()));
            // 官方那层建了 chat span 却没写这两个规范属性，provider 是必填、stream 漏写会被读成非流式
            // 模型名反过来，它写的 gen_ai.request.model 与 langfuse.observation.model.name 喂同一个列，我方不必再写一遍
            span.setAttribute(GenAiAttributes.PROVIDER_NAME, GenAiAttributes.providerOf(input.model()));
            span.setAttribute(GenAiAttributes.REQUEST_STREAM, true);
            // 遮蔽生效之后的名单，能直接看出技能遮蔽有没有在干活
            span.setAttribute(RagentAttributes.TOOLS_VISIBLE, toolNames(input.tools()));
            AgentRunTracer.writeIdentity(span, ctx);
            AgentRunTracer.writeRoundIndex(span, ctx);

            StringBuilder text = new StringBuilder();
            StringBuilder thinking = new StringBuilder();
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            // 入参是逐片流过来的，攒在这里等收尾事件来认领；键用 toolCallId，同名并行的两条各攒各的
            Map<String, StringBuilder> pendingArguments = new HashMap<>();
            AtomicBoolean firstDelta = new AtomicBoolean();
            Runnable writeOutput = () -> span.setAttribute(AgentTraceAttributes.OBSERVATION_OUTPUT,
                    serializer.modelOutput(text, thinking, toolCalls));
            // 缓存写入量只在响应元数据里，事件流带不出来；包一层模型截下它，跟着本次调用走
            AtomicReference<Integer> cacheWrite = new AtomicReference<>();
            ModelCallInput tapped = input.model() instanceof AnthropicMessagesChatModel model
                    ? new ModelCallInput(input.messages(), input.tools(), input.options(), new CacheWriteTap(model, cacheWrite))
                    : input;
            return next.apply(tapped)
                    .doOnNext(event -> {
                        if (event instanceof TextBlockDeltaEvent delta) {
                            markCompletionStart(span, firstDelta);
                            text.append(StrUtil.nullToEmpty(delta.getDelta()));
                        } else if (event instanceof ThinkingBlockDeltaEvent delta) {
                            markCompletionStart(span, firstDelta);
                            thinking.append(StrUtil.nullToEmpty(delta.getDelta()));
                        } else if (event instanceof ToolCallDeltaEvent delta) {
                            appendArguments(pendingArguments, delta);
                        } else if (event instanceof ToolCallEndEvent call) {
                            toolCalls.add(describeToolCall(pendingArguments, call));
                        } else if (event instanceof ModelCallEndEvent end) {
                            writeUsage(span, end.getUsage(), cacheWrite.get());
                        }
                    })
                    .doOnTerminate(writeOutput)
                    // 取消路径上外层可能已经收了这个 span，此时写入是空操作，能落多少算多少
                    // 取消线程读 text/thinking/toolCalls 时产出线程仍在写，故要包一层
                    .doOnCancel(cancelQuietly("model_output", writeOutput));
        });
    }

    /**
     * 外层的 tool_batch 是这批工具的唯一节点，出入参与结局都补在它身上
     * 不按 toolCallId 另建子 span：单工具的真实区间由工具体自己打点，这里再建一层只会复述批区间
     * 工具在树上与轮次平级，故批也补写 round_index，按轮聚合工具耗时只能靠它
     */
    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(ctxView -> {
            Span batchSpan = currentSpan(ctxView);
            if (!batchSpan.getSpanContext().isValid()) {
                return next.apply(input);
            }
            AgentRunTracer.writeIdentity(batchSpan, ctx);
            // 本批属于刚推理完的那一轮，此刻计数器停在该轮序号上
            // 确认续跑时本次调用先执行工具后推理，计数器还停在 0，属性整个不写：这批本就不属于本次运行的任何一轮
            AgentRunTracer.writeRoundIndex(batchSpan, ctx);

            // 名册取自 ActingInput：它就是本批要执行的全部调用，且保持模型吐出的先后
            // 出入参一律按它排、按它对齐，靠事件攒出来的顺序是到达顺序，规则拒绝那几条会插到前面去
            ToolBatchRoster roster = new ToolBatchRoster(input.toolCalls(), AgentToolExecutionFacts.from(ctx));
            batchSpan.setAttribute(AgentTraceAttributes.OBSERVATION_INPUT, serializer.toolCalls(roster.describeInputs()));

            Runnable writeOutcomes = () -> {
                batchSpan.setAttribute(AgentTraceAttributes.OBSERVATION_OUTPUT,
                        serializer.toolCalls(roster.describeOutputs()));
                writeWorstLevel(batchSpan, roster.calls);
            };
            // 断流那刻还没定终局的判成 interrupted，与落库块同一条收尾规则；正常终止与取消共用一份收尾
            Runnable settle = () -> {
                roster.settleOpen();
                writeOutcomes.run();
            };
            // 开跑前先落一份全员未执行的名册：断流可能发生在任何一个事件之前，
            // 不打这个底就会停在「入参齐全、输出为空」，那正是一次静默成功的写操作的样子
            writeOutcomes.run();
            return next.apply(input)
                    .doOnNext(event -> {
                        if (event instanceof ToolResultTextDeltaEvent delta) {
                            // 正文只攒不落：逐 delta 重写整份名册是无界开销，排查工具会反过来拖慢被排查的对象
                            roster.append(delta.getToolCallId(), delta.getDelta());
                            return;
                        }
                        if (event instanceof RequireUserConfirmEvent) {
                            // 等确认的这批只有事件没有执行，不标就停在「级别正常、输出全是待执行」
                            // 整批一起推迟，故不取事件里那几条待确认的：卡片上没列的同批工具一样没跑
                            roster.awaitAll();
                        } else if (event instanceof AllToolsDeniedEvent) {
                            // 用户拒绝后框架会拿同一批工具再调一次 acting，这批只有事件没有执行
                            // 名册即事件里那批被拒调用，逐条标 denied 就够，不必再读一遍事件
                            roster.denyAll();
                        } else if (event instanceof ToolResultStartEvent start) {
                            roster.start(start.getToolCallId(), start.getToolCallName());
                        } else if (event instanceof ToolResultEndEvent end) {
                            roster.end(end.getToolCallId(), end.getToolCallName(), end.getState());
                        } else {
                            return;
                        }
                        // 每次状态变化当场落一遍：取消信号自外向内传，官方那层先收走批 span，
                        // 留到终止回调再写多半已是空操作，断流时留下的只能是最后一次落地的状态
                        writeOutcomes.run();
                    })
                    .doOnTerminate(settle)
                    // 取消线程读名册正文时产出线程仍在 append，故要包一层
                    .doOnCancel(cancelQuietly("tool_batch_outcome", settle));
        });
    }

    /**
     * 批 span 是这批工具唯一的节点，级别取其中最坏的一个，否则失败会被同批的成功盖掉
     * 只看已定终局的：中途覆写时还在跑的那几条不算异常，先写上 WARNING 后面也降不回来
     */
    private static void writeWorstLevel(Span batchSpan, List<ToolCallRecord> calls) {
        List<ToolCallRecord> settled = calls.stream()
                .filter(call -> !call.status.isOpen() && call.status != AgentToolStatus.DONE)
                .toList();
        if (settled.isEmpty()) {
            return;
        }
        boolean error = settled.stream().anyMatch(call -> call.status == AgentToolStatus.FAILED);
        batchSpan.setAttribute(AgentTraceAttributes.LEVEL,
                error ? AgentTraceAttributes.LEVEL_ERROR : AgentTraceAttributes.LEVEL_WARNING);
        batchSpan.setAttribute(AgentTraceAttributes.STATUS_MESSAGE, statusMessage(calls, settled));
        if (error) {
            batchSpan.setStatus(StatusCode.ERROR);
        }
    }

    /**
     * 整批同一个结局时说清「未执行」，混合结局才逐条列，只报状态读不出哪几个没跑
     */
    private static String statusMessage(List<ToolCallRecord> calls, List<ToolCallRecord> settled) {
        if (settled.size() == calls.size()) {
            if (settled.stream().allMatch(call -> call.status == AgentToolStatus.AWAITING)) {
                return AWAITING_PERMISSION_MESSAGE;
            }
            if (settled.stream().allMatch(call -> call.status == AgentToolStatus.DENIED)) {
                return ALL_TOOLS_DENIED_MESSAGE;
            }
        }
        return settled.stream()
                .map(call -> StrUtil.nullToDefault(call.name, "unknown") + "#" + call.callIndex + "="
                        + call.status.value())
                .collect(Collectors.joining(", "));
    }

    private void writeUsage(Span span, ChatUsage usage, Integer cacheWrite) {
        if (usage == null) {
            return;
        }
        // gen_ai.usage.* 官方已写两项，但 cachedTokens 没有对应的通用属性，这里补一份完整的
        span.setAttribute(AgentTraceAttributes.USAGE_DETAILS, serializer.usageDetails(usage.getInputTokens(),
                usage.getOutputTokens(), usage.getCachedTokens(), cacheWrite, usage.getTotalTokens()));
    }

    /**
     * 模型吐入参是一片一片来的，框架每片原样转发，攒起来才是完整 JSON
     * 必须追加不能覆盖：ToolCallDeltaEvent 给的是增量而非累积快照，覆盖只会剩最后一片
     */
    private static void appendArguments(Map<String, StringBuilder> pending, ToolCallDeltaEvent delta) {
        if (StrUtil.isEmpty(delta.getDelta())) {
            return;
        }
        pending.computeIfAbsent(ToolBatchRoster.callKey(delta.getToolCallId()), ignored -> new StringBuilder())
                .append(delta.getDelta());
    }

    /**
     * 一次调用的名册项：收尾事件只带 id 与名字，入参得从攒好的那份里取
     * 取走即移除，同一批里同名工具调两次不会读到上一次的残留
     */
    private Map<String, Object> describeToolCall(Map<String, StringBuilder> pending, ToolCallEndEvent call) {
        StringBuilder raw = pending.remove(ToolBatchRoster.callKey(call.getToolCallId()));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", StrUtil.nullToEmpty(call.getToolCallId()));
        item.put("name", StrUtil.nullToEmpty(call.getToolCallName()));
        item.put("arguments", serializer.argumentsFromRaw(raw == null ? null : raw.toString()));
        return item;
    }

    private void markCompletionStart(Span span, AtomicBoolean firstDelta) {
        if (firstDelta.compareAndSet(false, true)) {
            span.setAttribute(AgentTraceAttributes.COMPLETION_START_TIME, Instant.now().toString());
        }
    }

    private String describeQuestion(List<Msg> msgs) {
        // 一条消息都没有就没有问题可写，落空串会在过滤时多出「输入为空」这一档假值
        if (msgs == null || msgs.isEmpty()) {
            return null;
        }
        String question = msgs.stream()
                .map(Msg::getTextContent)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining("\n"));
        // 确认续跑那条消息正文为空，退回结构化输出才看得见确认结果
        return StrUtil.isNotBlank(question) ? serializer.plainText(question) : serializer.messages(msgs);
    }

    /**
     * 确认续跑由一条带确认结果元数据的空消息驱动，与普通提问只能靠它区分
     */
    private static boolean isConfirmResume(List<Msg> msgs) {
        return msgs != null && msgs.stream().anyMatch(msg -> msg.getMetadata() != null
                && msg.getMetadata().containsKey(Msg.METADATA_CONFIRM_RESULTS));
    }

    private static String toolNames(List<ToolSchema> tools) {
        return tools == null ? "" : tools.stream().map(ToolSchema::getName).collect(Collectors.joining(","));
    }

    /**
     * 结局属性得赶在 end 之前落笔，收了的 span 再写就是空操作
     * span 状态只有 OK/ERROR 两档，分不出这一轮是被用户停掉还是真报错，只能另开一把键
     */
    private static void endSpan(Span span, AtomicBoolean ended, StatusCode status, Throwable error, String outcome) {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        span.setAttribute(RagentAttributes.ROUND_OUTCOME, outcome);
        span.setStatus(status, error == null ? "" : StrUtil.nullToEmpty(error.getMessage()));
        if (error != null) {
            span.recordException(error);
        }
        span.end();
    }

    /**
     * 强制断流那刻产出线程仍在往缓冲里写，序列化按长度取内容会读到伸缩中的数组，属性丢了就丢了
     * 取消本身不会因此中断：抛出后 Reactor 走 Operators.onOperatorError，它先替回调取消了上游
     * 收在这里是为了那行日志——不收就按 dropped-error 打一条 ERROR 带栈，看着像追踪把这次运行搞崩了
     * 只包取消：正常终止与 onNext 由 Reactive Streams 保证串行，那条路上抛出是真问题，不该吞
     */
    private static Runnable cancelQuietly(String stage, Runnable body) {
        return () -> {
            try {
                body.run();
            } catch (Exception e) {
                log.warn("取消时回写追踪属性失败, 环节: {}", stage, e);
            }
        };
    }

    private static Span currentSpan(ContextView ctxView) {
        return Span.fromContext(AgentRunTracer.otelContext(ctxView));
    }

    /**
     * 根 span 的产出缓冲：只把业务正文按 delta 攒、按块落，最后一条助手消息到了再补必要的结构化 final
     * 模型思考由 generation 记录，根节点不再复制，也不再为它分配缓冲。
     * 不逐 delta 重写：每次重写都要把整串 JSON 重建一遍，一段长回答就是上万次全量拼接，
     * 排查工具反过来拖慢被排查的对象
     */
    private final class RootOutput {

        /**
         * 攒够这么多字符就落一次盘，取消时未落盘的正文最多也就差这么多
         */
        private static final int FLUSH_THRESHOLD_CHARS = 1024;

        private final Span span;

        private final StringBuilder answer = new StringBuilder();

        private int pending;

        private Msg finalMsg;

        private RootOutput(Span span) {
            this.span = span;
        }

        private void appendAnswer(String delta) {
            String safe = StrUtil.nullToEmpty(delta);
            answer.append(safe);
            pending += safe.length();
            if (pending >= FLUSH_THRESHOLD_CHARS) {
                flush();
            }
        }

        private void finish(Msg result) {
            finalMsg = result;
            flush();
        }

        /**
         * 缓冲直接交给序列化器，它只截上限内的前缀；先 toString 会让每次刷新按已攒长度复制一遍，
         * 于是整段流下来的开销随正文长度平方增长，越长的回答被拖得越狠
         */
        private void flush() {
            pending = 0;
            span.setAttribute(AgentTraceAttributes.OBSERVATION_OUTPUT,
                    serializer.rootOutput(answer, finalMsg));
        }
    }

    /**
     * 一个工具在本批内的痕迹，名字、状态、正文分散在三种事件里，攒齐了才写得出一条完整记录
     */
    private static final class ToolCallRecord {

        private final int callIndex;

        private final String toolCallId;

        private final Map<String, Object> arguments;

        private final StringBuilder output = new StringBuilder();

        private String name;

        /**
         * 收到过开头事件即为真，只在事实源缺席时兜底用：框架给被拒的那几条也照发开头事件
         */
        private boolean started;

        private AgentToolStatus status = AgentToolStatus.PENDING;

        private ToolCallRecord(int callIndex, ToolUseBlock call) {
            this.callIndex = callIndex;
            this.toolCallId = call == null ? null : call.getId();
            this.name = call == null ? null : call.getName();
            this.arguments = call == null ? null : call.getInput();
        }
    }

    /**
     * 本批的名册：顺序恒为模型吐出的先后，事件只往里填状态，不参与排序
     * 批次号与组内序号一律回读事实源，算第二遍就会与落库、SSE 各执一词
     */
    private final class ToolBatchRoster {

        /**
         * toolCallId 缺失时的兜底键，与落库侧同样处理，同批多条缺失会挤在一起，是框架给不出 id 的极端情形
         * 模型调用那层攒入参也按同一把键分组，两处共用 callKey 免得同一条规则写出两个版本
         */
        private static final String FALLBACK_CALL_KEY = "__anonymous__";

        private final List<ToolCallRecord> calls;

        /**
         * 仅用于按 id 回查，顺序一律取名册，故用 HashMap 也不会把偶然的迭代序泄漏到输出里
         */
        private final Map<String, ToolCallRecord> byId = new HashMap<>();

        private final AgentToolExecutionFacts facts;

        private ToolBatchRoster(List<ToolUseBlock> toolCalls, AgentToolExecutionFacts facts) {
            this.facts = facts;
            List<ToolUseBlock> source = toolCalls == null ? List.of() : toolCalls;
            List<ToolCallRecord> records = new ArrayList<>(source.size());
            for (int index = 0; index < source.size(); index++) {
                ToolUseBlock call = source.get(index);
                // 事实源在时以它分配的序号为准，取不到才退回名册席位
                int callIndex = facts == null ? index : facts.callIndexOf(call == null ? null : call.getId());
                ToolCallRecord record = new ToolCallRecord(callIndex, call);
                records.add(record);
                byId.putIfAbsent(callKey(record.toolCallId), record);
            }
            this.calls = List.copyOf(records);
        }

        /**
         * 开头事件只证明这条进过执行队列，归批是事实源的事，被规则拒掉的那条也留在同一批里
         */
        private void start(String toolCallId, String toolName) {
            ToolCallRecord record = find(toolCallId);
            if (record == null) {
                return;
            }
            record.started = true;
            record.status = AgentToolStatus.RUNNING;
            record.name = StrUtil.nullToDefault(toolName, record.name);
        }

        private void append(String toolCallId, String delta) {
            ToolCallRecord record = find(toolCallId);
            if (record != null) {
                record.output.append(StrUtil.nullToEmpty(delta));
            }
        }

        private void end(String toolCallId, String toolName, ToolResultState state) {
            ToolCallRecord record = find(toolCallId);
            if (record == null) {
                return;
            }
            record.status = AgentToolStatus.of(state);
            record.name = StrUtil.nullToDefault(toolName, record.name);
        }

        /**
         * 框架只在整批没有待确认工具时才执行，故挂起时全批都没跑，落库侧也是整批一起改
         */
        private void awaitAll() {
            calls.stream().filter(call -> call.status.isOpen())
                    .forEach(call -> call.status = AgentToolStatus.AWAITING);
        }

        private void denyAll() {
            calls.forEach(call -> call.status = AgentToolStatus.DENIED);
        }

        /**
         * 收尾时仍未定终局的判成 interrupted，与落库块同一条规则；已挂起、已拒绝的不动
         */
        private void settleOpen() {
            calls.stream().filter(call -> call.status.isOpen())
                    .forEach(call -> call.status = AgentToolStatus.INTERRUPTED);
        }

        /**
         * 跑没跑一律问事实源：起始时刻由工具执行体亲手写下，是「真进过工具体」唯一站得住的凭据
         * 光看开头事件会把框架前置短路的那几条算成跑过，批级结局与这里就各执一词
         */
        private boolean executed(ToolCallRecord call) {
            if (call.status == AgentToolStatus.DENIED) {
                return false;
            }
            if (facts == null || StrUtil.isBlank(call.toolCallId)) {
                return call.started;
            }
            return facts.toolFact(call.toolCallId).startedAt() != null;
        }

        /**
         * 批次号只有事实源一个来源，它还没归批时留空，写个占位串会让人以为归过批了
         */
        private String batchIdOf(String toolCallId) {
            if (facts == null || StrUtil.isBlank(toolCallId)) {
                return null;
            }
            return facts.toolFact(toolCallId).batchId();
        }

        /**
         * 入参是模型请求的原样，此刻还没有批可归，批次号留给产出侧写
         */
        private List<Map<String, Object>> describeInputs() {
            return calls.stream().map(call -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("callIndex", call.callIndex);
                item.put("toolCallId", call.toolCallId);
                item.put("name", call.name);
                item.put("arguments", serializer.arguments(call.arguments));
                return item;
            }).collect(Collectors.toList());
        }

        /**
         * 名册整条都要写：只写跑过的那几条，等待与被拒批就成了「入参齐全、输出为空」，
         * 那正是一次静默成功的写操作的样子
         */
        private List<Map<String, Object>> describeOutputs() {
            return calls.stream().map(call -> {
                boolean failed = call.status == AgentToolStatus.FAILED;
                String text = call.output.isEmpty() ? null : serializer.truncate(call.output);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("callIndex", call.callIndex);
                item.put("batchId", batchIdOf(call.toolCallId));
                item.put("toolCallId", call.toolCallId);
                item.put("name", call.name);
                item.put("state", call.status.value());
                item.put("executed", executed(call));
                item.put("result", failed ? null : text);
                item.put("error", failed ? text : null);
                return item;
            }).collect(Collectors.toList());
        }

        private ToolCallRecord find(String toolCallId) {
            return byId.get(callKey(toolCallId));
        }

        private static String callKey(String toolCallId) {
            return StrUtil.blankToDefault(toolCallId, FALLBACK_CALL_KEY);
        }
    }

    /**
     * 只截缓存写入量，其余原样转给被包的模型
     */
    private record CacheWriteTap(Model delegate, AtomicReference<Integer> sink) implements Model {

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return delegate.stream(messages, tools, options).doOnNext(response -> {
                Object tokens = response.getMetadata() == null ? null
                        : response.getMetadata().get(AnthropicMessagesChatModel.METADATA_CACHE_WRITE_TOKENS);
                if (tokens instanceof Integer value) {
                    sink.set(value);
                }
            });
        }

        @Override
        public String getModelName() {
            return delegate.getModelName();
        }

        @Override
        public int getContextWindowSize() {
            return delegate.getContextWindowSize();
        }
    }
}
