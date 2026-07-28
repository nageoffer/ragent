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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceNodeMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code cancelRunByTaskId} 必须是一次条件更新：只翻转仍处于 RUNNING 的行，
 * 以便与正常终态（SUCCESS / ERROR）竞争时最多一方生效
 */
class RagTraceRecordServiceImplTest {

    private static final String TASK_ID = "task-1";
    private static final String TRACE_ID = "trace-1";

    private RagTraceRunMapper runMapper;
    private RagTraceRecordServiceImpl service;

    @BeforeAll
    static void initTableMetadata() {
        // LambdaWrapper 生成 SQL 依赖 MyBatis-Plus 的表元数据缓存，纯单测环境需要手动初始化
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                RagTraceRunDO.class);
    }

    @BeforeEach
    void setUp() {
        runMapper = mock(RagTraceRunMapper.class);
        service = new RagTraceRecordServiceImpl(runMapper, mock(RagTraceNodeMapper.class));
    }

    @Test
    void cancelsRunningRunAndComputesDurationFromStartTime() {
        Date startTime = new Date(1_000_000L);
        Date endTime = new Date(1_002_500L);
        when(runMapper.selectOne(any())).thenReturn(RagTraceRunDO.builder()
                .traceId(TRACE_ID)
                .taskId(TASK_ID)
                .status("RUNNING")
                .startTime(startTime)
                .build());
        when(runMapper.update(any(), any())).thenReturn(1);

        assertTrue(service.cancelRunByTaskId(TASK_ID, endTime));

        ArgumentCaptor<RagTraceRunDO> captor = ArgumentCaptor.forClass(RagTraceRunDO.class);
        verify(runMapper).update(captor.capture(), any());
        RagTraceRunDO update = captor.getValue();
        assertEquals("CANCELLED", update.getStatus());
        assertEquals(endTime, update.getEndTime());
        assertEquals(2500L, update.getDurationMs());
    }

    @Test
    void constrainsUpdateToRowsStillRunning() {
        when(runMapper.selectOne(any())).thenReturn(RagTraceRunDO.builder()
                .traceId(TRACE_ID)
                .status("RUNNING")
                .startTime(new Date())
                .build());
        when(runMapper.update(any(), any())).thenReturn(1);

        service.cancelRunByTaskId(TASK_ID, new Date());

        ArgumentCaptor<Wrapper<RagTraceRunDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(runMapper).update(any(), captor.capture());
        assertTrue(captor.getValue().getTargetSql().contains("status"),
                "更新条件必须带 status，否则会覆盖已收尾的终态");
    }

    @Test
    void skipsUpdateWhenTaskHasNoRunningRun() {
        when(runMapper.selectOne(any())).thenReturn(null);

        assertFalse(service.cancelRunByTaskId(TASK_ID, new Date()));

        verify(runMapper, never()).update(any(), any());
    }

    @Test
    void reportsNotCancelledWhenNormalFinishWonTheRace() {
        when(runMapper.selectOne(any())).thenReturn(RagTraceRunDO.builder()
                .traceId(TRACE_ID)
                .status("RUNNING")
                .startTime(new Date())
                .build());
        when(runMapper.update(any(), any())).thenReturn(0);

        assertFalse(service.cancelRunByTaskId(TASK_ID, new Date()));
    }
}
