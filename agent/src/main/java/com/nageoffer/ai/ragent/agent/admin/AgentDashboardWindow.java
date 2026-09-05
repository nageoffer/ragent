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

package com.nageoffer.ai.ragent.agent.admin;

import com.nageoffer.ai.ragent.framework.exception.ClientException;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 固定窗口和粒度白名单：请求参数不得放大为无界扫描或成为 SQL 片段
 */
record AgentDashboardWindow(String label, String granularity, LocalDateTime start, LocalDateTime end,
                            LocalDateTime previousStart, long updatedAt) {

    static String normalize(String window, String fallback) {
        String normalized = window == null || window.isBlank() ? fallback : window.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("24h") && !normalized.equals("7d") && !normalized.equals("30d")) {
            throw new ClientException("统计时间范围仅支持 24h、7d、30d");
        }
        return normalized;
    }

    static String granularity(String granularity, String window) {
        String value = granularity == null || granularity.isBlank()
                ? (window.equals("24h") ? "hour" : "day") : granularity.trim().toLowerCase(Locale.ROOT);
        if (!value.equals("hour") && !value.equals("day")) {
            throw new ClientException("统计粒度仅支持 hour、day");
        }
        return value;
    }

    static AgentDashboardWindow at(String label, String granularity, Clock clock) {
        Duration duration = switch (label) {
            case "24h" -> Duration.ofHours(24);
            case "7d" -> Duration.ofDays(7);
            case "30d" -> Duration.ofDays(30);
            default -> throw new IllegalArgumentException("Unnormalized dashboard window");
        };
        var now = clock.instant();
        LocalDateTime end = LocalDateTime.ofInstant(now, clock.getZone());
        return new AgentDashboardWindow(label, granularity, end.minus(duration), end,
                end.minus(duration.multipliedBy(2)), now.toEpochMilli());
    }
}
