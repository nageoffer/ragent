/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0. */
package com.nageoffer.ai.ragent.initializer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按消息 ID 计量每轮新增用量；摘要事件来自追加审计表，避免被下一代摘要覆盖 */
record EvaluationState(Map<String, Object> payload, List<Map<String, Object>> compactions,
                       boolean auditAvailable) {
    static final String SUMMARY = "__compaction_summary__";
    static final String OMITTED = "[历史工具结果已省略";

    static EvaluationState empty(boolean auditAvailable) {
        return new EvaluationState(Map.of(), List.of(), auditAvailable);
    }

    static EvaluationState read(JdbcClient jdbc, String user, String session, boolean audit) throws Exception {
        if (session == null) return empty(audit);
        String owner = "user_id = " + JdbcClient.literal(user);
        List<List<String>> rows = jdbc.queryRows("SELECT payload::text FROM t_agent_state WHERE " + owner
                + " AND session_id = " + JdbcClient.literal(session)
                + " AND jsonb_typeof(payload->'context') = 'array'");
        if (rows.size() > 1) throw new IllegalStateException("会话存在多份 context，无法确定计量对象");
        List<Map<String, Object>> events = new ArrayList<>();
        if (audit) {
            for (List<String> row : jdbc.queryRows("SELECT to_jsonb(c)::text FROM t_agent_context_compaction c WHERE "
                    + owner + " AND conversation_id = " + JdbcClient.literal(session) + " ORDER BY create_time, id")) {
                Map<String, Object> raw = SimpleJson.object(SimpleJson.parse(row.get(0)));
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("id", raw.get("id"));
                event.put("generation", raw.get("generation"));
                event.put("materialChars", raw.get("material_chars"));
                event.put("summaryChars", raw.get("summary_chars"));
                event.put("contextCharsBefore", raw.get("context_chars_before"));
                event.put("contextCharsAfter", raw.get("context_chars_after"));
                event.put("summary", raw.get("summary"));
                events.add(event);
            }
        }
        return new EvaluationState(rows.isEmpty() ? Map.of() : SimpleJson.object(SimpleJson.parse(rows.get(0).get(0))),
                events, audit);
    }

    List<Map<String, Object>> messages() { return objects(payload.get("context")); }

    Set<String> usageIds() {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> msg : messages()) {
            if ("assistant".equalsIgnoreCase(string(msg, "role")) && msg.get("usage") instanceof Map<?, ?>) {
                String id = string(msg, "id");
                if (id.isBlank()) throw new IllegalStateException("assistant usage 缺少 Msg.id，不能可靠去重");
                if (!ids.add(id)) throw new IllegalStateException("assistant usage 的 Msg.id 重复，不能可靠去重: " + id);
            }
        }
        return ids;
    }

    boolean containsQuestion(String question) {
        return messages().stream().anyMatch(msg -> "user".equalsIgnoreCase(string(msg, "role"))
                && objects(msg.get("content")).stream().anyMatch(b -> question.equals(string(b, "text"))));
    }

    Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Long> byType = new LinkedHashMap<>();
        long chars = 0;
        int omitted = 0, summaries = 0;
        for (Map<String, Object> msg : messages()) {
            if (SUMMARY.equals(string(msg, "name"))) summaries++;
            for (Map<String, Object> b : objects(msg.get("content"))) {
                long length = blockChars(b);
                chars += length;
                byType.merge(string(b, "type"), length, Long::sum);
                if (evicted(b)) omitted++;
            }
        }
        result.put("contextChars", chars);
        result.put("messageCount", messages().size());
        result.put("charsByType", byType);
        result.put("evictedBlocksPresent", omitted);
        result.put("summariesPresent", summaries);
        result.put("auditAvailable", auditAvailable);
        return result;
    }

    List<Map<String, Object>> usagesSince(EvaluationState before, String model) {
        Set<String> old = before.usageIds();
        usageIds(); // 同时校验当前快照，不能把缺失或重复 ID 当作不同调用
        List<Map<String, Object>> usages = new ArrayList<>();
        for (Map<String, Object> msg : messages()) {
            if (!"assistant".equalsIgnoreCase(string(msg, "role")) || !(msg.get("usage") instanceof Map<?, ?>)
                    || old.contains(string(msg, "id"))) continue;
            Map<String, Object> raw = SimpleJson.object(msg.get("usage"));
            Long input = number(raw.get("inputTokens"));
            Long cached = number(raw.get("cachedTokens"));
            Long reportedCached = cached;
            Long output = number(raw.get("outputTokens"));
            if (input != null && input < 0 || cached != null && cached < 0 || output != null && output < 0
                    || input != null && cached != null && cached > input) {
                throw new IllegalStateException("usage token 数不能为负，且缓存数不得超过输入数");
            }
            // SDK 会把供应商没有返回缓存字段的情况填成 0；state 无法区分真零与缺失
            if (cached != null && cached == 0) cached = null;
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("id", string(msg, "id"));
            usage.put("source", "state");
            usage.put("route", "agent");
            usage.put("model", model);
            usage.put("inputTokens", input);
            usage.put("cachedTokens", cached);
            usage.put("reportedCachedTokens", reportedCached);
            usage.put("uncachedInputTokens", input == null || cached == null ? null : input - cached);
            usage.put("outputTokens", output);
            usage.put("usageAvailable", input != null && output != null);
            usage.put("cacheKnown", input != null && cached != null);
            usage.put("durationMillis", raw.get("time") instanceof Number n ? n.doubleValue() * 1000 : null);
            usage.put("status", "OK");
            usages.add(usage);
        }
        return usages;
    }

    int newSummaryVersions(EvaluationState before) {
        Set<String> old = new HashSet<>();
        for (Map<String, Object> msg : before.messages()) if (SUMMARY.equals(string(msg, "name"))) old.add(string(msg, "id"));
        return (int) messages().stream().filter(msg -> SUMMARY.equals(string(msg, "name"))
                && !old.contains(string(msg, "id"))).count();
    }

    List<Map<String, Object>> compactionsSince(EvaluationState before) {
        Set<String> old = new HashSet<>();
        for (Map<String, Object> event : before.compactions) old.add(string(event, "id"));
        return compactions.stream().filter(e -> !old.contains(string(e, "id"))).toList();
    }

    Map<String, Object> trimSince(EvaluationState before) {
        Map<String, Map<String, Object>> old = before.toolResults();
        int blocks = 0;
        long reclaimed = 0;
        boolean complete = newSummaryVersions(before) == 0 && compactionsSince(before).isEmpty();
        for (Map<String, Object> b : toolResults().values()) {
            Map<String, Object> previous = old.get(string(b, "id"));
            if (!evicted(b) || previous != null && evicted(previous)) continue;
            blocks++;
            if (previous == null) complete = false;
            else reclaimed += blockChars(previous) - blockChars(b);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("newBlocks", blocks);
        result.put("reclaimedChars", complete ? reclaimed : null);
        result.put("observedReclaimedChars", reclaimed);
        result.put("complete", complete);
        result.put("source", "state-diff");
        return result;
    }

    private Map<String, Map<String, Object>> toolResults() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> msg : messages()) for (Map<String, Object> b : objects(msg.get("content"))) {
            if ("tool_result".equals(string(b, "type"))) {
                String id = string(b, "id");
                if (id.isBlank() || result.putIfAbsent(id, b) != null) {
                    throw new IllegalStateException("tool_result 的 id 缺失或重复，不能可靠计量裁剪");
                }
            }
        }
        return result;
    }

    static long blockChars(Map<String, Object> b) {
        return switch (string(b, "type")) {
            case "text" -> string(b, "text").length();
            case "thinking" -> string(b, "thinking").length();
            case "tool_use" -> string(b, "name").length() + (b.get("input") == null ? 0 : b.get("input").toString().length());
            case "tool_result" -> objects(b.get("output")).stream().mapToLong(EvaluationState::blockChars).sum();
            default -> 0;
        };
    }

    private static boolean evicted(Map<String, Object> b) {
        return "tool_result".equals(string(b, "type")) && objects(b.get("output")).stream()
                .anyMatch(o -> string(o, "text").startsWith(OMITTED));
    }

    static String string(Map<String, Object> m, String key) {
        Object value = m.get(key);
        return value == null ? "" : value.toString();
    }

    static Long number(Object value) { return value instanceof Number n ? n.longValue() : null; }

    static List<Map<String, Object>> objects(Object value) {
        if (!(value instanceof List<?> items)) return List.of();
        return items.stream().filter(v -> v instanceof Map<?, ?>).map(SimpleJson::object).toList();
    }
}
