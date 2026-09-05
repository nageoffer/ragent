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

import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewGroupVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewKpiVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardTrendPointVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardTrendSeriesVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardTrendsVO;
import com.nageoffer.ai.ragent.admin.service.DashboardService;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 独立于 Agent 执行链路的统计适配器，最多缓存 3 个窗口 × 2 个粒度，无后台任务
 */
@Service
@ConditionalOnAgentEngine
public class AgentDashboardService implements DashboardService {

    private static final long CACHE_TTL_MS = 30_000;
    /**
     * 只有这三个流量指标叠加上周期对比：replies 本身是多序列，tools 再加一条会读不出主次
     */
    private static final List<String> COMPARED_METRICS = List.of("sessions", "messages", "activeusers");
    private final AgentDashboardReader reader;
    private final Clock clock;
    private final Map<String, Snapshot> cache = new ConcurrentHashMap<>();

    @Autowired
    public AgentDashboardService(AgentDashboardReader reader) {
        this(reader, Clock.systemDefaultZone());
    }

    AgentDashboardService(AgentDashboardReader reader, Clock clock) {
        this.reader = reader;
        this.clock = clock;
    }

    @Override
    public DashboardOverviewVO loadOverview(String window) {
        return snapshot(window, null, "24h").overview();
    }

    @Override
    public AgentDashboardPerformance loadPerformance(String window) {
        return snapshot(window, null, "24h").performance();
    }

    @Override
    public DashboardTrendsVO loadTrends(String metric, String window, String granularity) {
        String key = metric == null ? "" : metric.trim().toLowerCase(Locale.ROOT);
        if (!List.of("sessions", "messages", "activeusers", "tools", "replies").contains(key)) {
            throw new ClientException("Agent 模式不支持该统计指标");
        }
        return snapshot(window, granularity, "7d").trends().get(key);
    }

    private Snapshot snapshot(String window, String granularity, String fallback) {
        String label = AgentDashboardWindow.normalize(window, fallback);
        String grain = AgentDashboardWindow.granularity(granularity, label);
        return cache.compute(label + ":" + grain, (key, cached) -> {
            long now = clock.millis();
            if (cached != null && now >= cached.updatedAt() && now - cached.updatedAt() < CACHE_TTL_MS) {
                return cached;
            }
            AgentDashboardWindow range = AgentDashboardWindow.at(label, grain, clock);
            AgentDashboardReader.Rows rows = reader.read(range);
            return new Snapshot(range.updatedAt(), overview(range, rows.overview()), performance(range, rows),
                    trends(range, rows));
        });
    }

    private DashboardOverviewVO overview(AgentDashboardWindow window, Map<String, Object> row) {
        return DashboardOverviewVO.builder().engine("agent").window(window.label())
                .compareWindow("prev_" + window.label()).updatedAt(window.updatedAt())
                .kpis(DashboardOverviewGroupVO.builder()
                        .totalUsers(kpi(count(row, "total_users"), count(row, "new_users"), null))
                        .totalSessions(kpi(count(row, "total_sessions"), count(row, "sessions"), null))
                        .totalMessages(kpi(count(row, "total_messages"), count(row, "messages"), null))
                        .sessions24h(compared(row, "sessions"))
                        .messages24h(compared(row, "messages"))
                        .activeUsers(compared(row, "active_users"))
                        .activeSessions(compared(row, "active_sessions")).build())
                .build();
    }

