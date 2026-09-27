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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Offline, JDK-only aggregation. Unknown usage or coverage is never silently counted as zero. */
public final class EvaluationReportMain {
    private EvaluationReportMain() { }

    public static void main(String[] arguments) throws Exception {
        Map<String, String> args = arguments(arguments);
        if (args.containsKey("help") || args.isEmpty()) {
            System.out.println("EvaluationReportMain --run-dir PATH [--output-dir PATH]\n"
                    + "EvaluationReportMain --baseline PATH --candidate PATH --output-dir PATH");
            return;
        }
        Set<String> allowed = Set.of("run-dir", "output-dir", "baseline", "candidate", "help");
        for (String key : args.keySet()) require(allowed.contains(key), "未知参数: --" + key);
        if (args.containsKey("run-dir")) {
            require(!args.containsKey("baseline") && !args.containsKey("candidate"), "单报告与比较参数不能混用");
            Path runDir = Path.of(args.get("run-dir"));
            writeReport(readRun(runDir), Path.of(args.getOrDefault("output-dir", runDir.toString())));
        } else {
            require(args.containsKey("baseline") && args.containsKey("candidate") && args.containsKey("output-dir"),
                    "比较需要 --baseline、--candidate、--output-dir");
            compare(readRun(Path.of(args.get("baseline"))), readRun(Path.of(args.get("candidate"))),
                    Path.of(args.get("output-dir")));
        }
    }

    static Run readRun(Path directory) throws IOException {
        Map<String, Object> metadata = object(SimpleJson.parse(Files.readString(directory.resolve("run.json"))));
        require(number(metadata.get("schemaVersion"), -1) == 1, "只支持 schemaVersion=1");
        require(Set.of("state", "gateway").contains(string(metadata.get("usageScope"))), "usageScope 必须为 state/gateway");
        List<Map<String, Object>> turns = new ArrayList<>();
        Set<String> actualKeys = new LinkedHashSet<>();
        Set<String> gatewayIds = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        for (Object warning : list(metadata.get("warnings"))) warnings.add(string(warning));
        int line = 0;
        for (String raw : Files.readAllLines(directory.resolve("turns.jsonl"), StandardCharsets.UTF_8)) {
            line++;
            if (raw.isBlank()) continue;
            Map<String, Object> turn;
            try { turn = object(SimpleJson.parse(raw)); }
            catch (RuntimeException ex) { throw new IllegalArgumentException("turns.jsonl 第 " + line + " 行无效", ex); }
            String key = turnKey(turn);
            require(actualKeys.add(key), "重复轮键: " + key);
            require(Set.of("warmup", "measure").contains(string(turn.get("phase"))), "无效 phase: " + key);
            require(Set.of("OK", "FAILED").contains(string(turn.get("status"))), "无效 status: " + key);
            for (Object rawUsage : list(turn.get("usages"))) {
                Map<String, Object> usage = object(rawUsage);
                if ("gateway".equals(usage.get("source"))) {
                    String id = string(usage.get("id"));
                    require(!id.isBlank() && gatewayIds.add(id), "gateway id 必须在整个运行内非空且唯一: " + id);
                }
            }
            for (Object warning : list(turn.get("warnings"))) warnings.add(key + ": " + string(warning));
            turns.add(turn);
        }
        if (number(metadata.get("completedTurns"), -1) != turns.size()) warnings.add("completedTurns 与 turns.jsonl 行数不一致");
        if (!"COMPLETE".equals(metadata.get("status"))) warnings.add("运行未 COMPLETE；结果为已执行部分，不代表计划全集");
        if ("state".equals(metadata.get("usageScope"))) {
            warnings.add("state 仅观测保存在会话状态中的主模型用量；摘要模型与后台调用费用未知，不能称全生命周期费用");
        } else {
            warnings.add("网关费用仅覆盖该网关实际捕获的调用；隔离实例、全部 provider 接入及配置开关来自运行声明，variant 标签不构成配置证据");
        }
        if (metadata.get("plannedTurnKeys") == null || metadata.get("completedTurnKeys") == null) {
            warnings.add("缺少 plannedTurnKeys/completedTurnKeys，无法验证完整轮计划；拒绝进行 A/B 比较");
        }
        if (objectOrEmpty(metadata.get("compiledHarnessSha256")).isEmpty()) {
            warnings.add("缺少测评程序指纹；旧报告无法核对计量代码是否相同");
        }
        return new Run(directory.toAbsolutePath().normalize(), metadata, turns, warnings);
    }

    static Map<String, Object> summarize(Run run) {
        Map<String, Object> result = map("schemaVersion", 1, "generatedAt", Instant.now().toString(),
                "sourceDirectory", run.directory().toString(), "run", run.metadata());
        result.put("measure", aggregate(run, filter(run.turns(), null, "measure")));
        result.put("warmup", aggregate(run, filter(run.turns(), null, "warmup")));
        Map<String, Object> cases = new LinkedHashMap<>();
        Set<String> caseIds = new LinkedHashSet<>();
        for (Object id : list(run.metadata().get("selectedCaseIds"))) caseIds.add(string(id));
        for (Map<String, Object> turn : run.turns()) caseIds.add(string(turn.get("caseId")));
        for (String caseId : caseIds) {
            cases.put(caseId, map("measure", aggregate(run, filter(run.turns(), caseId, "measure")),
                    "warmup", aggregate(run, filter(run.turns(), caseId, "warmup"))));
        }
        result.put("byCase", cases);
        result.put("warnings", new ArrayList<>(new LinkedHashSet<>(run.warnings())));
        result.put("interpretation", map("configuration", "声明元数据；variant 名称不能证明实际服务配置",
                "tokens", "inputTokens 已含 cachedTokens；uncached = input - cached，未知缓存不按零；主调用数与 token 仅来自状态可见用量，摘要移除的同轮调用可能不可见",
                "cost", "USD 估算；按每条用量的 model 查找价格。state 无全生命周期费用；gateway 完整时也只称可观测网关费用",
                "latency", "durationMillis 为整轮 HTTP 耗时；模型耗时另列；nearest-rank p50/p95，少于 20 个样本的 p95 谨慎解释",
                "space", "字符数是 Java UTF-16 长度，摘要素材比例不是 token 压缩率；裁剪/摘要减量按事件累计，可重复作用于历史内容，不可解释为最终净压缩率",
                "quality", "仅 scored=true 且成功执行的轮进入质量分母；错误率独立；无裁剪/摘要事件为 UNCOVERED"));
        return result;
    }

