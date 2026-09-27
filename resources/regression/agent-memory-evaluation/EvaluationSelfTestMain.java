/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0. */
package com.nageoffer.ai.ragent.initializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 不连接服务，验证数据子集、断言分母、跨摘要用量去重和未知缓存的统计边界 */
public final class EvaluationSelfTestMain {
    private static int checks;
    private EvaluationSelfTestMain() { }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length == 0 ? "resources/regression/agent-memory-evaluation/datasets" : args[0]);
        List<EvaluationDataset.Case> retention = EvaluationDataset.load(dir.resolve("bit-selection.json"));
        require(!retention.isEmpty(), "bit-selection cases");
        require(EvaluationDataset.fingerprint(retention).equals(EvaluationDataset.fingerprint(retention)), "stable hash");
        require(EvaluationDataset.select(retention, "", "", 1).size() == 1, "limit selects cases");
        expectFailure(() -> EvaluationDataset.select(retention, "does-not-exist", "", 0));
        var turn = new EvaluationDataset.Turn("x", "question", List.of("new"), List.of(), List.of("old"),
                List.of(), List.of("search_knowledge"), "measure");
        require(Boolean.TRUE.equals(turn.evaluate("new", List.of()).get("passed")), "valid correction");
        require(Boolean.FALSE.equals(turn.evaluate("new old", List.of()).get("passed")), "stale value rejected");
        require(Boolean.FALSE.equals(turn.evaluate("new", List.of("search_knowledge")).get("passed")), "forbidden re-query");
        var unscored = new EvaluationDataset.Turn("unscored", "question", List.of(), List.of(), List.of(), List.of(), List.of(), "measure");
        require(Boolean.FALSE.equals(unscored.evaluate("anything", List.of()).get("scored")), "unscored excluded");

        Map<String, Object> old = assistant("a1", Map.of("inputTokens", 100, "cachedTokens", 80, "outputTokens", 5));
        Map<String, Object> next = assistant("a2", Map.of("inputTokens", 40, "outputTokens", 2));
        EvaluationState before = state(List.of(old, tool("call1", "x".repeat(100))), List.of());
        EvaluationState after = state(List.of(old, next, tool("call1", EvaluationState.OMITTED + "]")), List.of());
        List<Map<String, Object>> usage = after.usagesSince(before, "example-model");
        require(usage.size() == 1 && usage.get(0).get("id").equals("a2"), "Msg.id deduplication");
        require(usage.get(0).get("cachedTokens") == null && Boolean.FALSE.equals(usage.get(0).get("cacheKnown")), "missing cache is unknown");
        EvaluationState defaultZero = state(List.of(assistant("z", Map.of("inputTokens", 100, "outputTokens", 1, "cachedTokens", 0))), List.of());
        require(Boolean.FALSE.equals(defaultZero.usagesSince(EvaluationState.empty(true), "m").get(0).get("cacheKnown")), "SDK default zero is not raw cache evidence");
        Map<String, Object> trim = after.trimSince(before);
        require(((Number) trim.get("newBlocks")).intValue() == 1, "one eviction");
        require(((Number) trim.get("reclaimedChars")).longValue() == 100 - EvaluationState.OMITTED.length() - 1, "net reclaimed excludes placeholder");
        require(((Number) after.trimSince(after).get("newBlocks")).intValue() == 0, "eviction not recounted");
        Map<String, Object> summary = Map.of("id", "s1", "role", "USER", "name", EvaluationState.SUMMARY,
                "content", List.of(Map.of("type", "text", "text", "summary")));
        Map<String, Object> event = Map.of("id", "e1", "generation", 1, "materialChars", 200, "summaryChars", 7,
                "contextCharsBefore", 240, "contextCharsAfter", 47);
        EvaluationState compacted = state(List.of(summary, next), List.of(event));
        require(compacted.usagesSince(before, "example-model").size() == 1, "removed historical usages not subtracted");
        require(compacted.compactionsSince(before).size() == 1, "audit events append");
        require(compacted.newSummaryVersions(before) == 1, "summary tracked by id");
        require(compacted.trimSince(before).get("reclaimedChars") == null, "summary prevents false complete trim delta");
        require(compacted.compactionsSince(compacted).isEmpty(), "audit deduplicated");
        require(EvaluationState.blockChars(Map.of("type", "text", "text", "😀")) == 2, "Java UTF-16 counting");
        Path source = Files.createTempFile("evaluation-dataset-", ".json");
        try {
            String valid = "[{\"id\":\"sample\",\"turns\":[{\"id\":\"x\",\"question\":\"q\"}]}]";
            Files.writeString(source, "\uFEFF\n" + valid);
            List<EvaluationDataset.Case> bomCases = EvaluationDataset.load(source);
            require(bomCases.size() == 1 && bomCases.get(0).turns().get(0).question().equals("q"), "JSON array with BOM");
            Files.writeString(source, valid.replace("[{", "[\n  {").replace("}]", "}\n]"));
            require(EvaluationDataset.fingerprint(bomCases).equals(EvaluationDataset.fingerprint(EvaluationDataset.load(source))),
                    "JSON formatting does not change selected-content hash");
            Files.writeString(source, "[{\"id\":\"duplicate\",\"turns\":[{\"id\":\"x\",\"question\":\"q\"},{\"id\":\"x\",\"question\":\"r\"}]}]");
            expectFailure(() -> EvaluationDataset.load(source));
            Files.writeString(source, "[]");
            expectFailure(() -> EvaluationDataset.load(source));
            Files.writeString(source, "{\"id\":\"sample\",\"turns\":[{\"id\":\"x\",\"question\":\"q\"}]}");
            expectFailure(() -> EvaluationDataset.load(source));
            Files.writeString(source, valid + " trailing");
            expectFailure(() -> EvaluationDataset.load(source));
            Files.writeString(source, "[7]");
            expectFailure(() -> EvaluationDataset.load(source));
        } finally { Files.deleteIfExists(source); }
        configurationAndFailureChecks();
        System.out.println("EvaluationSelfTestMain PASS " + checks + " checks");
    }

    private static void configurationAndFailureChecks() throws Exception {
        Path dir = Files.createTempDirectory("evaluation-config-");
        Path yaml = dir.resolve("application.yaml"), config = dir.resolve("regression.properties"), usage = dir.resolve("usage.jsonl");
        try {
            Files.writeString(config, "application.config=application.yaml\n");
            String base = """
                    spring:
                      datasource:
                        url: jdbc:postgresql://localhost:5432/evaluation
                        username: evaluation
                      data:
                        redis:
                          host: localhost
                    agent:
                      chat:
                        provider: example-provider
                        model: %s # 模型由主配置提供
                    """;
            Files.writeString(yaml, base.formatted("model-before"));
            InitializerConfig first = InitializerConfig.load(config);
            require(first.require("agent.chat.model").equals("model-before"), "model inherited from application YAML");
            require(first.require("agent.chat.provider").equals("example-provider"), "provider inherited from application YAML");
            Files.writeString(yaml, base.formatted("model-after"));
            require(InitializerConfig.load(config).require("agent.chat.model").equals("model-after"), "model follows main configuration changes");

            Map<String, Object> row = new LinkedHashMap<>(Map.of("failureClass", "original-failure"));
            Files.writeString(usage, "{\"id\":\"partial");
            AgentMemoryEvaluationMain.captureFailedUsage(row, usage, 0, Instant.EPOCH);
            require(row.get("failureClass").equals("original-failure") && row.containsKey("warnings") && !row.containsKey("usages"),
                    "broken usage evidence preserves original failure and unknown cost");
            Files.writeString(usage, "{\"id\":\"request-1\",\"startedAt\":\"2026-01-01T00:00:00Z\"}\n");
            AgentMemoryEvaluationMain.captureFailedUsage(row, usage, 0, Instant.EPOCH);
            require(((List<?>) row.get("usages")).size() == 1, "failed turn retains available gateway evidence");
            Map<String, Object> hashes = AgentMemoryEvaluationMain.harnessFingerprint();
            require(hashes.containsKey("EvaluationDataset$Turn") && hashes.containsKey("AgentChatClient$Accumulator")
                    && hashes.containsKey("ApplicationYamlConfig"), "fingerprint includes nested logic and configuration reader");
        } finally {
            Files.deleteIfExists(usage); Files.deleteIfExists(config); Files.deleteIfExists(yaml); Files.deleteIfExists(dir);
        }
    }

    private static EvaluationState state(List<Map<String, Object>> messages, List<Map<String, Object>> audits) {
        return new EvaluationState(Map.of("context", new ArrayList<>(messages)), audits, true);
    }
    private static Map<String, Object> assistant(String id, Map<String, Object> usage) {
        return Map.of("id", id, "role", "assistant", "usage", usage, "content", List.of(Map.of("type", "text", "text", "answer")));
    }
    private static Map<String, Object> tool(String id, String text) {
        return Map.of("id", "msg-" + id, "role", "tool", "content", List.of(Map.of("type", "tool_result", "id", id,
                "output", List.of(Map.of("type", "text", "text", text)))));
    }
    private static void require(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static void expectFailure(Checked action) throws Exception {
        checks++; try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid input to fail");
    }
    @FunctionalInterface interface Checked { void run() throws Exception; }
}
