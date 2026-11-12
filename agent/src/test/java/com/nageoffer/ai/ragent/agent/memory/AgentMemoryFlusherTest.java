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
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
 * 主动整理只处理本轮请求：不读不推后台游标，不读后台积压；请求之后的发言只作约束；没有变更也带着判定说明结束
 */
class AgentMemoryFlusherTest {

    private static final String USER_ID = "u-1001";
    private static final AgentMessageDO REQUEST = AgentMessageDO.builder().id("1900000000000000050")
            .conversationId("c-1").userId(USER_ID).role("user").content("我一直戴眼镜，记住").createTime(new Date()).build();

    private RLock lock;
    private AgentMemoryLock memoryLock;
    private AgentMemoryRepository memoryRepository;
    private AgentMemoryJudge memoryJudge;
    private AgentMemoryProperties memoryProperties;
    private AgentMemoryFlusher flusher;

    @BeforeEach
    void setUp() {
        lock = mock(RLock.class);
        memoryLock = mock(AgentMemoryLock.class);
        when(memoryLock.tryAcquire(USER_ID)).thenReturn(lock);
        memoryRepository = mock(AgentMemoryRepository.class);
        memoryJudge = mock(AgentMemoryJudge.class);
        memoryProperties = new AgentMemoryProperties();
        flusher = new AgentMemoryFlusher(memoryLock, memoryRepository, memoryJudge, memoryProperties);
        when(memoryRepository.loadUserMessage(USER_ID, REQUEST.getId())).thenReturn(REQUEST);
        when(memoryRepository.loadRecentBefore(eq(USER_ID), eq("c-1"), eq(REQUEST.getId()), any(Integer.class))).thenReturn(List.of());
        when(memoryRepository.loadUserMessagesAfter(eq(USER_ID), eq(REQUEST.getId()), any(Integer.class))).thenReturn(List.of());
        when(memoryRepository.listActiveItems(USER_ID)).thenReturn(List.of());
    }

    @Test
    void shouldDoNothingWhenDisabled() {
        memoryProperties.setLongTermEnabled(false);

        assertThat(flusher.flush(USER_ID, REQUEST.getId()).status()).isEqualTo(Status.DISABLED);
        verifyNoInteractions(memoryLock, memoryRepository);
    }

    @Test
    void shouldReportBusyWhenLockIsHeld() {
        when(memoryLock.tryAcquire(USER_ID)).thenReturn(null);

        assertThat(flusher.flush(USER_ID, REQUEST.getId()).status()).isEqualTo(Status.BUSY);
        verifyNoInteractions(memoryRepository);
    }

    /**
     * 只按本轮请求判、只提交记忆变更；后台游标与积压一概不碰
     */
    @Test
    void shouldJudgeOnlyTheRequestAndCommitWithoutCursor() {
        AgentMessageDO source = REQUEST;
        when(memoryJudge.judgeFlush(List.of(), REQUEST, List.of(), List.of()))
                .thenReturn(new AgentMemoryJudgement(List.of(AgentMemoryEditTest.add("用户戴眼镜", source)), "戴眼镜是稳定信息"));

        AgentMemoryOutcome outcome = flusher.flush(USER_ID, REQUEST.getId());

        assertThat(outcome.status()).isEqualTo(Status.COMMITTED);
        assertThat(outcome.changes()).containsExactly("新增：用户戴眼镜");
        assertThat(outcome.explanation()).isEqualTo("戴眼镜是稳定信息");
        ArgumentCaptor<AgentMemoryEdit> edit = ArgumentCaptor.forClass(AgentMemoryEdit.class);
        verify(memoryRepository).commitFlush(eq(USER_ID), edit.capture());
        assertThat(edit.getValue().additions()).containsExactly(new AgentMemoryEdit.Addition("用户戴眼镜", REQUEST.getId(), REQUEST.getCreateTime()));
        verify(memoryRepository, never()).loadCursor(anyString());
        verify(memoryRepository, never()).commitBackground(anyString(), any(), anyString(), any());
        verify(memoryLock).release(lock, USER_ID);
    }