    static Map<String, Object> aggregate(Run run, List<Map<String, Object>> turns) {
        long errors = 0, scored = 0, passed = 0, trimBlocks = 0, knownReclaimed = 0, trimCompleteTurns = 0;
        long compactionCount = 0, knownMaterial = 0, knownSummary = 0, knownSummaryReduction = 0, summaryVersions = 0;
        boolean materialComplete = true, reductionComplete = true, auditComplete = !turns.isEmpty();
        Set<String> compactionKeys = new LinkedHashSet<>();
        List<Double> httpLatencies = new ArrayList<>();
        Map<String, Long> finalContexts = new LinkedHashMap<>();
        long maxContextChars = 0;
        List<Usage> stateAgent = new ArrayList<>(), gateway = new ArrayList<>();
        boolean usageComplete = !turns.isEmpty();
        Map<String, Object> config = objectOrEmpty(run.metadata().get("configuration"));
        boolean gatewayScopeDeclared = "complete".equals(config.get("gatewayScope")) && bool(config.get("isolated"));
        Set<String> usageKeys = new LinkedHashSet<>();
        boolean isGateway = "gateway".equals(run.metadata().get("usageScope"));
        for (Map<String, Object> turn : turns) {
            String caseKey = string(turn.get("caseId")) + "/" + number(turn.get("repetition"), -1);
            boolean ok = "OK".equals(turn.get("status"));
            if (!ok) errors++;
            if (!ok || !bool(objectOrEmpty(turn.get("after")).get("auditAvailable"))) auditComplete = false;
            Number elapsed = nonnegative(turn.get("durationMillis"));
            if (elapsed != null) httpLatencies.add(elapsed.doubleValue());
            Map<String, Object> quality = objectOrEmpty(turn.get("quality"));
            if (ok && bool(quality.get("scored"))) {
                scored++;
                if (bool(quality.get("passed"))) passed++;
            }
            Map<String, Object> trim = objectOrEmpty(turn.get("trim"));
            trimBlocks += number(trim.get("newBlocks"), 0);
            Number reclaimed = nonnegative(trim.get("reclaimedChars"));
            if (reclaimed != null) knownReclaimed += reclaimed.longValue();
            else {
                Number observed = nonnegative(trim.get("observedReclaimedChars"));
                if (observed != null) knownReclaimed += observed.longValue();
            }
            if (bool(trim.get("complete")) && reclaimed != null) trimCompleteTurns++;
            summaryVersions += number(turn.get("summaryVersionsObserved"), 0);
            Long after = nullableLong(objectOrEmpty(turn.get("after")).get("contextChars"));
            Long before = nullableLong(objectOrEmpty(turn.get("before")).get("contextChars"));
            if (after != null) { finalContexts.put(caseKey, after); maxContextChars = Math.max(maxContextChars, after); }
            if (before != null) maxContextChars = Math.max(maxContextChars, before);
            for (Object item : list(turn.get("compactions"))) {
                Map<String, Object> compaction = object(item);
                String identity = compaction.get("id") != null ? string(compaction.get("id")) : string(compaction.get("generation"));
                if (identity.isBlank()) identity = turnKey(turn) + "/" + compactionCount;
                if (!compactionKeys.add(caseKey + "/" + identity)) continue;
                compactionCount++;
                Long material = nullableLong(compaction.get("materialChars"));
                Long summary = nullableLong(compaction.get("summaryChars"));
                if (material == null || summary == null) materialComplete = false;
                else { knownMaterial += material; knownSummary += summary; }
                Long prior = nullableLong(compaction.get("contextCharsBefore"));
                Long afterSummary = nullableLong(compaction.get("contextCharsAfter"));
                if (prior != null) maxContextChars = Math.max(maxContextChars, prior);
                if (afterSummary != null) maxContextChars = Math.max(maxContextChars, afterSummary);
                if (prior == null || afterSummary == null) reductionComplete = false;
                else knownSummaryReduction += prior - afterSummary;
            }
            if (!bool(turn.get("usageComplete"))) usageComplete = false;
            List<Object> allUsages = new ArrayList<>(list(turn.get("usages")));
            if (turn.get("stateUsages") instanceof List<?>) {
                // The runner retains the independent state evidence in both modes. Never add it twice.
                allUsages.removeIf(raw -> "state".equals(object(raw).get("source")));
                allUsages.addAll(list(turn.get("stateUsages")));
            }
            for (Object raw : allUsages) {
                Map<String, Object> value = object(raw);
                Usage usage = usage(value, run.metadata());
                String id = string(value.get("id"));
                String usageKey = "gateway".equals(usage.source()) ? "gateway/" + id : caseKey + "/" + usage.source() + "/" + id;
                if (!id.isBlank() && !usageKeys.add(usageKey)) {
                    throw new IllegalArgumentException("同一分组重复计量 usage id，runner 应按调用去重: " + caseKey + "/" + id);
                }
                if ("state".equals(usage.source()) && "agent".equals(usage.route())) stateAgent.add(usage);
                if ("gateway".equals(usage.source())) {
                    gateway.add(usage);
                }
            }
        }
        Map<String, Object> state = aggregateUsages(stateAgent);
        Map<String, Object> allGateway = aggregateUsages(gateway);
        // route 是供应商路由，不能区分主调用、摘要与其他任务；不把“无法分类”写成零次摘要调用
        Map<String, Object> summaries = aggregateUsages(List.of());
        summaries.put("calls", null);
        summaries.put("classificationKnown", false);
        boolean trimComplete = !turns.isEmpty() && trimCompleteTurns == turns.size();
        Object totalCost = isGateway && gatewayScopeDeclared && usageComplete && errors == 0 && number(allGateway.get("errorCalls"), 0) == 0 && bool(allGateway.get("costComplete"))
                ? allGateway.get("costEstimateUsd") : null;
        long summaryEvents = Math.max(compactionCount, summaryVersions);
        if (!auditComplete || summaryVersions > compactionCount) { materialComplete = false; reductionComplete = false; }
        return map("turnCount", turns.size(), "successfulTurns", turns.size() - errors, "errorCount", errors,
                "errorRate", ratio(errors, turns.size()), "qualityScoredTurns", scored, "qualityPassedTurns", passed,
                "qualityPassRate", ratio(passed, scored), "qualityCoverage", ratio(scored, turns.size()),
                "httpLatencyMillis", distribution(httpLatencies), "agentUsage", state, "stateAgentUsage", state,
                "gatewayUsage", allGateway, "summaryUsage", summaries,
                "usageComplete", usageComplete, "gatewayScopeCompleteDeclared", gatewayScopeDeclared, "observableGatewayCostEstimateUsd", totalCost,
                "unobservedSummaryAndBackgroundCostUsd", null,
                "trim", map("coverage", trimBlocks > 0 ? "COVERED" : (trimComplete ? "UNCOVERED" : "UNKNOWN"),
                        "newBlocks", trimBlocks, "reclaimedChars", trimComplete ? knownReclaimed : null,
                        "knownReclaimedChars", knownReclaimed, "complete", trimComplete,
                        "completeTurns", trimCompleteTurns, "observationCoverage", ratio(trimCompleteTurns, turns.size())),
                "summary", map("coverage", summaryEvents > 0 ? "COVERED" : (auditComplete ? "UNCOVERED" : "UNKNOWN"),
                        "auditObservationComplete", auditComplete,
                        "compactionEvents", compactionCount, "summaryVersionsObserved", summaryVersions,
                        "contextReductionChars", compactionCount > 0 && reductionComplete ? knownSummaryReduction : null,
                        "knownContextReductionChars", knownSummaryReduction, "contextReductionComplete", compactionCount > 0 && reductionComplete,
                        "materialChars", compactionCount > 0 && materialComplete ? knownMaterial : null,
                        "summaryChars", compactionCount > 0 && materialComplete ? knownSummary : null,
                        "materialCompressionRatio", compactionCount > 0 && materialComplete && knownMaterial > 0 ? 1.0 - (double) knownSummary / knownMaterial : null,
                        "materialObservationComplete", compactionCount > 0 && materialComplete),
                "finalContextCharsByCaseRepetition", finalContexts,
                "meanFinalContextChars", finalContexts.isEmpty() ? null : finalContexts.values().stream().mapToLong(Long::longValue).average().orElseThrow(),
                "maxObservedContextChars", turns.isEmpty() || maxContextChars == 0 && finalContexts.isEmpty() ? null : maxContextChars);
    }

