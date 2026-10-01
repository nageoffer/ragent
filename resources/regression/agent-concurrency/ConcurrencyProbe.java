/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only evidence collection for live-service concurrency regression.
 * Each database snapshot is one SELECT, hence one MVCC snapshot and one owned connection.
 * Redis samples are atomic across all requested keys. This object holds no connections.
 */
final class ConcurrencyProbe {

    private static final List<String> AGENT_TABLES = List.of(
            "t_agent_conversation", "t_agent_message", "t_agent_state", "t_agent_memory",
            "t_agent_memory_extraction", "t_agent_context_compaction", "t_agent_memory_control");
    private static final List<String> BUSINESS_TABLES = List.of(
            "t_product", "t_product_sku", "t_order", "t_order_item", "t_logistics_trace",
            "t_after_sale", "t_cart", "t_coupon", "t_user_coupon", "t_ticket");
    private static final Set<String> TERMINAL_MESSAGE_STATUSES = Set.of("NORMAL", "INTERRUPTED", "CANCELLED");
    private static final String REDIS_READ_SCRIPT = """
            local result = {}
            for _, key in ipairs(KEYS) do
              local value = redis.call('GET', key)
              local hex = ''
              if value then
                hex = (string.gsub(value, '.', function(c) return string.format('%02x', string.byte(c)) end))
              end
              table.insert(result, {value and 1 or 0, redis.call('PTTL', key), hex})
            end
            return result
            """;

    private final InitializerConfig config;

    ConcurrencyProbe(InitializerConfig config) {
        this.config = config;
    }

