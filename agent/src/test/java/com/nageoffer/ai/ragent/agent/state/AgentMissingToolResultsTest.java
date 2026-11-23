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

package com.nageoffer.ai.ragent.agent.state;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.config.ProductionModels.Gateway;
import com.nageoffer.ai.ragent.agent.config.ProductionModels;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentStateMapper;
import com.nageoffer.ai.ragent.agent.memory.AgentConversationSummarizer;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 库里的会话状态有缺结果的工具调用时，加载就补齐：DeepSeek 与 Opus 遇到这种历史都会拒收（2026-11-23 实测）
 * 坏快照由真实停止流程产生、不预先修复，三条读历史的路径各从一份新拷贝开始，第一问就要成功
 */
class AgentMissingToolResultsTest {

    private static final String USER = "u-1001";
    private static final String SESSION = "c-2002";
    private static final String KEY = "agent_state";
    private static final String RECOVERY = "I noticed that you have interrupted me. What can I do for you?";

    @Test
    void shouldFillCallFollowedByRecoveryMessage() {
        List<Msg> context = new ArrayList<>(List.of(user("查订单"), calls("call_A"), assistantText(RECOVERY)));

        assertThat(AgentMissingToolResults.fill(context)).isEqualTo(1);

        assertThat(context).extracting(Msg::getRole)
                .containsExactly(MsgRole.USER, MsgRole.ASSISTANT, MsgRole.TOOL, MsgRole.ASSISTANT);
        ToolResultBlock filled = context.get(2).getFirstContentBlock(ToolResultBlock.class);
        assertThat(filled.getId()).isEqualTo("call_A");
        assertThat(((TextBlock) filled.getOutput().get(0)).getText()).isEqualTo(AgentMissingToolResults.UNKNOWN_RESULT);
        assertThat(context.get(2).getName()).isEqualTo("RagentAI");
    }

    /**
     * 并行批里已经返回的真实结果原样保留，只补缺的那个，排在真实结果之后
     */
    @Test
    void shouldKeepRealResultsAndFillOnlyMissingOnes() {
        Msg real = result("call_A", "{\"status\":\"已发货\"}");
        List<Msg> context = new ArrayList<>(List.of(user("查两单"), calls("call_A", "call_B"), real, assistantText(RECOVERY)));

        assertThat(AgentMissingToolResults.fill(context)).isEqualTo(1);

        assertThat(context.get(2)).isSameAs(real);
        assertThat(context.get(3).getFirstContentBlock(ToolResultBlock.class).getId()).isEqualTo("call_B");
        assertThat(context.get(4).getRole()).isEqualTo(MsgRole.ASSISTANT);
    }

    /**
     * 对话还停在这批调用上（待确认的卡、框架自己会补的）就不碰，否则会替用户把卡判掉
     */
    @Test
    void shouldLeaveOpenBatchAlone() {
        List<Msg> trailing = new ArrayList<>(List.of(user("下单"), calls("call_A")));
        List<Msg> partlyAnswered = new ArrayList<>(List.of(user("下单"), calls("call_A", "call_B"), result("call_A", "{}")));

        assertThat(AgentMissingToolResults.fill(trailing)).isZero();
        assertThat(AgentMissingToolResults.fill(partlyAnswered)).isZero();
    }

    /**
     * 兜底生成的 id 带毫秒时间戳，同一毫秒就会重复；按全会话找结果，前一批的结果会掩盖后一批的缺口
     */
    @Test
    void shouldPairByBatchWhenIdIsReused() {
        List<Msg> context = new ArrayList<>(List.of(user("查订单"), calls("tool_call_1"), result("tool_call_1", "{}"),
                assistantText("已发货"), user("再查一次"), calls("tool_call_1"), assistantText(RECOVERY)));

        assertThat(AgentMissingToolResults.fill(context)).isEqualTo(1);

        assertThat(context.get(6).getFirstContentBlock(ToolResultBlock.class).getId()).isEqualTo("tool_call_1");
        assertThat(context.get(7).getTextContent()).isEqualTo(RECOVERY);
        assertThat(AgentMissingToolResults.fill(context)).as("补过一次再补不会多出结果").isZero();
    }