    private static Usage usage(Map<String, Object> value, Map<String, Object> metadata) {
        String source = string(value.get("source")), route = string(value.get("route")), model = string(value.get("model"));
        require(Set.of("state", "gateway").contains(source), "usage source 必须为 state/gateway");
        Long input = nullableLong(value.get("inputTokens")), cached = nullableLong(value.get("cachedTokens"));
        Long uncached = nullableLong(value.get("uncachedInputTokens")), output = nullableLong(value.get("outputTokens"));
        boolean available = bool(value.get("usageAvailable"));
        boolean cacheKnown = bool(value.get("cacheKnown"));
        if (!available) { input = null; cached = null; uncached = null; output = null; }
        if (!cacheKnown) { cached = null; uncached = null; }
        if (input != null && cached != null) {
            require(cached <= input, "cachedTokens 不得大于 inputTokens");
            if (uncached != null) require(input - cached == uncached, "inputTokens 必须等于 cachedTokens + uncachedInputTokens");
            uncached = input - cached;
        }
        Map<String, Object> prices = objectOrEmpty(objectOrEmpty(metadata.get("prices")).get(model));
        Double hitPrice = nullableDouble(prices.get("cachedInput")), missPrice = nullableDouble(prices.get("uncachedInput"));
        Double outputPrice = nullableDouble(prices.get("output"));
        Double inputCost = input != null && cached != null && uncached != null && hitPrice != null && missPrice != null
                ? (cached * hitPrice + uncached * missPrice) / 1_000_000.0 : null;
        Double outputCost = output != null && outputPrice != null ? output * outputPrice / 1_000_000.0 : null;
        Double cost = inputCost != null && outputCost != null ? inputCost + outputCost : null;
        Number latency = nonnegative(value.get("durationMillis"));
        return new Usage(source, route, model, input, cached, uncached, output, inputCost, outputCost, cost,
                latency == null ? null : latency.doubleValue(), string(value.get("status")));
    }

