/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 摘要剧本的证据导出，只读库，只导出白名单字段
 */
final class MemoryAuditSummary {
    private static final String SUMMARY_NAME = "__compaction_summary__";

    static void export(JdbcClient jdbc, String userId, String conversationId, Path out, String identity) throws Exception {
        String uid = JdbcClient.literal(userId);
        String cid = JdbcClient.literal(conversationId);
        var rows = jdbc.queryRows("SELECT payload::text, update_time::text FROM t_agent_state WHERE user_id = "
                + uid + " AND session_id = " + cid + " AND jsonb_exists(payload, 'context')"
                + " ORDER BY update_time DESC LIMIT 1");
        Map<String, Object> state = rows.isEmpty() ? null : SimpleJson.object(SimpleJson.parse(rows.get(0).get(0)));
        var memories = jdbc.queryRows("SELECT id, content, source_type, create_time::text FROM t_agent_memory"
                + " WHERE user_id = " + uid + " AND invalid_at IS NULL ORDER BY create_time,id");
        var compactions = jdbc.queryRows("SELECT generation::text, summary_chars::text, context_chars_before::text,"
                + " context_chars_after::text, summary, create_time::text FROM t_agent_context_compaction"
                + " WHERE user_id = " + uid + " AND conversation_id = " + cid + " ORDER BY create_time,id");
        List<Map<String, Object>> turns = new ArrayList<>();
        Path log = out.resolve(identity + "-turns.jsonl");
        if (Files.exists(log)) {
            for (String line : Files.readAllLines(log)) {
                if (!line.isBlank()) turns.add(SimpleJson.object(SimpleJson.parse(line)));
            }
        }
        Map<String, Object> evidence = build(userId, conversationId, state, turns, memories, compactions);
        evidence.put("source", "Live read-only export; sequential database reads, not an atomic snapshot.");
        evidence.put("exportedAt", Instant.now().toString());
        evidence.put("stateUpdatedAt", rows.isEmpty() ? null : rows.get(0).get(1));
        evidence.put("turnLog", log.getFileName().toString());
        Path target = out.resolve(identity + "-context-evidence.json");
        Files.writeString(target, SimpleJson.stringify(evidence) + "\n");
        System.out.println("Summary evidence: " + target);
    }

    // 也用于从之前抓下来的本地状态重新生成可公开的导出
    static Map<String, Object> build(String userId, String conversationId, Map<String, Object> state,
                                      List<Map<String, Object>> allTurns, List<List<String>> memories,
                                      List<List<String>> compactions) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("userId", userId);
        result.put("conversationId", conversationId);
        result.put("scope", "Text and tool names only. Exact source-text presence is not a semantic judgment; "
                + "this export does not establish a business PASS or cover overlong-summary handling.");
        boolean statePresent = state != null && state.get("context") instanceof List<?>;
        result.put("statePresent", statePresent);
        List<Object> context = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        List<String> retained = new ArrayList<>();
        if (statePresent) {
            int index = 0;
            for (Object raw : SimpleJson.array(state.get("context"))) {
                Map<String, Object> message = SimpleJson.object(raw);
                index++;
                String role = text(message.get("role"));
                boolean summary = SUMMARY_NAME.equals(message.get("name"));
                List<String> texts = new ArrayList<>();
                List<String> toolNames = new ArrayList<>();
                for (Object value : list(message.get("content"))) {
                    Map<String, Object> block = SimpleJson.object(value);
                    String type = text(block.get("type"));
                    if ("text".equals(type) && (summary || "USER".equals(role) || "ASSISTANT".equals(role))) {
                        String valueText = text(block.get("text"));
                        texts.add(valueText);
                        (summary ? summaries : retained).add(valueText);
                    } else if ("tool_use".equals(type) || "tool_result".equals(type)) {
                        String name = text(block.get("name"));
                        if (!name.isBlank()) toolNames.add(name);
                    }
                }
                if (!texts.isEmpty() || !toolNames.isEmpty()) {
                    Map<String, Object> clean = new LinkedHashMap<>();
                    clean.put("ordinal", index);
                    clean.put("role", role);
                    clean.put("summary", summary);
                    clean.put("textBlocks", texts);
                    clean.put("toolNames", toolNames);
                    context.add(clean);
                }
            }
        }
        result.put("summaryObservation", !statePresent ? "UNKNOWN" : summaries.isEmpty() ? "UNCOVERED" : "OBSERVED");
        result.put("context", context);
        result.put("summaryHeadings", summaries.stream().flatMap(s -> s.lines()).filter(s -> s.startsWith("## ")).toList());
        result.put("retainedNonSummaryTextBlocks", retained.size());
        result.put("activeLongTermMemoryColumns", List.of("id", "content", "sourceType", "createdAt"));
        result.put("activeLongTermMemoriesAtExport", memories);
        result.put("compactionColumns", List.of("generation", "summaryChars", "contextCharsBefore", "contextCharsAfter", "summary", "createdAt"));
        result.put("compactions", compactions);

