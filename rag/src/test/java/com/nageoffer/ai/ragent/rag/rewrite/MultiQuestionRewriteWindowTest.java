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

package com.nageoffer.ai.ragent.rag.rewrite;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.rewrite.MultiQuestionRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryTermMappingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 改写阶段的历史取舍契约：摘要与全部历史提问原样带上，助手回答只留最近两条可用的
 * <p>
 * 指代的落点大多是用户自己提过的主体，提问被截掉就再也还原不出实体；
 * 助手回答是历史里唯一贵的部分，必须封顶，且中断与限流的残句不许占名额
 */
class MultiQuestionRewriteWindowTest {

    private static final String QUESTION = "它的上限是多少";
    private static final String LLM_JSON = """
            {"rewrite": "差旅报销的上限是多少", "sub_questions": ["差旅报销的上限是多少"]}
            """;

    private final LLMService llmService = mock(LLMService.class);
    private final QueryTermMappingService queryTermMappingService = mock(QueryTermMappingService.class);
    private final PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);

    /**
     * 常规多轮：摘要留在原位，历史提问一条不少，助手回答只剩最近两条
     */
    @Test
    void keepsSummaryAndEveryUserQuestionButOnlyLastTwoAnswers() {
        List<ChatMessage> history = List.of(
                ChatMessage.system("<conversation-summary>用户在咨询差旅报销</conversation-summary>"),
                ChatMessage.user("差旅报销走什么流程"),
                ChatMessage.assistant("先在 OA 提交申请单"),
                ChatMessage.user("需要哪些材料"),
                ChatMessage.assistant("发票和审批单"),
                ChatMessage.user("多久能到账"),
                ChatMessage.assistant("审批通过后三个工作日"));

        List<ChatMessage> sent = captureHistorySentToLlm(history);

        assertThat(contentsOf(sent)).containsExactly(
                "<conversation-summary>用户在咨询差旅报销</conversation-summary>",
                "差旅报销走什么流程",
                "需要哪些材料",
                "发票和审批单",
                "多久能到账",
                "审批通过后三个工作日");
    }

    /**
     * 摘要是 SYSTEM 角色，喂给改写时必须保留，靠它才能消解压缩掉的早期轮次里的实体
     */
    @Test
    void keepsSummaryMessage() {
        List<ChatMessage> history = List.of(
                ChatMessage.system("<conversation-summary>用户在咨询差旅报销</conversation-summary>"),
                ChatMessage.user("差旅报销走什么流程"),
                ChatMessage.assistant("先在 OA 提交申请单"));

        List<ChatMessage> sent = captureHistorySentToLlm(history);

        assertThat(sent).first()
                .satisfies(msg -> assertThat(msg.getRole()).isEqualTo(ChatMessage.Role.SYSTEM));
    }

    /**
     * 中断的半截答案不占名额，名额让给再往前那条完整回答
     */
    @Test
    void skipsInterruptedAnswerAndFallsBackToEarlierOne() {
        List<ChatMessage> history = List.of(
                ChatMessage.user("差旅报销走什么流程"),
                ChatMessage.assistant("先在 OA 提交申请单"),
                ChatMessage.user("需要哪些材料"),
                assistantWith("发票和审", ChatMessage.MessageStatus.INTERRUPTED),
                ChatMessage.user("多久能到账"),
                assistantWith("审批通过后三", ChatMessage.MessageStatus.INTERRUPTED));

        List<ChatMessage> sent = captureHistorySentToLlm(history);

        assertThat(contentsOf(sent)).containsExactly(
                "差旅报销走什么流程",
                "先在 OA 提交申请单",
                "需要哪些材料",
                "多久能到账");
    }

    /**
     * 限流落库的排队话术同样不占名额，拿它消解指代只会带进无关实体
     */
    @Test
    void skipsRejectedAnswer() {
        List<ChatMessage> history = List.of(
                ChatMessage.user("差旅报销走什么流程"),
                ChatMessage.assistant("先在 OA 提交申请单"),
                ChatMessage.user("需要哪些材料"),
                assistantWith("当前排队人数过多，请稍后再试", ChatMessage.MessageStatus.REJECTED));

        List<ChatMessage> sent = captureHistorySentToLlm(history);

        assertThat(contentsOf(sent)).containsExactly(
                "差旅报销走什么流程",
                "先在 OA 提交申请单",
                "需要哪些材料");
    }

    /**
     * 用户在第一个字之前点停止，那一轮零内容不落库，历史里就会出现连着的两条提问
     */
    @Test
    void keepsConsecutiveUserQuestionsCausedByStop() {
        List<ChatMessage> history = List.of(
                ChatMessage.user("差旅报销走什么流程"),
                ChatMessage.user("需要哪些材料"),
                ChatMessage.assistant("发票和审批单"));

        List<ChatMessage> sent = captureHistorySentToLlm(history);

        assertThat(contentsOf(sent)).containsExactly(
                "差旅报销走什么流程",
                "需要哪些材料",
                "发票和审批单");
    }

    /**
     * 连停两次就是三条提问只跟一条回答，按轮次数名额会把这条唯一的答案丢掉，按条数数才留得住
     */
    @Test
    void keepsTheOnlyAnswerWhenLaterTurnsWereAllStopped() {
        List<ChatMessage> history = List.of(
                ChatMessage.user("差旅报销走什么流程"),
                ChatMessage.assistant("先在 OA 提交申请单"),
                ChatMessage.user("需要哪些材料"),
                ChatMessage.user("多久能到账"));

        List<ChatMessage> sent = captureHistorySentToLlm(history);

        assertThat(contentsOf(sent)).containsExactly(
                "差旅报销走什么流程",
                "先在 OA 提交申请单",
                "需要哪些材料",
                "多久能到账");
    }

    /**
     * 历史再长也不截断提问，改写窗口的轮次跨度交给 rag.memory.history-keep-turns 决定
     */
    @Test
    void doesNotTruncateLongUserQuestionHistory() {
        List<ChatMessage> history = List.of(
                ChatMessage.user("第一问"),
                ChatMessage.assistant("第一答"),
                ChatMessage.user("第二问"),
                ChatMessage.assistant("第二答"),
                ChatMessage.user("第三问"),
                ChatMessage.assistant("第三答"),
                ChatMessage.user("第四问"),
                ChatMessage.assistant("第四答"));

        List<ChatMessage> sent = captureHistorySentToLlm(history);

        assertThat(contentsOf(sent)).containsExactly(
                "第一问", "第二问", "第三问", "第三答", "第四问", "第四答");
    }

    /**
     * 末条恒为当前问题，提示词里「最后一条 user 才是当前问题」这句话靠它成立
     */
    @Test
    void putsCurrentQuestionLast() {
        List<ChatMessage> history = List.of(
                ChatMessage.user("差旅报销走什么流程"),
                ChatMessage.assistant("先在 OA 提交申请单"));

        List<ChatMessage> messages = captureRequestSentToLlm(history).getMessages();

        assertThat(messages).last().satisfies(msg -> {
            assertThat(msg.getRole()).isEqualTo(ChatMessage.Role.USER);
            assertThat(msg.getContent()).isEqualTo(QUESTION);
        });
        assertThat(messages).first()
                .satisfies(msg -> assertThat(msg.getRole()).isEqualTo(ChatMessage.Role.SYSTEM));
    }

    /**
     * 首轮无历史：只发系统提示词与当前问题
     */
    @Test
    void sendsOnlyPromptAndQuestionWhenHistoryIsEmpty() {
        List<ChatMessage> messages = captureRequestSentToLlm(List.of()).getMessages();

        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).getContent()).isEqualTo(QUESTION);
    }

    private static ChatMessage assistantWith(String content, ChatMessage.MessageStatus status) {
        return new ChatMessage(ChatMessage.Role.ASSISTANT, content, status);
    }

    private static List<String> contentsOf(List<ChatMessage> messages) {
        return messages.stream().map(ChatMessage::getContent).toList();
    }

    /**
     * 抓住真正发给模型的那份请求，只取其中的历史（去掉系统提示词与末条当前问题）
     */
    private List<ChatMessage> captureHistorySentToLlm(List<ChatMessage> history) {
        List<ChatMessage> messages = captureRequestSentToLlm(history).getMessages();
        return messages.subList(1, messages.size() - 1);
    }

    private ChatRequest captureRequestSentToLlm(List<ChatMessage> history) {
        RAGConfigProperties properties = new RAGConfigProperties();
        properties.setQueryRewriteEnabled(true);
        when(queryTermMappingService.normalize(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(promptTemplateLoader.load(anyString())).thenReturn("系统提示词");
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn(LLM_JSON);

        MultiQuestionRewriteService service = new MultiQuestionRewriteService(
                llmService, properties, queryTermMappingService, promptTemplateLoader);
        service.rewriteWithSplit(QUESTION, history);

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmService).chat(request.capture(), eq(Tier.FAST));
        return request.getValue();
    }
}
