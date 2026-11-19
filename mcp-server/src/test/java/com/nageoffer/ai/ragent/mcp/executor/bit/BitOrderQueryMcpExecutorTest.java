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

package com.nageoffer.ai.ragent.mcp.executor.bit;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 订单列表的表头必须让模型分得清「这是全部」还是「只列了一部分」
 */
class BitOrderQueryMcpExecutorTest {

    @Test
    void headerSaysAllListedWhenTotalFits() {
        assertEquals("【近 3 个月共 8 笔订单，已全部列出】", BitOrderQueryMcpExecutor.listHeader(3, 8, 8));
    }

    @Test
    void headerCountsUnlistedOrders() {
        assertEquals("【近 3 个月共 1342 笔订单，以下为最近 20 笔，其余 1322 笔未列出】",
                BitOrderQueryMcpExecutor.listHeader(3, 1342, 20));
    }

    @Test
    void emptyWindowSuggestsWideningUntilMax() {
        assertEquals("近 3 个月没有订单；更早的购买可把 months 调大（最多 24）再查",
                BitOrderQueryMcpExecutor.listHeader(3, 0, 0));
        assertEquals("近 24 个月没有订单", BitOrderQueryMcpExecutor.listHeader(24, 0, 0));
    }

    /**
     * 窗口从 N 个月前那天的北京时间 0 点算起，当天下的单也在窗口内
     */
    @Test
    void windowStartsAtBeijingMidnight() {
        Timestamp expected = Timestamp.from(Instant.parse("2026-08-13T16:00:00Z"));
        assertEquals(expected, BitOrderQueryMcpExecutor.windowStart(3, LocalDate.parse("2026-11-14")));
    }
}