    /**
     * 请求较早、执行较晚时，用户在别处后来说的话一并交给 Judge 作约束
     */
    @Test
    void laterMessagesShouldReachJudge() {
        AgentMessageDO question = AgentMessageDO.builder().id("1900000000000000020").conversationId("c-1")
                .role("assistant").content("您平时戴眼镜吗？").build();
        AgentMessageDO clear = AgentMessageDO.builder().id("1900000000000000060").conversationId("c-2")
                .role("user").content("清空我的长期记忆").build();
        when(memoryRepository.loadRecentBefore(eq(USER_ID), eq("c-1"), eq(REQUEST.getId()), any(Integer.class)))
                .thenReturn(List.of(question));
        when(memoryRepository.loadUserMessagesAfter(eq(USER_ID), eq(REQUEST.getId()), any(Integer.class)))
                .thenReturn(List.of(clear));
        when(memoryJudge.judgeFlush(List.of(), REQUEST, List.of(question), List.of(clear)))
                .thenReturn(new AgentMemoryJudgement(List.of(), "用户随后在另一段对话里要求清空全部长期记忆，这句在那之前"));

        AgentMemoryOutcome outcome = flusher.flush(USER_ID, REQUEST.getId());

        assertThat(outcome.status()).isEqualTo(Status.COMMITTED);
        assertThat(outcome.explanation()).contains("清空");
        verify(memoryRepository, never()).commitFlush(anyString(), any());
    }

    /**
     * Judge 给出执行不了的决策（指错条目）：整批拒绝，报失败，不把 Judge 的说明当成结论交出去
     */
    @Test
    void invalidDecisionShouldFailWithoutExplanation() {
        AgentMessageDO source = REQUEST;
        when(memoryJudge.judgeFlush(anyList(), any(), anyList(), anyList()))
                .thenReturn(new AgentMemoryJudgement(List.of(AgentMemoryEditTest.update("m-x", "用户住在南京", source)),
                        "用户较晚改口为南京，替换旧的常驻城市"));

        AgentMemoryOutcome outcome = flusher.flush(USER_ID, REQUEST.getId());

        assertThat(outcome.status()).isEqualTo(Status.FAILED);
        assertThat(outcome.explanation()).isEmpty();
        assertThat(outcome.changes()).isEmpty();
        verify(memoryRepository, never()).commitFlush(anyString(), any());
    }

    /**
     * 判下来不用改：不进事务，判定说明照样交给工具
     */
    @Test
    void noChangeShouldCarryExplanationWithoutCommit() {
        when(memoryJudge.judgeFlush(anyList(), any(), anyList(), anyList()))
                .thenReturn(new AgentMemoryJudgement(List.of(), "黑色只用于这次选购，不作为长期偏好"));

        AgentMemoryOutcome outcome = flusher.flush(USER_ID, REQUEST.getId());

        assertThat(outcome.status()).isEqualTo(Status.COMMITTED);
        assertThat(outcome.changes()).isEmpty();
        assertThat(outcome.explanation()).isEqualTo("黑色只用于这次选购，不作为长期偏好");
        verify(memoryRepository, never()).commitFlush(anyString(), any());
    }

    @Test
    void missingRequestShouldFail() {
        when(memoryRepository.loadUserMessage(USER_ID, REQUEST.getId())).thenReturn(null);

        assertThat(flusher.flush(USER_ID, REQUEST.getId()).status()).isEqualTo(Status.FAILED);
        verifyNoInteractions(memoryJudge);
    }

    @Test
    void judgeFailureShouldWriteNothing() {
        when(memoryJudge.judgeFlush(anyList(), any(), anyList(), anyList())).thenThrow(new IllegalStateException("模型调用超时"));

        AgentMemoryOutcome outcome = flusher.flush(USER_ID, REQUEST.getId());

        assertThat(outcome.status()).isEqualTo(Status.FAILED);
        assertThat(outcome.explanation()).isEmpty();
        verify(memoryRepository, never()).commitFlush(anyString(), any());
        verify(memoryLock).release(lock, USER_ID);
    }

    /**
     * 提交调用抛出时库里是否已写入说不准，报无法确认，也不把 Judge 的计划当成已完成
     */
    @Test
    void commitErrorShouldBeReportedAsUnknown() {
        AgentMessageDO source = REQUEST;
        when(memoryJudge.judgeFlush(anyList(), any(), anyList(), anyList()))
                .thenReturn(new AgentMemoryJudgement(List.of(AgentMemoryEditTest.add("用户戴眼镜", source)), "x"));
        doThrow(new IllegalStateException("连接在提交时断开")).when(memoryRepository).commitFlush(anyString(), any());

        AgentMemoryOutcome outcome = flusher.flush(USER_ID, REQUEST.getId());

        assertThat(outcome.status()).isEqualTo(Status.UNKNOWN);
        assertThat(outcome.changes()).isEmpty();
    }

    @Test
    void reloadShouldBeEmptyWhenDisabled() {
        memoryProperties.setLongTermEnabled(false);

        assertThat(flusher.reloadSnapshot(USER_ID).hasItems()).isFalse();
        verifyNoInteractions(memoryRepository);
    }
}