    private static Map<String, Object> aggregateUsages(List<Usage> usages) {
        boolean inputComplete = !usages.isEmpty(), cacheComplete = !usages.isEmpty(), outputComplete = !usages.isEmpty();
        boolean inputCostComplete = !usages.isEmpty(), outputCostComplete = !usages.isEmpty(), costComplete = !usages.isEmpty();
        long input = 0, cached = 0, miss = 0, output = 0, knownCacheInput = 0, knownCacheCalls = 0, errors = 0;
        double inputCost = 0, outputCost = 0, cost = 0;
        List<Double> latency = new ArrayList<>();
        Map<String, Long> models = new LinkedHashMap<>(), routes = new LinkedHashMap<>();
        for (Usage usage : usages) {
            models.merge(usage.model(), 1L, Long::sum); routes.merge(usage.route(), 1L, Long::sum);
            if (usage.input() == null) inputComplete = false; else input += usage.input();
            if (usage.cached() == null || usage.uncached() == null || usage.input() == null) cacheComplete = false;
            else { cached += usage.cached(); miss += usage.uncached(); knownCacheInput += usage.input(); knownCacheCalls++; }
            if (usage.output() == null) outputComplete = false; else output += usage.output();
            if (usage.inputCost() == null) inputCostComplete = false; else inputCost += usage.inputCost();
            if (usage.outputCost() == null) outputCostComplete = false; else outputCost += usage.outputCost();
            if (usage.cost() == null) costComplete = false; else cost += usage.cost();
            if (usage.duration() != null) latency.add(usage.duration());
            if (!usage.status().isBlank() && !Set.of("ok", "success", "200").contains(usage.status().toLowerCase(Locale.ROOT))) errors++;
        }
        return map("calls", usages.size(), "errorCalls", errors, "models", models, "routes", routes,
                "inputTokens", inputComplete ? input : null, "knownInputTokens", input,
                "cachedTokens", cacheComplete ? cached : null, "knownCachedTokens", cached,
                "uncachedInputTokens", cacheComplete ? miss : null, "knownUncachedInputTokens", miss,
                "outputTokens", outputComplete ? output : null, "knownOutputTokens", output,
                "inputComplete", inputComplete, "cacheComplete", cacheComplete, "outputComplete", outputComplete,
                "cacheKnownCalls", knownCacheCalls, "cacheObservationCoverage", ratio(knownCacheCalls, usages.size()),
                "cacheHitRatio", cacheComplete ? ratio(cached, input) : null,
                "knownUsageCacheHitRatio", ratio(cached, knownCacheInput),
                "inputCostEstimateUsd", inputCostComplete ? inputCost : null,
                "outputCostEstimateUsd", outputCostComplete ? outputCost : null,
                "costEstimateUsd", costComplete ? cost : null, "costComplete", costComplete,
                "knownCostEstimateUsd", cost, "modelLatencyMillis", distribution(latency));
    }

    static void writeReport(Run run, Path output) throws IOException {
        Map<String, Object> summary = summarize(run);
        Files.createDirectories(output);
        Files.writeString(output.resolve("summary.json"), SimpleJson.stringify(summary) + "\n", StandardCharsets.UTF_8);
        Files.writeString(output.resolve("report.md"), report(run, summary), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("turns.csv"), csv(run), StandardCharsets.UTF_8);
        System.out.println("报告已生成: " + output.toAbsolutePath().normalize());
    }

    private static String report(Run run, Map<String, Object> summary) {
        StringBuilder out = new StringBuilder("# 短期裁剪 / 中期摘要测评报告\n\n");
        Map<String, Object> metadata = run.metadata(), measure = object(summary.get("measure"));
        out.append("运行 `").append(markdown(metadata.get("runId"))).append("`，状态 **")
                .append(markdown(metadata.get("status"))).append("**，版本 `").append(markdown(metadata.get("variant")))
                .append("`，用量范围 `").append(markdown(metadata.get("usageScope"))).append("`。\n\n")
                .append("用例：").append(markdown(jsonDisplay(metadata.get("selectedCaseIds"))))
                .append("；重复 ").append(display(metadata.get("repetitions"))).append(" 次；完成 / 计划 ")
                .append(display(metadata.get("completedTurns"))).append(" / ").append(display(metadata.get("plannedTurns"))).append(" 轮。\n\n");
        appendMetrics(out, measure, "gateway".equals(metadata.get("usageScope")));
        out.append("## 分用例结果（正式轮）\n\n")
                .append("| 用例 | 轮数 / 错误 | 断言通过 / 已评分 | 裁剪回收字符 | 摘要次数 | 缓存命中率 | 主调用费用 USD |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|\n");
        for (Map.Entry<String, Object> entry : object(summary.get("byCase")).entrySet()) {
            Map<String, Object> group = object(object(entry.getValue()).get("measure"));
            row(out, entry.getKey(), display(group.get("turnCount")) + " / " + display(group.get("errorCount")),
                    display(group.get("qualityPassedTurns")) + " / " + display(group.get("qualityScoredTurns")),
                    display(at(group, "trim", "reclaimedChars")), display(at(group, "summary", "compactionEvents")),
                    percent(at(group, "agentUsage", "cacheHitRatio")), decimal(at(group, "agentUsage", "costEstimateUsd"), 8));
        }
        Map<String, Object> warmup = object(summary.get("warmup"));
        if (number(warmup.get("turnCount"), 0) > 0) {
            out.append("\n## 预热（不计入正式结果）\n\n")
                    .append("| 轮数 / 错误 | 主调用输入 token | 缓存命中率 | 主调用费用 USD |\n|---:|---:|---:|---:|\n");
            row(out, display(warmup.get("turnCount")) + " / " + display(warmup.get("errorCount")),
                    display(at(warmup, "agentUsage", "inputTokens")), percent(at(warmup, "agentUsage", "cacheHitRatio")),
                    decimal(at(warmup, "agentUsage", "costEstimateUsd"), 8));
        }
        appendNotes(out);
        appendWarnings(out, list(summary.get("warnings")));
        out.append("\n完整指标见 `summary.json`，逐轮结果见 `turns.csv`；配置与价格见源运行目录的 `run.json`，原始记录见 `turns.jsonl`。\n")
                .append("源运行目录：`").append(markdown(run.directory())).append("`。\n");
        return out.toString();
    }

