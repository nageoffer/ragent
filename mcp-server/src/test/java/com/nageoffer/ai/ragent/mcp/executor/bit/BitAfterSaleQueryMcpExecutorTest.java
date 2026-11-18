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

import com.nageoffer.ai.ragent.mcp.dao.result.AfterSaleDetailResult;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 售后单列表必须带状态说明，模型才分得清「处理中」覆盖了哪些环节
 */
class BitAfterSaleQueryMcpExecutorTest {

    @Test
    void legendPrecedesRowsAndAppearsOnce() {
        String result = BitAfterSaleQueryMcpExecutor.buildResult(List.of(row("AS1", "处理中"), row("AS2", "已完成")));

        String[] lines = result.split("\n");
        assertEquals("【售后单 2 张】", lines[0]);
        assertEquals(BitAfterSaleQueryMcpExecutor.STATUS_LEGEND, lines[1]);
        assertEquals(result.indexOf("状态说明"), result.lastIndexOf("状态说明"));
        assertTrue(result.contains("类型: 退货退款 | 当前状态: 处理中"));
    }

    private static AfterSaleDetailResult row(String afterSaleNo, String status) {
        AfterSaleDetailResult row = new AfterSaleDetailResult();
        row.setAfterSaleNo(afterSaleNo);
        row.setOrderNo("88232");
        row.setSkuCode("MFHP4CH/A");
        row.setSkuName("AirPods Pro 3");
        row.setType("退货退款");
        row.setStatus(status);
        row.setCreateTime(Timestamp.from(Instant.parse("2026-11-03T02:00:00Z")));
        row.setUpdateTime(Timestamp.from(Instant.parse("2026-11-04T02:00:00Z")));
        return row;
    }
}
