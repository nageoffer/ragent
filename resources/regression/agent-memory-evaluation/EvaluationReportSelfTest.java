/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Small offline fixtures test the accounting contract, without calling a service or model. */
public final class EvaluationReportSelfTest {
    private EvaluationReportSelfTest() { }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("ragent-memory-report-selftest-");
        Map<String, Object> base = metadata("state");
        Map<String, Object> first = turn("1", true, 100, 90, true);
        Map<String, Object> second = turn("2", true, 900, 0, true);
        Map<String, Object> warmup = turn("warm", true, 10000, 10000, true);
        warmup.put("phase", "warmup");
        first.put("stateUsages", first.get("usages"));
        List<Map<String, Object>> rows = List.of(warmup, first, second);
        completePlan(base, rows);
        Path source = write(directory.resolve("baseline"), base, rows);
        Map<String, Object> result = EvaluationReportMain.summarize(EvaluationReportMain.readRun(source));
        Map<String, Object> measure = object(result.get("measure"));
        equal(number(at(measure, "agentUsage", "inputTokens")), 1000, "warmup 排除");
        near(number(at(measure, "agentUsage", "cacheHitRatio")), 0.09, "缓存命中率按 token 加权");
        equal(number(measure.get("qualityScoredTurns")), 0, "无评分的分母为 0");
        check(measure.get("qualityPassRate") == null, "无评分的通过率必须 N/A");
        check(measure.get("observableGatewayCostEstimateUsd") == null, "state 不能估算网关总费用");
        check("UNCOVERED".equals(at(measure, "summary", "coverage")), "未摘要标为 UNCOVERED");
        check("UNCOVERED".equals(at(measure, "trim", "coverage")), "未裁剪标为 UNCOVERED");
        check(at(measure, "summaryUsage", "calls") == null, "无法按业务用途分类的调用数不能报 0");
        near(number(at(measure, "agentUsage", "inputCostEstimateUsd")), (90.0 * 0.1 + 910.0) / 1_000_000, "价格逐模型估算");
        EvaluationReportMain.writeReport(EvaluationReportMain.readRun(source), source);
        check(Files.readString(source.resolve("turns.csv")).contains("cachedTokens"), "CSV 已输出");

        Map<String, Object> missingCache = turn("1", false, 100, 90, true);
        Map<String, Object> unknown = aggregate(directory, "unknown-cache", metadata("state"), List.of(missingCache));
        check(at(unknown, "agentUsage", "cachedTokens") == null, "未知缓存不能计为 0");
        check(at(unknown, "agentUsage", "costEstimateUsd") == null, "未知缓存不估输入费用");
        check(at(unknown, "agentUsage", "cacheHitRatio") == null, "未知缓存不估命中率");
        equal(number(at(unknown, "agentUsage", "inputTokens")), 100, "缓存未知但输入仍保留");

        Map<String, Object> mixed = aggregate(directory, "mixed-cache", metadata("state"), List.of(first, turn("2", false, 900, 0, true)));
        check(at(mixed, "agentUsage", "cacheHitRatio") == null, "部分已知不能冒充完整命中率");
        near(number(at(mixed, "agentUsage", "knownUsageCacheHitRatio")), 0.9, "已知部分可单列");
        near(number(at(mixed, "agentUsage", "cacheObservationCoverage")), 0.5, "缓存覆盖率");

        Map<String, Object> summaryTurn = turn("1", true, 100, 90, true);
        summaryTurn.put("trim", map("newBlocks", 0, "reclaimedChars", null, "complete", false));
        summaryTurn.put("compactions", List.of(map("id", "s1", "generation", 1, "materialChars", 8000, "summaryChars", 2000,
                "contextCharsBefore", 9000, "contextCharsAfter", 3000)));
        summaryTurn.put("summaryVersionsObserved", 1);
        Map<String, Object> compressed = aggregate(directory, "compacted", metadata("state"), List.of(summaryTurn));
        check(at(compressed, "trim", "reclaimedChars") == null, "摘要可能吞掉裁剪证据时不报完整回收");
        equal(number(at(compressed, "trim", "observationCoverage")), 0, "不完整裁剪覆盖率");
        check("UNKNOWN".equals(at(compressed, "trim", "coverage")), "不完整且未见裁剪应 UNKNOWN");
        check("COVERED".equals(at(compressed, "summary", "coverage")), "摘要覆盖");
        near(number(at(compressed, "summary", "materialCompressionRatio")), 0.75, "素材压缩率为字符比例");
        equal(number(at(compressed, "summary", "contextReductionChars")), 6000, "摘要上下文减量");
        equal(number(compressed.get("maxObservedContextChars")), 9000, "最大观测长度包含摘要审计的瞬时上下文");
        check(at(compressed, "summaryUsage", "costEstimateUsd") == null, "有摘要状态但无摘要 usage 不报 0 费用");