    @Test
    void shouldServeFirstQuestionFromBrokenSnapshotForAnthropic() throws Exception {
        Map<String, String> broken = stopWhileCallStreams(ProductionModels::opus, anthropicCall("toolu_A"));
        String summaryResponse = "{\"id\":\"m\",\"content\":[{\"type\":\"text\",\"text\":\"## 当前进度\\n无\"}],"
                + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";

        Store next = new Store(broken);
        String followUp = followUp(ProductionModels::opus, next, 10, anthropicText());
        String summary = summarize(ProductionModels::opus, "zenmux-anthropic", new Store(broken), summaryResponse);
        String maxIters = followUp(ProductionModels::opus, new Store(broken), 1, anthropicCall("toolu_B"), anthropicText());

        SoftAssertions.assertSoftly(softly -> {
            assertPaired(softly, "普通续聊", followUp, AgentMissingToolResultsTest::anthropicPairs);
            assertPaired(softly, "压缩摘要", summary, AgentMissingToolResultsTest::anthropicPairs);
            assertPaired(softly, "迭代上限收尾", maxIters, AgentMissingToolResultsTest::anthropicPairs);
            softly.assertThat(JSONUtil.parseObj(maxIters).containsKey("tools")).as("迭代上限收尾不带工具声明").isFalse();
            softly.assertThat(followUp).as("补的结果标成出错").contains("\"tool_use_id\":\"toolu_A\"", "\"is_error\":true");
            softly.assertThat(resultsFor(next, "toolu_A")).as("修好的状态存回去后再加载不会多补").isEqualTo(1);
        });
    }

    @Test
    void shouldServeFirstQuestionFromBrokenSnapshotForDeepSeek() throws Exception {
        Map<String, String> broken = stopWhileCallStreams(ProductionModels::deepseek, openAiCall("call_A"));
        String summaryResponse = "{\"id\":\"s\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":"
                + "{\"role\":\"assistant\",\"content\":\"## 当前进度\\n无\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";

        Store next = new Store(broken);
        String followUp = followUp(ProductionModels::deepseek, next, 10, openAiText());
        String summary = summarize(ProductionModels::deepseek, "deepseek", new Store(broken), summaryResponse);
        String maxIters = followUp(ProductionModels::deepseek, new Store(broken), 1, openAiCall("call_B"), openAiText());

        SoftAssertions.assertSoftly(softly -> {
            assertPaired(softly, "普通续聊", followUp, AgentMissingToolResultsTest::openAiPairs);
            assertPaired(softly, "压缩摘要", summary, AgentMissingToolResultsTest::openAiPairs);
            assertPaired(softly, "迭代上限收尾", maxIters, AgentMissingToolResultsTest::openAiPairs);
            softly.assertThat(resultsFor(next, "call_A")).as("修好的状态存回去后再加载不会多补").isEqualTo(1);
        });
    }

