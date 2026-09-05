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

import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 管理端专属读模型，聚合在 PostgreSQL 内完成，永不加载消息/工具结果/记忆正文
 * <p>不依赖聊天服务，也不访问框架运行状态；只读快照事务限制查询时间，避免首页拖慢业务连接池
 */
@Repository
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentDashboardReader {

    private final NamedParameterJdbcTemplate jdbc;

    static final String OVERVIEW_SQL = """
            WITH users AS (
                SELECT count(*) AS total_users,
                       count(*) FILTER (WHERE create_time >= :start) AS new_users
                FROM t_user WHERE deleted = 0 AND create_time < :end
            ), sessions AS (
                SELECT count(*) AS total_sessions,
                       count(*) FILTER (WHERE create_time >= :start) AS sessions,
                       count(*) FILTER (WHERE create_time >= :previousStart AND create_time < :start) AS previous_sessions
                FROM t_agent_conversation WHERE deleted = 0 AND create_time < :end
            ), messages AS (
                SELECT count(*) AS total_messages,
                       count(*) FILTER (WHERE create_time >= :start) AS messages,
                       count(*) FILTER (WHERE create_time >= :previousStart AND create_time < :start) AS previous_messages,
                       count(DISTINCT user_id) FILTER (WHERE create_time >= :start) AS active_users,
                       count(DISTINCT user_id) FILTER (WHERE create_time >= :previousStart AND create_time < :start) AS previous_active_users,
                       count(DISTINCT (conversation_id, user_id)) FILTER (WHERE create_time >= :start) AS active_sessions,
                       count(DISTINCT (conversation_id, user_id)) FILTER (WHERE create_time >= :previousStart AND create_time < :start) AS previous_active_sessions
                FROM t_agent_message WHERE deleted = 0 AND create_time < :end
            )
            SELECT * FROM users CROSS JOIN sessions CROSS JOIN messages
            """;

    /*
     * 工具调用数分档的分母是「有轨迹的回复」而非全部回复：没有 blocks 的回复只说明轨迹没落库，
     * 并不能推断它没调用工具，把它算进直接回答会虚高；三档相加等于 with_blocks
     *
     * 上周期总数走独立子查询而不是把外层窗口拉宽：拉宽会让 jsonb 展开也跟着翻倍，
     * 而这一个数只要计数，不需要轨迹
     */
    static final String REPLIES_SQL = """
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE message_status = 'NORMAL') AS normal,
                   count(*) FILTER (WHERE message_status = 'INTERRUPTED') AS interrupted,
                   count(*) FILTER (WHERE message_status = 'AWAITING_CONFIRM') AS awaiting_confirm,
                   count(*) FILTER (WHERE block_count > 0) AS with_blocks,
                   count(*) FILTER (WHERE block_count > 0 AND tool_calls = 0) AS direct_replies,
                   count(*) FILTER (WHERE tool_calls = 1) AS single_tool_replies,
                   count(*) FILTER (WHERE tool_calls > 1) AS multi_tool_replies,
                   (SELECT count(*) FROM t_agent_message
                     WHERE deleted = 0 AND role = 'assistant'
                       AND create_time >= :previousStart AND create_time < :start) AS previous_total
            FROM (
                SELECT message_status,
                       CASE WHEN jsonb_typeof(blocks) = 'array' THEN jsonb_array_length(blocks) ELSE 0 END AS block_count,
                       CASE WHEN jsonb_typeof(blocks) = 'array'
                            THEN (SELECT count(*) FROM jsonb_array_elements(blocks) b WHERE b ->> 'kind' = 'tool')
                            ELSE 0 END AS tool_calls
                FROM t_agent_message
                WHERE deleted = 0 AND role = 'assistant' AND create_time >= :start AND create_time < :end
            ) reply
            """;

    /*
     * 同一批展开结果同时用于状态汇总、Top 工具、确认工具与调用趋势；未知 JSON/状态显式归入 other
     *
     * last_call_at 取的是所在回复的创建时间而不是块里的 at：at 是字符串且历史数据只有 HH:mm:ss，
     * 解析不出日期，两者相差的是一次回复内部的间隔，够支撑「最近一次调用」这种相对时间读数
     */
    static final String BLOCKS_SQL = """
            WITH blocks AS MATERIALIZED (
                SELECT m.create_time, b ->> 'kind' AS kind,
                       COALESCE(NULLIF(b ->> 'name', ''), 'unknown') AS name,
                       COALESCE(NULLIF(b ->> 'displayName', ''), NULLIF(b ->> 'name', ''), '未命名工具') AS display_name,
                       CASE WHEN b ->> 'status' IN ('done', 'failed', 'interrupted', 'approved', 'denied', 'pending', 'expired')
                            THEN b ->> 'status' ELSE 'other' END AS status,
                       b -> 'calls' AS calls
                FROM t_agent_message m
                CROSS JOIN LATERAL jsonb_array_elements(
                    CASE WHEN jsonb_typeof(m.blocks) = 'array' THEN m.blocks ELSE '[]'::jsonb END) b
                WHERE m.deleted = 0 AND m.role = 'assistant'
                  AND m.create_time >= :start AND m.create_time < :end
                  AND b ->> 'kind' IN ('tool', 'confirm')
            ), top_tools AS (
                SELECT name, min(display_name) AS display_name, count(*) AS count,
                       count(*) FILTER (WHERE status = 'done') AS done,
                       count(*) FILTER (WHERE status = 'failed') AS failed,
                       max(create_time) AS last_call_at
                FROM blocks WHERE kind = 'tool' GROUP BY name ORDER BY count DESC, name LIMIT 20
            ), confirm_tools AS (
                /* 展开的是卡里的调用，状态取自整卡：一张卡整体裁决，卡内每个调用共用同一个结果 */
                SELECT COALESCE(NULLIF(c ->> 'name', ''), 'unknown') AS name,
                       min(COALESCE(NULLIF(c ->> 'displayName', ''), NULLIF(c ->> 'name', ''), '未命名工具')) AS display_name,
                       count(*) AS count,
                       count(*) FILTER (WHERE status = 'approved') AS approved,
                       count(*) FILTER (WHERE status = 'denied') AS denied
                FROM blocks CROSS JOIN LATERAL jsonb_array_elements(
                    CASE WHEN jsonb_typeof(calls) = 'array' THEN calls ELSE '[]'::jsonb END) c
                WHERE kind = 'confirm' GROUP BY 1 ORDER BY count DESC, name LIMIT 20
            )
            SELECT 'status' AS category, kind, status, NULL::text AS name, NULL::text AS display_name,
                   NULL::timestamp AS bucket, count(*) AS count,
                   count(*) FILTER (WHERE kind = 'tool' AND name = 'search_knowledge') AS knowledge_calls,
                   0::bigint AS done, 0::bigint AS failed, 0::bigint AS approved, 0::bigint AS denied,
                   NULL::timestamp AS last_call_at
            FROM blocks GROUP BY kind, status
            UNION ALL
            SELECT 'tool', 'tool', NULL, name, display_name, NULL::timestamp, count, 0, done, failed, 0, 0, last_call_at
            FROM top_tools
            UNION ALL
            SELECT 'confirmTool', 'confirm', NULL, name, display_name, NULL::timestamp, count, 0, 0, 0,
                   approved, denied, NULL::timestamp
            FROM confirm_tools
            UNION ALL
            SELECT 'trend', 'tool', NULL, NULL, NULL, date_trunc(:granularity, create_time), count(*), 0, 0, 0, 0, 0,
                   NULL::timestamp
            FROM blocks WHERE kind = 'tool' GROUP BY 6
            """;

    /*
     * 绝对值与百分比共用 context_chars_before > 0 这一道过滤：分母人群不同，两个数就会互相矛盾
     *
     * compactions_with_chars 就是这道过滤下的事件数，「平均每次节省」只能拿它当分母，
     * 拿 compactions 会把没记字符数的那几次也摊进去
     */
    static final String MEMORY_SQL = """
            SELECT
                (SELECT count(*) FROM t_agent_context_compaction WHERE create_time >= :start AND create_time < :end) AS compactions,
                (SELECT count(*) FROM t_agent_context_compaction
                   WHERE create_time >= :start AND create_time < :end AND context_chars_before > 0) AS compactions_with_chars,
                (SELECT 100.0 * (1 - sum(context_chars_after)::numeric / NULLIF(sum(context_chars_before), 0))
                   FROM t_agent_context_compaction
                   WHERE create_time >= :start AND create_time < :end AND context_chars_before > 0) AS context_reduction_pct,
                (SELECT COALESCE(sum(context_chars_before), 0) FROM t_agent_context_compaction
                   WHERE create_time >= :start AND create_time < :end AND context_chars_before > 0) AS context_chars_before,
                (SELECT COALESCE(sum(context_chars_after), 0) FROM t_agent_context_compaction
                   WHERE create_time >= :start AND create_time < :end AND context_chars_before > 0) AS context_chars_after,
                count(*) FILTER (WHERE invalid_at IS NULL OR invalid_at >= :end) AS active_memories,
                count(*) FILTER (WHERE create_time >= :start) AS added_memories,
                count(*) FILTER (WHERE invalid_at >= :start AND invalid_at < :end) AS invalidated_memories
            FROM t_agent_memory WHERE create_time < :end
            """;

    /*
     * 带 _prev 后缀的三条是上周期同指标，桶键仍落在上周期区间内，由调用方按窗口长度整体前移后对齐
     *
     * 平移放在 Java 侧做，是为了不把时间间隔拼进 SQL
     */
    static final String TRENDS_SQL = """
            SELECT date_trunc(:granularity, create_time) AS bucket, 'messages' AS metric, count(*) AS value
            FROM t_agent_message WHERE deleted = 0 AND create_time >= :start AND create_time < :end GROUP BY bucket
            UNION ALL
            SELECT date_trunc(:granularity, create_time), 'activeusers', count(DISTINCT user_id)
            FROM t_agent_message WHERE deleted = 0 AND create_time >= :start AND create_time < :end GROUP BY 1
            UNION ALL
            SELECT date_trunc(:granularity, create_time), 'sessions', count(*)
            FROM t_agent_conversation WHERE deleted = 0 AND create_time >= :start AND create_time < :end GROUP BY 1
            UNION ALL
            SELECT date_trunc(:granularity, create_time), 'messages_prev', count(*)
            FROM t_agent_message WHERE deleted = 0 AND create_time >= :previousStart AND create_time < :start GROUP BY 1
            UNION ALL
            SELECT date_trunc(:granularity, create_time), 'activeusers_prev', count(DISTINCT user_id)
            FROM t_agent_message WHERE deleted = 0 AND create_time >= :previousStart AND create_time < :start GROUP BY 1
            UNION ALL
            SELECT date_trunc(:granularity, create_time), 'sessions_prev', count(*)
            FROM t_agent_conversation WHERE deleted = 0 AND create_time >= :previousStart AND create_time < :start GROUP BY 1
            UNION ALL
            SELECT date_trunc(:granularity, create_time),
                   CASE message_status WHEN 'NORMAL' THEN 'normal' WHEN 'INTERRUPTED' THEN 'interrupted'
                       WHEN 'AWAITING_CONFIRM' THEN 'awaitingConfirm' ELSE 'unknown' END, count(*)
            FROM t_agent_message WHERE deleted = 0 AND role = 'assistant'
                AND create_time >= :start AND create_time < :end GROUP BY 1, 2
            """;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public Rows read(AgentDashboardWindow window) {
        Map<String, Object> params = Map.of("start", window.start(), "end", window.end(),
                "previousStart", window.previousStart(), "granularity", window.granularity());
        return new Rows(jdbc.queryForMap(OVERVIEW_SQL, params), jdbc.queryForMap(REPLIES_SQL, params),
                jdbc.queryForList(BLOCKS_SQL, params), jdbc.queryForMap(MEMORY_SQL, params),
                jdbc.queryForList(TRENDS_SQL, params));
    }

    public record Rows(Map<String, Object> overview, Map<String, Object> replies, List<Map<String, Object>> blocks,
                       Map<String, Object> memory, List<Map<String, Object>> trends) {
    }
}