        Map<String, Object> missingAudit = new java.util.LinkedHashMap<>(summaryTurn);
        missingAudit.put("summaryVersionsObserved", 2);
        Map<String, Object> auditPartial = aggregate(directory, "missing-summary-audit", metadata("state"), List.of(missingAudit));
        check(at(auditPartial, "summary", "materialCompressionRatio") == null, "摘要版本多于审计事件时不能声称完整素材压缩率");
        check(at(auditPartial, "summary", "contextReductionChars") == null, "摘要审计漏事件时不能声称完整空间减量");

        Map<String, Object> noAudit = turn("1", true, 100, 90, true);
        noAudit.put("after", map("contextChars", 1100, "auditAvailable", false));
        Map<String, Object> noAuditResult = aggregate(directory, "no-audit", metadata("state"), List.of(noAudit));
        check("UNKNOWN".equals(at(noAuditResult, "summary", "coverage")), "缺少摘要审计不能把未观测说成未触发");
        Map<String, Object> failedBeforeState = turn("1", true, 100, 90, true);
        failedBeforeState.put("status", "FAILED"); failedBeforeState.remove("after");
        check("UNKNOWN".equals(at(aggregate(directory, "failed-before-state", metadata("state"), List.of(failedBeforeState)), "summary", "coverage")), "失败后没有快照时摘要覆盖未知");

        Map<String, Object> gatewayMeta = metadata("gateway");
        gatewayMeta.put("configuration", map("gatewayScope", "complete", "isolated", true));
        Map<String, Object> gatewayTurn = turn("1", true, 100, 90, true);
        gatewayTurn.put("stateUsages", gatewayTurn.get("usages"));
        gatewayTurn.put("usages", List.of(usage("g1", "gateway", "deepseek", true, 100, 90, true),
                usage("g2", "gateway", "deepseek", true, 200, 0, true)));
        Map<String, Object> gateway = aggregate(directory, "gateway", gatewayMeta, List.of(gatewayTurn));
        equal(number(at(gateway, "agentUsage", "inputTokens")), 100, "stateUsages 独立主调用");
        equal(number(at(gateway, "gatewayUsage", "inputTokens")), 300, "网关不与 state 重复求和");
        check(gateway.get("observableGatewayCostEstimateUsd") != null, "完整网关调用可估算");
        check(at(gateway, "summaryUsage", "costEstimateUsd") == null, "provider route 不硬猜摘要费用");
        Map<String, Object> misleadingRoute = new java.util.LinkedHashMap<>(gatewayTurn);
        misleadingRoute.put("usages", List.of(usage("misleading-route", "gateway", "summary", true, 100, 90, true)));
        check(at(aggregate(directory, "misleading-route", gatewayMeta, List.of(misleadingRoute)), "summaryUsage", "calls") == null, "任意 route 名称不能作为摘要分类证据");
        near(number(gateway.get("observableGatewayCostEstimateUsd")), (90 * 0.1 + 210 + 2 * 3 * 2) / 1_000_000, "网关逐调用费用");
        Map<String, Object> notIsolated = metadata("gateway");
        notIsolated.put("configuration", map("gatewayScope", "complete", "isolated", false));
        check(aggregate(directory, "not-isolated", notIsolated, List.of(gatewayTurn)).get("observableGatewayCostEstimateUsd") == null, "未隔离不宣称完整网关费用");
        Map<String, Object> incompleteTurn = new java.util.LinkedHashMap<>(gatewayTurn);
        incompleteTurn.put("usageComplete", false);
        check(aggregate(directory, "partial-gateway", gatewayMeta, List.of(incompleteTurn)).get("observableGatewayCostEstimateUsd") == null, "网关字段不完整不得报完整费用");
        Map<String, Object> noPrices = metadata("gateway");
        noPrices.put("configuration", gatewayMeta.get("configuration")); noPrices.put("prices", Map.of());
        check(aggregate(directory, "missing-price", noPrices, List.of(gatewayTurn)).get("observableGatewayCostEstimateUsd") == null, "缺模型价不得报完整费用");
        Map<String, Object> unavailable = turn("1", true, 100, 90, false);
        check(at(aggregate(directory, "unavailable-usage", metadata("state"), List.of(unavailable)), "agentUsage", "inputTokens") == null, "usageAvailable=false 不估 token");