    private static void appendMetrics(StringBuilder out, Map<String, Object> group, boolean gateway) {
        out.append("## 正式指标（不含预热）\n\n| 指标 | 值 |\n|---|---:|\n");
        row(out, "执行轮数 / 错误率", display(group.get("turnCount")) + " / " + percent(group.get("errorRate")));
        row(out, "断言通过率（通过 / 已评分）", percent(group.get("qualityPassRate")) + "（"
                + display(group.get("qualityPassedTurns")) + " / " + display(group.get("qualityScoredTurns")) + "）");
        row(out, "评分覆盖率", percent(group.get("qualityCoverage")));
        row(out, "主调用数 / 输入 token / 输出 token", display(at(group, "agentUsage", "calls")) + " / "
                + display(at(group, "agentUsage", "inputTokens")) + " / " + display(at(group, "agentUsage", "outputTokens")));
        row(out, "输入中缓存命中 / 未命中 token", display(at(group, "agentUsage", "cachedTokens")) + " / "
                + display(at(group, "agentUsage", "uncachedInputTokens")));
        row(out, "缓存命中率 / 缓存观测覆盖率", percent(at(group, "agentUsage", "cacheHitRatio")) + " / "
                + percent(at(group, "agentUsage", "cacheObservationCoverage")));
        row(out, "主调用估算费用 USD（输入 / 输出 / 合计）", decimal(at(group, "agentUsage", "inputCostEstimateUsd"), 8) + " / "
                + decimal(at(group, "agentUsage", "outputCostEstimateUsd"), 8) + " / " + decimal(at(group, "agentUsage", "costEstimateUsd"), 8));
        if (gateway) row(out, "网关内全部调用估算费用 USD", decimal(group.get("observableGatewayCostEstimateUsd"), 8));
        row(out, "裁剪覆盖 / 块数 / 回收字符", display(at(group, "trim", "coverage")) + " / "
                + display(at(group, "trim", "newBlocks")) + " / " + display(at(group, "trim", "reclaimedChars")));
        row(out, "摘要覆盖 / 审计次数 / 观察到的版本数", display(at(group, "summary", "coverage")) + " / "
                + display(at(group, "summary", "compactionEvents")) + " / " + display(at(group, "summary", "summaryVersionsObserved")));
        row(out, "摘要素材 / 正文字符数 / 字符压缩率", display(at(group, "summary", "materialChars")) + " / "
                + display(at(group, "summary", "summaryChars")) + " / " + percent(at(group, "summary", "materialCompressionRatio")));
        row(out, "摘要事件累计上下文减量（字符）", display(at(group, "summary", "contextReductionChars")));
        row(out, "每会话最终上下文均值 / 最大观测长度（字符）", decimal(group.get("meanFinalContextChars"), 1) + " / " + display(group.get("maxObservedContextChars")));
        row(out, "HTTP p50 / p95 ms（样本数）", decimal(at(group, "httpLatencyMillis", "p50"), 1) + " / "
                + decimal(at(group, "httpLatencyMillis", "p95"), 1) + "（" + display(at(group, "httpLatencyMillis", "count")) + "）");
        out.append('\n');
    }

    private static void appendNotes(StringBuilder out) {
        out.append("\n字符压缩率不等于 token 压缩率，裁剪与摘要的累计减量也不等于最终净压缩率。\n")
                .append("`state` 费用只含状态可见主调用、不含摘要；`gateway` 只含接入且完整捕获的调用，费用均为估算。\n")
                .append("`UNCOVERED` 表示未触发，`UNKNOWN` 表示观测不完整，`N/A` 表示缺少数值或不适用。\n");
    }

