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

import com.nageoffer.ai.ragent.agent.dao.mapper.AgentContextCompactionMapper;
import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentContextCompactorTest {

    private static final String FIRST_SUMMARY = "## 用户诉求\n邮件确认后再发\n## 待办\n等待确认\n## 当前进度\n已起草，未发出";
    private static final String SECOND_SUMMARY = "## 用户诉求\n邮件确认后再发\n## 待办\n无\n## 当前进度\n已发出";

    private AgentConversationSummarizer summarizer;
    private AgentContextCompactor compactor;
    private int keep;

    @BeforeEach
    void setUp() {
        AgentMemoryProperties memoryProperties = new AgentMemoryProperties();
        memoryProperties.setContextWindowChars(8_000);
        keep = memoryProperties.resolveKeepRecentChars();
        summarizer = mock(AgentConversationSummarizer.class);
        compactor = new AgentContextCompactor(summarizer, memoryProperties, mock(AgentContextCompactionMapper.class));
    }

    @Test
    void shouldPreserveContextAndSkipAuditWhenSummaryCutByOutputLimit() {
        AgentMemoryProperties memoryProperties = new AgentMemoryProperties();
        memoryProperties.setContextWindowChars(8_000);
        String summary = "## 用户诉求\n确认后再发送邮件\n## 待办\n等待确认\n## 下一步\n资料资料";
        Model model = mock(Model.class);
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(summary).build()))
                .finishReason("length")
                .build()));
        AgentPromptResolver promptResolver = mock(AgentPromptResolver.class);
        when(promptResolver.render(eq(AgentPromptSlot.AGENT_CONTEXT_COMPACTION), anyMap()))
                .thenReturn("按小节输出会话摘要");
        AgentContextCompactionMapper compactionMapper = mock(AgentContextCompactionMapper.class);
        AgentConversationSummarizer summarizer =
                new AgentConversationSummarizer(model, new AgentProperties(), promptResolver, memoryProperties);
        AgentContextCompactor compactor =
                new AgentContextCompactor(summarizer, memoryProperties, compactionMapper);
        List<Msg> context = new ArrayList<>(List.of(
                message(MsgRole.USER, "邮件先起草，未经确认不要发送"),
                message(MsgRole.ASSISTANT, "历史".repeat(2_500)),
                message(MsgRole.USER, "继续介绍近期方案"),
                message(MsgRole.ASSISTANT, "近期".repeat(1_000)),
                message(MsgRole.USER, "继续")));
        List<Msg> before = List.copyOf(context);

        assertThat(compactor.compactInPlace(context, "u-1", "c-1")).isFalse();
        assertThat(context).containsExactlyElementsOf(before);
        for (int i = 0; i < before.size(); i++) {
            assertThat(context.get(i)).isSameAs(before.get(i));
        }
        verify(model).stream(anyList(), any(), any());
        verifyNoInteractions(compactionMapper);
    }

    @Test
    void shouldCutAtNearestUserTurnBeforeKeepBoundary() {
        Msg early = message(MsgRole.USER, "早");
        Msg history = message(MsgRole.ASSISTANT, "历史".repeat(keep * 2));
        Msg turnStart = message(MsgRole.USER, "问");
        Msg reachesKeep = message(MsgRole.ASSISTANT, "助".repeat(keep / 2));
        List<Msg> context = new ArrayList<>(List.of(early, history, turnStart, reachesKeep,
                message(MsgRole.ASSISTANT, "手".repeat(keep / 2)), message(MsgRole.USER, "继续")));
        when(summarizer.summarize(anyList(), isNull())).thenReturn(FIRST_SUMMARY);

        assertThat(compactor.compactInPlace(context, "u-1", "c-1")).isTrue();
        assertThat(capturedMaterial(1).get(0)).containsExactly(early, history);
        assertThat(context.get(1)).isSameAs(turnStart);
    }

    @Test
    void shouldCutAtUserTurnThatReachesKeepExactly() {
        Msg early = message(MsgRole.USER, "早");
        Msg history = message(MsgRole.ASSISTANT, "历史".repeat(keep * 2));
        Msg turnStart = message(MsgRole.USER, "问".repeat(100));
        List<Msg> context = new ArrayList<>(List.of(early, history, turnStart,
                message(MsgRole.ASSISTANT, "答".repeat(keep - 102)), message(MsgRole.USER, "继续")));
        when(summarizer.summarize(anyList(), isNull())).thenReturn(FIRST_SUMMARY);

        assertThat(AgentContextChars.total(context.subList(2, context.size()))).isEqualTo(keep);
        assertThat(compactor.compactInPlace(context, "u-1", "c-1")).isTrue();
        assertThat(capturedMaterial(1).get(0)).containsExactly(early, history);
        assertThat(context.get(1)).isSameAs(turnStart);
    }

    @Test
    void shouldCompactWhenMaterialIsLessThanHalfContext() {
        AgentMemoryProperties memoryProperties = new AgentMemoryProperties();
        memoryProperties.setContextWindowChars(8_000);
        Msg early = message(MsgRole.USER, "早");
        Msg history = message(MsgRole.ASSISTANT, "历史".repeat(keep));
        Msg turnStart = message(MsgRole.USER, "问");
        List<Msg> context = new ArrayList<>(List.of(early, history, turnStart,
                message(MsgRole.ASSISTANT, "近".repeat(keep * 2 + keep / 4)),
                message(MsgRole.USER, "继续")));
        int beforeChars = AgentContextChars.total(context);
        when(summarizer.summarize(anyList(), isNull())).thenReturn(FIRST_SUMMARY);

        assertThat(beforeChars).isGreaterThan(memoryProperties.resolveCompactTriggerChars());
        assertThat(AgentContextChars.total(List.of(early, history)) * 2).isLessThan(beforeChars);
        assertThat(compactor.compactInPlace(context, "u-1", "c-1")).isTrue();
        assertThat(capturedMaterial(1).get(0)).containsExactly(early, history);
        assertThat(context.get(1)).isSameAs(turnStart);
        assertThat(AgentContextChars.total(context)).isLessThan(beforeChars);
    }

    @Test
    void shouldKeepPreviousSummaryOutOfMaterialOnNextGeneration() {
        when(summarizer.summarize(anyList(), any())).thenReturn(FIRST_SUMMARY, SECOND_SUMMARY);
        List<Msg> context = compactedOnce();
        Msg carried = context.get(1);
        context.addAll(List.of(
                message(MsgRole.ASSISTANT, "新历史".repeat(keep)),
                message(MsgRole.USER, "再问"),
                message(MsgRole.ASSISTANT, "近".repeat(keep)),
                message(MsgRole.USER, "继续")));
        ArgumentCaptor<String> existing = ArgumentCaptor.forClass(String.class);

        assertThat(compactor.compactInPlace(context, "u-1", "c-1")).isTrue();
        List<List<Msg>> materials = capturedMaterial(2, existing);
        assertThat(existing.getAllValues().get(1)).isEqualTo(FIRST_SUMMARY);
        assertThat(materials.get(1)).hasSize(4).first().isSameAs(carried);
        assertThat(context.get(0).getTextContent())
                .startsWith("（以下是系统自动生成的历史对话摘要")
                .endsWith("\n" + SECOND_SUMMARY)
                .doesNotContain(FIRST_SUMMARY, "<conversation_summary>", "</conversation_summary>");
    }

    @Test
    void shouldKeepPairedToolMessagesInRetainedTurn() {
        Msg turnStart = message(MsgRole.USER, "订单 123 退货的运费由谁承担");
        Msg call = toolCall("call-shipping");
        Msg result = toolResult("call-shipping");
        Msg current = message(MsgRole.USER, "继续解释");
        List<Msg> messages = new ArrayList<>(List.of(
                message(MsgRole.USER, "商品未拆封，先咨询退货条件"),
                message(MsgRole.ASSISTANT, "退货政策".repeat(keep)),
                turnStart, call, result, current));
        when(summarizer.summarize(anyList(), isNull())).thenReturn("用户仅咨询退货，申请尚未提交");

        assertThat(compactor.compactInPlace(messages, "u-1", "c-1")).isTrue();
        assertThat(messages.subList(1, messages.size()))
                .containsExactly(turnStart, call, result, current);
        assertThat(capturedMaterial(1).get(0)).hasSize(2);
    }

    @Test
    void shouldSkipWhenOnlyUserTurnIsFirstMessage() {
        List<Msg> context = new ArrayList<>(List.of(
                message(MsgRole.USER, "问"),
                message(MsgRole.ASSISTANT, "答".repeat(keep * 3))));

        assertThat(compactor.compactInPlace(context, "u-1", "c-1")).isFalse();
        verifyNoInteractions(summarizer);
    }

    @Test
    void shouldSkipWhenContextShorterThanKeep() {
        List<Msg> context = new ArrayList<>(List.of(
                message(MsgRole.USER, "问"),
                message(MsgRole.ASSISTANT, "答"),
                message(MsgRole.USER, "继续")));

        assertThat(compactor.compactInPlace(context, "u-1", "c-1")).isFalse();
        verifyNoInteractions(summarizer);
    }

    @Test
    void shouldSkipWhenOnlyPreviousSummaryPrecedesCutoff() {
        when(summarizer.summarize(anyList(), isNull())).thenReturn(FIRST_SUMMARY);
        List<Msg> context = compactedOnce();
        List<Msg> before = List.copyOf(context);
        AgentMemoryProperties memoryProperties = new AgentMemoryProperties();
        memoryProperties.setContextWindowChars(8_000);
        Model model = mock(Model.class);
        AgentPromptResolver promptResolver = mock(AgentPromptResolver.class);
        AgentContextCompactionMapper compactionMapper = mock(AgentContextCompactionMapper.class);
        AgentConversationSummarizer realSummarizer =
                new AgentConversationSummarizer(model, new AgentProperties(), promptResolver, memoryProperties);
        AgentContextCompactor realCompactor =
                new AgentContextCompactor(realSummarizer, memoryProperties, compactionMapper);

        assertThat(context.get(1).getRole()).isEqualTo(MsgRole.USER);
        assertThat(realCompactor.compactInPlace(context, "u-1", "c-1")).isFalse();
        assertThat(context).containsExactlyElementsOf(before);
        verifyNoInteractions(model, promptResolver, compactionMapper);
    }

    /**
     * 先真实压缩一次，得到 [摘要, 用户, 助手(够保留量), 用户]
     */
    private List<Msg> compactedOnce() {
        List<Msg> context = new ArrayList<>(List.of(
                message(MsgRole.USER, "早"),
                message(MsgRole.ASSISTANT, "历史".repeat(keep * 2)),
                message(MsgRole.USER, "问"),
                message(MsgRole.ASSISTANT, "答".repeat(keep)),
                message(MsgRole.USER, "继续")));
        assertThat(compactor.compactInPlace(context, "u-1", "c-1")).isTrue();
        assertThat(context).hasSize(4);
        return context;
    }

    private List<List<Msg>> capturedMaterial(int calls) {
        return capturedMaterial(calls, ArgumentCaptor.forClass(String.class));
    }

    @SuppressWarnings("unchecked")
    private List<List<Msg>> capturedMaterial(int calls, ArgumentCaptor<String> existing) {
        ArgumentCaptor<List<Msg>> material = ArgumentCaptor.forClass(List.class);
        verify(summarizer, times(calls)).summarize(material.capture(), existing.capture());
        return material.getAllValues();
    }

    private static Msg message(MsgRole role, String text) {
        return Msg.builder()
                .name(role == MsgRole.USER ? "user" : "assistant")
                .role(role)
                .textContent(text)
                .build();
    }

    private static Msg toolCall(String id) {
        return Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(ToolUseBlock.builder().id(id).name("search_knowledge")
                        .input(Map.of("query", "订单 123 退货运费"))
                        .content("{\"query\":\"订单 123 退货运费\"}").build())
                .build();
    }

    private Msg toolResult(String id) {
        return Msg.builder().name("tool").role(MsgRole.TOOL)
                .content(ToolResultBlock.builder().id(id).name("search_knowledge")
                        .output(TextBlock.builder().text("退货运费规则".repeat(keep / 6 + 1)).build()).build())
                .build();
    }
}