        Path candidate = write(directory.resolve("candidate"), base, rows);
        EvaluationReportMain.compare(EvaluationReportMain.readRun(source), EvaluationReportMain.readRun(candidate), directory.resolve("compare"));
        Map<String, Object> compare = object(SimpleJson.parse(Files.readString(directory.resolve("compare/compare.json"))));
        check(at(compare, "measureDifferences", "错误率", "relativeDifference") == null, "基线为 0 的相对差是 N/A");
        Map<String, Object> reformatted = new java.util.LinkedHashMap<>(base); reformatted.put("datasetHash", "new-format-same-selected-cases");
        Path otherFormat = write(directory.resolve("other-format"), reformatted, rows);
        EvaluationReportMain.compare(EvaluationReportMain.readRun(source), EvaluationReportMain.readRun(otherFormat), directory.resolve("compare-reformatted"));
        check(Files.readString(directory.resolve("compare-reformatted/compare.md")).contains("已选用例的展开内容与顺序相同"), "仅格式或未选用例变化仍可比并保留来源提示");
        Map<String, Object> otherSubset = new java.util.LinkedHashMap<>(base); otherSubset.put("selectionHash", "different");
        Path other = write(directory.resolve("other-subset"), otherSubset, rows);
        rejected(() -> EvaluationReportMain.compare(EvaluationReportMain.readRun(source), EvaluationReportMain.readRun(other), directory.resolve("rejected")), "不同子集禁止比较");
        Map<String, Object> changedPrice = new java.util.LinkedHashMap<>(base); changedPrice.put("prices", Map.of());
        Path otherPrice = write(directory.resolve("other-price"), changedPrice, rows);
        rejected(() -> EvaluationReportMain.compare(EvaluationReportMain.readRun(source), EvaluationReportMain.readRun(otherPrice), directory.resolve("rejected")), "价格不同禁止归因比较");
        Map<String, Object> changedInterval = new java.util.LinkedHashMap<>(base); changedInterval.put("intervalMillis", 5000);
        Path otherInterval = write(directory.resolve("other-interval"), changedInterval, rows);
        rejected(() -> EvaluationReportMain.compare(EvaluationReportMain.readRun(source), EvaluationReportMain.readRun(otherInterval), directory.resolve("rejected")), "调用间隔不同禁止归因比较");
        Map<String, Object> changedHarness = new java.util.LinkedHashMap<>(base); changedHarness.put("compiledHarnessSha256", map("EvaluationState", "different-code"));
        Path otherHarness = write(directory.resolve("other-harness"), changedHarness, rows);
        rejected(() -> EvaluationReportMain.compare(EvaluationReportMain.readRun(source), EvaluationReportMain.readRun(otherHarness), directory.resolve("rejected")), "计量代码不同禁止归因比较");
        Map<String, Object> unfinished = new java.util.LinkedHashMap<>(base); unfinished.put("status", "FAILED");
        Path failed = write(directory.resolve("failed"), unfinished, rows);
        rejected(() -> EvaluationReportMain.compare(EvaluationReportMain.readRun(source), EvaluationReportMain.readRun(failed), directory.resolve("rejected")), "不完整运行禁止比较");
        Map<String, Object> mismatchedKeys = new java.util.LinkedHashMap<>(base); mismatchedKeys.put("completedTurnKeys", List.of("demo/1/1"));
        Path mismatch = write(directory.resolve("mismatch"), mismatchedKeys, rows);
        rejected(() -> EvaluationReportMain.compare(EvaluationReportMain.readRun(source), EvaluationReportMain.readRun(mismatch), directory.resolve("rejected")), "轮键不一致禁止比较");
        List<Map<String, Object>> reversedRows = new ArrayList<>(rows);
        java.util.Collections.reverse(reversedRows);
        Map<String, Object> reversePlan = new java.util.LinkedHashMap<>(base); completePlan(reversePlan, reversedRows);
        Path wrongRowOrder = write(directory.resolve("wrong-row-order"), reversePlan, rows);
        rejected(() -> EvaluationReportMain.compare(EvaluationReportMain.readRun(source), EvaluationReportMain.readRun(wrongRowOrder), directory.resolve("rejected")), "实际顺序不符计划禁止比较");
        Path reversedRun = write(directory.resolve("reversed-run"), reversePlan, reversedRows);
        rejected(() -> EvaluationReportMain.compare(EvaluationReportMain.readRun(source), EvaluationReportMain.readRun(reversedRun), directory.resolve("rejected")), "A/B 执行顺序不同禁止比较");
        Map<String, Object> firstGatewayCase = turn("1", true, 100, 90, true);
        firstGatewayCase.put("usages", List.of(usage("global-id", "gateway", "deepseek", true, 100, 90, true)));
        Map<String, Object> nextGatewayCase = turn("1", true, 100, 90, true); nextGatewayCase.put("caseId", "different-case");
        nextGatewayCase.put("usages", firstGatewayCase.get("usages"));
        rejected(() -> aggregate(directory, "cross-case-gateway-duplicate", metadata("gateway"), List.of(firstGatewayCase, nextGatewayCase)), "gateway ID 跨 case 重复必须拒绝");
        Map<String, Object> warmGatewayCase = new java.util.LinkedHashMap<>(nextGatewayCase); warmGatewayCase.put("phase", "warmup");
        rejected(() -> aggregate(directory, "cross-phase-gateway-duplicate", metadata("gateway"), List.of(firstGatewayCase, warmGatewayCase)), "gateway ID 跨 phase 重复必须拒绝");
        Map<String, Object> firstStateCase = turn("1", true, 100, 90, true);
        Map<String, Object> nextStateCase = turn("1", true, 100, 90, true); nextStateCase.put("caseId", "different-case");
        Map<String, Object> scopedState = aggregate(directory, "scoped-state-id", metadata("state"), List.of(firstStateCase, nextStateCase));
        equal(number(at(scopedState, "agentUsage", "inputTokens")), 200, "state ID 按 case/repetition 分域");
        Map<String, Object> duplicate = turn("1", true, 100, 90, true);
        duplicate.put("usages", List.of(usage("same", "state", "agent", true, 100, 90, true), usage("same", "state", "agent", true, 100, 90, true)));
        rejected(() -> aggregate(directory, "duplicate", metadata("state"), List.of(duplicate)), "重复计量必须拒绝");
        invalidStateUsage(map("inputTokens", 10, "outputTokens", -1), "state 负输出拒绝");
        invalidStateUsage(map("cachedTokens", -1, "outputTokens", 1), "state 缺输入仍需校验缓存非负");
        Map<String, Object> duplicateMsg = map("id", "same", "role", "assistant", "usage", map("inputTokens", 1, "outputTokens", 1));
        invalidState(List.of(duplicateMsg, duplicateMsg), false, "state 重复 Msg.id 拒绝");
        Map<String, Object> missingId = map("role", "assistant", "usage", map("inputTokens", 1, "outputTokens", 1));
        invalidState(List.of(missingId), false, "state 缺失 Msg.id 拒绝");
        Map<String, Object> duplicateTool = map("role", "tool", "content", List.of(map("type", "tool_result", "id", "same", "output", List.of())));
        invalidState(List.of(duplicateTool, duplicateTool), true, "重复工具 ID 不能覆盖证据");
        System.out.println("EvaluationReportSelfTest: PASS（缓存未知、完整范围、摘要/裁剪覆盖、子集一致性与去重契约）；输出: " + directory);
    }

    private static Map<String, Object> metadata(String scope) {
        return map("schemaVersion", 1, "runId", "selftest", "variant", "label-only", "datasetHash", "sha256-dataset",
                "selectionHash", "sha256-selection", "selectedCaseIds", List.of("demo"), "repetitions", 1,
                "plannedTurns", 1, "completedTurns", 1, "status", "COMPLETE", "startedAt", "2026-09-26T00:00:00Z",
                "finishedAt", "2026-09-26T00:01:00Z", "configuration", Map.of(), "compiledHarnessSha256", map("EvaluationState", "selftest-code"), "prices", map("model", map("cachedInput", 0.1, "uncachedInput", 1.0, "output", 2.0)),
                "pricingDate", "fixture-not-real-pricing", "usageScope", scope, "warnings", List.of());
    }
    private static Map<String, Object> turn(String id, boolean cacheKnown, long input, long cached, boolean available) {
        return map("caseId", "demo", "repetition", 1, "turnId", id, "phase", "measure", "status", "OK",
                "conversationId", "demo-conversation", "durationMillis", 100, "before", map("contextChars", 1000), "after", map("contextChars", 1100, "auditAvailable", true),
                "quality", map("scored", false, "passed", false, "checks", List.of()), "trim", map("newBlocks", 0, "reclaimedChars", 0, "complete", true),
                "compactions", List.of(), "summaryVersionsObserved", 0, "usages", List.of(usage(id, "state", "agent", cacheKnown, input, cached, available)),
                "usageComplete", true, "warnings", List.of());
    }
    private static Map<String, Object> usage(String id, String source, String route, boolean cacheKnown, long input, long cached, boolean available) {
        return map("id", id, "source", source, "route", route, "model", "model", "inputTokens", input, "cachedTokens", cached,
                "uncachedInputTokens", input - cached, "outputTokens", 3, "usageAvailable", available, "cacheKnown", cacheKnown,
                "durationMillis", 80, "status", "state".equals(source) ? "OK" : "success");
    }
    private static void completePlan(Map<String, Object> metadata, List<Map<String, Object>> rows) {
        List<String> keys = rows.stream().map(EvaluationReportMain::turnKey).toList();
        metadata.put("plannedTurnKeys", keys); metadata.put("completedTurnKeys", keys);
        metadata.put("plannedTurns", rows.size()); metadata.put("completedTurns", rows.size());
    }
    private static Map<String, Object> aggregate(Path directory, String name, Map<String, Object> metadata, List<Map<String, Object>> rows) throws Exception {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(metadata); completePlan(copy, rows);
        Path source = write(directory.resolve(name), copy, rows);
        return object(EvaluationReportMain.summarize(EvaluationReportMain.readRun(source)).get("measure"));
    }
    private static Path write(Path directory, Map<String, Object> metadata, List<Map<String, Object>> rows) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("run.json"), SimpleJson.stringify(metadata), StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>(); for (Map<String, Object> row : rows) lines.add(SimpleJson.stringify(row));
        Files.write(directory.resolve("turns.jsonl"), lines, StandardCharsets.UTF_8); return directory;
    }
    private static Map<String, Object> map(Object... pairs) { return EvaluationReportMain.map(pairs); }
    private static Map<String, Object> object(Object value) { return SimpleJson.object(value); }
    private static Object at(Map<String, Object> value, String... path) { Object current = value; for (String key : path) current = object(current).get(key); return current; }
    private static double number(Object value) { return ((Number) value).doubleValue(); }
    private static void equal(double actual, double expected, String message) { check(actual == expected, message + ": " + actual + " != " + expected); }
    private static void near(double actual, double expected, String message) { check(Math.abs(actual - expected) < 1e-12, message + ": " + actual + " != " + expected); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void rejected(Throwing action, String message) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
    private static void invalidStateUsage(Map<String, Object> usage, String message) {
        invalidState(List.of(map("id", "invalid", "role", "assistant", "usage", usage)), false, message);
    }
    private static void invalidState(List<Map<String, Object>> messages, boolean trim, String message) {
        EvaluationState state = new EvaluationState(map("context", messages), List.of(), true);
        try {
            if (trim) state.trimSince(EvaluationState.empty(true));
            else state.usagesSince(EvaluationState.empty(true), "model");
        } catch (IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