    Map<String, Object> platformSnapshot(List<String> userIds, List<String> conversationIds,
                                         Map<String, String> markerByUser) throws SQLException, IOException {
        List<String> users = distinctIds(userIds);
        List<String> conversations = distinctIds(conversationIds);
        if (users.isEmpty()) {
            throw new IllegalArgumentException("平台探针必须限定本次测试用户，禁止无范围导出");
        }
        validateMarkers(users, markerByUser);
        Map<String, Object> stateSchema = stateSchema();
        boolean stateCompatible = Boolean.TRUE.equals(stateSchema.get("compatible"));
        boolean checkoutState = Boolean.TRUE.equals(stateSchema.get("checkoutCompatible"));
        boolean officialState = Boolean.TRUE.equals(stateSchema.get("officialCompositeSession"));
        String userFilter = in("t.user_id", users);
        List<String> ctes = new ArrayList<>();
        List<String> tableExpressions = new ArrayList<>();
        for (String table : AGENT_TABLES) {
            if (table.equals("t_agent_state") && !checkoutState) {
                if (officialState) {
                    // agentscope-extensions-postgresql 2.0.2 slotId(user, session) is normalizeUser(user) + ':' + session.
                    // Verified from the actual loaded jar's slotId bytecode and concat bootstrap constant #591.
                    // Our service IDs contain no colon, so decoding the first separator preserves their identity.
                    String owner = "split_part(t.session_id, ':', 1)";
                    String session = "substring(t.session_id FROM position(':' IN t.session_id) + 1)";
                    ctes.add("t_agent_state_scoped AS (SELECT " + owner + " AS user_id, " + session + " AS session_id,"
                            + " t.session_id AS storage_session_id, t.state_key, t.item_index,"
                            + " CASE WHEN right(t.state_key, 6) = ':_hash' THEN to_jsonb(t.state_data)"
                            + " ELSE t.state_data::jsonb END AS payload, t.created_at AS create_time, t.updated_at AS update_time"
                            + " FROM t_agent_state t WHERE " + in(owner, users) + " OR " + in(session, conversations) + ")");
                } else {
                    ctes.add("t_agent_state_scoped AS (SELECT NULL::text AS user_id, NULL::text AS session_id,"
                            + " NULL::text AS state_key, NULL::jsonb AS payload WHERE FALSE)");
                }
                tableExpressions.add("'t_agent_state', (SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY t.session_id, t.state_key),"
                        + " '[]'::jsonb) FROM t_agent_state_scoped t)");
                continue;
            }
            String sessionColumn = switch (table) {
                case "t_agent_state" -> "session_id";
                case "t_agent_conversation", "t_agent_message", "t_agent_memory_extraction",
                        "t_agent_context_compaction" -> "conversation_id";
                default -> null;
            };
            String filter = userFilter;
            if (sessionColumn != null && !conversations.isEmpty()) {
                filter += " OR " + in("t." + sessionColumn, conversations);
            }
            ctes.add(table + "_scoped AS (SELECT t.* FROM " + table + " t WHERE " + filter + ")");
            String order = table.equals("t_agent_state") ? "t.user_id, t.session_id, t.state_key"
                    : table.equals("t_agent_memory_control") ? "t.user_id" : "t.id";
            tableExpressions.add(JdbcClient.literal(table) + ", (SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY "
                    + order + "), '[]'::jsonb) FROM " + table + "_scoped t)");
        }
        String sql = "WITH " + String.join(", ", ctes)
                + " SELECT jsonb_build_object('database', current_database(), 'sampledAt', clock_timestamp(),"
                + " 'tables', jsonb_build_object(" + String.join(", ", tableExpressions) + "),"
                + " 'relationshipViolations', (SELECT COALESCE(jsonb_agg(v), '[]'::jsonb) FROM ("
                + relationshipSql(stateCompatible) + ") violations(v)),"
                + " 'testUserAuditCounts', (SELECT COALESCE(jsonb_agg(to_jsonb(a)), '[]'::jsonb) FROM"
                + " (SELECT operator_id, biz_type, operation_type, count(*) AS count FROM t_biz_change_log"
                + " WHERE " + in("operator_id", users)
                + " GROUP BY operator_id, biz_type, operation_type ORDER BY operator_id, biz_type, operation_type) a))::text";
        Map<String, Object> snapshot = queryObject("database", sql);
        Map<String, Object> tables = SimpleJson.object(snapshot.get("tables"));
        List<Object> violations = new ArrayList<>(SimpleJson.array(snapshot.get("relationshipViolations")));
        violations.addAll(markerViolations(tables, markerByUser));
        if (!stateCompatible) {
            violations.add(Map.of("kind", "incompatibleStateSchema", "table", "t_agent_state",
                    "detail", "Live state table matches neither the checkout's PgAgentStateStore nor the verified official "
                            + "PostgresAgentStateStore 2.0.2 schema. State ownership cannot be verified; no schema was changed."));
        }
        List<Object> nonterminal = new ArrayList<>();
        List<Object> userSummary = new ArrayList<>();
        for (String userId : users) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("userId", userId);
            for (String table : AGENT_TABLES) {
                summary.put(table + "Count", rows(tables, table).stream()
                        .filter(row -> userId.equals(text(row, "user_id"))).count());
            }
            List<Map<String, Object>> messages = rows(tables, "t_agent_message").stream()
                    .filter(row -> userId.equals(text(row, "user_id"))).toList();
            summary.put("userMessages", messages.stream().filter(row -> "user".equals(text(row, "role"))).count());
            summary.put("assistantMessages", messages.stream().filter(row -> "assistant".equals(text(row, "role"))).count());
            Map<String, Long> statuses = new LinkedHashMap<>();
            messages.stream().filter(row -> "assistant".equals(text(row, "role")))
                    .forEach(row -> statuses.merge(text(row, "message_status"), 1L, Long::sum));
            summary.put("assistantStatuses", statuses);
            summary.put("processingExtractions", rows(tables, "t_agent_memory_extraction").stream()
                    .filter(row -> userId.equals(text(row, "user_id")) && "PROCESSING".equals(text(row, "status"))).count());
            userSummary.add(summary);
        }
        for (Map<String, Object> row : rows(tables, "t_agent_message")) {
            if (!TERMINAL_MESSAGE_STATUSES.contains(text(row, "message_status"))) {
                Map<String, Object> violation = finding("nonterminalMessage", "t_agent_message", row);
                violation.put("status", text(row, "message_status"));
                nonterminal.add(violation);
            }
        }
        // The runner retries settling before judging these: PROCESSING extraction is legal during a run.
        snapshot.put("nonterminalMessages", nonterminal);
        snapshot.put("violations", violations);
        snapshot.put("summary", userSummary);
        snapshot.put("requestedUserIds", users);
        snapshot.put("requestedConversationIds", conversations);
        snapshot.put("stateSchema", stateSchema);
        snapshot.put("limitations", checkoutState ? List.of() : List.of(
                "Live state storage differs from this checkout. Conclusions apply to the running service; "
                        + "the current custom PgAgentStateStore has not been exercised by this run."));
        snapshot.put("readOnly", true);
        return snapshot;
    }

    /** One Redis command gives actual simultaneous ownership, rather than sequential GET observations. */
    Map<String, Object> redisSnapshot(List<String> userIds, List<String> taskIds) throws IOException {
        List<String> users = distinctIds(userIds);
        List<String> tasks = distinctIds(taskIds);
        List<String> keys = new ArrayList<>();
        for (String user : users) keys.add("ragent:agent:running:" + user);
        for (String task : tasks) {
            keys.add("ragent:stream:owner:" + task);
            keys.add("ragent:stream:cancel:" + task);
        }
        List<String> command = new ArrayList<>(List.of("EVAL", REDIS_READ_SCRIPT, String.valueOf(keys.size())));
        command.addAll(keys);
        List<?> values;
        String startedAt = Instant.now().toString();
        try (RedisRespClient redis = new RedisRespClient(config)) {
            Object response = redis.command(command.toArray(String[]::new));
            if (!(response instanceof List<?> list) || list.size() != keys.size()) {
                throw new IOException("Redis 并发采样返回格式异常");
            }
            values = list;
        }
        List<Object> userRows = new ArrayList<>();
        int activeCount = 0;
        for (int i = 0; i < users.size(); i++) {
            Map<String, Object> state = redisState(keys.get(i), values.get(i));
            state.put("userId", users.get(i));
            if (Boolean.TRUE.equals(state.get("exists"))) activeCount++;
            userRows.add(state);
        }
        List<Object> taskRows = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            int offset = users.size() + 2 * i;
            taskRows.add(Map.of("taskId", tasks.get(i),
                    "owner", redisState(keys.get(offset), values.get(offset)),
                    "cancel", redisState(keys.get(offset + 1), values.get(offset + 1))));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sampledAt", startedAt);
        result.put("completedAt", Instant.now().toString());
        result.put("atomic", true);
        result.put("activeUserCount", activeCount);
        result.put("users", userRows);
        result.put("tasks", taskRows);
        result.put("valueEncoding", "hex of raw Redisson codec bytes; never interpreted as plain UTF-8");
        return result;
    }

    Map<String, Object> businessSnapshot() throws SQLException, IOException {
        return businessSnapshot(List.of());
    }

    /** Business rows never leave PostgreSQL; only table fingerprints and per-test-user counts do. */
    Map<String, Object> businessSnapshot(List<String> userIds) throws SQLException, IOException {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sampledAt", Instant.now().toString());
        snapshot.put("readOnly", true);
        snapshot.put("comparisonCaveat", "A changed whole-table fingerprint does not identify the writer; "
                + "scheduled tasks or other users may cause it. Unchanged fingerprints show no net row change, not absence of transient writes.");
        if (!config.get("biz-database.jdbc-url", "").isBlank()) {
            List<String> aggregates = new ArrayList<>();
            for (String table : BUSINESS_TABLES) {
                aggregates.add(JdbcClient.literal(table) + ", " + fingerprint(table));
            }
            List<String> userCounts = new ArrayList<>();
            for (String user : distinctIds(userIds)) {
                String owner = JdbcClient.literal(user);
                userCounts.add("jsonb_build_object('userId', " + owner
                        + ", 'orders', (SELECT count(*) FROM t_order WHERE user_id = " + owner + ")"
                        + ", 'orderItems', (SELECT count(*) FROM t_order_item i JOIN t_order o ON o.order_no = i.order_no WHERE o.user_id = " + owner + ")"
                        + ", 'afterSales', (SELECT count(*) FROM t_after_sale a JOIN t_order o ON o.order_no = a.order_no WHERE o.user_id = " + owner + ")"
                        + ", 'cartItems', (SELECT count(*) FROM t_cart WHERE user_id = " + owner + ")"
                        + ", 'userCoupons', (SELECT count(*) FROM t_user_coupon WHERE user_id = " + owner + ")"
                        + ", 'tickets', (SELECT count(*) FROM t_ticket WHERE user_id = " + owner + "))");
            }
            snapshot.put("business", queryObject("biz-database", "SELECT jsonb_build_object('database', current_database(),"
                    + " 'sampledAt', clock_timestamp(), 'tables', jsonb_build_object(" + String.join(",", aggregates) + "),"
                    + " 'testUserCounts', jsonb_build_array(" + String.join(",", userCounts) + "))::text"));
        } else {
            snapshot.put("business", Map.of("status", "NOT_CONFIGURED"));
        }
        if ("pg".equals(config.get("execution.expected-vector-type", ""))) {
            snapshot.put("pgvector", queryObject("database", "SELECT jsonb_build_object('database', current_database(),"
                    + " 'sampledAt', clock_timestamp(), 'extensionVersion', (SELECT extversion FROM pg_extension WHERE extname = 'vector'),"
                    + " 't_knowledge_vector', " + fingerprint("t_knowledge_vector") + ")::text"));
        } else {
            snapshot.put("pgvector", Map.of("status", "NOT_CONFIGURED", "configuredVectorType",
                    config.get("execution.expected-vector-type", "")));
        }
        return snapshot;
    }

    Map<String, Object> coverage() throws IOException {
        Map<String, String> settings = selectedYamlSettings();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configurationSource", config.get("application.config-resolved", "regression.properties"));
        result.put("configurationIsRuntimeProof", false);
        result.put("engine", config.get("execution.engine-type", "unknown"));
        result.put("platformDatabase", config.get("database.name", "unknown"));
        result.put("businessDatabase", config.get("biz-database.name", "not-configured"));
        result.put("redisDatabase", config.getInt("redis.database", 0));
        result.put("vectorType", config.get("execution.expected-vector-type", "unknown"));
        result.put("storageType", config.get("execution.expected-storage-type", "unknown"));
        result.put("selectedSettings", settings);
        result.put("covered", List.of("7 Agent PostgreSQL tables scoped to test users/conversations",
                "cross-owner relationship joins and foreign marker detection within exported scope",
                "atomic Redis running/owner/cancel key existence and TTL",
                "MCP business tables: row counts and full-row MD5 fingerprints; test-user ownership counts",
                "pgvector table: full-row fingerprint when configured as pg"));
        result.put("notCovered", List.of("Unrelated users' conversation contents are not exported or searched",
                "S3/object storage is not audited; these chat-only cases do not upload/delete documents",
                "Alternate vector, keyword and graph backends are not probed",
                "External model provider state and in-process AgentScope caches/callbacks are not introspected",
                "Trace storage is not queried; selectedSettings records declared trace enablement",
                "Fingerprint equality does not exclude writes that were reverted between snapshots"));
        return result;
    }

    static List<Object> markerViolations(Map<String, Object> tables, Map<String, String> markerByUser) {
        List<Object> violations = new ArrayList<>();
        for (Map.Entry<String, Object> table : tables.entrySet()) {
            for (Object value : SimpleJson.array(table.getValue())) {
                Map<String, Object> row = SimpleJson.object(value);
                String owner = text(row, "user_id");
                if (owner.isBlank()) continue; // Unsupported schemas are reported separately, not assigned an invented owner.
                String serialized = SimpleJson.stringify(row);
                for (Map.Entry<String, String> marker : markerByUser.entrySet()) {
                    if (!marker.getKey().equals(owner) && !marker.getValue().isBlank()
                            && serialized.contains(marker.getValue())) {
                        Map<String, Object> violation = finding("foreignMarker", table.getKey(), row);
                        violation.put("markerOwnerUserId", marker.getKey());
                        violation.put("marker", marker.getValue());
                        violations.add(violation);
                    }
                }
            }
        }
        return violations;
    }

    private Map<String, Object> queryObject(String prefix, String sql) throws SQLException, IOException {
        try (JdbcClient jdbc = new JdbcClient(config, prefix)) {
            List<List<String>> result = jdbc.queryRows(sql);
            if (result.size() != 1 || result.get(0).size() != 1) {
                throw new SQLException("并发探针预期返回一行 JSON");
            }
            return SimpleJson.object(SimpleJson.parse(result.get(0).get(0)));
        }
    }

    private Map<String, Object> stateSchema() throws SQLException, IOException {
        Map<String, Object> schema = queryObject("database", "SELECT jsonb_build_object('columns',"
                + " COALESCE(jsonb_agg(column_name ORDER BY ordinal_position), '[]'::jsonb))::text"
                + " FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='t_agent_state'");
        List<Object> columns = SimpleJson.array(schema.get("columns"));
        boolean checkout = columns.containsAll(List.of("user_id", "session_id", "state_key", "payload"));
        boolean official = !columns.contains("user_id") && columns.containsAll(List.of(
                "session_id", "state_key", "state_data", "item_index", "created_at", "updated_at"));
        schema.put("checkoutCompatible", checkout);
        schema.put("officialCompositeSession", official);
        schema.put("compatible", checkout || official);
        schema.put("layout", checkout ? "custom_user_session_payload" : official ? "official_postgresql_2_0_2_composite_session" : "unsupported");
        if (official) {
            schema.put("ownerEncoding", "normalizeUser(userId) + ':' + sessionId; decoded at first colon");
            schema.put("encodingEvidence", "agentscope-extensions-postgresql-2.0.2.jar: "
                    + "io.agentscope.extensions.postgresql.state.PostgresAgentStateStore.slotId bytecode; "
                    + "StringConcatFactory bootstrap #28 has recipe user:session");
        }
        return schema;
    }

    private static String relationshipSql(boolean includeState) {
        List<String> checks = new ArrayList<>();
        for (String table : List.of("t_agent_message", "t_agent_state", "t_agent_memory_extraction", "t_agent_context_compaction")) {
            if (table.equals("t_agent_state") && !includeState) continue;
            String conv = table.equals("t_agent_state") ? "session_id" : "conversation_id";
            String identity = table.equals("t_agent_state") ? "t.session_id || ':' || t.state_key" : "t.id";
            checks.add("SELECT jsonb_build_object('kind','missingOwnedConversation','table'," + JdbcClient.literal(table)
                    + ",'id'," + identity + ",'userId',t.user_id,'conversationId',t." + conv + ") FROM " + table + "_scoped t"
                    + " WHERE NOT EXISTS (SELECT 1 FROM t_agent_conversation c WHERE c.conversation_id = t." + conv
                    + " AND c.user_id = t.user_id)");
        }
        checks.add("SELECT jsonb_build_object('kind','invalidReplyOwner','table','t_agent_message','id',t.id,'userId',t.user_id,"
                + "'replyTo',t.reply_to_message_id) FROM t_agent_message_scoped t LEFT JOIN t_agent_message p ON p.id=t.reply_to_message_id"
                + " WHERE t.reply_to_message_id IS NOT NULL AND (p.id IS NULL OR p.user_id<>t.user_id OR p.conversation_id<>t.conversation_id OR p.role<>'user')");
        for (String endpoint : List.of("from_message_id", "to_message_id")) {
            checks.add("SELECT jsonb_build_object('kind','invalidExtractionEndpointOwner','table','t_agent_memory_extraction',"
                    + "'id',t.id,'userId',t.user_id,'endpoint'," + JdbcClient.literal(endpoint) + ",'messageId',t." + endpoint + ")"
                    + " FROM t_agent_memory_extraction_scoped t LEFT JOIN t_agent_message m ON m.id=t." + endpoint
                    + " WHERE m.id IS NULL OR m.user_id<>t.user_id OR m.conversation_id<>t.conversation_id OR m.role<>'user'");
        }
        if (includeState) {
            for (String identityField : List.of("user_id", "session_id")) {
                String field = JdbcClient.literal(identityField);
                checks.add("SELECT jsonb_build_object('kind','invalidStatePayloadIdentity','table','t_agent_state',"
                        + "'id',t.session_id || ':' || t.state_key,'userId',t.user_id,'conversationId',t.session_id,"
                        + "'field'," + field + ",'payloadValue',t.payload->" + field + ",'expected',t." + identityField + ")"
                        + " FROM t_agent_state_scoped t WHERE jsonb_typeof(t.payload)='object' AND t.payload ? " + field
                        + " AND (t.payload->>" + field + ") IS DISTINCT FROM t." + identityField);
            }
        }
        checks.add("SELECT jsonb_build_object('kind','invalidMemorySuccessorOwner','table','t_agent_memory','id',t.id,'userId',t.user_id,"
                + "'supersededBy',t.superseded_by) FROM t_agent_memory_scoped t LEFT JOIN t_agent_memory m ON m.id=t.superseded_by"
                + " WHERE t.superseded_by IS NOT NULL AND (m.id IS NULL OR m.user_id<>t.user_id)");
        checks.add("SELECT jsonb_build_object('kind','conversationSharedByUsers','table','t_agent_conversation',"
                + "'conversationId',t.conversation_id,'ownerCount',count(DISTINCT c.user_id))"
                + " FROM t_agent_conversation_scoped t JOIN t_agent_conversation c ON c.conversation_id=t.conversation_id"
                + " GROUP BY t.conversation_id HAVING count(DISTINCT c.user_id)>1");
        return String.join(" UNION ALL ", checks);
    }

    private static String fingerprint(String table) {
        return "(SELECT jsonb_build_object('rows', count(*), 'md5', md5(COALESCE(string_agg(md5(to_jsonb(t)::text), '' ORDER BY t.id), ''))) FROM "
                + table + " t)";
    }

    private static Map<String, Object> redisState(String key, Object value) throws IOException {
        if (!(value instanceof List<?> cells) || cells.size() != 3) {
            throw new IOException("Redis key 状态格式异常");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("exists", ((Number) cells.get(0)).longValue() == 1);
        result.put("pttlMillis", ((Number) cells.get(1)).longValue());
        result.put("valueHex", String.valueOf(cells.get(2)));
        return result;
    }

    private static List<Map<String, Object>> rows(Map<String, Object> tables, String table) {
        return SimpleJson.array(tables.get(table)).stream().map(SimpleJson::object).toList();
    }

    private static Map<String, Object> finding(String kind, String table, Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", kind);
        result.put("table", table);
        result.put("id", text(row, "id"));
        result.put("userId", text(row, "user_id"));
        result.put("conversationId", row.containsKey("session_id") ? text(row, "session_id") : text(row, "conversation_id"));
        return result;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String in(String column, List<String> values) {
        return values.isEmpty() ? "FALSE" : column + " IN (" + values.stream().map(JdbcClient::literal).collect(Collectors.joining(",")) + ")";
    }

    private static List<String> distinctIds(List<String> ids) {
        if (ids == null) return List.of();
        return ids.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    private static void validateMarkers(List<String> userIds, Map<String, String> markers) {
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, String> marker : markers.entrySet()) {
            if (!userIds.contains(marker.getKey()) || marker.getValue() == null || marker.getValue().length() < 12
                    || !seen.add(marker.getValue())) {
                throw new IllegalArgumentException("marker 必须是本次用户独有、长度至少 12 的随机字符串");
            }
        }
    }

    /** Whitelist configuration evidence; never serialize the config snapshot or secrets. */
    private Map<String, String> selectedYamlSettings() throws IOException {
        String configuredPath = config.get("application.config-resolved", "");
        if (configuredPath.isBlank()) return Map.of();
        Set<String> selected = Set.of("agent.trace.enabled", "rag.keyword.type", "rag.graph.type",
                "rag.vector.type", "rag.storage.type", "ragent.engine.type", "agent.memory.long-term-enabled",
                "agent.memory.summary-enabled", "agent.memory.context-window-chars");
        Map<String, String> result = new LinkedHashMap<>();
        List<Integer> indents = new ArrayList<>();
        List<String> segments = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(configuredPath), StandardCharsets.UTF_8)) {
            String stripped = line.stripLeading();
            if (stripped.isBlank() || stripped.startsWith("#") || stripped.startsWith("-")) continue;
            int indent = line.length() - stripped.length();
            int colon = stripped.indexOf(':');
            if (colon <= 0) continue;
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                segments.remove(segments.size() - 1);
            }
            String key = stripped.substring(0, colon).trim();
            String value = stripped.substring(colon + 1).split("\\s+#", 2)[0].trim();
            String fullKey = (segments.isEmpty() ? "" : String.join(".", segments) + ".") + key;
            if (selected.contains(fullKey)) result.put(fullKey, InitializerConfig.expandPlaceholders(value));
            if (value.isEmpty()) {
                indents.add(indent);
                segments.add(key);
            }
        }
        return result;
    }
}