    private AgentDashboardPerformance performance(AgentDashboardWindow window, AgentDashboardReader.Rows rows) {
        Map<String, Object> replies = rows.replies();
        long total = count(replies, "total");
        long normal = count(replies, "normal");
        long interrupted = count(replies, "interrupted");
        long awaiting = count(replies, "awaiting_confirm");
        long withBlocks = count(replies, "with_blocks");
        Map<String, Long> tools = new HashMap<>();
        Map<String, Long> confirms = new HashMap<>();
        List<AgentDashboardPerformance.ToolCount> topTools = new ArrayList<>();
        List<AgentDashboardPerformance.ConfirmToolCount> confirmTools = new ArrayList<>();
        long knowledgeCalls = 0;
        for (Map<String, Object> block : rows.blocks()) {
            if ("status".equals(block.get("category"))) {
                Map<String, Long> target = "tool".equals(block.get("kind")) ? tools : confirms;
                target.merge(String.valueOf(block.get("status")), count(block, "count"), Long::sum);
                knowledgeCalls += count(block, "knowledge_calls");
            } else if ("tool".equals(block.get("category"))) {
                long calls = count(block, "count");
                topTools.add(new AgentDashboardPerformance.ToolCount(String.valueOf(block.get("name")),
                        String.valueOf(block.get("display_name")), calls, count(block, "done"),
                        count(block, "failed"), ratio(count(block, "done"), calls, 100),
                        epochMillis(block.get("last_call_at"))));
            } else if ("confirmTool".equals(block.get("category"))) {
                confirmTools.add(new AgentDashboardPerformance.ConfirmToolCount(String.valueOf(block.get("name")),
                        String.valueOf(block.get("display_name")), count(block, "count"),
                        count(block, "approved"), count(block, "denied")));
            }
        }
        topTools.sort(Comparator.comparingLong(AgentDashboardPerformance.ToolCount::count).reversed()
                .thenComparing(AgentDashboardPerformance.ToolCount::name));
        confirmTools.sort(Comparator.comparingLong(AgentDashboardPerformance.ConfirmToolCount::calls).reversed()
                .thenComparing(AgentDashboardPerformance.ConfirmToolCount::name));
        long toolTotal = sum(tools);
        long confirmTotal = sum(confirms);
        long approved = confirms.getOrDefault("approved", 0L);
        long denied = confirms.getOrDefault("denied", 0L);
        Map<String, Object> memory = rows.memory();
        long previousTotal = count(replies, "previous_total");
        return new AgentDashboardPerformance(window.label(), window.updatedAt(),
                new AgentDashboardPerformance.Replies(total, previousTotal,
                        ratio(total - previousTotal, previousTotal, 100), normal, interrupted, awaiting,
                        total - normal - interrupted - awaiting, withBlocks, count(replies, "direct_replies"),
                        count(replies, "single_tool_replies"), count(replies, "multi_tool_replies")),
                new AgentDashboardPerformance.Tools(toolTotal, tools.getOrDefault("done", 0L),
                        tools.getOrDefault("failed", 0L), tools.getOrDefault("interrupted", 0L),
                        toolTotal - tools.getOrDefault("done", 0L) - tools.getOrDefault("failed", 0L) - tools.getOrDefault("interrupted", 0L),
                        ratio(toolTotal, withBlocks, 1), knowledgeCalls, List.copyOf(topTools)),
                new AgentDashboardPerformance.Confirmations(confirmTotal, approved, denied,
                        confirms.getOrDefault("pending", 0L), confirms.getOrDefault("expired", 0L),
                        confirmTotal - approved - denied - confirms.getOrDefault("pending", 0L) - confirms.getOrDefault("expired", 0L),
                        ratio(approved, approved + denied, 100), List.copyOf(confirmTools)),
                new AgentDashboardPerformance.Memory(count(memory, "compactions"),
                        count(memory, "compactions_with_chars"), decimal(memory, "context_reduction_pct"),
                        count(memory, "context_chars_before"), count(memory, "context_chars_after"),
                        count(memory, "active_memories"), count(memory, "added_memories"), count(memory, "invalidated_memories")));
    }

