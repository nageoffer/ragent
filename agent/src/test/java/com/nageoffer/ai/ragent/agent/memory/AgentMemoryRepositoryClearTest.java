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
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryControlMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryExtractionMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryExtractionStatus;
import com.nageoffer.ai.ragent.agent.enums.AgentMemorySourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 清空批的提交：新增先验后清、合并淘汰不走、原本就空也要有专门结局
 */
class AgentMemoryRepositoryClearTest {

    private static final String USER_ID = "u-1";
    private static final String EXTRACTION_ID = "e-1";
    private static final long REVISION = 7L;
    private static final String WATERMARK = "1900000000000000001";

    private AgentMemoryMapper memoryMapper;
    private AgentMemoryExtractionMapper extractionMapper;
    private AgentMemoryControlMapper controlMapper;
    private AgentMemoryRepository repository;

    @BeforeEach
    void setUp() {
        memoryMapper = mock(AgentMemoryMapper.class);
        extractionMapper = mock(AgentMemoryExtractionMapper.class);
        controlMapper = mock(AgentMemoryControlMapper.class);
        repository = new AgentMemoryRepository(memoryMapper, extractionMapper, controlMapper,
                mock(AgentMessageMapper.class), new AgentMemoryProperties());

        AgentMemoryControlDO control = new AgentMemoryControlDO();
        control.setUserId(USER_ID);
        control.setRevision(REVISION);
        when(controlMapper.selectForUpdate(USER_ID)).thenReturn(control);
        when(extractionMapper.selectWatermark(USER_ID)).thenReturn(WATERMARK);
        when(extractionMapper.settle(eq(EXTRACTION_ID), anyString(), anyInt(), anyInt())).thenReturn(1);
    }

    @Test
    void shouldClearThenKeepLaterAdds() {
        when(memoryMapper.retractAll(USER_ID)).thenReturn(2);

        AgentMemoryCommitResult result = repository.commit(commit(
                List.of(AgentMemoryDecision.clear(), AgentMemoryDecision.add("用户住在南京")),
                List.of(new AgentMemoryMerge(List.of("m-1", "m-2"), "合并产物"))));

        assertThat(result.status()).isEqualTo(AgentMemoryExtractionStatus.WRITTEN);
        assertThat(result.cleared()).isTrue();
        assertThat(result.clearedItems()).isEqualTo(2);
        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.mutated()).isTrue();
        ArgumentCaptor<AgentMemoryDO> inserted = ArgumentCaptor.forClass(AgentMemoryDO.class);
        verify(memoryMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getContent()).isEqualTo("用户住在南京");
        // 旧条目整片失效，合并它们毫无意义
        verify(memoryMapper, never()).supersede(anyString(), anyString(), anyString());
        verify(controlMapper).bumpRevision(USER_ID);
        verify(extractionMapper).settle(EXTRACTION_ID, AgentMemoryExtractionStatus.WRITTEN.name(), 2, 1);
    }

    /**
     * 原本就空：没东西变也得结算推水位，结果上仍标「清空过」，工具才能说「当前已无长期记忆」而不是「没什么要记的」
     */
    @Test
    void shouldSettleEmptyClearAsClear() {
        when(memoryMapper.retractAll(USER_ID)).thenReturn(0);

        AgentMemoryCommitResult result = repository.commit(commit(List.of(AgentMemoryDecision.clear()), List.of()));

        assertThat(result.status()).isEqualTo(AgentMemoryExtractionStatus.NOOP);
        assertThat(result.cleared()).isTrue();
        assertThat(result.clearedItems()).isZero();
        assertThat(result.mutated()).isFalse();
        verify(controlMapper, never()).bumpRevision(any());
        verify(extractionMapper).settle(EXTRACTION_ID, AgentMemoryExtractionStatus.NOOP.name(), 0, 1);
    }

    /**
     * 清空后要记的那条存不下：整批拒收，旧条目一条都不许先动
     */
    @Test
    void shouldRejectWholeBatchWhenAddIsTooLong() {
        List<AgentMemoryDecision> decisions = List.of(AgentMemoryDecision.clear(), AgentMemoryDecision.add("长".repeat(501)));

        assertThatThrownBy(() -> repository.commit(commit(decisions, List.of())))
                .isInstanceOf(AgentMemoryCapacityException.class);
        verify(memoryMapper, never()).retractAll(any());
        verify(memoryMapper, never()).insert(any(AgentMemoryDO.class));
        verify(extractionMapper, never()).settle(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void shouldRejectWholeBatchWhenAddsExceedCapacity() {
        List<AgentMemoryDecision> decisions = new ArrayList<>();
        decisions.add(AgentMemoryDecision.clear());
        for (int i = 0; i < 16; i++) {
            decisions.add(AgentMemoryDecision.add(i + "条".repeat(399)));
        }

        assertThatThrownBy(() -> repository.commit(commit(decisions, List.of())))
                .isInstanceOf(AgentMemoryCapacityException.class);
        verify(memoryMapper, never()).retractAll(any());
    }

    /**
     * 快照过期的清空同样整批作废：清空之后又落了新条目，旧快照判出来的清空不许把它们一起抹掉
     */
    @Test
    void shouldRejectStaleClear() {
        when(extractionMapper.selectWatermark(USER_ID)).thenReturn("1900000000000000009");

        AgentMemoryCommitResult result = repository.commit(commit(List.of(AgentMemoryDecision.clear()), List.of()));

        assertThat(result.status()).isEqualTo(AgentMemoryExtractionStatus.CONFLICT);
        verify(memoryMapper, never()).retractAll(any());
    }

    private AgentMemoryCommit commit(List<AgentMemoryDecision> decisions, List<AgentMemoryMerge> merges) {
        return new AgentMemoryCommit(USER_ID, EXTRACTION_ID, 1, REVISION, WATERMARK,
                AgentMemorySourceType.FLUSH, decisions, merges);
    }
}
