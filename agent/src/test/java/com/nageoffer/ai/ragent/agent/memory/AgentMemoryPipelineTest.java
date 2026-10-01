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

import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryControlDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryExtractionDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryExtractionStatus;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryTriggerType;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentMemoryPipelineTest {

    private static final String USER_ID = "u-1001";
    private static final String CONVERSATION_ID = "c-1";
    private static final String REQUEST_ID = "1900000000000000050";

    private AgentMemoryRepository memoryRepository;
    private AgentMemoryJudge memoryJudge;
    private AgentMemoryConsolidator memoryConsolidator;
    private AgentMemoryProperties memoryProperties;
    private AgentMemoryPipeline pipeline;

    @BeforeEach
    void setUp() {
        memoryRepository = mock(AgentMemoryRepository.class);
        memoryJudge = mock(AgentMemoryJudge.class);
        memoryConsolidator = mock(AgentMemoryConsolidator.class);
        memoryProperties = new AgentMemoryProperties();
        pipeline = new AgentMemoryPipeline(memoryRepository, memoryJudge, memoryConsolidator, memoryProperties);
    }

    @Test
    void shouldEnsureControlWhenLongTermEnabled() {
        pipeline.ensureExtractionBaseline(USER_ID);

        verify(memoryRepository).ensureControl(USER_ID);
    }

    @Test
    void shouldSkipBaselineWhenLongTermDisabled() {
        memoryProperties.setLongTermEnabled(false);

        pipeline.ensureExtractionBaseline(USER_ID);

        verifyNoInteractions(memoryRepository);
    }

    /**
     * 预建炸了不许拦对话：长期记忆是增强不是前提，代价只是本轮消息可能漏出下界
     */
    @Test
    void shouldSwallowBaselineFailure() {
        doThrow(new IllegalStateException("库连不上")).when(memoryRepository).ensureControl(USER_ID);

        assertThatCode(() -> pipeline.ensureExtractionBaseline(USER_ID)).doesNotThrowAnyException();
    }

    @Test
    void flushShouldStopOnceRequestIsCovered() {
        AgentMemoryPipeline flushing = stubBatches(written(1));
        when(memoryRepository.settledStatusCovering(USER_ID, REQUEST_ID))
                .thenReturn(AgentMemoryExtractionStatus.WRITTEN);

        AgentMemoryOutcome outcome = flushing.flush(USER_ID, CONVERSATION_ID, REQUEST_ID);

        assertThat(outcome.status()).isEqualTo(Status.WRITTEN);
        verify(flushing, times(1)).extract(USER_ID, CONVERSATION_ID, AgentMemoryTriggerType.FLUSH);
    }

    /**
     * 积压超过一批时本次请求排在后面：头一批判完不算完，接着跑到覆盖它为止
     */
    @Test
    void flushShouldKeepGoingUntilRequestIsCovered() {
        AgentMemoryPipeline flushing = stubBatches(written(2), written(1));
        when(memoryRepository.settledStatusCovering(USER_ID, REQUEST_ID))
                .thenReturn(null, AgentMemoryExtractionStatus.WRITTEN);

        AgentMemoryOutcome outcome = flushing.flush(USER_ID, CONVERSATION_ID, REQUEST_ID);

        assertThat(outcome.status()).isEqualTo(Status.WRITTEN);
        assertThat(outcome.applied()).isEqualTo(3);
        verify(flushing, times(2)).extract(USER_ID, CONVERSATION_ID, AgentMemoryTriggerType.FLUSH);
    }

    /**
     * 跑满上限还没轮到本次请求：前几批写成了也不许报「记忆已更新」
     */
    @Test
    void flushShouldReportIncompleteWhenBatchesRunOut() {
        AgentMemoryPipeline flushing = stubBatches(written(1));
        when(memoryRepository.settledStatusCovering(USER_ID, REQUEST_ID)).thenReturn(null);

        AgentMemoryOutcome outcome = flushing.flush(USER_ID, CONVERSATION_ID, REQUEST_ID);

        assertThat(outcome.status()).isEqualTo(Status.INCOMPLETE);
        // 前几批确实改了库，本轮快照照样要刷新
        assertThat(outcome.mutated()).isTrue();
        verify(flushing, times(3)).extract(USER_ID, CONVERSATION_ID, AgentMemoryTriggerType.FLUSH);
    }

    /**
     * DROPPED 也推水位：本次请求被一批丢弃越过后再调，「没有待处理」不许把那次失败洗成「都整理过了」
     */
    @Test
    void flushShouldReportFailureWhenRequestWasDropped() {
        AgentMemoryPipeline flushing = stubBatches(AgentMemoryOutcome.of(Status.NOTHING_PENDING, 0));
        when(memoryRepository.settledStatusCovering(USER_ID, REQUEST_ID))
                .thenReturn(AgentMemoryExtractionStatus.DROPPED);

        assertThat(flushing.flush(USER_ID, CONVERSATION_ID, REQUEST_ID).status()).isEqualTo(Status.FAILED);
    }

    @Test
    void flushShouldReportAlreadyProcessedWhenRequestWasSettled() {
        AgentMemoryPipeline flushing = stubBatches(AgentMemoryOutcome.of(Status.NOTHING_PENDING, 0));
        when(memoryRepository.settledStatusCovering(USER_ID, REQUEST_ID))
                .thenReturn(AgentMemoryExtractionStatus.NOOP);

        assertThat(flushing.flush(USER_ID, CONVERSATION_ID, REQUEST_ID).status()).isEqualTo(Status.NOTHING_PENDING);
    }

    /**
     * 前一批写过、覆盖本次请求的那批判成 NOOP：记忆确实变了，不许按末批报成「没有需要记住的」
     */
    @Test
    void flushShouldStayWrittenWhenLaterBatchIsEmpty() {
        AgentMemoryPipeline flushing = stubBatches(written(1),
                new AgentMemoryOutcome(Status.SETTLED_EMPTY, 0, 1, false));
        when(memoryRepository.settledStatusCovering(USER_ID, REQUEST_ID))
                .thenReturn(null, AgentMemoryExtractionStatus.NOOP);

        AgentMemoryOutcome outcome = flushing.flush(USER_ID, CONVERSATION_ID, REQUEST_ID);

        assertThat(outcome.status()).isEqualTo(Status.WRITTEN);
        assertThat(outcome.applied()).isEqualTo(1);
        assertThat(outcome.mutated()).isTrue();
    }

    /**
     * 清空批之后的批只累计生效变更，清空标记和清掉的条数不被后面没清空的批冲掉
     */
    @Test
    void flushShouldKeepClearAcrossLaterBatches() {
        AgentMemoryPipeline flushing = stubBatches(
                new AgentMemoryOutcome(Status.WRITTEN, 2, 2, true, true, 2), written(1));
        when(memoryRepository.settledStatusCovering(USER_ID, REQUEST_ID))
                .thenReturn(null, AgentMemoryExtractionStatus.WRITTEN);

        AgentMemoryOutcome outcome = flushing.flush(USER_ID, CONVERSATION_ID, REQUEST_ID);

        assertThat(outcome.status()).isEqualTo(Status.WRITTEN);
        assertThat(outcome.cleared()).isTrue();
        assertThat(outcome.clearedItems()).isEqualTo(2);
        assertThat(outcome.applied()).isEqualTo(3);
    }

    @Test
    void flushShouldPassFailureThrough() {
        AgentMemoryPipeline flushing = stubBatches(AgentMemoryOutcome.of(Status.BUSY, 2));

        assertThat(flushing.flush(USER_ID, CONVERSATION_ID, REQUEST_ID).status()).isEqualTo(Status.BUSY);
        verify(memoryRepository, never()).settledStatusCovering(anyString(), anyString());
    }

    /**
     * 待处理按用户取、不带会话；清空批哪怕新增顶到上限也不叫合并模型——旧条目马上整片失效
     */
    @Test
    void extractShouldReadPerUserAndSkipConsolidationForClear() {
        Date since = new Date();
        AgentMemoryControlDO control = new AgentMemoryControlDO();
        control.setUserId(USER_ID);
        control.setRevision(3L);
        control.setCreateTime(since);
        when(memoryRepository.ensureControl(USER_ID)).thenReturn(control);
        when(memoryRepository.currentWatermark(USER_ID)).thenReturn(null);
        List<AgentMessageDO> pending = List.of(AgentMessageDO.builder().id("m-1").conversationId("c-A").build());
        when(memoryRepository.loadPending(USER_ID, null, since)).thenReturn(pending);
        AgentMemoryExtractionDO extraction = AgentMemoryExtractionDO.builder().id("e-1").attemptCount(1).build();
        when(memoryRepository.claim(USER_ID, CONVERSATION_ID, "m-1", "m-1", AgentMemoryTriggerType.FLUSH))
                .thenReturn(extraction);
        List<AgentMemoryDecision> decisions = new ArrayList<>();
        decisions.add(AgentMemoryDecision.clear());
        for (int i = 0; i < 16; i++) {
            decisions.add(AgentMemoryDecision.add(i + "条".repeat(399)));
        }
        when(memoryJudge.judge(anyList(), eq(pending))).thenReturn(decisions);
        when(memoryRepository.commit(any())).thenReturn(new AgentMemoryCommitResult(
                AgentMemoryExtractionStatus.WRITTEN, 16, true, true, 4));

        AgentMemoryOutcome outcome = pipeline.extract(USER_ID, CONVERSATION_ID, AgentMemoryTriggerType.FLUSH);

        assertThat(outcome.cleared()).isTrue();
        assertThat(outcome.clearedItems()).isEqualTo(4);
        verify(memoryConsolidator, never()).plan(anyList());
        ArgumentCaptor<AgentMemoryCommit> commit = ArgumentCaptor.forClass(AgentMemoryCommit.class);
        verify(memoryRepository).commit(commit.capture());
        assertThat(commit.getValue().merges()).isEmpty();
    }

    private AgentMemoryPipeline stubBatches(AgentMemoryOutcome first, AgentMemoryOutcome... rest) {
        AgentMemoryPipeline flushing = spy(pipeline);
        doReturn(first, (Object[]) rest).when(flushing).extract(USER_ID, CONVERSATION_ID, AgentMemoryTriggerType.FLUSH);
        return flushing;
    }

    private static AgentMemoryOutcome written(int applied) {
        return new AgentMemoryOutcome(Status.WRITTEN, applied, applied, true);
    }
}