    private Map<String, DashboardTrendsVO> trends(AgentDashboardWindow window, AgentDashboardReader.Rows rows) {
        Map<String, Map<LocalDateTime, Long>> values = new HashMap<>();
        for (Map<String, Object> row : rows.trends()) {
            values.computeIfAbsent(String.valueOf(row.get("metric")), ignored -> new HashMap<>())
                    .put(timestamp(row.get("bucket")), count(row, "value"));
        }
        for (Map<String, Object> row : rows.blocks()) {
            if ("trend".equals(row.get("category"))) {
                values.computeIfAbsent("tools", ignored -> new HashMap<>())
                        .put(timestamp(row.get("bucket")), count(row, "count"));
            }
        }
        // 上周期桶键仍落在上周期区间，查表时把当前桶整体前移一个窗口长度；窗口长度是粒度的整数倍，平移后精确对齐
        Duration shift = Duration.between(window.previousStart(), window.start());
        Map<String, String> names = Map.of("tools", "已记录工具调用", "normal", "正常", "interrupted", "中断",
                "awaitingConfirm", "待确认", "unknown", "其他状态");
        Map<String, DashboardTrendsVO> result = new HashMap<>();
        for (String metric : List.of("sessions", "messages", "activeusers", "tools", "replies")) {
            List<DashboardTrendSeriesVO> series = new ArrayList<>();
            if (COMPARED_METRICS.contains(metric)) {
                // 指标名由前端的维度切换标签承载，这里两条序列只区分周期
                series.add(series("当前周期", window, values.get(metric), Duration.ZERO));
                series.add(series("上周期", window, values.get(metric + "_prev"), shift));
            } else if ("replies".equals(metric)) {
                for (String key : List.of("normal", "interrupted", "awaitingConfirm", "unknown")) {
                    series.add(series(names.get(key), window, values.get(key), Duration.ZERO));
                }
            } else {
                series.add(series(names.get(metric), window, values.get(metric), Duration.ZERO));
            }
            result.put(metric, DashboardTrendsVO.builder().metric(metric).window(window.label())
                    .granularity(window.granularity()).series(series).build());
        }
        return Map.copyOf(result);
    }

    private DashboardTrendSeriesVO series(String name, AgentDashboardWindow window,
                                          Map<LocalDateTime, Long> values, Duration shift) {
        return DashboardTrendSeriesVO.builder().name(name).data(points(window, values, shift)).build();
    }

    private List<DashboardTrendPointVO> points(AgentDashboardWindow window, Map<LocalDateTime, Long> values,
                                               Duration shift) {
        ChronoUnit unit = window.granularity().equals("hour") ? ChronoUnit.HOURS : ChronoUnit.DAYS;
        Map<LocalDateTime, Long> source = values == null ? Map.of() : values;
        List<DashboardTrendPointVO> points = new ArrayList<>();
        // 首尾桶只计入滚动窗口内的记录，不扩展查询到整小时/整日
        for (LocalDateTime bucket = window.start().truncatedTo(unit); bucket.isBefore(window.end()); bucket = bucket.plus(1, unit)) {
            points.add(DashboardTrendPointVO.builder().ts(bucket.atZone(clock.getZone()).toInstant().toEpochMilli())
                    .value(source.getOrDefault(bucket.minus(shift), 0L).doubleValue()).build());
        }
        return points;
    }

    private static LocalDateTime timestamp(Object value) {
        return value instanceof Timestamp time ? time.toLocalDateTime() : (LocalDateTime) value;
    }

    /**
     * 库里是不带时区的 TIMESTAMP，换算成毫秒要用统计自己的时区，不能落到 JVM 默认时区上
     */
    private Long epochMillis(Object value) {
        if (!(value instanceof Timestamp) && !(value instanceof LocalDateTime)) {
            return null;
        }
        return timestamp(value).atZone(clock.getZone()).toInstant().toEpochMilli();
    }

    private static long count(Map<String, Object> row, String key) {
        return row.get(key) instanceof Number number ? number.longValue() : 0;
    }

    private static Double decimal(Map<String, Object> row, String key) {
        return row.get(key) instanceof Number number ? Math.round(number.doubleValue() * 100.0) / 100.0 : null;
    }

    private static long sum(Map<String, Long> values) {
        return values.values().stream().mapToLong(Long::longValue).sum();
    }

    private static Double ratio(long numerator, long denominator, int scale) {
        return denominator == 0 ? null : Math.round(numerator * (double) scale / denominator * 100) / 100.0;
    }

    private static DashboardOverviewKpiVO compared(Map<String, Object> row, String key) {
        long current = count(row, key);
        long previous = count(row, "previous_" + key);
        return kpi(current, current - previous, ratio(current - previous, previous, 100));
    }

    private static DashboardOverviewKpiVO kpi(long value, long delta, Double deltaPct) {
        return DashboardOverviewKpiVO.builder().value(value).delta(delta).deltaPct(deltaPct).build();
    }

    private record Snapshot(long updatedAt, DashboardOverviewVO overview, AgentDashboardPerformance performance,
                            Map<String, DashboardTrendsVO> trends) {
    }
}
