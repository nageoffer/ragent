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

package com.nageoffer.ai.ragent.agent.memory;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONNull;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryDecision.Action;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 仲裁的输入组装、输出解析与模型调用参数；语义交给在线回归
 */
class AgentMemoryJudgeTest {

    private static final AgentMessageDO REQUEST = AgentMessageDO.builder().id("1900000000000000030").conversationId("c-1")
            .userId("u-1").role("user").content("我一直戴眼镜，记住").createTime(Timestamp.valueOf("2026-10-21 10:54:30.123")).build();
    private static final String EMPTY = "{\"decisions\":[],\"explanation\":\"这条发言没有需要长期记住的内容\"}";

    private Model model;
    private AgentProperties agentProperties;
    private AgentMemoryProperties memoryProperties;
    private AgentPromptResolver promptResolver;
    private AgentMemoryJudge judge;

    @BeforeEach
    void setUp() {
        model = mock(Model.class);
        agentProperties = new AgentProperties();
        agentProperties.getChat().setProvider("deepseek");
        memoryProperties = new AgentMemoryProperties();
        promptResolver = mock(AgentPromptResolver.class);
        when(promptResolver.render(eq(AgentPromptSlot.AGENT_MEMORY_EXTRACTION), anyMap())).thenReturn("抽取提示词");
        judge = new AgentMemoryJudge(model, agentProperties, promptResolver, memoryProperties, Jackson2ObjectMapperBuilder.json().build());
    }

    /**
     * 规则在系统消息里，材料序列化成一个 JSON 对象作为一条用户消息；id 全是原样字符串，时间到毫秒、格式一致，空正文不进材料
     */
    @Test
    void flushShouldSendRealIdsAndTimesAsJson() {
        answer(EMPTY);
        memoryProperties.setContextWindowChars(20_000);
        AgentMemoryItem existing = new AgentMemoryItem("2112739267528200192", "用户常驻杭州", "2112739257621254144",
                Timestamp.valueOf("2026-10-21 10:54:26.471"));
        AgentMemoryItem legacy = new AgentMemoryItem("2112000000000000001", "用户是工程师", null, null);

        judge.judgeFlush(List.of(existing, legacy), REQUEST, List.of(
                        message("1900000000000000010", "c-1", "assistant", "您平时戴眼镜吗？"),
                        message("1900000000000000020", "c-1", "assistant", " ")),
                List.of(message("1900000000000000040", "c-2", "user", "清空我的长期记忆")));

        List<Msg> messages = captureMessages();
        assertThat(messages).extracting(Msg::getRole).containsExactly(MsgRole.SYSTEM, MsgRole.USER);
        assertThat(messages.get(0).getTextContent()).isEqualTo("抽取提示词");
        String json = messages.get(1).getTextContent();
        // 长 id 必须是 JSON 字符串，不能变成会丢精度的数字
        assertThat(json).contains("\"id\":\"2112739267528200192\"", "\"sourceMessageId\":\"2112739257621254144\"",
                "\"messageId\":\"" + REQUEST.getId() + "\"", "\"conversationId\":\"c-1\"");
        JSONObject input = JSONUtil.parseObj(json);
        assertThat(input.keySet()).containsExactly("memories", "background", "request", "later");

        JSONArray memories = input.getJSONArray("memories");
        assertThat(memories.getJSONObject(0).keySet()).containsExactly("id", "content", "sourceMessageId", "expressedAt");
        assertThat(memories.getJSONObject(0).getStr("expressedAt")).isEqualTo("2026-10-21 10:54:26.471");
        // 依据消息不在本次材料里也保留真实 id；早期数据的来源两列仍是 null
        assertThat(memories.getJSONObject(1).get("sourceMessageId")).isEqualTo(JSONNull.NULL);
        assertThat(memories.getJSONObject(1).get("expressedAt")).isEqualTo(JSONNull.NULL);

        JSONArray background = input.getJSONArray("background");
        assertThat(background).hasSize(1);
        assertThat(background.getJSONObject(0).keySet()).containsExactly("messageId", "conversationId", "speaker", "time", "text");
        assertThat(background.getJSONObject(0).getStr("messageId")).isEqualTo("1900000000000000010");
        assertThat(background.getJSONObject(0).getStr("speaker")).isEqualTo("assistant");
        assertThat(background.getJSONObject(0).getStr("text")).isEqualTo("您平时戴眼镜吗？");
        JSONObject request = input.getJSONObject("request");
        assertThat(request.getStr("text")).isEqualTo("我一直戴眼镜，记住");
        assertThat(request.getStr("time")).isEqualTo("2026-10-21 10:54:30.123");
        JSONObject later = input.getJSONArray("later").getJSONObject(0);
        assertThat(later.getStr("messageId")).isEqualTo("1900000000000000040");
        assertThat(later.getStr("conversationId")).isEqualTo("c-2");
        assertThat(captureSlots()).containsOnlyKeys("memory_max_chars").containsEntry("memory_max_chars", "1500");
    }

