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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewGroupVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewKpiVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardPerformanceVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardTrendPointVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardTrendSeriesVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardTrendsVO;
import com.nageoffer.ai.ragent.admin.service.DashboardService;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_ERROR = "ERROR";
    private static final String GRANULARITY_DAY = "day";
    private static final String GRANULARITY_HOUR = "hour";
    private static final DateTimeFormatter HOUR_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserMapper userMapper;
    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final RagTraceRunMapper traceRunMapper;

    @Override
    public DashboardOverviewVO loadOverview(String window) {
        WindowRange range = resolveWindowRange(window, Duration.ofHours(24));

        long totalUsers = userMapper.selectCount(Wrappers.lambdaQuery(UserDO.class));
        long usersInWindow = countUsers(range.start, range.end);

        long totalSessions = conversationMapper.selectCount(Wrappers.lambdaQuery(ConversationDO.class));
        long sessionsInWindow = countConversations(range.start, range.end);
        long sessionsPrevWindow = countConversations(range.prevStart, range.prevEnd);

        long totalMessages = messageMapper.selectCount(Wrappers.lambdaQuery(ConversationMessageDO.class));
        long messagesInWindow = countMessages(range.start, range.end);
        long messagesPrevWindow = countMessages(range.prevStart, range.prevEnd);

        long activeUsers = countActiveUsers(range.start, range.end);
        long activeUsersPrev = countActiveUsers(range.prevStart, range.prevEnd);

        DashboardOverviewGroupVO group = DashboardOverviewGroupVO.builder()
                .totalUsers(buildKpi(totalUsers, usersInWindow, null))
                .activeUsers(buildKpi(activeUsers, activeUsers - activeUsersPrev, calcPct(activeUsers, activeUsersPrev)))
                .totalSessions(buildKpi(totalSessions, sessionsInWindow, null))
                .sessions24h(buildKpi(sessionsInWindow, sessionsInWindow - sessionsPrevWindow, calcPct(sessionsInWindow, sessionsPrevWindow)))
                .totalMessages(buildKpi(totalMessages, messagesInWindow, null))
                .messages24h(buildKpi(messagesInWindow, messagesInWindow - messagesPrevWindow, calcPct(messagesInWindow, messagesPrevWindow)))
                .build();

        return DashboardOverviewVO.builder()
                .window(range.windowLabel)
                .compareWindow(range.compareLabel)
                .updatedAt(System.currentTimeMillis())
                .kpis(group)
                .build();
    }

    @Override
    public DashboardPerformanceVO loadPerformance(String window) {
        WindowRange range = resolveWindowRange(window, Duration.ofHours(24));
        List<Long> durations = listDurations(range.start, range.end);
        long avgLatency = average(durations);
        long p95Latency = percentile(durations);

        long success = countTraceRuns(range.start, range.end, STATUS_SUCCESS);
        long error = countTraceRuns(range.start, range.end, STATUS_ERROR);
        long total = success + error;

        double successRate = total == 0 ? 0.0 : round1((success * 100.0) / total);
        double errorRate = total == 0 ? 0.0 : round1((error * 100.0) / total);
        double noDocRate = total == 0 ? 0.0 : 100.0;

        return DashboardPerformanceVO.builder()
                .window(range.windowLabel)
                .avgLatencyMs(avgLatency)
                .p95LatencyMs(p95Latency)
                .successRate(successRate)
                .errorRate(errorRate)
                .noDocRate(noDocRate)
                .build();
    }

    @Override
    public DashboardTrendsVO loadTrends(String metric, String window, String granularity) {
        String normalizedMetric = metric == null ? "" : metric.trim().toLowerCase();
        Duration windowDuration = parseWindow(window, Duration.ofDays(7));
        WindowRange range = resolveWindowRange(window, Duration.ofDays(7));
        String resolvedGranularity = resolveTrendGranularity(granularity, windowDuration);
        ZoneId zoneId = ZoneId.systemDefault();
        List<DashboardTrendSeriesVO> series = new ArrayList<>();

        if (GRANULARITY_HOUR.equals(resolvedGranularity)) {
            LocalDateTime endHourExclusive = toLocalDateTime(range.end, zoneId)
                    .truncatedTo(ChronoUnit.HOURS)
                    .plusHours(1);
            LocalDateTime startHour = endHourExclusive.minusHours(Math.max(1, windowDuration.toHours()));

            if ("sessions".equals(normalizedMetric)) {
                Map<LocalDateTime, Long> counts = countConversationsByHour(startHour, endHourExclusive, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("会话数")
                        .data(buildPointsByHour(startHour, endHourExclusive, zoneId, counts))
                        .build());
            } else if ("activeusers".equals(normalizedMetric)) {
                Map<LocalDateTime, Long> counts = countActiveUsersByHour(startHour, endHourExclusive, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("活跃用户")
                        .data(buildPointsByHour(startHour, endHourExclusive, zoneId, counts))
                        .build());
            } else if ("avglatency".equals(normalizedMetric)) {
                Map<LocalDateTime, Double> averages = averageLatencyByHour(startHour, endHourExclusive, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("平均响应时间")
                        .data(buildPointsDoubleByHour(startHour, endHourExclusive, zoneId, averages))
                        .build());
            } else if ("quality".equals(normalizedMetric)) {
                Map<LocalDateTime, Long> successMap = countTraceRunsByHour(startHour, endHourExclusive, zoneId, STATUS_SUCCESS);
                Map<LocalDateTime, Long> errorMap = countTraceRunsByHour(startHour, endHourExclusive, zoneId, STATUS_ERROR);
                Map<LocalDateTime, Double> errorRate = new HashMap<>();
                Map<LocalDateTime, Double> noDocRate = new HashMap<>();
                for (LocalDateTime hour = startHour; hour.isBefore(endHourExclusive); hour = hour.plusHours(1)) {
                    long total = successMap.getOrDefault(hour, 0L) + errorMap.getOrDefault(hour, 0L);
                    long error = errorMap.getOrDefault(hour, 0L);
                    double err = total == 0 ? 0.0 : round1((error * 100.0) / total);
                    double noDoc = total == 0 ? 0.0 : 100.0;
                    errorRate.put(hour, err);
                    noDocRate.put(hour, noDoc);
                }
                series.add(DashboardTrendSeriesVO.builder()
                        .name("错误率")
                        .data(buildPointsDoubleByHour(startHour, endHourExclusive, zoneId, errorRate))
                        .build());
                series.add(DashboardTrendSeriesVO.builder()
                        .name("无知识率")
                        .data(buildPointsDoubleByHour(startHour, endHourExclusive, zoneId, noDocRate))
                        .build());
            }
        } else {
            LocalDate startDay = toLocalDate(range.start, zoneId);
            LocalDate endExclusiveDay = toLocalDate(range.end, zoneId).plusDays(1);

            if ("sessions".equals(normalizedMetric)) {
                Map<LocalDate, Long> counts = countConversationsByDay(startDay, endExclusiveDay, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("会话数")
                        .data(buildPoints(startDay, endExclusiveDay, zoneId, counts))
                        .build());
            } else if ("activeusers".equals(normalizedMetric)) {
                Map<LocalDate, Long> counts = countActiveUsersByDay(startDay, endExclusiveDay, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("活跃用户")
                        .data(buildPoints(startDay, endExclusiveDay, zoneId, counts))
                        .build());
            } else if ("avglatency".equals(normalizedMetric)) {
                Map<LocalDate, Double> averages = averageLatencyByDay(startDay, endExclusiveDay, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("平均响应时间")
                        .data(buildPointsDouble(startDay, endExclusiveDay, zoneId, averages))
                        .build());
            } else if ("quality".equals(normalizedMetric)) {
                Map<LocalDate, Long> successMap = countTraceRunsByDay(startDay, endExclusiveDay, zoneId, STATUS_SUCCESS);
                Map<LocalDate, Long> errorMap = countTraceRunsByDay(startDay, endExclusiveDay, zoneId, STATUS_ERROR);
                Map<LocalDate, Double> errorRate = new HashMap<>();
                Map<LocalDate, Double> noDocRate = new HashMap<>();
                for (LocalDate day = startDay; day.isBefore(endExclusiveDay); day = day.plusDays(1)) {
                    long total = successMap.getOrDefault(day, 0L) + errorMap.getOrDefault(day, 0L);
                    long error = errorMap.getOrDefault(day, 0L);
                    double err = total == 0 ? 0.0 : round1((error * 100.0) / total);
                    double noDoc = total == 0 ? 0.0 : 100.0;
                    errorRate.put(day, err);
                    noDocRate.put(day, noDoc);
                }
                series.add(DashboardTrendSeriesVO.builder()
                        .name("错误率")
                        .data(buildPointsDouble(startDay, endExclusiveDay, zoneId, errorRate))
                        .build());
                series.add(DashboardTrendSeriesVO.builder()
                        .name("无知识率")
                        .data(buildPointsDouble(startDay, endExclusiveDay, zoneId, noDocRate))
                        .build());
            }
        }

        return DashboardTrendsVO.builder()
                .metric(metric)
                .window(range.windowLabel)
                .granularity(resolvedGranularity)
                .series(series)
                .build();
    }

    private long countUsers(Date start, Date end) {
        return userMapper.selectCount(Wrappers.lambdaQuery(UserDO.class)
                .ge(UserDO::getCreateTime, start)
                .lt(UserDO::getCreateTime, end));
    }

    private long countConversations(Date start, Date end) {
        return conversationMapper.selectCount(Wrappers.lambdaQuery(ConversationDO.class)
                .ge(ConversationDO::getCreateTime, start)
                .lt(ConversationDO::getCreateTime, end));
    }

    private long countMessages(Date start, Date end) {
        return messageMapper.selectCount(Wrappers.lambdaQuery(ConversationMessageDO.class)
                .ge(ConversationMessageDO::getCreateTime, start)
                .lt(ConversationMessageDO::getCreateTime, end));
    }

    private long countActiveUsers(Date start, Date end) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("count(distinct user_id) as cnt")
                .ge("create_time", start)
                .lt("create_time", end);
        return extractCount(messageMapper.selectMaps(wrapper));
    }

    private long countTraceRuns(Date start, Date end, String status) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.ge("start_time", start).lt("start_time", end);
        if (status != null) {
            wrapper.eq("status", status);
        }
        return traceRunMapper.selectCount(wrapper);
    }

    private List<Long> listDurations(Date start, Date end) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("duration_ms")
                .ge("start_time", start)
                .lt("start_time", end)
                .eq("status", STATUS_SUCCESS);
        List<Object> results = traceRunMapper.selectObjs(wrapper);
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> durations = new ArrayList<>();
        for (Object value : results) {
            if (value instanceof Number number) {
                long duration = number.longValue();
                if (duration > 0) {
                    durations.add(duration);
                }
            }
        }
        return durations;
    }

    private long extractCount(List<Map<String, Object>> maps) {
        if (maps == null || maps.isEmpty()) {
            return 0L;
        }
        Object value = maps.get(0).get("cnt");
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private Double calcPct(long current, long prev) {
        if (prev <= 0) {
            return null;
        }
        return round1(((current - prev) * 100.0) / prev);
    }

    private DashboardOverviewKpiVO buildKpi(long value, long delta, Double deltaPct) {
        return DashboardOverviewKpiVO.builder()
                .value(value)
                .delta(delta)
                .deltaPct(deltaPct)
                .build();
    }

    private Map<LocalDate, Long> countConversationsByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(create_time,'%Y-%m-%d') as d", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("d");
        return mapLongResults(conversationMapper.selectMaps(wrapper));
    }

    private Map<LocalDate, Long> countActiveUsersByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(create_time,'%Y-%m-%d') as d", "count(distinct user_id) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("d");
        return mapLongResults(messageMapper.selectMaps(wrapper));
    }

    private Map<LocalDate, Double> averageLatencyByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(start_time,'%Y-%m-%d') as d", "avg(duration_ms) as avg")
                .ge("start_time", toDate(start, zoneId))
                .lt("start_time", toDate(endExclusive, zoneId))
                .eq("status", STATUS_SUCCESS)
                .groupBy("d");
        List<Map<String, Object>> maps = traceRunMapper.selectMaps(wrapper);
        Map<LocalDate, Double> result = new HashMap<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> row : maps) {
            LocalDate date = parseLocalDate(row.get("d"));
            if (date == null) {
                continue;
            }
            Object value = row.get("avg");
            double avg = value instanceof Number number ? number.doubleValue() : 0.0;
            result.put(date, round1(avg));
        }
        return result;
    }

    private Map<LocalDate, Long> countTraceRunsByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId, String status) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(start_time,'%Y-%m-%d') as d", "count(*) as cnt")
                .ge("start_time", toDate(start, zoneId))
                .lt("start_time", toDate(endExclusive, zoneId));
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.groupBy("d");
        return mapLongResults(traceRunMapper.selectMaps(wrapper));
    }

    private Map<LocalDateTime, Long> countConversationsByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(create_time,'%Y-%m-%d %H:00:00') as h", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("h");
        return mapLongResultsByHour(conversationMapper.selectMaps(wrapper));
    }

    private Map<LocalDateTime, Long> countActiveUsersByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(create_time,'%Y-%m-%d %H:00:00') as h", "count(distinct user_id) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("h");
        return mapLongResultsByHour(messageMapper.selectMaps(wrapper));
    }

    private Map<LocalDateTime, Double> averageLatencyByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(start_time,'%Y-%m-%d %H:00:00') as h", "avg(duration_ms) as avg")
                .ge("start_time", toDate(start, zoneId))
                .lt("start_time", toDate(endExclusive, zoneId))
                .eq("status", STATUS_SUCCESS)
                .groupBy("h");
        return mapDoubleResultsByHour(traceRunMapper.selectMaps(wrapper));
    }

    private Map<LocalDateTime, Long> countTraceRunsByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId, String status) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(start_time,'%Y-%m-%d %H:00:00') as h", "count(*) as cnt")
                .ge("start_time", toDate(start, zoneId))
                .lt("start_time", toDate(endExclusive, zoneId));
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.groupBy("h");
        return mapLongResultsByHour(traceRunMapper.selectMaps(wrapper));
    }

    private Map<LocalDate, Long> mapLongResults(List<Map<String, Object>> maps) {
        Map<LocalDate, Long> result = new HashMap<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> row : maps) {
            LocalDate date = parseLocalDate(row.get("d"));
            if (date == null) {
                continue;
            }
            Long value = toLongValue(row.get("cnt"));
            if (value != null) {
                result.put(date, value);
            }
        }
        return result;
    }

    private Map<LocalDateTime, Long> mapLongResultsByHour(List<Map<String, Object>> maps) {
        Map<LocalDateTime, Long> result = new HashMap<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> row : maps) {
            LocalDateTime dateTime = parseLocalDateTime(row.get("h"));
            if (dateTime == null) {
                continue;
            }
            Long value = toLongValue(row.get("cnt"));
            if (value != null) {
                result.put(dateTime, value);
            }
        }
        return result;
    }

    private Map<LocalDateTime, Double> mapDoubleResultsByHour(List<Map<String, Object>> maps) {
        Map<LocalDateTime, Double> result = new HashMap<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> row : maps) {
            LocalDateTime dateTime = parseLocalDateTime(row.get("h"));
            if (dateTime == null) {
                continue;
            }
            Object value = row.get("avg");
            double avg = value instanceof Number number ? number.doubleValue() : 0.0;
            result.put(dateTime, round1(avg));
        }
        return result;
    }

    private List<DashboardTrendPointVO> buildPoints(LocalDate start, LocalDate endExclusive, ZoneId zoneId, Map<LocalDate, Long> values) {
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDate cursor = start;
        while (cursor.isBefore(endExclusive)) {
            long value = values.getOrDefault(cursor, 0L);
            points.add(DashboardTrendPointVO.builder()
                    .ts(toDate(cursor, zoneId).getTime())
                    .value((double) value)
                    .build());
            cursor = cursor.plusDays(1);
        }
        return points;
    }

    private List<DashboardTrendPointVO> buildPointsDouble(LocalDate start, LocalDate endExclusive, ZoneId zoneId, Map<LocalDate, Double> values) {
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDate cursor = start;
        while (cursor.isBefore(endExclusive)) {
            double value = values.getOrDefault(cursor, 0.0);
            points.add(DashboardTrendPointVO.builder()
                    .ts(toDate(cursor, zoneId).getTime())
                    .value(value)
                    .build());
            cursor = cursor.plusDays(1);
        }
        return points;
    }

    private List<DashboardTrendPointVO> buildPointsByHour(
            LocalDateTime start,
            LocalDateTime endExclusive,
            ZoneId zoneId,
            Map<LocalDateTime, Long> values) {
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDateTime cursor = start;
        while (cursor.isBefore(endExclusive)) {
            long value = values.getOrDefault(cursor, 0L);
            points.add(DashboardTrendPointVO.builder()
                    .ts(toDate(cursor, zoneId).getTime())
                    .value((double) value)
                    .build());
            cursor = cursor.plusHours(1);
        }
        return points;
    }

    private List<DashboardTrendPointVO> buildPointsDoubleByHour(
            LocalDateTime start,
            LocalDateTime endExclusive,
            ZoneId zoneId,
            Map<LocalDateTime, Double> values) {
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDateTime cursor = start;
        while (cursor.isBefore(endExclusive)) {
            double value = values.getOrDefault(cursor, 0.0);
            points.add(DashboardTrendPointVO.builder()
                    .ts(toDate(cursor, zoneId).getTime())
                    .value(value)
                    .build());
            cursor = cursor.plusHours(1);
        }
        return points;
    }

    private long average(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (Long value : values) {
            sum += value;
        }
        return Math.round(sum / (double) values.size());
    }

    private long percentile(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private LocalDate parseLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private LocalDateTime parseLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        return LocalDateTime.parse(String.valueOf(value), HOUR_FORMATTER);
    }

    private Long toLongValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Date toDate(LocalDate date, ZoneId zoneId) {
        return Date.from(date.atStartOfDay(zoneId).toInstant());
    }

    private Date toDate(LocalDateTime time, ZoneId zoneId) {
        return Date.from(time.atZone(zoneId).toInstant());
    }

    private LocalDate toLocalDate(Date date, ZoneId zoneId) {
        return date.toInstant().atZone(zoneId).toLocalDate();
    }

    private LocalDateTime toLocalDateTime(Date date, ZoneId zoneId) {
        return date.toInstant().atZone(zoneId).toLocalDateTime();
    }

    private WindowRange resolveWindowRange(String window, Duration fallback) {
        Duration duration = parseWindow(window, fallback);
        Instant now = Instant.now();
        Instant start = now.minus(duration);
        Instant prevStart = start.minus(duration);
        return new WindowRange(Date.from(start), Date.from(now), Date.from(prevStart), Date.from(start),
                window == null ? formatDuration(fallback) : window, "prev_" + (window == null ? formatDuration(fallback) : window));
    }

    private Duration parseWindow(String window, Duration fallback) {
        if (window == null || window.isBlank()) {
            return fallback;
        }
        String normalized = window.trim().toLowerCase();
        if (normalized.endsWith("h")) {
            long hours = parseNumber(normalized.substring(0, normalized.length() - 1), fallback.toHours());
            return Duration.ofHours(hours);
        }
        if (normalized.endsWith("d")) {
            long days = parseNumber(normalized.substring(0, normalized.length() - 1), fallback.toDays());
            return Duration.ofDays(days);
        }
        return fallback;
    }

    private String resolveTrendGranularity(String granularity, Duration windowDuration) {
        if (granularity != null && !granularity.isBlank()) {
            String normalized = granularity.trim().toLowerCase();
            if (GRANULARITY_HOUR.equals(normalized) || GRANULARITY_DAY.equals(normalized)) {
                return normalized;
            }
        }
        return windowDuration.toHours() <= 48 ? GRANULARITY_HOUR : GRANULARITY_DAY;
    }

    private long parseNumber(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        if (hours % 24 == 0) {
            return (hours / 24) + "d";
        }
        return hours + "h";
    }

    private static class WindowRange {
        private final Date start;
        private final Date end;
        private final Date prevStart;
        private final Date prevEnd;
        private final String windowLabel;
        private final String compareLabel;

        WindowRange(Date start, Date end, Date prevStart, Date prevEnd, String windowLabel, String compareLabel) {
            this.start = start;
            this.end = end;
            this.prevStart = prevStart;
            this.prevEnd = prevEnd;
            this.windowLabel = windowLabel;
            this.compareLabel = compareLabel;
        }
    }
}
