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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.admin.controller.DashboardController;
import com.nageoffer.ai.ragent.admin.service.DashboardService;
import com.nageoffer.ai.ragent.admin.service.impl.DashboardServiceImpl;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import cn.dev33.satoken.annotation.SaCheckRole;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentDashboardServiceTest {

    private final AgentDashboardReader reader = mock(AgentDashboardReader.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-04T04:30:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now.get(), zone); }
        @Override public Instant instant() { return now.get(); }
    };
    private final AgentDashboardService service = new AgentDashboardService(reader, clock);

    @Test
    void missingSamplesAreNotZeroRatesAndOnlyAgentFieldsAreSerialized() throws Exception {
        when(reader.read(any())).thenReturn(emptyRows());
        var performance = service.loadPerformance("24h");
        assertThat(performance.replies().total()).isZero();
        assertThat(performance.tools().callsPerRecordedReply()).isNull();
        assertThat(performance.confirmations().approvalRate()).isNull();
        assertThat(performance.memory().contextReductionPct()).isNull();
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(performance));
        assertThat(json.get("engine").asText()).isEqualTo("agent");
        assertThat(json.has("successRate")).isFalse();
        assertThat(json.has("avgLatencyMs")).isFalse();
    }

    @Test
    void aggregatesKnownAndUnknownStatusesWithExplicitDenominators() {
        when(reader.read(any())).thenReturn(new AgentDashboardReader.Rows(
                Map.of("messages", 12L, "previous_messages", 8L, "active_sessions", 2L, "previous_active_sessions", 1L),
                Map.of("total", 6L, "previous_total", 4L, "normal", 2L, "interrupted", 1L, "awaiting_confirm", 2L,
                        "with_blocks", 3L, "direct_replies", 1L, "single_tool_replies", 1L, "multi_tool_replies", 1L),
                List.of(status("tool", "done", 3), status("tool", "failed", 2), status("tool", "other", 1),
                        status("confirm", "approved", 1), status("confirm", "denied", 1), status("confirm", "pending", 8),
                        status("confirm", "expired", 2), status("confirm", "other", 1),
                        tool("search_knowledge", 4, 3, 1), tool("write", 2, 0, 1),
                        confirmTool("write", 2, 1, 1), confirmTool("submit_leave", 12, 8, 2)),
                Map.of("active_memories", 8L, "compactions", 5L, "compactions_with_chars", 4L,
                        "context_reduction_pct", 62.123,
                        "context_chars_before", 4000L, "context_chars_after", 1500L), List.of()));
        var overview = service.loadOverview("24h");
        assertThat(overview.getEngine()).isEqualTo("agent");
        assertThat(overview.getKpis().getMessages24h().getDeltaPct()).isEqualTo(50.0);
        assertThat(overview.getKpis().getActiveSessions().getValue()).isEqualTo(2L);
        var performance = service.loadPerformance("24h");
        assertThat(performance.replies().unknown()).isEqualTo(1);
        assertThat(performance.tools().total()).isEqualTo(6);
        assertThat(performance.tools().callsPerRecordedReply()).isEqualTo(2.0);
        assertThat(performance.tools().other()).isEqualTo(1);
        assertThat(performance.confirmations().approvalRate()).isEqualTo(50.0);
        assertThat(performance.confirmations().total()).isEqualTo(13);
        assertThat(performance.confirmations().other()).isEqualTo(1);
        assertThat(performance.memory().contextReductionPct()).isEqualTo(62.12);
        assertThat(performance.memory().contextCharsBefore()).isEqualTo(4000);
        assertThat(performance.memory().contextCharsAfter()).isEqualTo(1500);
        // 三档相加等于 with_blocks，剩下的 3 条回复只是轨迹没落库，不能算成直接回答
        assertThat(performance.replies().directReplies() + performance.replies().singleToolReplies()
                + performance.replies().multiToolReplies()).isEqualTo(performance.replies().withBlocks());
        // 成功率分母是该工具的全部调用，不是 done + failed：write 的 2 次里有 1 次既非成功也非失败
        assertThat(performance.tools().topTools()).extracting("name", "count", "done", "failed", "successRate")
                .containsExactly(tuple("search_knowledge", 4L, 3L, 1L, 75.0), tuple("write", 2L, 0L, 1L, 0.0));
        // 库里是不带时区的 TIMESTAMP，毫秒要按统计时区换算，不能落到 JVM 默认时区
        assertThat(performance.tools().topTools().get(0).lastCallAt()).isEqualTo(
                LocalDateTime.parse("2026-09-04T11:20:00").atZone(clock.getZone()).toInstant().toEpochMilli());
        // 助手回复的环比与概览四个 KPI 同口径：(6 - 4) / 4
        assertThat(performance.replies().deltaPct()).isEqualTo(50.0);
        // 一张卡可以裹多个调用，所以各工具调用数之和（14）允许大于卡片总数（13）
        assertThat(performance.confirmations().topTools())
                .extracting("name", "calls", "approved", "denied")
                .containsExactly(tuple("submit_leave", 12L, 8L, 2L), tuple("write", 2L, 1L, 1L));
        assertThat(performance.confirmations().topTools().stream().mapToLong(t -> t.calls()).sum())
                .isGreaterThan(performance.confirmations().total());
        // 字符数只覆盖 compactions_with_chars 这批事件，求平均时分母不能用 compactions
        assertThat(performance.memory().compactions()).isEqualTo(5);
        assertThat(performance.memory().compactionsWithChars()).isEqualTo(4);
    }

    @Test
    void sharesSnapshotAcrossEndpointsAndRefreshesAfterThirtySeconds() {
        when(reader.read(any())).thenReturn(emptyRows());
        long initial = service.loadOverview(" 24H ").getUpdatedAt();
        service.loadPerformance("24h");
        service.loadTrends("messages", "24h", "hour");
        now.set(now.get().plusSeconds(29));
        assertThat(service.loadOverview("24h").getUpdatedAt()).isEqualTo(initial);
        verify(reader, times(1)).read(any());
        now.set(now.get().plusSeconds(1));
        assertThat(service.loadOverview("24h").getUpdatedAt()).isEqualTo(initial + 30_000);
        verify(reader, times(2)).read(any());
    }

    @Test
    void failedReadsAreNotCached() {
        when(reader.read(any())).thenThrow(new IllegalStateException("unavailable")).thenReturn(emptyRows());
        assertThatThrownBy(() -> service.loadOverview("24h")).isInstanceOf(IllegalStateException.class);
        assertThat(service.loadOverview("24h").getKpis().getMessages24h().getValue()).isZero();
        verify(reader, times(2)).read(any());
    }

    @Test
    void rollingTrendKeepsPartialBucketsAndFillsMissingCounts() {
        var base = emptyRows();
        when(reader.read(any())).thenReturn(new AgentDashboardReader.Rows(base.overview(), base.replies(),
                base.blocks(), base.memory(), List.of(
                Map.of("metric", "messages", "value", 7L, "bucket", Timestamp.valueOf("2026-09-03 12:00:00")),
                Map.of("metric", "messages_prev", "value", 5L, "bucket", Timestamp.valueOf("2026-09-02 12:00:00")))));
        var trend = service.loadTrends("messages", "24h", "hour");
        assertThat(trend.getSeries()).extracting("name").containsExactly("当前周期", "上周期");
        assertThat(trend.getSeries().get(0).getData()).hasSize(25);
        assertThat(trend.getSeries().get(0).getData().get(0).getValue()).isEqualTo(7.0);
        assertThat(trend.getSeries().get(0).getData().get(1).getValue()).isZero();
        assertThat(trend.getSeries().get(0).getData().get(0).getTs())
                .isEqualTo(LocalDateTime.parse("2026-09-03T12:00:00").atZone(clock.getZone()).toInstant().toEpochMilli());
        // 上周期那一桶比当前周期早 24 小时，前移后必须落在同一个下标与同一个横坐标上
        assertThat(trend.getSeries().get(1).getData().get(0).getValue()).isEqualTo(5.0);
        assertThat(trend.getSeries().get(1).getData().get(0).getTs())
                .isEqualTo(trend.getSeries().get(0).getData().get(0).getTs());
        assertThat(trend.getSeries().get(1).getData().get(1).getValue()).isZero();
        assertThat(service.loadTrends("replies", "24h", "hour").getSeries())
                .extracting("name").containsExactly("正常", "中断", "待确认", "其他状态");
        assertThat(service.loadTrends("tools", "24h", "hour").getSeries()).hasSize(1);
    }

    @Test
    void rejectsUnboundedWindowsAndWorkflowOnlyMetricsBeforeQuerying() {
        for (String window : List.of("0h", "-7d", "365d", "999999999999999d", "invalid")) {
            assertThatThrownBy(() -> service.loadOverview(window)).isInstanceOf(ClientException.class);
        }
        assertThatThrownBy(() -> service.loadTrends("messages", "24h", "minute")).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.loadTrends("avgLatency", "24h", "hour")).isInstanceOf(ClientException.class);
        verifyNoInteractions(reader);
    }

    @Test
    void bindsExactlyOneStatisticsServiceForEitherEngineAndDefaultsToWorkflow() {
        var context = new ApplicationContextRunner()
                .withUserConfiguration(AgentDashboardService.class, DashboardServiceImpl.class)
                .withBean(AgentDashboardReader.class, () -> reader)
                .withBean(UserMapper.class, () -> mock(UserMapper.class))
                .withBean(ConversationMapper.class, () -> mock(ConversationMapper.class))
                .withBean(ConversationMessageMapper.class, () -> mock(ConversationMessageMapper.class))
                .withBean(RagTraceRunMapper.class, () -> mock(RagTraceRunMapper.class));
        context.run(ctx -> assertThat(ctx).hasSingleBean(DashboardService.class).hasSingleBean(DashboardServiceImpl.class));
        context.withPropertyValues("ragent.engine.type=workflow")
                .run(ctx -> assertThat(ctx).hasSingleBean(DashboardService.class).hasSingleBean(DashboardServiceImpl.class));
        context.withPropertyValues("ragent.engine.type=agent")
                .run(ctx -> assertThat(ctx).hasSingleBean(DashboardService.class).hasSingleBean(AgentDashboardService.class));
    }

    @Test
    void dashboardIsAdminOnly() {
        assertThat(DashboardController.class.getAnnotation(SaCheckRole.class).value()).containsExactly("admin");
    }

    private static Map<String, Object> status(String kind, String status, long count) {
        return Map.of("category", "status", "kind", kind, "status", status, "count", count);
    }

    private static Map<String, Object> tool(String name, long count, long done, long failed) {
        return Map.of("category", "tool", "kind", "tool", "name", name, "display_name", name,
                "count", count, "done", done, "failed", failed,
                "last_call_at", Timestamp.valueOf("2026-09-04 11:20:00"));
    }

    private static Map<String, Object> confirmTool(String name, long calls, long approved, long denied) {
        return Map.of("category", "confirmTool", "kind", "confirm", "name", name, "display_name", name,
                "count", calls, "approved", approved, "denied", denied);
    }

    private static AgentDashboardReader.Rows emptyRows() {
        return new AgentDashboardReader.Rows(Map.of(), Map.of(), List.of(), Map.of(), List.of());
    }
}