    /**
     * 后台：本批消息与背景都带真实 messageId 和 conversationId，A→B→A 的会话归属照原样给出，顺序保持传入顺序
     * 背景由仓库减好后传入，这里只负责原样组装，不再二次加工
     */
    @Test
    void backgroundShouldKeepRealConversationIdsAndOrder() {
        answer(EMPTY);
        AgentMessageDO context = message("1900000000000000010", "c-A", "user", "我想换个手机");
        AgentMessageDO a1 = message("1900000000000000011", "c-A", "user", "我一直喜欢小屏");
        AgentMessageDO b1 = message("1900000000000000020", "c-B", "user", "想给自己买个平板");
        AgentMessageDO a2 = message("1900000000000000030", "c-A", "user", "还是要轻一点的");

        judge.judgeBackground(List.of(), List.of(a1, b1, a2), List.of(context));

        JSONObject input = JSONUtil.parseObj(captureMessages().get(1).getTextContent());
        assertThat(input.keySet()).containsExactly("memories", "background", "messages");
        assertThat(input.getJSONArray("background")).hasSize(1);
        assertThat(input.getJSONArray("background").getJSONObject(0).getStr("messageId")).isEqualTo(context.getId());
        JSONArray judged = input.getJSONArray("messages");
        assertThat(judged.stream().map(item -> ((JSONObject) item).getStr("messageId")))
                .containsExactly(a1.getId(), b1.getId(), a2.getId());
        assertThat(judged.stream().map(item -> ((JSONObject) item).getStr("conversationId"))).containsExactly("c-A", "c-B", "c-A");
    }

    /**
     * 模型只给来源 id；决策的 id 与表达时间取自那条原始消息，结论带着判定说明
     */
    @Test
    void shouldTakeSourceIdAndTimeFromOriginalMessage() {
        answer("{\"decisions\":[{\"action\":\"ADD\",\"content\":\"用户戴眼镜\",\"sourceMessageId\":\"" + REQUEST.getId() + "\"},"
                + "{\"action\":\"update\",\"id\":\"m-7\",\"content\":\"用户常驻杭州\",\"sourceMessageId\":\"" + REQUEST.getId() + "\"},"
                + "{\"action\":\"DELETE\",\"id\":\"m-8\",\"sourceMessageId\":\"" + REQUEST.getId() + "\"}],"
                + "\"explanation\":\"戴眼镜是稳定信息\"}");

        AgentMemoryJudgement judgement = judge.judgeFlush(List.of(), REQUEST, List.of(), List.of());

        assertThat(judgement.explanation()).isEqualTo("戴眼镜是稳定信息");
        assertThat(judgement.decisions()).extracting(AgentMemoryDecision::action)
                .containsExactly(Action.ADD, Action.UPDATE, Action.DELETE);
        assertThat(judgement.decisions()).allSatisfy(decision -> {
            assertThat(decision.sourceMessageId()).isEqualTo(REQUEST.getId());
            assertThat(decision.expressedAt()).isSameAs(REQUEST.getCreateTime());
        });
    }

