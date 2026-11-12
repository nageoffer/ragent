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

import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryCursorDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 后台学习：按用户累计够三条才调模型，一批判一次、游标推到这批最后一条，提交与游标推进同一事务，失败不推游标
 */
class AgentMemoryLearnerTest {

    private static final String USER_ID = "u-1001";
    private static final String CURSOR = "1900000000000000001";

    /**
     * 仓库减好的背景，用例据此核对学习流程把它原样交给了裁决
     */
    private static final List<AgentMessageDO> BACKGROUND = List.of(message("1900000000000000002", "A"));

    private RLock lock;
    private AgentMemoryLock memoryLock;
    private AgentMemoryRepository memoryRepository;
    private AgentMemoryJudge memoryJudge;
    private AgentMemoryProperties memoryProperties;
    private AgentMemoryLearner learner;

    @BeforeEach
    void setUp() {
        lock = mock(RLock.class);
        memoryLock = mock(AgentMemoryLock.class);
        when(memoryLock.tryAcquire(USER_ID)).thenReturn(lock);
        memoryRepository = mock(AgentMemoryRepository.class);
        memoryJudge = mock(AgentMemoryJudge.class);
        memoryProperties = new AgentMemoryProperties();
        learner = new AgentMemoryLearner(memoryLock, memoryRepository, memoryJudge, memoryProperties);
        AgentMemoryCursorDO cursor = new AgentMemoryCursorDO();
        cursor.setUserId(USER_ID);
        cursor.setLastMessageId(CURSOR);
        when(memoryRepository.loadCursor(USER_ID)).thenReturn(cursor);
        when(memoryRepository.listActiveItems(USER_ID)).thenReturn(List.of());
        when(memoryRepository.loadBackground(eq(USER_ID), anyList())).thenReturn(BACKGROUND);
        when(memoryRepository.commitBackground(anyString(), any(), anyString(), any())).thenReturn(true);
        when(memoryJudge.judgeBackground(anyList(), anyList(), anyList()))
                .thenReturn(new AgentMemoryJudgement(List.of(), "这批只是在提问"));
    }

    @Test
    void ensureCursorShouldSkipWhenDisabledAndSwallowFailure() {
        memoryProperties.setLongTermEnabled(false);
        learner.ensureCursor(USER_ID);
        verifyNoInteractions(memoryRepository);

        memoryProperties.setLongTermEnabled(true);
        doThrow(new IllegalStateException("库连不上")).when(memoryRepository).ensureCursor(USER_ID);
        assertThatCode(() -> learner.ensureCursor(USER_ID)).doesNotThrowAnyException();
    }

    /**
     * 拿不到锁：有人在处理就留给下次触发，什么都不读
     */
    @Test
    void shouldGiveUpWhenLockIsHeld() {
        when(memoryLock.tryAcquire(USER_ID)).thenReturn(null);

        assertThat(learner.learn(USER_ID).status()).isEqualTo(Status.BUSY);
        verifyNoInteractions(memoryRepository);
    }

    /**
     * 两段对话各一条，按用户累计也只有两条，不调模型
     */
    @Test
    void shouldWaitForThreeAcrossConversations() {
        when(memoryRepository.loadUserMessagesAfter(eq(USER_ID), eq(CURSOR), any(Integer.class)))
                .thenReturn(List.of(message("1900000000000000010", "A"), message("1900000000000000020", "B")));

        assertThat(learner.learn(USER_ID).status()).isEqualTo(Status.BELOW_THRESHOLD);
        verifyNoInteractions(memoryJudge);
        verify(memoryLock).release(lock, USER_ID);
    }

    @Test
    void shouldJudgeTheBatchOnceAndAdvanceCursorWithCommit() {
        List<AgentMessageDO> batch = List.of(message("1900000000000000010", "A"),
                message("1900000000000000020", "B"), message("1900000000000000030", "A"));
        when(memoryRepository.loadUserMessagesAfter(eq(USER_ID), eq(CURSOR), any(Integer.class))).thenReturn(batch);

        AgentMemoryOutcome outcome = learner.learn(USER_ID);

        assertThat(outcome.status()).isEqualTo(Status.COMMITTED);
        assertThat(outcome.explanation()).isEqualTo("这批只是在提问");
        verify(memoryRepository).loadBackground(USER_ID, batch);
        verify(memoryJudge).judgeBackground(List.of(), batch, BACKGROUND);
        verify(memoryRepository).commitBackground(eq(USER_ID), eq(CURSOR), eq("1900000000000000030"), any());
    }

    /**
     * 积压超过一次能取的量：先判最早取到的那些，游标推到其中最后一条，剩下的留给下次触发，不停在原地
     */
    @Test
    void largeBacklogShouldBeConsumedInOrderAcrossTriggers() {
        List<AgentMessageDO> first = new ArrayList<>();
        for (int i = 10; i < 60; i++) {
            first.add(message("19000000000000000" + i, "A"));
        }
        when(memoryRepository.loadUserMessagesAfter(eq(USER_ID), eq(CURSOR), any(Integer.class))).thenReturn(first);

        assertThat(learner.learn(USER_ID).status()).isEqualTo(Status.COMMITTED);

        verify(memoryJudge).judgeBackground(List.of(), first, BACKGROUND);
        verify(memoryRepository).commitBackground(eq(USER_ID), eq(CURSOR), eq(first.get(first.size() - 1).getId()), any());
    }

    /**
     * 游标只在聊天入口建：缺了不补建，更不能把当前最新一条当成界线
     */
    @Test
    void missingCursorShouldNotBeCreatedHere() {
        when(memoryRepository.loadCursor(USER_ID)).thenReturn(null);

        assertThat(learner.learn(USER_ID).status()).isEqualTo(Status.FAILED);
        verify(memoryRepository, never()).ensureCursor(anyString());
        verify(memoryRepository, never()).loadUserMessagesAfter(anyString(), any(), any(Integer.class));
    }

    @Test
    void judgeFailureShouldLeaveCursorUntouched() {
        when(memoryRepository.loadUserMessagesAfter(eq(USER_ID), eq(CURSOR), any(Integer.class))).thenReturn(List.of(
                message("1900000000000000010", "A"), message("1900000000000000020", "A"), message("1900000000000000030", "A")));
        when(memoryJudge.judgeBackground(anyList(), anyList(), anyList())).thenThrow(new IllegalStateException("超时"));

        assertThat(learner.learn(USER_ID).status()).isEqualTo(Status.FAILED);
        verify(memoryRepository, never()).commitBackground(anyString(), any(), anyString(), any());
    }

    @Test
    void movedCursorShouldBeReportedAsFailure() {
        when(memoryRepository.loadUserMessagesAfter(eq(USER_ID), eq(CURSOR), any(Integer.class))).thenReturn(List.of(
                message("1900000000000000010", "A"), message("1900000000000000020", "A"), message("1900000000000000030", "A")));
        when(memoryRepository.commitBackground(anyString(), any(), anyString(), any())).thenReturn(false);

        assertThat(learner.learn(USER_ID).status()).isEqualTo(Status.FAILED);
    }

    private static AgentMessageDO message(String id, String conversationId) {
        return AgentMessageDO.builder().id(id).conversationId(conversationId).userId(USER_ID).role("user")
                .content("发言 " + id).createTime(new Date()).build();
    }
}
