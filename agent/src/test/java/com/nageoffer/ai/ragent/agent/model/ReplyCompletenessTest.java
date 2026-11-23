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

package com.nageoffer.ai.ragent.agent.model;

import cn.hutool.core.exceptions.ExceptionUtil;
import com.nageoffer.ai.ragent.agent.config.ProductionModels;
import com.nageoffer.ai.ragent.agent.config.ProductionModels.Gateway;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.nageoffer.ai.ragent.agent.config.Replies.anArgs;
import static com.nageoffer.ai.ragent.agent.config.Replies.anBlockStop;
import static com.nageoffer.ai.ragent.agent.config.Replies.anDelta;
import static com.nageoffer.ai.ragent.agent.config.Replies.anStart;
import static com.nageoffer.ai.ragent.agent.config.Replies.anStop;
import static com.nageoffer.ai.ragent.agent.config.Replies.anToolStart;
import static com.nageoffer.ai.ragent.agent.config.Replies.anthropicText;
import static com.nageoffer.ai.ragent.agent.config.Replies.oaArgs;
import static com.nageoffer.ai.ragent.agent.config.Replies.oaFinish;
import static com.nageoffer.ai.ragent.agent.config.Replies.oaRefusal;
import static com.nageoffer.ai.ragent.agent.config.Replies.oaToolStart;
import static com.nageoffer.ai.ragent.agent.config.Replies.openAiText;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 回复不完整时一个工具都不能执行：流断在参数中途、到了输出上限、结束原因与内容对不上、参数分片交错、拒答
 * 两条入口都按生产装配（DeepSeek 的 formatter 与传输链、Opus 的适配器），工具参数是选填，不靠必填校验碰巧拦住
 */
class ReplyCompletenessTest {

    private static final Function<Gateway, Model> DEEPSEEK = ProductionModels::deepseek;
    private static final Function<Gateway, Model> GEMINI = gateway -> ProductionModels.zenmux("google/gemini-4-argon:google-vertex", gateway);
    private static final Function<Gateway, Model> OPUS = ProductionModels::opus;

    enum Outcome { ANSWERED, INCOMPLETE }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void shouldExecuteToolsOnlyForCompleteReplies(String name, Function<Gateway, Model> model, List<String> reply,
                                                  Outcome outcome, List<String> executed) {
        Gateway gateway = new Gateway().thenStream(reply);
        boolean answersAfterTools = outcome == Outcome.ANSWERED && !executed.isEmpty();
        if (answersAfterTools) {
            gateway.thenStream(model == OPUS ? anthropicText("好的", "end_turn") : openAiText("好的", "stop"));
        }
        Tools tools = new Tools();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        ReActAgent agent = ReActAgent.builder().name("RagentAI").sysPrompt("你是售后客服").model(model.apply(gateway))
                .toolkit(toolkit).maxIters(3).maxRetries(1).stateStore(new InMemoryAgentStateStore()).build();

        Throwable failure = catchThrowable(() -> agent.call("查订单",
                RuntimeContext.builder().userId("u-1").sessionId("c-1").build()).block(Duration.ofSeconds(10)));

        assertThat(tools.executed).as("实际执行的工具").containsExactlyElementsOf(executed);
        switch (outcome) {
            case ANSWERED -> assertThat(failure).isNull();
            case INCOMPLETE -> assertThat(ExceptionUtil.getCausedBy(failure, ModelException.class)).isNotNull();
        }
    }

