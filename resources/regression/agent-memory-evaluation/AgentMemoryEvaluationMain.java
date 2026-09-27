/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0. */
package com.nageoffer.ai.ragent.initializer;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 对独立会话执行选中的数据集，保留逐轮证据；不改变服务策略、预算或运行参数 */
public final class AgentMemoryEvaluationMain {
    private static final Pattern TRIM = Pattern.compile("上下文裁剪完成, 总字符: (\\d+) -> (\\d+), 命中消息: (\\d+), 工具结果: (\\d+)");

    private AgentMemoryEvaluationMain() { }

    public static void main(String[] args) {
        try { execute(arguments(args)); }
        catch (Exception e) {
            System.err.println("[evaluation] FAILED: " + e.getClass().getSimpleName() + ": " + safeMessage(e));
            System.exit(1);
        }
    }

    private static void execute(Map<String, String> args) throws Exception {
        Path suite = Path.of(args.getOrDefault("suite-dir", "resources/regression/agent-memory-evaluation")).toAbsolutePath();
        Path dataset = Path.of(args.getOrDefault("dataset", suite.resolve("datasets/bit-selection.json").toString())).toAbsolutePath();
        List<EvaluationDataset.Case> cases = EvaluationDataset.select(EvaluationDataset.load(dataset),
                args.getOrDefault("cases", ""), args.getOrDefault("tags", ""), nonnegative(args, "limit", 0));
        int repetitions = positive(args, "repetitions", 1);
        List<String> plannedKeys = new ArrayList<>();
        for (int r = 1; r <= repetitions; r++) for (EvaluationDataset.Case c : cases) {
            if (c.turns().get(0).question().length() + 65 > 500) {
                throw new IllegalArgumentException("首轮需给测试标记留 65 字符，不能超过 435 字符: " + c.id());
            }
            for (EvaluationDataset.Turn t : c.turns()) plannedKeys.add(c.id() + "/" + r + "/" + t.id());
        }
        Map<String, Object> run = new LinkedHashMap<>(EvaluationDataset.manifest(dataset, cases));
        run.put("schemaVersion", 1);
        run.put("runId", UUID.randomUUID().toString());
        run.put("variant", args.getOrDefault("variant", "current"));
        run.put("datasetHash", sha(Files.readAllBytes(dataset)));
        run.put("selectionHash", EvaluationDataset.fingerprint(cases));
        run.put("selectedCaseIds", cases.stream().map(EvaluationDataset.Case::id).toList());
        run.put("repetitions", repetitions);
        run.put("plannedTurns", plannedKeys.size());
        run.put("plannedTurnKeys", plannedKeys);
        run.put("compiledHarnessSha256", harnessFingerprint());
        if (flag(args, "dry-run")) {
            run.put("status", "DRY_RUN");
            System.out.println(SimpleJson.stringify(run));
            return;
        }
        Path output = Path.of(required(args, "output-dir")).toAbsolutePath();
        Path config = Path.of(args.getOrDefault("config", suite.resolve("regression.properties").toString())).toAbsolutePath();
        Path usageLog = optionalPath(args, "usage-log"), serviceLog = optionalPath(args, "service-log");
        if ((usageLog != null || serviceLog != null) && !flag(args, "isolated")) {
            throw new IllegalArgumentException("日志按时间归属，需 --isolated true 确认测试实例无其他流量");
        }
        for (Path path : new Path[]{usageLog, serviceLog}) if (path != null && !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("日志文件不存在: " + path);
        }
        String gatewayScope = args.getOrDefault("gateway-scope", "observed");
        if (!Set.of("observed", "complete").contains(gatewayScope)) throw new IllegalArgumentException("gateway-scope 只能是 observed 或 complete");
        long interval = nonnegative(args, "interval-ms", 2000);
        Files.createDirectories(output);
        Files.createFile(output.resolve("turns.jsonl"));
        Files.copy(dataset, output.resolve("dataset.source.json"));
        List<Map<String, Object>> expanded = new ArrayList<>();
        for (EvaluationDataset.Case c : cases) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.id()); item.put("tags", c.tags()); item.put("description", c.description());
            List<Map<String, Object>> turns = new ArrayList<>();
            for (EvaluationDataset.Turn t : c.turns()) {
                Map<String, Object> turn = new LinkedHashMap<>();
                turn.put("id", t.id()); turn.put("question", t.question()); turn.put("phase", t.phase());
                turn.put("expectAll", t.expectAll()); turn.put("expectAny", t.expectAny()); turn.put("forbidAny", t.forbidAny());
                turn.put("expectTools", t.expectTools()); turn.put("forbidTools", t.forbidTools());
                turns.add(turn);
            }
            item.put("turns", turns); expanded.add(item);
        }
        write(output.resolve("dataset.selected.json"), expanded);
        List<String> completedKeys = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (usageLog == null) warnings.add("只采集 state 中主 Agent 已观测用量；摘要、后台和已移除调用费用未知，不能比较全生命周期费用");
        else warnings.add("用量日志按时间归属；完整范围依赖独占测试实例及全部模型供应商均接入网关的声明");
        run.put("warnings", warnings); run.put("completedTurnKeys", completedKeys); run.put("completedTurns", 0);
        run.put("startedAt", Instant.now().toString()); run.put("status", "RUNNING");
        run.put("usageScope", usageLog == null ? "state" : "gateway");
        run.put("gatewayScopeDeclared", gatewayScope); run.put("intervalMillis", interval);
        Map<String, Object> prices = args.containsKey("prices")
                ? SimpleJson.object(SimpleJson.parse(Files.readString(Path.of(args.get("prices"))))) : Map.of();
        if (!prices.isEmpty() && !"USD".equals(prices.get("currency"))) throw new IllegalArgumentException("价格文件 currency 必须为 USD");
        run.put("prices", prices.getOrDefault("models", Map.of()));
        run.put("pricingDate", prices.getOrDefault("date", "unspecified"));
        run.put("pricingSource", prices.getOrDefault("source", "unspecified"));
        write(output.resolve("run.json"), run);
        Exception failure = null;
        long[] usageCursor = {size(usageLog)};
        try (RegressionContext context = RegressionContext.load(new String[]{"--suite-dir", suite.toString(), "--config", config.toString()});
             JdbcClient jdbc = new JdbcClient(context.config(), "database")) {
            Map<String, Object> declared = new LinkedHashMap<>();
            for (String key : List.of("agent.memory.context-window-chars", "agent.memory.summary-enabled", "agent.memory.long-term-enabled",
                    "agent.chat.provider", "agent.chat.model")) {
                declared.put(key, context.config().get(key, "unknown"));
            }
            context.config().require("agent.chat.model");
            declared.put("source", "local configuration, not live JVM verification");
            declared.put("revisionDeclared", args.getOrDefault("revision", "unspecified"));
            declared.put("isolatedDeclared", flag(args, "isolated"));
            declared.put("isolated", flag(args, "isolated"));
            declared.put("gatewayScope", gatewayScope);
            if (usageLog != null && "complete".equals(gatewayScope)
                    && context.config().getBoolean("agent.memory.long-term-enabled", true)) {
                throw new IllegalArgumentException("完整短期/中期费用模式要求测试服务关闭长期记忆，并在测评配置中同步声明 agent.memory.long-term-enabled=false；异步后台调用无法按轮归属");
            }
            run.put("configuration", declared);
            if (context.config().getBoolean("agent.memory.long-term-enabled", true)) {
                warnings.add("长期记忆开启；跨用例或重复运行可能受用户历史事实影响，质量结果不代表隔离的短期/中期能力");
            }
            write(output.resolve("run.json"), run);
            context.login();
            boolean audit = jdbc.queryLong("SELECT CASE WHEN to_regclass('t_agent_context_compaction') IS NULL THEN 0 ELSE 1 END") == 1;
            if (!audit) warnings.add("摘要审计表不可用；只记录 state 中观察到的摘要版本，空间减量不完整");
            for (int r = 1; r <= repetitions; r++) for (EvaluationDataset.Case c : cases) {
                String session = null;
                String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                Path caseDir = output.resolve(c.id() + "-r" + r); Files.createDirectories(caseDir);
                for (EvaluationDataset.Turn t : c.turns()) {
                    if (interval > 0) Thread.sleep(interval);
                    String question = session == null ? "MEMORY_EVALUATION " + nonce
                            + "（合成评测会话，不要存入长期记忆）\n" + t.question() : t.question();
                    session = turn(context, jdbc, t, c.id(), r, session, question, audit, output, caseDir,
                            usageLog, serviceLog, gatewayScope, run, completedKeys, usageCursor);
                }
            }
            if (usageLog != null && usageCursor[0] != size(usageLog)) {
                throw new IllegalStateException("最后一轮之后还有网关调用，未能完整归属；保留原日志并拒绝标记测评完成");
            }
            run.put("status", "COMPLETE");
        } catch (Exception e) {
            run.put("status", "FAILED"); run.put("failureClass", e.getClass().getName()); failure = e;
        } finally {
            run.put("finishedAt", Instant.now().toString());
            write(output.resolve("run.json"), run);
        }
        EvaluationReportMain.main(new String[]{"--run-dir", output.toString()});
        if (failure != null) throw failure;
        System.out.println("[evaluation] COMPLETE " + output.resolve("report.md"));
    }

    private static String turn(RegressionContext context, JdbcClient jdbc, EvaluationDataset.Turn t, String caseId,
                               int repetition, String session, String question, boolean audit, Path output, Path caseDir,
                               Path usageLog, Path serviceLog, String scope, Map<String, Object> run, List<String> completed, long[] usageCursor) throws Exception {
        EvaluationState before = EvaluationState.read(jdbc, context.userId(), session, audit);
        write(caseDir.resolve(t.id() + ".before.json"), before.payload());
        long usageOffset = usageCursor[0], logOffset = size(serviceLog), startNanos = System.nanoTime();
        Instant started = Instant.now();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("caseId", caseId); row.put("repetition", repetition); row.put("turnId", t.id()); row.put("phase", t.phase());
        row.put("startedAt", started.toString()); row.put("question", question); row.put("before", before.metrics());
        System.out.println("[evaluation] START " + caseId + "/" + repetition + "/" + t.id());
        try {
            AgentChatClient.AgentTurnResult answer = ask(context, question, session);
            row.put("durationMillis", (System.nanoTime() - startNanos) / 1_000_000L);
            session = answer.conversationId();
            if (session == null || session.isBlank()) throw new IllegalStateException("SSE 没有 conversationId");
            row.put("conversationId", session); row.put("taskId", answer.taskId());
            row.put("answer", answer.answer()); row.put("tools", answer.tools()); row.put("thinkingChars", answer.thinkChars());
            EvaluationState after = awaitState(context, jdbc, session, question, before, audit);
            write(caseDir.resolve(t.id() + ".after.json"), after.payload());
            List<Map<String, Object>> events = after.compactionsSince(before);
            List<Map<String, Object>> stateUsage = after.usagesSince(before, context.config().require("agent.chat.model"));
            GatewayBatch batch = usageLog == null ? null : gatewayRows(usageLog, usageOffset, started);
            List<Map<String, Object>> usages = batch == null ? stateUsage : batch.rows();
            Map<String, Object> trim = after.trimSince(before);
            if (serviceLog != null) {
                Map<String, Object> fromLog = logTrim(serviceLog, logOffset, caseDir.resolve(t.id() + ".trim.log"));
                long observed = ((Number) trim.get("observedReclaimedChars")).longValue();
                if (((Number) fromLog.get("newBlocks")).intValue() < ((Number) trim.get("newBlocks")).intValue()
                        || ((Number) fromLog.get("reclaimedChars")).longValue() < observed) {
                    trim.put("complete", false); trim.put("reclaimedChars", null); trim.put("logMismatch", true);
                } else if (Boolean.TRUE.equals(trim.get("complete")) || ((Number) fromLog.get("newBlocks")).intValue() > 0) {
                    trim = fromLog;
                }
            }
            List<String> warnings = new ArrayList<>();
            boolean complete = usageLog != null && "complete".equals(scope)
                    && usages.size() >= stateUsage.size() + Math.max(events.size(), after.newSummaryVersions(before)) && !stateUsage.isEmpty()
                    && usages.stream().allMatch(u -> Boolean.TRUE.equals(u.get("usageAvailable")) && Boolean.TRUE.equals(u.get("cacheKnown"))
                    && "success".equalsIgnoreCase(EvaluationState.string(u, "status")));
            if (!complete) warnings.add("完整调用费用未覆盖；只汇总已观测用量，未知项不能当零");
            if (!Boolean.TRUE.equals(trim.get("complete"))) warnings.add("state 经摘要删除，无法恢复完整裁剪量；独占实例可补采服务日志");
            if (after.newSummaryVersions(before) > events.size()) warnings.add("摘要已生效但审计事件缺失；摘要空间减量只有下界");
            row.put("status", "OK"); row.put("conversationId", session); row.put("taskId", answer.taskId());
            row.put("answer", answer.answer()); row.put("tools", answer.tools()); row.put("thinkingChars", answer.thinkChars());
            row.put("after", after.metrics()); row.put("quality", t.evaluate(answer.answer(), answer.tools()));
            row.put("trim", trim); row.put("compactions", events); row.put("summaryVersionsObserved", after.newSummaryVersions(before));
            row.put("usages", usages); row.put("stateUsages", stateUsage); row.put("usageComplete", complete); row.put("warnings", warnings);
            if (batch != null) usageCursor[0] = batch.nextOffset();
            System.out.println("[evaluation] DONE chars=" + after.metrics().get("contextChars") + " trim=" + trim.get("newBlocks")
                    + " summaries=" + events.size() + " calls=" + usages.size());
            return session;
        } catch (Exception e) {
            row.put("status", "FAILED"); row.put("failureClass", e.getClass().getName()); row.put("usageComplete", false);
            row.putIfAbsent("durationMillis", (System.nanoTime() - startNanos) / 1_000_000L);
            captureFailedUsage(row, usageLog, usageOffset, started);
            throw e;
        } finally {
            row.put("finishedAt", Instant.now().toString());
            append(output.resolve("turns.jsonl"), row);
            completed.add(caseId + "/" + repetition + "/" + t.id());
            run.put("completedTurns", completed.size());
            write(output.resolve("run.json"), run);
        }
    }

    static void captureFailedUsage(Map<String, Object> row, Path usageLog, long usageOffset, Instant started) {
        if (usageLog == null) return;
        try {
            row.put("usages", gatewayRows(usageLog, usageOffset, started).rows());
        } catch (Exception observationFailure) {
            // 采集失败不能盖掉原始请求失败；原日志仍保留在网关输出位置。
            row.put("warnings", List.of("失败轮网关用量未完整读取，缺失费用未知（"
                    + observationFailure.getClass().getSimpleName() + "）"));
        }
    }

    private static AgentChatClient.AgentTurnResult ask(RegressionContext context, String question, String session) throws Exception {
        var executor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "evaluation-http"); t.setDaemon(true); return t; });
        var future = executor.submit(() -> context.chat().ask(question, session, context.turnTimeout()));
        try { return future.get(context.turnTimeout().toMillis(), TimeUnit.MILLISECONDS); }
        finally { future.cancel(true); executor.shutdownNow(); }
    }

    private static EvaluationState awaitState(RegressionContext context, JdbcClient jdbc, String session, String question,
                                               EvaluationState before, boolean audit) throws Exception {
        int attempts = Math.max(1, context.config().getInt("probe.max-attempts", 8));
        for (int i = 0; i < attempts; i++) {
            EvaluationState after = EvaluationState.read(jdbc, context.userId(), session, audit);
            Set<String> added = new HashSet<>(after.usageIds()); added.removeAll(before.usageIds());
            if (after.containsQuestion(question) && !added.isEmpty()) return after;
            if (i + 1 < attempts) Thread.sleep(Math.max(1, context.config().getInt("probe.retry-interval-seconds", 1)) * 1000L);
        }
        throw new IllegalStateException("没有读到本轮问题和新的 assistant usage，拒绝使用旧 state");
    }

    private record GatewayBatch(List<Map<String, Object>> rows, long nextOffset) { }

    private static GatewayBatch gatewayRows(Path log, long offset, Instant start) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        String data = tail(log, offset);
        if (!data.isEmpty() && !data.endsWith("\n")) throw new IllegalStateException("网关用量行尚未写完，拒绝计量不完整 JSON");
        for (String line : data.split("\\R")) {
            if (line.isBlank()) continue;
            Map<String, Object> row = new LinkedHashMap<>(SimpleJson.object(SimpleJson.parse(line)));
            if (Instant.parse(EvaluationState.string(row, "startedAt")).isBefore(start)) {
                throw new IllegalStateException("网关有跨轮或外部请求，不能可靠归属费用");
            }
            String id = EvaluationState.string(row, "id");
            if (id.isBlank() || !ids.add(id)) throw new IllegalStateException("网关请求 ID 缺失或重复");
            row.put("source", "gateway"); rows.add(row);
        }
        return new GatewayBatch(rows, offset + data.getBytes(StandardCharsets.UTF_8).length);
    }

    private static Map<String, Object> logTrim(Path log, long offset, Path evidence) throws Exception {
        long reclaimed = 0; int blocks = 0; List<String> lines = new ArrayList<>();
        for (String line : tail(log, offset).split("\\R")) {
            Matcher match = TRIM.matcher(line);
            if (match.find()) {
                reclaimed += Long.parseLong(match.group(1)) - Long.parseLong(match.group(2));
                blocks += Integer.parseInt(match.group(4)); lines.add(match.group());
            }
        }
        Files.write(evidence, lines, StandardCharsets.UTF_8);
        return Map.of("newBlocks", blocks, "reclaimedChars", reclaimed, "complete", true, "source", "isolated-service-log");
    }

    private static String tail(Path path, long offset) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long length = file.length() - offset;
            if (length < 0 || length > 16 * 1024 * 1024) throw new IllegalStateException("日志轮转或窗口过大，拒绝不完整计量");
            byte[] bytes = new byte[(int) length]; file.seek(offset); file.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static long size(Path path) throws Exception { return path == null ? 0 : Files.size(path); }
    static Map<String, Object> harnessFingerprint() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Class<?> type : List.of(AgentMemoryEvaluationMain.class, EvaluationDataset.class, EvaluationState.class,
                EvaluationReportMain.class, EvaluationUsageGatewayMain.class, AgentChatClient.class,
                InitializerConfig.class, ApplicationYamlConfig.class, RegressionContext.class,
                RagentHttpClient.class, JdbcClient.class, SimpleJson.class)) {
            fingerprintClass(type, result);
        }
        return result;
    }

    private static void fingerprintClass(Class<?> type, Map<String, Object> result) throws Exception {
        String name = type.getName().substring(type.getPackageName().length() + 1);
        try (var stream = type.getResourceAsStream(name + ".class")) {
            if (stream == null) throw new IllegalStateException("找不到测评代码字节: " + name);
            result.put(name, sha(stream.readAllBytes()));
        }
        for (Class<?> nested : type.getDeclaredClasses()) fingerprintClass(nested, result);
    }

    private static void write(Path path, Object data) throws Exception { Files.writeString(path, SimpleJson.stringify(data) + "\n", StandardCharsets.UTF_8); }
    private static void append(Path path, Object data) throws Exception { Files.writeString(path, SimpleJson.stringify(data) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND); }
    private static String sha(byte[] data) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
    private static Path optionalPath(Map<String, String> args, String key) { return args.containsKey(key) ? Path.of(args.get(key)).toAbsolutePath() : null; }
    private static boolean flag(Map<String, String> args, String key) { return Boolean.parseBoolean(args.getOrDefault(key, "false")); }
    private static String required(Map<String, String> args, String key) {
        String value = args.get(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少 --" + key); return value;
    }
    private static int positive(Map<String, String> args, String key, int fallback) {
        int value = nonnegative(args, key, fallback); if (value == 0) throw new IllegalArgumentException(key + " 须大于 0"); return value;
    }
    private static int nonnegative(Map<String, String> args, String key, int fallback) {
        int value = Integer.parseInt(args.getOrDefault(key, String.valueOf(fallback)));
        if (value < 0) throw new IllegalArgumentException(key + " 不能小于 0"); return value;
    }
    private static Map<String, String> arguments(String[] args) {
        Set<String> allowed = Set.of("suite-dir", "dataset", "cases", "tags", "limit", "repetitions", "dry-run", "output-dir", "config",
                "usage-log", "service-log", "isolated", "gateway-scope", "interval-ms", "variant", "prices", "revision");
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || !allowed.contains(args[i].substring(2)) || i + 1 >= args.length
                    || result.put(args[i].substring(2), args[i + 1]) != null) throw new IllegalArgumentException("未知、重复或缺值参数: " + args[i]);
        }
        for (String key : List.of("dry-run", "isolated")) if (result.containsKey(key)
                && !Set.of("true", "false").contains(result.get(key))) throw new IllegalArgumentException(key + " 只能是 true/false");
        return result;
    }
    private static String safeMessage(Exception e) {
        return e instanceof IllegalArgumentException || e instanceof IllegalStateException ? String.valueOf(e.getMessage())
                : "检查服务、配置及本次输出目录；连接异常正文未写入报告";
    }
}
