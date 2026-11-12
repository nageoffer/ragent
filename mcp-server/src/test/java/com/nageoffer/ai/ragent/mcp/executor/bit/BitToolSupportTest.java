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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 售后窗口按日历日算：签收次日起第 N 个自然日 24:00 截止，签收钟点不影响截止日
 */
class BitToolSupportTest {

    /**
     * 入参是北京时间，与库里 TIMESTAMPTZ 读出来的时间点一致
     */
    private static Timestamp at(String dateTime) {
        return Timestamp.from(LocalDateTime.parse(dateTime).atZone(BitToolSupport.ZONE).toInstant());
    }

    @Test
    void deadlineIgnoresReceiveClockTime() {
        assertEquals(LocalDate.parse("2026-10-30"), BitToolSupport.windowDeadline(at("2026-10-23T00:01"), 7));
        assertEquals(LocalDate.parse("2026-10-30"), BitToolSupport.windowDeadline(at("2026-10-23T23:59"), 7));
        assertEquals(LocalDate.parse("2026-11-07"), BitToolSupport.windowDeadline(at("2026-10-23T09:37"), 15));
    }

    /**
     * 北京时间 10-23 00:01 在 UTC 下还是 10-22，机器默认时区不能改变签收日与展示时间
     */
    @Test
    void ignoresJvmDefaultTimeZone() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            assertEquals(LocalDate.parse("2026-10-30"), BitToolSupport.windowDeadline(at("2026-10-23T00:01"), 7));
            assertEquals("2026-10-23 00:01", BitToolSupport.dateTime(at("2026-10-23T00:01")));
            assertEquals("2026-10-23", BitToolSupport.date(at("2026-10-23T00:01")));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void qualityWindowCountsCalendarDays() {
        // 签收 09:37，截止日仍是第 15 个自然日 11-07，11-02 之后还有 5 天
        assertEquals("售后窗口: 已签收 10 天；七天无理由已于 2026-10-30 截止；十五天质量问题 2026-11-07 24:00 截止（今天之后还有 5 天）",
                BitToolSupport.returnWindow(at("2026-10-23T09:37"), LocalDate.parse("2026-11-02")));
        // 截止日 11-03，11-02 之后还剩 11-03 当天可以申请
        assertEquals("售后窗口: 已签收 14 天；七天无理由已于 2026-10-26 截止；十五天质量问题 2026-11-03 24:00 截止（今天之后还有 1 天）",
                BitToolSupport.returnWindow(at("2026-10-19T09:30"), LocalDate.parse("2026-11-02")));
    }

    @Test
    void noReasonWindowListsBothDeadlines() {
        assertEquals("售后窗口: 已签收 3 天；七天无理由 2026-11-06 24:00 截止（今天之后还有 4 天）；十五天质量问题 2026-11-14 24:00 截止",
                BitToolSupport.returnWindow(at("2026-10-30T21:00"), LocalDate.parse("2026-11-02")));
    }

    @Test
    void lastDayAndExpired() {
        assertEquals("售后窗口: 已签收 7 天；七天无理由 2026-11-02 24:00 截止（今天是最后一天）；十五天质量问题 2026-11-10 24:00 截止",
                BitToolSupport.returnWindow(at("2026-10-26T18:00"), LocalDate.parse("2026-11-02")));
        assertEquals("售后窗口: 已签收 15 天；七天无理由已于 2026-10-26 截止；十五天质量问题 2026-11-03 24:00 截止（今天是最后一天）",
                BitToolSupport.returnWindow(at("2026-10-19T09:30"), LocalDate.parse("2026-11-03")));
        assertEquals("售后窗口: 已签收 16 天；七天无理由与十五天质量问题窗口都已过（2026-11-03 截止）",
                BitToolSupport.returnWindow(at("2026-10-19T09:30"), LocalDate.parse("2026-11-04")));
    }
}