    static Stream<Arguments> cases() {
        String half = "{\"order_no\":\"BT";
        String full = "{\"order_no\":\"BT1\"}";
        List<Arguments> cases = new ArrayList<>();
        for (Map.Entry<String, Function<Gateway, Model>> entry : Map.of("DeepSeek", DEEPSEEK, "Gemini", GEMINI).entrySet()) {
            String p = entry.getKey();
            Function<Gateway, Model> m = entry.getValue();
            cases.add(Arguments.of(p + " 断在参数中途", m, List.of(oaToolStart(0, "call_A", "query_order", half)), Outcome.INCOMPLETE, List.of()));
            cases.add(Arguments.of(p + " 参数写到一半到了输出上限", m,
                    List.of(oaToolStart(0, "call_A", "query_order", half), oaFinish("length")), Outcome.INCOMPLETE, List.of()));
            cases.add(Arguments.of(p + " 完整调用照常执行", m,
                    List.of(oaToolStart(0, "call_A", "query_order", full), oaFinish("tool_calls")), Outcome.ANSWERED, List.of("query_order:BT1")));
        }
        Function<Gateway, Model> d = DEEPSEEK;
        cases.add(Arguments.of("DeepSeek 半截参数加 stop", d,
                List.of(oaToolStart(0, "call_A", "query_order", half), oaFinish("stop")), Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("DeepSeek tool_calls 但参数解析不了", d,
                List.of(oaToolStart(0, "call_A", "query_order", "{\"order_no\":\"BT1\""), oaFinish("tool_calls")), Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("DeepSeek 完整调用但结束原因是 stop", d,
                List.of(oaToolStart(0, "call_A", "query_order", full), oaFinish("stop")), Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("DeepSeek 无参工具发 {}", d,
                List.of(oaToolStart(0, "call_A", "list_categories", "{}"), oaFinish("tool_calls")), Outcome.ANSWERED, List.of("list_categories")));
        cases.add(Arguments.of("DeepSeek 无参工具一个参数都没收到", d,
                List.of(oaToolStart(0, "call_A", "list_categories", ""), oaFinish("tool_calls")), Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("DeepSeek 先开 A 再开 B，随后到 A 的参数", d,
                List.of(oaToolStart(0, "call_A", "query_order", ""), oaToolStart(1, "call_B", "query_order", ""),
                        oaArgs(0, full), oaArgs(1, "{\"order_no\":\"BT2\"}"), oaFinish("tool_calls")), Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("DeepSeek 并行调用各收到自己的参数", d,
                List.of(oaToolStart(0, "call_A", "query_order", ""), oaArgs(0, full),
                        oaToolStart(1, "call_B", "query_order", ""), oaArgs(1, "{\"order_no\":\"BT2\"}"), oaFinish("tool_calls")),
                Outcome.ANSWERED, List.of("query_order:BT1", "query_order:BT2")));
        cases.add(Arguments.of("DeepSeek 先开 A 再开 B，随后的分片带调用 id", d,
                List.of(oaToolStart(0, "call_A", "query_order", ""), oaToolStart(1, "call_B", "query_order", ""),
                        oaArgs(0, "call_A", full), oaArgs(1, "call_B", "{\"order_no\":\"BT2\"}"), oaFinish("tool_calls")),
                Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("DeepSeek 并行调用各自紧跟分片，分片带调用 id", d,
                List.of(oaToolStart(0, "call_A", "query_order", ""), oaArgs(0, "call_A", full),
                        oaToolStart(1, "call_B", "query_order", ""), oaArgs(1, "call_B", "{\"order_no\":\"BT2\"}"), oaFinish("tool_calls")),
                Outcome.ANSWERED, List.of("query_order:BT1", "query_order:BT2")));
        cases.add(Arguments.of("DeepSeek 参数串是一个对象后面又跟半截", d,
                List.of(oaToolStart(0, "call_A", "query_order", full + "{\"order_no\":"), oaFinish("tool_calls")),
                Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("DeepSeek 并行调用有一个残缺，整批不执行", d,
                List.of(oaToolStart(0, "call_A", "query_order", full), oaToolStart(1, "call_B", "query_order", half), oaFinish("tool_calls")),
                Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("DeepSeek 内容过滤", d, List.of(oaToolStart(0, "call_A", "query_order", full), oaFinish("content_filter")),
                Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("DeepSeek 明确拒答", d, List.of(oaRefusal("这个请求我不能处理"), oaFinish("stop")), Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("DeepSeek 纯文字到了输出上限", d, openAiText("半截回答", "length"), Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("DeepSeek 纯文字正常结束", d, openAiText("好的", "stop"), Outcome.ANSWERED, List.of()));

        Function<Gateway, Model> o = OPUS;
        cases.add(Arguments.of("Opus 断在参数中途", o,
                List.of(anStart(), anToolStart(0, "toolu_A", "query_order"), anArgs(0, half)), Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("Opus 参数写到一半到了输出上限", o,
                List.of(anStart(), anToolStart(0, "toolu_A", "query_order"), anArgs(0, half), anBlockStop(0), anDelta("max_tokens"), anStop()),
                Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("Opus 半截参数加 end_turn", o,
                List.of(anStart(), anToolStart(0, "toolu_A", "query_order"), anArgs(0, half), anBlockStop(0), anDelta("end_turn"), anStop()),
                Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("Opus 完整调用但结束原因是 end_turn", o,
                List.of(anStart(), anToolStart(0, "toolu_A", "query_order"), anArgs(0, full), anBlockStop(0), anDelta("end_turn"), anStop()),
                Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("Opus tool_use 但参数解析不了", o,
                List.of(anStart(), anToolStart(0, "toolu_A", "query_order"), anArgs(0, "{\"order_no\":\"BT1\""), anBlockStop(0),
                        anDelta("tool_use"), anStop()), Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("Opus tool_use 但调用块没收尾", o,
                List.of(anStart(), anToolStart(0, "toolu_A", "query_order"), anArgs(0, full), anDelta("tool_use"), anStop()),
                Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("Opus 参数串是一个对象后面又跟半截", o,
                List.of(anStart(), anToolStart(0, "toolu_A", "query_order"), anArgs(0, full + "{\"order_no\":"), anBlockStop(0),
                        anDelta("tool_use"), anStop()), Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("Opus 起始块已带完整入参", o,
                List.of(anStart(), anToolStart(0, "toolu_A", "query_order", Map.of("order_no", "BT1")), anBlockStop(0),
                        anDelta("tool_use"), anStop()), Outcome.ANSWERED, List.of("query_order:BT1")));
        cases.add(Arguments.of("Opus message_stop 之后又来工具块", o,
                List.of(anStart(), anToolStart(0, "toolu_A", "query_order"), anArgs(0, full), anBlockStop(0), anDelta("tool_use"), anStop(),
                        anToolStart(1, "toolu_B", "query_order"), anArgs(1, "{\"order_no\":\"BT2\"")), Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("Opus 完整调用照常执行", o,
                List.of(anStart(), anToolStart(0, "toolu_A", "query_order"), anArgs(0, full), anBlockStop(0), anDelta("tool_use"), anStop()),
                Outcome.ANSWERED, List.of("query_order:BT1")));
        cases.add(Arguments.of("Opus 无参工具只有起始块的 input", o,
                List.of(anStart(), anToolStart(0, "toolu_A", "list_categories"), anBlockStop(0), anDelta("tool_use"), anStop()),
                Outcome.ANSWERED, List.of("list_categories")));
        cases.add(Arguments.of("Opus 先开 A 再开 B，随后到 A 的参数", o,
                List.of(anStart(), anToolStart(0, "toolu_A", "query_order"), anToolStart(1, "toolu_B", "query_order"),
                        anArgs(0, full), anBlockStop(0), anBlockStop(1), anDelta("tool_use"), anStop()), Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("Opus 并行调用各收到自己的参数", o,
                List.of(anStart(), anToolStart(0, "toolu_A", "query_order"), anArgs(0, full), anBlockStop(0),
                        anToolStart(1, "toolu_B", "query_order"), anArgs(1, "{\"order_no\":\"BT2\"}"), anBlockStop(1),
                        anDelta("tool_use"), anStop()), Outcome.ANSWERED, List.of("query_order:BT1", "query_order:BT2")));
        cases.add(Arguments.of("Opus 拒答", o, List.of(anStart(), anDelta("refusal"), anStop()), Outcome.INCOMPLETE, List.of()));
        cases.add(Arguments.of("Opus 纯文字到了输出上限", o, anthropicText("半截回答", "max_tokens"), Outcome.INCOMPLETE, List.of()));
        return cases.stream();
    }

    public static class Tools {

        private final List<String> executed = Collections.synchronizedList(new ArrayList<>());

        @Tool(name = "query_order", description = "按订单号查询订单")
        public String queryOrder(@ToolParam(name = "order_no", description = "订单号", required = false) String orderNo) {
            executed.add("query_order:" + orderNo);
            return "{\"status\":\"已发货\"}";
        }

        @Tool(name = "list_categories", description = "列出全部商品类目")
        public String listCategories() {
            executed.add("list_categories");
            return "[\"耳机\"]";
        }
    }
}
