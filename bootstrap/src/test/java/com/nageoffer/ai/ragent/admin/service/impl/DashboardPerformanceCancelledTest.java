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

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardPerformanceVO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 被用户取消的会话必须计入成功率分母
 * <p>
 * 否则「用户等不及点了停止」这一最该被捕捉的负面信号会被系统性剔除，看板呈现幸存者偏差
 * </p>
 */
class DashboardPerformanceCancelledTest {

    private RagTraceRunMapper traceRunMapper;
    private ConversationMessageMapper messageMapper;
    private DashboardServiceImpl dashboardService;

    @BeforeEach
    void setUp() {
        traceRunMapper = mock(RagTraceRunMapper.class);
        messageMapper = mock(ConversationMessageMapper.class);
        dashboardService = new DashboardServiceImpl(
                mock(UserMapper.class),
                mock(ConversationMapper.class),
                messageMapper,
                traceRunMapper);
    }

    @Test
    void countsCancelledRunsInSuccessRateDenominator() {
        stubTraceRunCount("SUCCESS", 6L);
        stubTraceRunCount("ERROR", 2L);
        stubTraceRunCount("CANCELLED", 2L);
        when(traceRunMapper.selectObjs(any())).thenReturn(Collections.emptyList());
        when(messageMapper.selectCount(any())).thenReturn(0L);

        DashboardPerformanceVO performance = dashboardService.loadPerformance("24h");

        // 分母 = 6 + 2 + 2 = 10，而非只算成功与失败的 8
        assertEquals(60.0, performance.getSuccessRate());
        assertEquals(20.0, performance.getErrorRate());
    }

    private void stubTraceRunCount(String status, long count) {
        when(traceRunMapper.selectCount(argThat(wrapperMatching(status)))).thenReturn(count);
    }

    private static ArgumentMatcher<Wrapper<RagTraceRunDO>> wrapperMatching(String status) {
        return wrapper -> {
            if (!(wrapper instanceof QueryWrapper<?> queryWrapper)) {
                return false;
            }
            // MyBatis-Plus 的条件值是惰性写入 paramNameValuePairs 的，先触发 SQL 片段生成
            queryWrapper.getTargetSql();
            return queryWrapper.getParamNameValuePairs().containsValue(status);
        };
    }
}