    /**
     * 待确认的卡存进库再加载时不能被补齐当成缺结果：放行后工具照常执行一次，请求里调用与结果紧邻
     */
    @Test
    void shouldResumeConfirmCardThroughStateStore() {
        Store store = new Store(Map.of());
        AtomicInteger executed = new AtomicInteger();
        Gateway asks = new Gateway().thenStream(openAiCall("call_C"));
        ReActAgent first = confirmAgent(ProductionModels.deepseek(asks), store, executed);
        Msg paused = first.call("把订单 BT1 取消", context()).block(Duration.ofSeconds(10));
        first.clearStateCache(USER, SESSION);
        assertThat(paused.getGenerateReason()).isEqualTo(GenerateReason.PERMISSION_ASKING);
        assertThat(executed).as("确认前不执行").hasValue(0);

        Gateway resumes = new Gateway().thenStream(openAiText());
        ReActAgent second = confirmAgent(ProductionModels.deepseek(resumes), store, executed);
        ToolUseBlock asking = new PgAgentStateStore(store).get(USER, SESSION, KEY, AgentState.class).orElseThrow()
                .contextMutable().get(1).getFirstContentBlock(ToolUseBlock.class);
        second.call(List.of(UserMessage.builder()
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.of(new ConfirmResult(true, asking))))
                .build()), context()).block(Duration.ofSeconds(10));

        assertThat(executed).as("放行后执行一次").hasValue(1);
        SoftAssertions.assertSoftly(softly ->
                assertPaired(softly, "确认续跑", resumes.lastRequest(), AgentMissingToolResultsTest::openAiPairs));
        assertThat(resumes.lastRequest()).doesNotContain(AgentMissingToolResults.UNKNOWN_RESULT);
    }

    private static ReActAgent confirmAgent(Model model, Store store, AtomicInteger executed) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new ConfirmedTool(executed));
        return ReActAgent.builder().name("RagentAI").sysPrompt("你是售后客服").model(model).toolkit(toolkit)
                .maxIters(10).maxRetries(1).enablePendingToolRecovery(true)
                .stateStore(new PgAgentStateStore(store)).build();
    }

    /**
     * 写操作要先确认，跟 McpToolProxy 的 needsConfirm 一样从 checkPermissions 返回 ask
     */
    private static final class ConfirmedTool extends ToolBase {

        private final AtomicInteger executed;

        private ConfirmedTool(AtomicInteger executed) {
            super(ToolBase.builder().name("query_order").description("取消订单")
                    .inputSchema(Map.of("type", "object", "properties", Map.of("order_no", Map.of("type", "string")))));
            this.executed = executed;
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.ask("该操作执行前需要你确认"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            executed.incrementAndGet();
            return Mono.just(ToolResultBlock.builder().id(param.getToolUseBlock().getId()).name(getName())
                    .output(TextBlock.builder().text("{\"status\":\"已取消\"}").build()).state(ToolResultState.SUCCESS).build());
        }
    }

    /**
     * 第一问：模型吐出工具调用后、流还没结束时用户停止；框架存下的就是坏状态，这里不做任何修复
     */
    private static Map<String, String> stopWhileCallStreams(Function<Gateway, Model> model, List<String> callStream)
            throws Exception {
        CompletableFuture<Void> streamed = new CompletableFuture<>();
        CompletableFuture<Void> resume = new CompletableFuture<>();
        Gateway gateway = new Gateway().thenStream(Flux.concat(
                Flux.fromIterable(callStream.subList(0, callStream.size() - 1)).doOnComplete(() -> streamed.complete(null)),
                Mono.fromFuture(resume).thenMany(Flux.fromIterable(callStream.subList(callStream.size() - 1, callStream.size())))));
        Store store = new Store(Map.of());
        ReActAgent agent = agent(model.apply(gateway), store, 10);
        CompletableFuture<Msg> first = agent.call("查一下订单 BT1", context()).subscribeOn(Schedulers.boundedElastic()).toFuture();
        streamed.get(10, TimeUnit.SECONDS);
        agent.interrupt(USER, SESSION);
        resume.complete(null);
        assertThat(first.get(10, TimeUnit.SECONDS).getTextContent()).isEqualTo(RECOVERY);
        agent.clearStateCache(USER, SESSION);

        AgentState raw = JsonUtils.getJsonCodec().fromJson(store.selectPayload(USER, SESSION, KEY), AgentState.class);
        assertThat(AgentMissingToolResults.fill(raw.contextMutable())).as("快照确实是坏的").isEqualTo(1);
        return Map.copyOf(store.rows);
    }

    /**
     * 新建 Agent 从存下的状态接着问；maxIters 为 1 时第二次调模型就是迭代上限收尾，那次请求不带工具声明
     */
    @SafeVarargs
    private static String followUp(Function<Gateway, Model> model, Store store, int maxIters, List<String>... streams) {
        Gateway gateway = new Gateway();
        for (List<String> stream : streams) {
            gateway.thenStream(stream);
        }
        Msg answer = agent(model.apply(gateway), store, maxIters).call("算了，帮我看看购物车", context()).block(Duration.ofSeconds(10));
        assertThat(answer).as("第一问就要成功").isNotNull();
        return gateway.lastRequest();
    }

    /**
     * 压缩摘要直接调模型，不经过任何 Middleware；素材是从库里加载的会话上下文
     */
    private static String summarize(Function<Gateway, Model> model, String provider, Store store, String response) {
        Gateway gateway = new Gateway().thenBody(response);
        AgentProperties properties = new AgentProperties();
        properties.getChat().setProvider(provider);
        AgentPromptResolver prompts = mock(AgentPromptResolver.class);
        when(prompts.render(any(), anyMap())).thenReturn("按小节输出会话摘要");
        AgentMemoryProperties memory = new AgentMemoryProperties();
        memory.setContextWindowChars(100_000);
        List<Msg> material = new PgAgentStateStore(store).get(USER, SESSION, KEY, AgentState.class).orElseThrow().contextMutable();
        String summary = new AgentConversationSummarizer(model.apply(gateway), properties, prompts, memory)
                .summarize(material, null, context());
        assertThat(summary).as("摘要要成功").isNotBlank();
        return gateway.lastRequest();
    }

    private static int resultsFor(Store store, String callId) {
        AgentState state = new PgAgentStateStore(store).get(USER, SESSION, KEY, AgentState.class).orElseThrow();
        return (int) state.contextMutable().stream()
                .flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                .filter(result -> callId.equals(result.getId()))
                .count();
    }

    private static ReActAgent agent(Model model, Store store, int maxIters) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new Tools());
        return ReActAgent.builder().name("RagentAI").sysPrompt("你是售后客服").model(model).toolkit(toolkit)
                .maxIters(maxIters).maxRetries(1).enablePendingToolRecovery(true)
                .stateStore(new PgAgentStateStore(store)).build();
    }

    private static void assertPaired(SoftAssertions softly, String path, String body,
                                     Function<JSONObject, List<List<Set<String>>>> pairs) {
        List<List<Set<String>>> found = pairs.apply(JSONUtil.parseObj(body));
        softly.assertThat(found).as(path + "：请求里应有工具调用").isNotEmpty();
        found.forEach(pair -> softly.assertThat(pair.get(1)).as(path + "：调用 " + pair.get(0) + " 后面必须紧跟同一批结果")
                .isEqualTo(pair.get(0)));
    }

    /**
     * Anthropic：带 tool_use 的 assistant 回合，下一回合必须是同一批 id 的 tool_result
     */
    private static List<List<Set<String>>> anthropicPairs(JSONObject body) {
        JSONArray turns = body.getJSONArray("messages");
        List<List<Set<String>>> pairs = new ArrayList<>();
        for (int i = 0; i < turns.size(); i++) {
            Set<String> calls = ids(turns.getJSONObject(i).getJSONArray("content"), "tool_use", "id");
            if ("assistant".equals(turns.getJSONObject(i).getStr("role")) && !calls.isEmpty()) {
                Set<String> results = i + 1 < turns.size()
                        ? ids(turns.getJSONObject(i + 1).getJSONArray("content"), "tool_result", "tool_use_id") : Set.of();
                pairs.add(List.of(calls, results));
            }
        }
        return pairs;
    }

    /**
     * OpenAI 兼容：带 tool_calls 的 assistant 消息，后面连续的 tool 消息必须覆盖同一批 id
     */
    private static List<List<Set<String>>> openAiPairs(JSONObject body) {
        JSONArray messages = body.getJSONArray("messages");
        List<List<Set<String>>> pairs = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            JSONArray toolCalls = messages.getJSONObject(i).getJSONArray("tool_calls");
            if (toolCalls == null || toolCalls.isEmpty()) {
                continue;
            }
            Set<String> calls = new HashSet<>();
            toolCalls.forEach(call -> calls.add(((JSONObject) call).getStr("id")));
            Set<String> results = new HashSet<>();
            for (int j = i + 1; j < messages.size() && "tool".equals(messages.getJSONObject(j).getStr("role")); j++) {
                results.add(messages.getJSONObject(j).getStr("tool_call_id"));
            }
            pairs.add(List.of(calls, results));
        }
        return pairs;
    }

    private static Set<String> ids(JSONArray content, String type, String key) {
        Set<String> ids = new HashSet<>();
        if (content != null) {
            content.forEach(block -> {
                if (type.equals(((JSONObject) block).getStr("type"))) {
                    ids.add(((JSONObject) block).getStr(key));
                }
            });
        }
        return ids;
    }

    private static List<String> anthropicCall(String id) {
        return List.of(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"m-" + id + "\",\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"" + id
                        + "\",\"name\":\"query_order\",\"input\":{}}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"order_no\\\":\\\"BT1\\\"}\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":20}}",
                "{\"type\":\"message_stop\"}");
    }

    private static List<String> anthropicText() {
        return List.of(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"m-text\",\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"好的\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}",
                "{\"type\":\"message_stop\"}");
    }

    private static List<String> openAiCall(String id) {
        return List.of(
                "{\"id\":\"c-" + id + "\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"" + id
                        + "\",\"type\":\"function\",\"function\":{\"name\":\"query_order\",\"arguments\":\"{\\\"order_no\\\":\\\"BT1\\\"}\"}}]},\"finish_reason\":null}]}",
                "{\"id\":\"c-" + id + "\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
    }

    private static List<String> openAiText() {
        return List.of(
                "{\"id\":\"c-text\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"好的\"},\"finish_reason\":null}]}",
                "{\"id\":\"c-text\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
    }

    private static RuntimeContext context() {
        return RuntimeContext.builder().userId(USER).sessionId(SESSION).build();
    }

    private static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static Msg assistantText(String text) {
        return Msg.builder().name("RagentAI").role(MsgRole.ASSISTANT).textContent(text).build();
    }

    private static Msg calls(String... ids) {
        List<ContentBlock> blocks = new ArrayList<>();
        for (String id : ids) {
            blocks.add(ToolUseBlock.builder().id(id).name("query_order").input(Map.of("order_no", id)).build());
        }
        return Msg.builder().name("RagentAI").role(MsgRole.ASSISTANT).content(blocks).build();
    }

    private static Msg result(String id, String text) {
        return Msg.builder().role(MsgRole.TOOL)
                .content(new ToolResultBlock(id, "query_order", TextBlock.builder().text(text).build())).build();
    }

    public static class Tools {

        @Tool(name = "query_order", description = "按订单号查询订单")
        public String queryOrder(@ToolParam(name = "order_no", description = "订单号", required = false) String orderNo) {
            return "{\"order_no\":\"" + orderNo + "\",\"status\":\"已发货\"}";
        }
    }

    private static final class Store implements AgentStateMapper {

        private final Map<String, String> rows;

        private Store(Map<String, String> snapshot) {
            this.rows = new HashMap<>(snapshot);
        }

        @Override
        public void upsert(String userId, String sessionId, String stateKey, String payload) {
            rows.put(userId + "|" + sessionId + "|" + stateKey, payload);
        }

        @Override
        public String selectPayload(String userId, String sessionId, String stateKey) {
            return rows.get(userId + "|" + sessionId + "|" + stateKey);
        }

        @Override
        public boolean exists(String userId, String sessionId) {
            return rows.keySet().stream().anyMatch(key -> key.startsWith(userId + "|" + sessionId + "|"));
        }

        @Override
        public void deleteBySession(String userId, String sessionId) {
            rows.keySet().removeIf(key -> key.startsWith(userId + "|" + sessionId + "|"));
        }

        @Override
        public void deleteByKey(String userId, String sessionId, String stateKey) {
            rows.remove(userId + "|" + sessionId + "|" + stateKey);
        }

        @Override
        public List<String> selectSessionIds(String userId) {
            return List.of(SESSION);
        }
    }
}