        // 剧本前两轮固定是种子和起草请求，只认本用户本会话的日志，不借别的会话
        List<Map<String, Object>> turns = allTurns.stream().filter(t -> conversationId.equals(t.get("conversationId")))
                .filter(t -> userId.equals(object(t.get("before")).get("userId"))).toList();
        result.put("matchingLoggedTurns", turns.size());
        result.put("sourceTurnConvention", "First two logged turns of this user/session are the supplied script's seed and draft.");
        Map<String, Object> sourceChecks = new LinkedHashMap<>();
        String retainedText = String.join("\n", retained);
        for (int i = 0; i < 2; i++) {
            Map<String, Object> turn = turns.size() > i ? turns.get(i) : Map.of();
            boolean complete = "COMPLETED".equals(turn.get("status"));
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("question", text(turn.get("question")));
            source.put("answer", text(turn.get("answer")));
            source.put("questionInRetainedNonSummaryText", presence(retainedText, turn.get("question"), statePresent && complete));
            source.put("answerInRetainedNonSummaryText", presence(retainedText, turn.get("answer"), statePresent && complete));
            sourceChecks.put(i == 0 ? "seed" : "draft", source);
        }
        result.put("sourceChecks", sourceChecks);
        Map<String, Object> last = turns.isEmpty() ? Map.of() : turns.get(turns.size() - 1);
        Map<String, Object> lastEvidence = new LinkedHashMap<>();
        for (String key : List.of("question", "answer", "status", "finishedAt", "messageId")) {
            lastEvidence.put(key, last.get(key));
        }
        // AgentChatClient 只把工具名记成字符串，混进别的结构就置空，不原样照搬
        boolean toolsKnown = last.get("tools") instanceof List<?> names && names.stream().allMatch(String.class::isInstance);
        lastEvidence.put("tools", toolsKnown ? last.get("tools") : null);
        lastEvidence.put("observation", "COMPLETED".equals(last.get("status")) && toolsKnown
                ? "OBSERVED" : "UNKNOWN");
        lastEvidence.put("answerInExportedNonSummaryText", presence(retainedText, last.get("answer"),
                statePresent && "COMPLETED".equals(last.get("status"))));
        result.put("lastLoggedTurn", lastEvidence);
        result.put("limitations", List.of("Inspect the last logged question to confirm it is the final recall probe.",
                "ABSENT only means no exact substring in retained user/assistant text; paraphrases may remain.",
                "Tool payloads, tool input, thinking, system messages and other runtime fields are omitted.",
                "Live database state can change after the last logged turn; compare timestamps before attributing a result."));
        return result;
    }

    private static String presence(String retained, Object source, boolean known) {
        String value = text(source);
        return !known || value.isBlank() ? "UNKNOWN" : retained.contains(value) ? "PRESENT" : "ABSENT";
    }

    private static String text(Object value) {
        return value instanceof String s ? s : "";
    }

    private static List<Object> list(Object value) {
        return value instanceof List<?> ? SimpleJson.array(value) : List.of();
    }

    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> ? SimpleJson.object(value) : Map.of();
    }
}
