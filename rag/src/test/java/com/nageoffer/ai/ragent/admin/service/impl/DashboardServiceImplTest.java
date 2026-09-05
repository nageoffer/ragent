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

package com.nageoffer.ai.ragent.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardServiceImplTest {

    private final UserMapper users = mock(UserMapper.class);
    private final ConversationMapper sessions = mock(ConversationMapper.class);
    private final ConversationMessageMapper messages = mock(ConversationMessageMapper.class);
    private final RagTraceRunMapper traces = mock(RagTraceRunMapper.class);
    private final DashboardServiceImpl service = new DashboardServiceImpl(users, sessions, messages, traces);

    @Test
    void workflowAddsEngineAndActiveSessionsWithoutUsingNewSessionCountAsDepthDenominator() {
        when(sessions.selectCount(any())).thenReturn(100L, 1L, 2L);
        when(messages.selectMaps(any())).thenAnswer(invocation -> {
            QueryWrapper<ConversationMessageDO> query = invocation.getArgument(0);
            return List.of(Map.of("cnt", query.getSqlSelect().contains("conversation_id") ? 8L : 4L));
        });
        var overview = service.loadOverview("24h");
        assertThat(overview.getEngine()).isEqualTo("workflow");
        assertThat(overview.getKpis().getSessions24h().getValue()).isEqualTo(1L);
        assertThat(overview.getKpis().getActiveSessions().getValue()).isEqualTo(8L);
    }

    @Test
    void performanceDistinguishesNoTraceSamplesFromAllFailedSamples() {
        when(traces.selectCount(any())).thenReturn(0L, 0L, 0L, 3L);
        var empty = service.loadPerformance("24h");
        assertThat(empty.getEngine()).isEqualTo("workflow");
        assertThat(empty.getSampleCount()).isZero();
        var failed = service.loadPerformance("24h");
        assertThat(failed.getSampleCount()).isEqualTo(3L);
        assertThat(failed.getSuccessRate()).isZero();
        assertThat(failed.getErrorRate()).isEqualTo(100.0);
    }
}
