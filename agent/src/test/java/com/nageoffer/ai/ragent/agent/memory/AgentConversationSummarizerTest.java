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

import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentConversationSummarizerTest {

    private static final List<Msg> MATERIAL = List.of(Msg.builder()
            .name("user")
            .role(MsgRole.USER)
            .textContent("邮件先起草，等我确认后再发送")
            .build());

    private Model model;
    private AgentPromptResolver promptResolver;
    private AgentProperties agentProperties;
    private AgentMemoryProperties memoryProperties;
    private AgentConversationSummarizer summarizer;

    @BeforeEach
    void setUp() {
        model = mock(Model.class);
        agentProperties = new AgentProperties();
        agentProperties.getChat().setProvider("deepseek");
        promptResolver = mock(AgentPromptResolver.class);
        when(promptResolver.render(eq(AgentPromptSlot.AGENT_CONTEXT_COMPACTION), anyMap()))
                .thenReturn("按小节输出会话摘要");
        memoryProperties = new AgentMemoryProperties();
        memoryProperties.setContextWindowChars(20_000);
        summarizer = new AgentConversationSummarizer(model, agentProperties, promptResolver, memoryProperties);
    }

    @Test
    void shouldAcceptTrimmedSummary() {
        String summary = """
                ## 用户诉求
                确认后再发送邮件
                ## 待办
                等待确认
                ## 下一步
                无
                ## 当前进度
                邮件已起草，未发出
                ## 工具与发现
                无
                ## 走不通的路
                无
                ## 已完成
                已起草邮件""";
        answer(" \n" + summary + "\n ");

        assertThat(summarizer.summarize(MATERIAL, null)).isEqualTo(summary);
    }

    @Test
    void shouldAcceptSummaryInAnyFormat() {
        answer("邮件已起草，尚未发出，等待用户确认");

        assertThat(summarizer.summarize(MATERIAL, null)).isEqualTo("邮件已起草，尚未发出，等待用户确认");
    }

    @Test
    void shouldRejectEmptyResponseStream() {
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.empty());

        assertThat(summarizer.summarize(MATERIAL, null)).isNull();
    }

    @Test
    void shouldAcceptSummaryOverTargetWhenNotCut() {
        int targetChars = memoryProperties.resolveSummaryTargetChars();
        String summary = "## 用户诉求\n确认后再发送邮件\n## 待办\n等待确认\n## 下一步\n"
                + "资料".repeat(targetChars / 2)
                + "\n## 当前进度\n邮件已起草，未发出\n## 工具与发现\n无\n## 走不通的路\n无\n## 已完成\n已起草邮件";
        answer(summary);

        assertThat(summary.length()).isGreaterThan(targetChars);
        assertThat(summarizer.summarize(MATERIAL, null)).isEqualTo(summary);
    }

    @Test
    void shouldRejectBlankSummary() {
        answer(" \n\t ");

        assertThat(summarizer.summarize(MATERIAL, null)).isNull();
    }

    @Test
    void shouldRejectNullSummary() {
        answer(null);

        assertThat(summarizer.summarize(MATERIAL, null)).isNull();
    }

    @Test
    void shouldRejectModelException() {
        when(model.stream(anyList(), any(), any()))
                .thenReturn(Flux.error(new IllegalStateException("模型调用超时")));

        assertThat(summarizer.summarize(MATERIAL, null)).isNull();
    }

    @Test
    void shouldRejectSummaryCutByOutputLimit() {
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(response("## 当前进度\n邮件已起", "length")));

        assertThat(summarizer.summarize(MATERIAL, null)).isNull();
    }

    @Test
    void shouldRejectSummaryNotFinishedByModel() {
        for (String finishReason : Arrays.asList(
                "content_filter", "insufficient_system_resource", "aborted", "tool_calls", null)) {
            when(model.stream(anyList(), any(), any()))
                    .thenReturn(Flux.just(response("## 当前进度\n邮件已起草，未发出", finishReason)));

            assertThat(summarizer.summarize(MATERIAL, null)).as(String.valueOf(finishReason)).isNull();
        }
    }

    @Test
    void shouldWrapMaterialBetweenPromptAndClosing() {
        Msg question = userMsg("2026-10-04 12:34:56.789", "左耳没声音");
        answer("## 当前进度\n无");

        summarizer.summarize(List.of(question), "## 当前进度\n旧摘要");

        List<Msg> messages = captureMessages();
        assertThat(messages).extracting(Msg::getRole)
                .containsExactly(MsgRole.SYSTEM, MsgRole.USER, MsgRole.USER, MsgRole.USER);
        assertThat(messages.get(0).getTextContent()).isEqualTo("按小节输出会话摘要");
        assertThat(messages.get(1).getTextContent()).isEqualTo("<previous_summary>\n## 当前进度\n旧摘要\n</previous_summary>");
        assertThat(messages.get(2)).isSameAs(question);
        assertThat(messages.get(3).getTextContent()).contains("不继续对话")
                .endsWith("总长度不超过 " + memoryProperties.resolveSummaryTargetChars() + " 个字符。");
    }

    @Test
    void shouldPassToolMessagesThroughWithTheirRoles() {
        String query = "AirPods Pro 左耳无声".repeat(60);
        String result = "保修期内免费维修".repeat(150) + "但需经书面同意，且仅限签收七天内" + "送修流程".repeat(150);
        Msg toolUse = Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(ToolUseBlock.builder().id("call-1").name("search_knowledge")
                        .input(Map.of("query", query)).build())
                .build();
        Msg toolResult = Msg.builder().name("tool").role(MsgRole.TOOL)
                .content(ToolResultBlock.builder().id("call-1").name("search_knowledge")
                        .output(TextBlock.builder().text(result).build()).build())
                .build();
        answer("## 当前进度\n无");

        summarizer.summarize(List.of(toolUse, toolResult), null);

        assertThat(captureMessages()).containsSubsequence(toolUse, toolResult);
        verify(model).stream(anyList(), isNull(), any());
    }

    @Test
    void shouldDropThinkingFromMaterial() {
        Msg reply = Msg.builder().name("assistant").role(MsgRole.ASSISTANT).timestamp("2026-10-04 12:35:00.000")
                .content(List.of(
                        ThinkingBlock.builder().thinking("用户可能想直接提交").build(),
                        TextBlock.builder().text("好的，申请先不提交").build()))
                .build();
        Msg thinkingOnly = Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(ThinkingBlock.builder().thinking("再想想").build())
                .build();
        answer("## 当前进度\n无");

        summarizer.summarize(List.of(userMsg("2026-10-04 12:34:56.789", "先别提交"), reply, thinkingOnly), null);

        List<Msg> messages = captureMessages();
        assertThat(messages).hasSize(4);
        assertThat(messages.get(2).getContent()).hasSize(1).allMatch(TextBlock.class::isInstance);
        assertThat(messages.get(2).getTimestamp()).isEqualTo("2026-10-04 12:35:00.000");
    }

    @Test
    void shouldSkipModelWhenMaterialHasNoContent() {
        Msg thinkingOnly = Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(ThinkingBlock.builder().thinking("再想想").build())
                .build();

        assertThat(summarizer.summarize(List.of(userMsg("", " \n "), thinkingOnly), "## 当前进度\n旧摘要")).isNull();
        verifyNoInteractions(model, promptResolver);
    }

    @Test
    void shouldJoinTextAcrossResponses() {
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(
                response("## 当前进度\n邮件", null), response("已起草，未发出", "stop")));

        assertThat(summarizer.summarize(MATERIAL, null)).isEqualTo("## 当前进度\n邮件已起草，未发出");
    }

    @Test
    void shouldRejectWhenEarlierResponseCutByOutputLimit() {
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(
                response("## 当前进度\n邮件已起", "length"), response("草", null)));

        assertThat(summarizer.summarize(MATERIAL, null)).isNull();
    }

    @Test
    void shouldIgnoreThinkingBlocks() {
        ChatResponse response = ChatResponse.builder()
                .content(List.of(
                        ThinkingBlock.builder().thinking("先想想").build(),
                        TextBlock.builder().text("## 当前进度\n邮件已起草，尚未发出").build()))
                .finishReason("stop")
                .build();
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(response));

        assertThat(summarizer.summarize(MATERIAL, null)).isEqualTo("## 当前进度\n邮件已起草，尚未发出");
    }

    @Test
    void shouldDisableThinkingAndUseAgentRetriesForDeepSeek() {
        agentProperties.setMaxRetries(1);
        answer("邮件已起草，尚未发出");

        summarizer.summarize(MATERIAL, null);

        GenerateOptions options = captureOptions();
        assertThat(options.getStream()).isFalse();
        assertThat(options.getMaxTokens()).isNull();
        assertThat(options.getExecutionConfig().getMaxAttempts()).isEqualTo(1);
        assertThat(options.getAdditionalBodyParams()).containsEntry("thinking", Map.of("type", "disabled"));
    }

    @Test
    void shouldNotSendThinkingParamToOtherProviders() {
        agentProperties.getChat().setProvider("bailian");
        answer("邮件已起草，尚未发出");

        summarizer.summarize(MATERIAL, null);

        assertThat(captureOptions().getAdditionalBodyParams()).doesNotContainKey("thinking");
    }

    private GenerateOptions captureOptions() {
        ArgumentCaptor<GenerateOptions> options = ArgumentCaptor.forClass(GenerateOptions.class);
        verify(model).stream(anyList(), any(), options.capture());
        return options.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<Msg> captureMessages() {
        ArgumentCaptor<List<Msg>> messages = ArgumentCaptor.forClass(List.class);
        verify(model).stream(messages.capture(), any(), any());
        return messages.getValue();
    }

    private static Msg userMsg(String timestamp, String text) {
        return Msg.builder().name("user").role(MsgRole.USER).timestamp(timestamp).textContent(text).build();
    }

    private void answer(String summary) {
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(response(summary, "stop")));
    }

    private ChatResponse response(String text, String finishReason) {
        List<ContentBlock> content = text == null
                ? List.of()
                : List.of(TextBlock.builder().text(text).build());
        return ChatResponse.builder().content(content).finishReason(finishReason).build();
    }
}
