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

import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryDecision.Action;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentMemoryJudgeTest {

    private static final List<AgentMessageDO> ONE_TURN = List.of(message("m-1", "c-1", "清空你保存的关于我的全部长期记忆"));

    private LLMService llmService;
    private AgentPromptResolver promptResolver;
    private AgentMemoryJudge judge;

    @BeforeEach
    void setUp() {
        llmService = mock(LLMService.class);
        promptResolver = mock(AgentPromptResolver.class);
        when(promptResolver.render(eq(AgentPromptSlot.AGENT_MEMORY_EXTRACTION), anyMap())).thenReturn("抽取提示词");
        judge = new AgentMemoryJudge(llmService, promptResolver, new AgentMemoryProperties());
    }

    @Test
    void shouldKeepAddsAfterClear() {
        answer("[{\"action\":\"CLEAR\"},{\"action\":\"ADD\",\"content\":\"用户住在南京\"}]");

        List<AgentMemoryDecision> decisions = judge.judge(List.of(), ONE_TURN);

        assertThat(decisions).extracting(AgentMemoryDecision::action).containsExactly(Action.CLEAR, Action.ADD);
        assertThat(decisions.get(1).content()).isEqualTo("用户住在南京");
    }

    @Test
    void shouldCollapseRepeatedClear() {
        answer("[{\"action\":\"CLEAR\"},{\"action\":\"CLEAR\"}]");

        assertThat(judge.judge(List.of(), ONE_TURN)).extracting(AgentMemoryDecision::action)
                .containsExactly(Action.CLEAR);
    }

    /**
     * 「清空后只记住我住南京」判成 CLEAR + SUPERSEDE：先清空再让替换指不着目标自然丢弃，南京就被静默吞掉了
     */
    @Test
    void shouldRejectClearMixedWithSupersede() {
        answer("[{\"action\":\"CLEAR\"},{\"action\":\"SUPERSEDE\",\"id\":\"m-7\",\"content\":\"用户住在南京\"}]");

        assertThatThrownBy(() -> judge.judge(List.of(new AgentMemoryItem("m-7", "用户住在杭州")), ONE_TURN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPERSEDE");
    }

    @Test
    void shouldRejectClearMixedWithRetract() {
        answer("[{\"action\":\"RETRACT\",\"id\":\"m-7\"},{\"action\":\"CLEAR\"}]");

        assertThatThrownBy(() -> judge.judge(List.of(new AgentMemoryItem("m-7", "用户住在杭州")), ONE_TURN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RETRACT");
    }

    /**
     * 一批跨会话时换会话处要隔开，否则 B 里的「它」会被指到 A 说过的东西上；同会话连着的不隔
     */
    @Test
    void shouldSeparateConversationsInMaterial() {
        answer("[{\"action\":\"NOOP\"}]");
        List<AgentMessageDO> pending = List.of(
                message("m-1", "c-A", "我住在杭州"),
                message("m-2", "c-A", "平时写 Java"),
                message("m-3", "c-B", "我搬到南京了"),
                message("m-4", "c-A", "它的屏幕多大"));

        judge.judge(List.of(), pending);

        String turns = renderedTurns();
        String separator = "——（以下换到另一段对话）——";
        assertThat(turns).contains(
                "- 我住在杭州\n- 平时写 Java\n" + separator + "\n- 我搬到南京了\n" + separator + "\n- 它的屏幕多大");
        assertThat(turns.split(separator, -1)).hasSize(3);
    }

    private void answer(String raw) {
        when(llmService.chat(any(ChatRequest.class), eq(Tier.STANDARD))).thenReturn(raw);
    }

    @SuppressWarnings("unchecked")
    private String renderedTurns() {
        ArgumentCaptor<Map<String, String>> slots = ArgumentCaptor.forClass(Map.class);
        verify(promptResolver).render(eq(AgentPromptSlot.AGENT_MEMORY_EXTRACTION), slots.capture());
        return slots.getValue().get("recent_turns");
    }

    private static AgentMessageDO message(String id, String conversationId, String content) {
        return AgentMessageDO.builder()
                .id(id)
                .conversationId(conversationId)
                .userId("u-1")
                .role("user")
                .content(content)
                .build();
    }
}