    /**
     * 主动整理只能引用当前请求：背景、请求之后的发言、记忆条目 id、不存在的 id、空值都整批作废
     */
    @Test
    void flushShouldRejectSourceOutsideRequest() {
        AgentMessageDO before = message("1900000000000000010", "c-1", "user", "我常驻上海");
        AgentMessageDO after = message("1900000000000000040", "c-1", "user", "我搬去北京了");
        for (String source : List.of(before.getId(), after.getId(), "m-7", "1900000000000000099", "")) {
            answer("{\"decisions\":[{\"action\":\"ADD\",\"content\":\"用户常驻上海\",\"sourceMessageId\":\"" + source
                    + "\"}],\"explanation\":\"x\"}");

            assertThatThrownBy(() -> judge.judgeFlush(
                    List.of(new AgentMemoryItem("m-7", "用户常驻杭州", null, null)), REQUEST, List.of(before), List.of(after)))
                    .as(source).isInstanceOf(IllegalStateException.class).hasMessageContaining("来源");
        }
    }

    /**
     * 后台可以引用本批任一条用户发言，时间取各自那条；背景里的发言不行
     */
    @Test
    void backgroundShouldAcceptAnyMessageInBatchButNotBackground() {
        AgentMessageDO context = message("1900000000000000010", "c-A", "user", "我想换个手机");
        AgentMessageDO a1 = message("1900000000000000011", "c-A", "user", "我一直喜欢小屏");
        AgentMessageDO b1 = message("1900000000000000020", "c-B", "user", "我常驻杭州");
        answer("{\"decisions\":[{\"action\":\"ADD\",\"content\":\"用户偏好小屏手机\",\"sourceMessageId\":\"" + a1.getId() + "\"},"
                + "{\"action\":\"ADD\",\"content\":\"用户常驻杭州\",\"sourceMessageId\":\"" + b1.getId() + "\"}],\"explanation\":\"x\"}");

        List<AgentMemoryDecision> decisions = judge.judgeBackground(List.of(), List.of(a1, b1), List.of(context)).decisions();

        assertThat(decisions).extracting(AgentMemoryDecision::sourceMessageId).containsExactly(a1.getId(), b1.getId());
        assertThat(decisions).extracting(AgentMemoryDecision::expressedAt).containsExactly(a1.getCreateTime(), b1.getCreateTime());

        answer("{\"decisions\":[{\"action\":\"ADD\",\"content\":\"用户想换手机\",\"sourceMessageId\":\"" + context.getId()
                + "\"}],\"explanation\":\"x\"}");
        assertThatThrownBy(() -> judge.judgeBackground(List.of(), List.of(a1, b1), List.of(context)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("来源");
    }

    @Test
    void shouldRequireDecisionsAndExplanation() {
        for (String raw : List.of("[]", "{\"decisions\":[]}", "{\"explanation\":\"x\"}", "{\"decisions\":[],\"explanation\":\" \"}")) {
            answer(raw);

            assertThatThrownBy(() -> judge.judgeFlush(List.of(), REQUEST, List.of(), List.of())).as(raw)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * 只有 ADD、UPDATE、DELETE 三种；旧协议的动作或缺字段一律整批拒绝，不替模型改写
     */
    @Test
    void shouldRejectUnknownActionsAndIncompleteDecisions() {
        for (String decision : List.of(
                "{\"action\":\"CLEAR\",\"sourceMessageId\":\"" + REQUEST.getId() + "\"}",
                "{\"action\":\"NOOP\",\"sourceMessageId\":\"" + REQUEST.getId() + "\"}",
                "{\"action\":\"UPDATE\",\"id\":\"m-7\",\"sourceMessageId\":\"" + REQUEST.getId() + "\"}",
                "{\"action\":\"DELETE\",\"sourceMessageId\":\"" + REQUEST.getId() + "\"}",
                "{\"action\":\"ADD\",\"content\":\"用户戴眼镜\",\"source\":\"" + REQUEST.getId() + "\"}")) {
            answer("{\"decisions\":[" + decision + "],\"explanation\":\"x\"}");

            assertThatThrownBy(() -> judge.judgeFlush(List.of(), REQUEST, List.of(), List.of())).as(decision)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void shouldUseNonStreamingAgentModelWithExplicitTimeoutAndRetries() {
        agentProperties.setMaxRetries(2);
        answer(EMPTY);

        judge.judgeFlush(List.of(), REQUEST, List.of(), List.of());

        GenerateOptions options = captureOptions();
        assertThat(options.getStream()).isFalse();
        assertThat(options.getTemperature()).isEqualTo(0.3D);
        assertThat(options.getTopP()).isNull();
        assertThat(options.getMaxTokens()).isNull();
        assertThat(options.getExecutionConfig().getTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(options.getExecutionConfig().getMaxAttempts()).isEqualTo(2);
        assertThat(options.getAdditionalBodyParams()).containsEntry("thinking", Map.of("type", "enabled"));
        assertThat(options.getReasoningEffort()).isEqualTo("low");
    }

    /**
     * 后台学习与主动整理用同一组参数：开思考、强度 low；思考内容不混进解析的原文
     */
    @Test
    void backgroundShouldEnableThinkingAndParseOnlyFinalText() {
        AgentMessageDO a1 = message("1900000000000000011", "c-A", "user", "我一直喜欢小屏");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(ChatResponse.builder().content(List.of(
                        ThinkingBlock.builder().thinking("{\"decisions\":[{\"action\":\"DELETE\"}]}").build(),
                        TextBlock.builder().text(EMPTY).build()))
                .finishReason("stop").build()));

        assertThat(judge.judgeBackground(List.of(), List.of(a1), List.of()).decisions()).isEmpty();

        GenerateOptions options = captureOptions();
        assertThat(options.getAdditionalBodyParams()).containsEntry("thinking", Map.of("type", "enabled"));
        assertThat(options.getReasoningEffort()).isEqualTo("low");
        assertThat(options.getStream()).isFalse();
        assertThat(options.getExecutionConfig().getTimeout()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void shouldUseLowReasoningEffortWithoutThinkingParamForKimi() {
        agentProperties.getChat().setProvider("kimi");
        answer(EMPTY);

        judge.judgeFlush(List.of(), REQUEST, List.of(), List.of());

        GenerateOptions options = captureOptions();
        assertThat(options.getReasoningEffort()).isEqualTo("low");
        assertThat(options.getAdditionalBodyParams()).doesNotContainKey("thinking");
    }

    @Test
    void shouldNotSendThinkingParamToOtherProviders() {
        agentProperties.getChat().setProvider("bailian");
        answer(EMPTY);

        judge.judgeFlush(List.of(), REQUEST, List.of(), List.of());

        assertThat(captureOptions().getAdditionalBodyParams()).doesNotContainKey("thinking");
        AgentMessageDO a1 = message("1900000000000000011", "c-A", "user", "我一直喜欢小屏");
        org.mockito.Mockito.clearInvocations(model);
        judge.judgeBackground(List.of(), List.of(a1), List.of());
        assertThat(captureOptions().getAdditionalBodyParams()).doesNotContainKey("thinking");
        assertThat(captureOptions().getReasoningEffort()).isNull();
    }

    @Test
    void shouldExtractOnlyTextFromNonStreamingResponse() {
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(
                ChatResponse.builder().content(List.of(
                        ThinkingBlock.builder().thinking("这些推演不能写入记忆").build(),
                        TextBlock.builder().text("{\"decisions\":[{\"action\":\"ADD\",").build(),
                        TextBlock.builder().text("\"content\":\"用户住在南京\",\"sourceMessageId\":\"" + REQUEST.getId() + "\"}],\"explanation\":\"稳定信息\"}").build()))
                        .finishReason("stop").build()));

        assertThat(judge.judgeFlush(List.of(), REQUEST, List.of(), List.of()).decisions())
                .extracting(AgentMemoryDecision::content).containsExactly("用户住在南京");
    }

    @Test
    void shouldRejectMissingOrIncompleteFinishEvenWithValidJson() {
        for (String finishReason : Arrays.asList("length", "content_filter", "aborted", "tool_calls", null)) {
            when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(response(EMPTY, finishReason)));

            assertThatThrownBy(() -> judge.judgeFlush(List.of(), REQUEST, List.of(), List.of()))
                    .as(String.valueOf(finishReason))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("未正常结束");
        }
    }

    @Test
    void shouldRejectMultipleResponsesInNonStreamingMode() {
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(
                response("{", "length"), response("}", null)));

        assertThatThrownBy(() -> judge.judgeFlush(List.of(), REQUEST, List.of(), List.of()))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void shouldRejectEmptyResponseStream() {
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.empty());

        assertThatThrownBy(() -> judge.judgeFlush(List.of(), REQUEST, List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("返回为空");
    }

    /**
     * 没有正文的响应按解析失败处理，不当成空决策
     */
    @Test
    void shouldRejectMissingOrBlankResponseContent() {
        for (List<ContentBlock> content : Arrays.asList(null, List.<ContentBlock>of(),
                List.<ContentBlock>of(ThinkingBlock.builder().thinking("只有思考，没有正文").build()),
                List.<ContentBlock>of(TextBlock.builder().text(" \n ").build()))) {
            when(model.stream(anyList(), any(), any()))
                    .thenReturn(Flux.just(ChatResponse.builder().content(content).finishReason("stop").build()));

            assertThatThrownBy(() -> judge.judgeFlush(List.of(), REQUEST, List.of(), List.of()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("不是约定的 JSON 对象");
        }
    }

    @Test
    void shouldPropagateModelFailure() {
        IllegalStateException failure = new IllegalStateException("模型调用超时");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.error(failure));

        assertThatThrownBy(() -> judge.judgeFlush(List.of(), REQUEST, List.of(), List.of())).isSameAs(failure);
    }

    private void answer(String raw) {
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(response(raw, "stop")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> captureSlots() {
        ArgumentCaptor<Map<String, String>> slots = ArgumentCaptor.forClass(Map.class);
        verify(promptResolver).render(eq(AgentPromptSlot.AGENT_MEMORY_EXTRACTION), slots.capture());
        return slots.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<Msg> captureMessages() {
        ArgumentCaptor<List<Msg>> messages = ArgumentCaptor.forClass(List.class);
        verify(model).stream(messages.capture(), any(), any());
        return messages.getValue();
    }

    private GenerateOptions captureOptions() {
        ArgumentCaptor<GenerateOptions> options = ArgumentCaptor.forClass(GenerateOptions.class);
        verify(model).stream(anyList(), any(), options.capture());
        return options.getValue();
    }

    private ChatResponse response(String text, String finishReason) {
        List<ContentBlock> content = text == null ? List.of() : List.of(TextBlock.builder().text(text).build());
        return ChatResponse.builder().content(content).finishReason(finishReason).build();
    }

    private static AgentMessageDO message(String id, String conversationId, String role, String content) {
        return AgentMessageDO.builder()
                .id(id)
                .conversationId(conversationId)
                .userId("u-1")
                .role(role)
                .content(content)
                .createTime(new Date(Long.parseLong(id.substring(id.length() - 6)) * 1000))
                .build();
    }
}