    private static void appendWarnings(StringBuilder out, List<Object> warnings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Object raw : warnings) {
            String warning = string(raw).replaceFirst("^[^\\s:]+/[0-9]+/[^\\s:]+: ", "");
            // Scope is already explained once above; keep full warning evidence in the JSON report.
            if (warning.startsWith("state 仅观测") || warning.startsWith("只采集 state")
                    || warning.startsWith("网关费用仅覆盖") || warning.startsWith("用量日志按时间归属")) continue;
            if (warning.startsWith("完整调用费用未覆盖")) warning = "部分调用费用未完整观测，缺失项记为 N/A";
            counts.merge(warning, 1, Integer::sum);
        }
        if (counts.isEmpty()) return;
        out.append("\n## 数据缺失与异常\n\n");
        counts.forEach((warning, count) -> out.append("- ").append(markdown(warning))
                .append(count > 1 ? "（" + count + " 条）" : "").append('\n'));
        out.append("\n完整警告和具体轮次保留在 JSON 报告及源运行目录的 `turns.jsonl`。\n");
    }

    private static String csv(Run run) {
        StringBuilder out = new StringBuilder("caseId,repetition,turnId,phase,status,conversationId,httpMillis,beforeChars,afterChars,scored,passed,trimBlocks,trimReclaimedChars,trimComplete,compactions,agentCalls,inputTokens,cachedTokens,uncachedInputTokens,outputTokens,agentObservedCostUsd,observableGatewayCostUsd,usageComplete\n");
        for (Map<String, Object> turn : run.turns()) {
            Map<String, Object> group = aggregate(run, List.of(turn));
            Object[] values = {turn.get("caseId"), turn.get("repetition"), turn.get("turnId"), turn.get("phase"), turn.get("status"), turn.get("conversationId"),
                    turn.get("durationMillis"), at(turn, "before", "contextChars"), at(turn, "after", "contextChars"), at(turn, "quality", "scored"), at(turn, "quality", "passed"),
                    at(group, "trim", "newBlocks"), at(group, "trim", "reclaimedChars"), at(group, "trim", "complete"), at(group, "summary", "compactionEvents"),
                    at(group, "agentUsage", "calls"), at(group, "agentUsage", "inputTokens"), at(group, "agentUsage", "cachedTokens"), at(group, "agentUsage", "uncachedInputTokens"),
                    at(group, "agentUsage", "outputTokens"), at(group, "agentUsage", "costEstimateUsd"), group.get("observableGatewayCostEstimateUsd"), group.get("usageComplete")};
            for (int index = 0; index < values.length; index++) {
                if (index > 0) out.append(',');
                out.append(csvValue(values[index]));
            }
            out.append('\n');
        }
        return out.toString();
    }

    static void compare(Run baseline, Run candidate, Path output) throws IOException {
        validateComparison(baseline, candidate);
        Map<String, Object> baselineSummary = summarize(baseline), candidateSummary = summarize(candidate);
        Map<String, Object> differences = differences(object(baselineSummary.get("measure")), object(candidateSummary.get("measure")));
        Map<String, Object> byCase = new LinkedHashMap<>();
        for (Object id : list(baseline.metadata().get("selectedCaseIds"))) {
            String caseId = string(id);
            byCase.put(caseId, differences(object(at(baselineSummary, "byCase", caseId, "measure")), object(at(candidateSummary, "byCase", caseId, "measure"))));
        }
        List<String> warnings = new ArrayList<>(); warnings.addAll(baseline.warnings()); warnings.addAll(candidate.warnings());
        if (!Objects.equals(baseline.metadata().get("datasetHash"), candidate.metadata().get("datasetHash"))) {
            warnings.add("数据集源文件不同，但已选用例的展开内容与顺序相同；未选用例和 JSON 排版不影响本次比较");
        }
        Map<String, Object> result = map("schemaVersion", 1, "generatedAt", Instant.now().toString(),
                "baselineRun", baseline.metadata(), "candidateRun", candidate.metadata(), "measureDifferences", differences,
                "byCase", byCase, "baselineSummary", baselineSummary, "candidateSummary", candidateSummary,
                "warnings", new ArrayList<>(new LinkedHashSet<>(warnings)));
        Files.createDirectories(output);
        Files.writeString(output.resolve("compare.json"), SimpleJson.stringify(result) + "\n", StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder("# 短期裁剪 / 中期摘要 A/B 比较\n\n");
        out.append("基线 `").append(markdown(baseline.metadata().get("runId"))).append("`，候选 `").append(markdown(candidate.metadata().get("runId")))
                .append("`；均完成 ").append(display(baseline.metadata().get("completedTurns"))).append(" 轮，正式比较排除预热。\n\n")
                .append("用例：").append(markdown(jsonDisplay(baseline.metadata().get("selectedCaseIds"))))
                .append("；重复 ").append(display(baseline.metadata().get("repetitions"))).append(" 次；用量范围 `")
                .append(markdown(baseline.metadata().get("usageScope"))).append("`。\n")
                .append("已选用例内容、轮计划、价格及调用间隔已通过一致性校验。\n\n");
        appendDifferences(out, "正式指标", differences);
        out.append("## 分用例比较\n\n| 用例 | 断言通过率 | 裁剪回收字符 | 摘要次数 | 缓存命中率 | 主调用费用 USD |\n")
                .append("|---|---:|---:|---:|---:|---:|\n");
        for (Map.Entry<String, Object> entry : byCase.entrySet()) {
            Map<String, Object> values = object(entry.getValue());
            row(out, entry.getKey(), transition(values, "自动断言通过率"), transition(values, "裁剪回收字符（完整）"),
                    transition(values, "摘要事件数"), transition(values, "主调用加权缓存命中率"), transition(values, "主调用已观测合计费用 USD"));
        }
        out.append("\n分用例表：基线 → 候选；绝对差 = 候选 − 基线，相对差 = 绝对差 / 基线，基线为 0 时为 N/A。\n");
        appendConfigurationChanges(out, baseline.metadata(), candidate.metadata());
        appendNotes(out);
        appendWarnings(out, list(result.get("warnings")));
        out.append("\n完整指标与元数据见 `compare.json`；两版原始记录分别位于：\n\n")
                .append("- 基线：`").append(markdown(baseline.directory())).append("`\n")
                .append("- 候选：`").append(markdown(candidate.directory())).append("`\n");
        Files.writeString(output.resolve("compare.md"), out.toString(), StandardCharsets.UTF_8);
        System.out.println("比较报告已生成: " + output.toAbsolutePath().normalize());
    }

    private static void validateComparison(Run baseline, Run candidate) {
        for (Run run : List.of(baseline, candidate)) {
            Map<String, Object> metadata = run.metadata();
            require("COMPLETE".equals(metadata.get("status")), "比较拒绝非 COMPLETE 运行: " + metadata.get("runId"));
            for (String field : List.of("datasetHash", "selectionHash")) require(!string(metadata.get(field)).isBlank(), "比较要求 " + field);
            List<Object> selected = list(metadata.get("selectedCaseIds"));
            require(!selected.isEmpty() && new LinkedHashSet<>(selected).size() == selected.size(), "selectedCaseIds 不能为空或重复");
            long repetitions = number(metadata.get("repetitions"), 0);
            require(repetitions > 0, "repetitions 必须大于 0");
            require(metadata.get("plannedTurnKeys") instanceof List<?> && metadata.get("completedTurnKeys") instanceof List<?>, "比较需要 plannedTurnKeys 和 completedTurnKeys");
            keySet(metadata.get("plannedTurnKeys")); keySet(metadata.get("completedTurnKeys"));
            List<Object> planned = list(metadata.get("plannedTurnKeys")), completed = list(metadata.get("completedTurnKeys"));
            List<String> actual = new ArrayList<>();
            Set<String> seenCaseRepetitions = new LinkedHashSet<>();
            for (Map<String, Object> turn : run.turns()) {
                require("OK".equals(turn.get("status")), "比较拒绝包含 FAILED 轮的运行: " + turnKey(turn));
                require(selected.contains(turn.get("caseId")), "轮 case 不在 selectedCaseIds: " + turnKey(turn));
                long repetition = number(turn.get("repetition"), -1);
                require(repetition >= 1 && repetition <= repetitions, "轮 repetition 超出计划: " + turnKey(turn));
                seenCaseRepetitions.add(string(turn.get("caseId")) + "/" + repetition);
                actual.add(turnKey(turn));
            }
            require(!planned.isEmpty() && planned.equals(completed) && completed.equals(actual), "计划、完成与实际轮键或执行顺序不一致: " + metadata.get("runId"));
            require(number(metadata.get("plannedTurns"), -1) == actual.size() && number(metadata.get("completedTurns"), -1) == actual.size(), "计划/完成轮数与实际轮数不一致");
            require(seenCaseRepetitions.size() == selected.size() * repetitions, "缺少 case/repetition");
        }
        for (String field : List.of("selectionHash", "selectedCaseIds", "repetitions", "plannedTurns", "usageScope", "prices", "pricingDate", "intervalMillis", "compiledHarnessSha256"))
            require(Objects.equals(baseline.metadata().get(field), candidate.metadata().get(field)), "拒绝比较：" + field + " 不同");
        require(list(baseline.metadata().get("plannedTurnKeys")).equals(list(candidate.metadata().get("plannedTurnKeys"))), "拒绝比较：plannedTurnKeys 或执行顺序不同");
        require(list(baseline.metadata().get("completedTurnKeys")).equals(list(candidate.metadata().get("completedTurnKeys"))), "拒绝比较：completedTurnKeys 或执行顺序不同");
        Map<String, String> baselinePhase = new LinkedHashMap<>(), candidatePhase = new LinkedHashMap<>();
        for (Map<String, Object> turn : baseline.turns()) baselinePhase.put(turnKey(turn), string(turn.get("phase")));
        for (Map<String, Object> turn : candidate.turns()) candidatePhase.put(turnKey(turn), string(turn.get("phase")));
        require(baselinePhase.equals(candidatePhase), "拒绝比较：对应轮 phase 不同");
    }

    private static Map<String, Object> differences(Map<String, Object> baseline, Map<String, Object> candidate) {
        Map<String, String[]> metrics = new LinkedHashMap<>();
        metrics.put("正式轮数", new String[]{"turnCount"}); metrics.put("错误率", new String[]{"errorRate"});
        metrics.put("已评分轮数", new String[]{"qualityScoredTurns"}); metrics.put("自动断言通过率", new String[]{"qualityPassRate"});
        metrics.put("质量评分覆盖率", new String[]{"qualityCoverage"}); metrics.put("主模型调用数", new String[]{"agentUsage", "calls"});
        metrics.put("主调用输入 token", new String[]{"agentUsage", "inputTokens"}); metrics.put("主调用缓存 token", new String[]{"agentUsage", "cachedTokens"});
        metrics.put("主调用未命中 token", new String[]{"agentUsage", "uncachedInputTokens"}); metrics.put("主调用输出 token", new String[]{"agentUsage", "outputTokens"});
        metrics.put("主调用加权缓存命中率", new String[]{"agentUsage", "cacheHitRatio"});
        metrics.put("主调用已观测输入费用 USD", new String[]{"agentUsage", "inputCostEstimateUsd"});
        metrics.put("主调用已观测合计费用 USD", new String[]{"agentUsage", "costEstimateUsd"});
        metrics.put("接入网关的模型调用估算 USD", new String[]{"observableGatewayCostEstimateUsd"});
        metrics.put("裁剪回收字符（完整）", new String[]{"trim", "reclaimedChars"}); metrics.put("裁剪完整观测轮比例", new String[]{"trim", "observationCoverage"});
        metrics.put("裁剪块数", new String[]{"trim", "newBlocks"}); metrics.put("摘要事件数", new String[]{"summary", "compactionEvents"});
        metrics.put("摘要上下文减量字符", new String[]{"summary", "contextReductionChars"}); metrics.put("摘要素材字符压缩率", new String[]{"summary", "materialCompressionRatio"});
        metrics.put("每会话最后上下文长度均值", new String[]{"meanFinalContextChars"});
        metrics.put("HTTP p50 ms", new String[]{"httpLatencyMillis", "p50"}); metrics.put("HTTP p95 ms", new String[]{"httpLatencyMillis", "p95"});
        metrics.put("主模型 p50 ms", new String[]{"agentUsage", "modelLatencyMillis", "p50"}); metrics.put("主模型 p95 ms", new String[]{"agentUsage", "modelLatencyMillis", "p95"});
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> metric : metrics.entrySet()) {
            Object original = at(baseline, metric.getValue()), next = at(candidate, metric.getValue());
            Double difference = original instanceof Number a && next instanceof Number b ? b.doubleValue() - a.doubleValue() : null;
            Double relative = difference != null && ((Number) original).doubleValue() != 0.0 ? difference / ((Number) original).doubleValue() : null;
            result.put(metric.getKey(), map("baseline", original, "candidate", next, "absoluteDifference", difference, "relativeDifference", relative));
        }
        result.put("裁剪覆盖", map("baseline", at(baseline, "trim", "coverage"), "candidate", at(candidate, "trim", "coverage"), "absoluteDifference", null, "relativeDifference", null));
        result.put("摘要覆盖", map("baseline", at(baseline, "summary", "coverage"), "candidate", at(candidate, "summary", "coverage"), "absoluteDifference", null, "relativeDifference", null));
        return result;
    }

    private static void appendDifferences(StringBuilder out, String title, Map<String, Object> differences) {
        out.append("## ").append(markdown(title)).append("\n\n| 指标 | 基线 | 候选 | 绝对差 | 相对差 |\n|---|---:|---:|---:|---:|\n");
        for (String name : List.of("正式轮数", "错误率", "自动断言通过率", "质量评分覆盖率", "主调用输入 token",
                "主调用缓存 token", "主调用未命中 token", "主调用输出 token", "主调用加权缓存命中率", "主调用已观测合计费用 USD",
                "接入网关的模型调用估算 USD", "裁剪覆盖", "裁剪回收字符（完整）", "摘要覆盖", "摘要事件数",
                "摘要素材字符压缩率", "每会话最后上下文长度均值", "HTTP p50 ms", "HTTP p95 ms")) {
            Map<String, Object> diff = object(differences.get(name));
            if (name.equals("接入网关的模型调用估算 USD") && diff.get("baseline") == null && diff.get("candidate") == null) continue;
            row(out, name, metricDisplay(name, diff.get("baseline")), metricDisplay(name, diff.get("candidate")),
                    differenceDisplay(name, diff.get("absoluteDifference")), percent(diff.get("relativeDifference")));
        }
        out.append('\n');
    }

    private static String transition(Map<String, Object> differences, String name) {
        Map<String, Object> value = object(differences.get(name));
        return metricDisplay(name, value.get("baseline")) + " → " + metricDisplay(name, value.get("candidate"));
    }

    private static void appendConfigurationChanges(StringBuilder out, Map<String, Object> baseline, Map<String, Object> candidate) {
        out.append("\n## 配置变化（运行声明）\n\n| 配置 | 基线 | 候选 |\n|---|---|---|\n");
        row(out, "variant", display(baseline.get("variant")), display(candidate.get("variant")));
        Map<String, Object> before = objectOrEmpty(baseline.get("configuration")), after = objectOrEmpty(candidate.get("configuration"));
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            if (!Objects.equals(before.get(key), after.get(key))) row(out, key, jsonDisplay(before.get(key)), jsonDisplay(after.get(key)));
        }
    }

    private static String metricDisplay(String name, Object value) {
        if (!(value instanceof Number)) return display(value);
        if (name.contains("率") || name.contains("比例")) return percent(value);
        return decimal(value, name.contains("USD") ? 8 : name.contains("ms") || name.contains("均值") ? 2 : 0);
    }

    private static String differenceDisplay(String name, Object value) {
        if (value instanceof Number number && (name.contains("率") || name.contains("比例"))) {
            return decimal(number.doubleValue() * 100, 2) + " 个百分点";
        }
        return metricDisplay(name, value);
    }

    private static Set<String> keySet(Object value) {
        List<Object> values = list(value); Set<String> keys = new TreeSet<>();
        for (Object item : values) require(item instanceof String && !((String) item).isBlank() && keys.add((String) item), "轮键列表含空值/非字符串/重复项");
        return keys;
    }
    private static Map<String, String> arguments(String[] args) {
        Map<String, String> parsed = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index++) {
            String arg = args[index]; require(arg.startsWith("--"), "参数应以 -- 开头: " + arg);
            String key = arg.substring(2), value;
            if ("help".equals(key)) value = "true";
            else { require(index + 1 < args.length, "参数缺少值: " + arg); value = args[++index]; }
            require(parsed.putIfAbsent(key, value) == null, "重复参数: " + arg);
        }
        return parsed;
    }
    private static List<Map<String, Object>> filter(List<Map<String, Object>> turns, String caseId, String phase) {
        return turns.stream().filter(turn -> phase.equals(turn.get("phase")) && (caseId == null || caseId.equals(turn.get("caseId")))).toList();
    }
    static String turnKey(Map<String, Object> turn) {
        String caseId = string(turn.get("caseId")), turnId = string(turn.get("turnId"));
        require(!caseId.isBlank() && !turnId.isBlank() && number(turn.get("repetition"), -1) > 0, "轮必须具有 caseId/repetition/turnId");
        return caseId + "/" + number(turn.get("repetition"), -1) + "/" + turnId;
    }
    private static Map<String, Object> distribution(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        return map("count", values.size(), "mean", values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
                "p50", percentile(sorted, 0.5), "p95", percentile(sorted, 0.95), "method", "nearest-rank", "smallSample", values.size() < 20);
    }
    private static Double percentile(List<Double> sorted, double probability) { return sorted.isEmpty() ? null : sorted.get(Math.max(0, (int) Math.ceil(probability * sorted.size()) - 1)); }
    private static Object at(Map<String, Object> object, String... path) {
        Object value = object;
        for (String key : path) { if (!(value instanceof Map<?, ?> nested)) return null; value = nested.get(key); }
        return value;
    }
    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) result.put((String) pairs[index], pairs[index + 1]);
        return result;
    }
    private static Map<String, Object> object(Object value) { return SimpleJson.object(value); }
    private static Map<String, Object> objectOrEmpty(Object value) { return value instanceof Map<?, ?> ? object(value) : Map.of(); }
    private static List<Object> list(Object value) { return value == null ? List.of() : SimpleJson.array(value); }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static long number(Object value, long fallback) { return value instanceof Number number ? number.longValue() : fallback; }
    private static Long nullableLong(Object value) { Number n = nonnegative(value); return n == null ? null : n.longValue(); }
    private static Double nullableDouble(Object value) { Number n = nonnegative(value); return n == null ? null : n.doubleValue(); }
    private static Number nonnegative(Object value) {
        if (value == null) return null;
        require(value instanceof Number && Double.isFinite(((Number) value).doubleValue()) && ((Number) value).doubleValue() >= 0, "指标应为非负数或 null: " + value);
        return (Number) value;
    }
    private static boolean bool(Object value) { return Boolean.TRUE.equals(value); }
    private static Double ratio(long numerator, long denominator) { return denominator == 0 ? null : (double) numerator / denominator; }
    private static String display(Object value) { return value == null ? "N/A" : String.valueOf(value); }
    private static String decimal(Object value, int digits) { return value instanceof Number number ? String.format(Locale.ROOT, "%." + digits + "f", number.doubleValue()) : display(value); }
    private static String percent(Object value) { return value instanceof Number number ? String.format(Locale.ROOT, "%.2f%%", number.doubleValue() * 100) : "N/A"; }
    private static String jsonDisplay(Object value) { return value instanceof Map<?, ?> || value instanceof List<?> ? SimpleJson.stringify(value) : display(value); }
    private static String markdown(Object value) { return display(value).replace("|", "\\|").replace("\r", " ").replace("\n", " "); }
    private static void row(StringBuilder out, String... cells) {
        out.append('|'); for (String cell : cells) out.append(' ').append(markdown(cell)).append(" |"); out.append('\n');
    }
    private static String csvValue(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        if (value instanceof String && !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    record Run(Path directory, Map<String, Object> metadata, List<Map<String, Object>> turns, List<String> warnings) { }
    private record Usage(String source, String route, String model, Long input, Long cached, Long uncached, Long output,
                         Double inputCost, Double outputCost, Double cost, Double duration, String status) { }
}
