/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * 记忆摘要回归：按剧本在真服务上跑长会话，确认卡一律拒，逐代读库里的摘要下结论
 * 默认实跑；--evaluate <产物目录> 只拿已存的轮次与摘要重判，离线重放的产物也走这条
 */
public final class CompactionRegressionMain {

    // 与 AgentContextCompactor.SUMMARY_NAME 同源，改了那边这里必须同步
    private static final String SUMMARY_NAME = "__compaction_summary__";
    private static final String COMPACTION_SLOT = "AGENT_CONTEXT_COMPACTION";
    // AgentScope 存整份 AgentState 用的键，PgAgentStateStore 原样落进 state_key
    private static final String STATE_KEY = "agent_state";
    private static final int MAX_DENIALS = 3;
    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private CompactionRegressionMain() {
    }

    public static void main(String[] args) {
        try {
            Map<String, String> arguments = parseArguments(args);
            Path suiteDir = Path.of(require(arguments, "suite-dir")).toAbsolutePath().normalize();
            InitializerConfig config = InitializerConfig.load(suiteDir.resolve("regression.properties"));
            CompactionScript script = CompactionScript.load(suiteDir.resolve(config.require("script.file")));
            String evaluate = arguments.get("evaluate");
            Path runDir = evaluate == null
                    ? runLive(suiteDir, config, script, arguments)
                    : Path.of(evaluate).toAbsolutePath().normalize();
            boolean failed = report(runDir, config, script);
            System.exit(failed ? 1 : 0);
        } catch (Exception ex) {
            System.err.println("[compaction] FAILED: " + describe(ex));
            if (Boolean.parseBoolean(System.getenv().getOrDefault("RAGENT_REGRESSION_DEBUG", "false"))) {
                ex.printStackTrace(System.err);
            }
            System.exit(1);
        }
    }

    private static Path runLive(Path suiteDir, InitializerConfig config, CompactionScript script,
                                Map<String, String> arguments) throws Exception {
        Path runDir = suiteDir.resolve("artifacts").resolve(RUN_ID.format(LocalDateTime.now()));
        Files.createDirectories(runDir);
        int sessions = Integer.parseInt(arguments.getOrDefault("sessions", config.get("run.sessions", "2")));
        int limit = Integer.parseInt(arguments.getOrDefault("limit", String.valueOf(script.turns().size())));
        int steadyGenerations = Integer.parseInt(arguments.getOrDefault("steady-generations",
                config.get("run.steady-generations", "0")));
        try (RagentHttpClient http = new RagentHttpClient(config);
             JdbcClient platform = new JdbcClient(config, "database");
             JdbcClient biz = new JdbcClient(config, "biz-database")) {
            String userId = http.login(config.require("auth.username"), config.require("auth.password")).userId();
            if (userId == null || userId.isBlank()) {
                throw new IllegalStateException("登录响应缺少 userId，无法定位会话状态");
            }
            Map<String, Object> facts = new LinkedHashMap<>(preflight(suiteDir, config, platform));
            facts.putAll(businessFacts(config, biz, userId));
            long baseline = writes(biz, userId);
            log("产物目录 " + runDir + "，" + sessions + " 条会话 × " + limit + " 轮");

            ExecutorService pool = Executors.newFixedThreadPool(sessions);
            List<Future<?>> futures = new ArrayList<>();
            for (int number = 1; number <= sessions; number++) {
                Session session = new Session(number, runDir.resolve("session-" + number), script, limit,
                        steadyGenerations, new CompactionSseClient(http, config), platform, config, userId);
                futures.add(pool.submit(() -> {
                    session.run();
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
            pool.shutdown();
            facts.put("newWrites", writes(biz, userId) - baseline);
            Files.writeString(runDir.resolve("facts.json"), SimpleJson.stringify(facts), StandardCharsets.UTF_8);
        }
        return runDir;
    }

    /**
     * 库里在用的人设与数据集文件逐槽位比对，不一致就拒跑：否则结论说的是另一份提示词
     */
    private static Map<String, Object> preflight(Path suiteDir, InitializerConfig config, JdbcClient platform)
            throws Exception {
        if (!config.getBoolean("agent.memory.summary-enabled", true)) {
            throw new IllegalStateException("application.yaml 关掉了 agent.memory.summary-enabled，没有摘要可测");
        }
        String profileName = config.require("bit.profile-name");
        List<List<String>> profiles = platform.queryRows("SELECT id, name FROM t_agent_profile"
                + " WHERE active = 1 AND deleted = 0");
        if (profiles.size() != 1 || !profileName.equals(profiles.get(0).get(1))) {
            throw new IllegalStateException("当前激活的人设不是「" + profileName + "」: " + profiles);
        }
        String profileId = profiles.get(0).get(0);
        Path promptsDir = suiteDir.resolve(config.require("bit.prompts-dir")).normalize();
        Properties manifest = new Properties();
        try (Reader reader = Files.newBufferedReader(promptsDir.resolveSibling("agent-profile.properties"),
                StandardCharsets.UTF_8)) {
            manifest.load(reader);
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("profileId", profileId);
        for (String key : manifest.stringPropertyNames()) {
            if (!key.startsWith("prompt.")) {
                continue;
            }
            String slot = key.substring("prompt.".length());
            String expected = Files.readString(promptsDir.resolveSibling(manifest.getProperty(key).trim()),
                    StandardCharsets.UTF_8);
            List<List<String>> rows = platform.queryRows("SELECT content FROM t_agent_prompt WHERE deleted = 0"
                    + " AND agent_id = " + JdbcClient.literal(profileId) + " AND slot_key = " + JdbcClient.literal(slot));
            String actual = rows.isEmpty() ? null : rows.get(0).get(0);
            if (!expected.equals(actual)) {
                throw new IllegalStateException("库里槽位 " + slot + " 与 " + manifest.getProperty(key)
                        + " 不一致，先用初始化器或接口同步再跑");
            }
            if (COMPACTION_SLOT.equals(slot)) {
                facts.put("compactionPromptMd5", md5(expected));
                facts.put("compactionPromptChars", expected.length());
            }
        }
        if (!facts.containsKey("compactionPromptMd5")) {
            throw new IllegalStateException("数据集没有覆盖 " + COMPACTION_SLOT + "，被测的不是比特严选那份压缩提示词");
        }
        return facts;
    }

    private static Map<String, Object> businessFacts(InitializerConfig config, JdbcClient biz, String userId)
            throws Exception {
        String orderNo = config.require("bit.order-no");
        List<List<String>> order = biz.queryRows("SELECT to_char(receive_time AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD')"
                + " FROM t_order WHERE order_no = " + JdbcClient.literal(orderNo)
                + " AND user_id = " + JdbcClient.literal(userId));
        if (order.isEmpty() || order.get(0).get(0).isEmpty()) {
            throw new IllegalStateException("业务库里没有挂在当前账号名下、已签收的订单 " + orderNo);
        }
        List<String> afterSales = new ArrayList<>();
        for (List<String> row : biz.queryRows("SELECT a.after_sale_no FROM t_after_sale a"
                + " JOIN t_order o ON o.order_no = a.order_no WHERE o.user_id = " + JdbcClient.literal(userId))) {
            afterSales.add(row.get(0));
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("orderNo", orderNo);
        facts.put("receiveDate", order.get(0).get(0));
        facts.put("budget", config.require("bit.budget"));
        facts.put("knownAfterSales", afterSales);
        return facts;
    }

    /**
     * 回归从不批准确认卡，工单与售后单数量开跑前后必须一样
     */
    private static long writes(JdbcClient biz, String userId) throws Exception {
        String user = JdbcClient.literal(userId);
        return biz.queryLong("SELECT (SELECT count(*) FROM t_ticket WHERE user_id = " + user + ")"
                + " + (SELECT count(*) FROM t_after_sale a JOIN t_order o ON o.order_no = a.order_no"
                + " WHERE o.user_id = " + user + ")");
    }

    /**
     * 一条独立会话：逐轮发问、拒卡、等状态落库，再看这一轮有没有长出新的一代摘要
     */
    private static final class Session {

        private final int number;
        private final Path dir;
        private final CompactionScript script;
        private final int limit;
        private final int steadyGenerations;
        private final CompactionSseClient sse;
        private final JdbcClient platform;
        private final String userId;
        private final Duration timeout;
        private final int maxAttempts;
        private final int probeAttempts;
        private final long probeInterval;
        private final long turnInterval;

        Session(int number, Path dir, CompactionScript script, int limit, int steadyGenerations,
                CompactionSseClient sse, JdbcClient platform, InitializerConfig config, String userId) {
            this.number = number;
            this.dir = dir;
            this.script = script;
            this.limit = limit;
            this.steadyGenerations = steadyGenerations;
            this.sse = sse;
            this.platform = platform;
            this.userId = userId;
            this.timeout = Duration.ofSeconds(config.getInt("turn.timeout-seconds", 900));
            this.maxAttempts = Math.max(1, config.getInt("turn.max-attempts", 2));
            this.probeAttempts = Math.max(1, config.getInt("probe.max-attempts", 10));
            this.probeInterval = config.getInt("probe.retry-interval-seconds", 2) * 1000L;
            this.turnInterval = config.getInt("turn.interval-seconds", 1) * 1000L;
        }

        /**
         * 稳态期（hold3 已压出原文）观测够 steadyGenerations 代后直接跳到末轮：再往后的轮次只是重复同一组对比
         */
        void run() throws Exception {
            Files.createDirectories(dir);
            List<CompactionScript.Turn> turns = script.turns().subList(0, limit);
            CompactionScript.Turn last = turns.get(turns.size() - 1);
            int hold3 = script.index("hold3");
            String conversationId = null;
            int seen = 0;
            int steady = 0;
            for (CompactionScript.Turn turn : turns) {
                if (steadyGenerations > 0 && steady >= steadyGenerations && turn != last) {
                    continue;
                }
                String before = conversationId == null ? null : contextJson(conversationId);
                Map<String, Object> outcome = play(turn, conversationId);
                conversationId = (String) outcome.get("conversationId");
                append(dir.resolve("turns.jsonl"), outcome);
                Set<String> context = awaitContext(conversationId, turn.text());
                int through = compactedThrough(context, turn.index());
                boolean firstOfTurn = true;
                for (List<String> row : platform.queryRows("SELECT generation, summary, summary_chars,"
                        + " material_chars, context_chars_before, context_chars_after FROM t_agent_context_compaction"
                        + " WHERE conversation_id = " + JdbcClient.literal(conversationId)
                        + " AND generation > " + seen + " ORDER BY generation")) {
                    seen = Integer.parseInt(row.get(0));
                    if (firstOfTurn && before != null) {
                        saveInput(seen, turn, before);
                    }
                    firstOfTurn = false;
                    if (through >= hold3) {
                        steady++;
                    }
                    Map<String, Object> gen = new LinkedHashMap<>();
                    gen.put("number", seen);
                    gen.put("turn", turn.index());
                    gen.put("compactedThrough", through);
                    gen.put("summaryChars", Integer.parseInt(row.get(2)));
                    gen.put("materialChars", Integer.parseInt(row.get(3)));
                    gen.put("before", Integer.parseInt(row.get(4)));
                    gen.put("after", Integer.parseInt(row.get(5)));
                    gen.put("summary", row.get(1));
                    Files.writeString(dir.resolve("gen-" + seen + ".json"), SimpleJson.stringify(gen), StandardCharsets.UTF_8);
                    Files.writeString(dir.resolve("gen-" + seen + ".md"), row.get(1), StandardCharsets.UTF_8);
                    log("会话 " + number + " 第 " + turn.index() + " 轮压出第 " + seen + " 代：" + row.get(2)
                            + " 字，原文压到第 " + through + " 轮");
                }
                log("会话 " + number + " 第 " + turn.index() + "/" + limit + " 轮 " + outcome.get("status")
                        + " 工具 " + outcome.get("tools") + (((List<?>) outcome.get("cardTools")).isEmpty() ? ""
                        : " 拒卡 " + outcome.get("cardTools")));
                if (steadyGenerations > 0 && steady >= steadyGenerations && turn != last) {
                    log("会话 " + number + " 稳态期已观测到 " + steady + " 代，跳到末轮");
                }
                Thread.sleep(turnInterval);
            }
            Files.writeString(dir.resolve("conversation.txt"), String.valueOf(conversationId), StandardCharsets.UTF_8);
        }

        /**
         * 快照只供离线重放，读失败只少一份重放素材，不许把实跑打断
         */
        private String contextJson(String conversationId) {
            try {
                List<List<String>> rows = platform.queryRows("SELECT (payload->'context')::text FROM t_agent_state"
                        + " WHERE user_id = " + JdbcClient.literal(userId)
                        + " AND session_id = " + JdbcClient.literal(conversationId)
                        + " AND state_key = " + JdbcClient.literal(STATE_KEY));
                return rows.isEmpty() || rows.get(0).get(0).isEmpty() ? null : rows.get(0).get(0);
            } catch (Exception ex) {
                log("会话 " + number + " 发问前读上下文快照失败，这一轮压出的摘要将无法重放: " + describe(ex));
                return null;
            }
        }

        /**
         * 压缩只在一轮开头、用户消息刚追加时触发，那一刻的上下文就是发问前库里的状态加上这一问
         * 离线重放拿它喂生产压缩器；一轮里冒出第二代时没有对应的快照，那一代重放不了
         */
        private void saveInput(int generation, CompactionScript.Turn turn, String before) throws IOException {
            Files.writeString(dir.resolve("gen-" + generation + ".input.json"),
                    "{\"turn\":" + turn.index() + ",\"question\":" + SimpleJson.stringify(turn.text())
                            + ",\"context\":" + before + "}",
                    StandardCharsets.UTF_8);
        }

        /**
         * 确认卡一律拒；拒完模型可能再弹一张，最多拒 MAX_DENIALS 次
         */
        private Map<String, Object> play(CompactionScript.Turn turn, String conversationId) throws Exception {
            CompactionSseClient.Reply reply = null;
            List<String> errors = new ArrayList<>();
            for (int attempt = 1; attempt <= maxAttempts && reply == null; attempt++) {
                try {
                    reply = sse.ask(turn.text(), conversationId, timeout);
                } catch (IOException | IllegalStateException ex) {
                    errors.add("第 " + attempt + " 次发送中断: " + ex.getMessage());
                    // 服务端已经收下这一问就不再重发，重发会让同一句话进两次原文
                    if (conversationId != null && questionStored(conversationId, turn.text())) {
                        break;
                    }
                }
            }
            String resolved = reply == null || reply.conversationId() == null ? conversationId : reply.conversationId();
            if (resolved == null) {
                throw new IllegalStateException("会话 " + number + " 首轮没拿到 conversationId: " + errors);
            }
            Set<String> tools = new LinkedHashSet<>();
            List<String> results = new ArrayList<>();
            List<String> cardTools = new ArrayList<>();
            StringBuilder answer = new StringBuilder();
            String status = reply == null ? "BROKEN" : reply.messageStatus();
            for (int denials = 0; reply != null; denials++) {
                tools.addAll(reply.tools());
                results.addAll(reply.results());
                errors.addAll(reply.errors());
                answer.append(reply.answer());
                status = reply.messageStatus();
                if (reply.confirmMessageId() == null) {
                    break;
                }
                cardTools.addAll(reply.cardTools());
                tools.addAll(reply.cardTools());
                if (denials >= MAX_DENIALS) {
                    errors.add("连拒 " + MAX_DENIALS + " 次仍在弹卡，会话停在待确认");
                    break;
                }
                answer.append("\n[已拒绝确认卡 ").append(reply.cardTools()).append("]\n");
                reply = sse.deny(resolved, reply.confirmMessageId(), timeout);
            }
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("index", turn.index());
            outcome.put("ref", turn.ref());
            outcome.put("conversationId", resolved);
            outcome.put("status", status == null ? "UNKNOWN" : status);
            outcome.put("tools", List.copyOf(tools));
            outcome.put("cardTools", List.copyOf(cardTools));
            outcome.put("errors", List.copyOf(errors));
            outcome.put("answer", answer.toString());
            outcome.put("results", List.copyOf(results));
            return outcome;
        }

        private boolean questionStored(String conversationId, String text) throws Exception {
            return platform.queryLong("SELECT count(*) FROM t_agent_message WHERE role = 'user'"
                    + " AND conversation_id = " + JdbcClient.literal(conversationId)
                    + " AND content = " + JdbcClient.literal(text)) > 0;
        }

        /**
         * 状态落库是流结束后的异步收尾：读到本轮问题才算这一轮的原文已经定型
         */
        private Set<String> awaitContext(String conversationId, String text) throws Exception {
            Set<String> context = Set.of();
            for (int attempt = 0; attempt < probeAttempts; attempt++) {
                context = userTexts(platform, userId, conversationId);
                if (context.contains(text)) {
                    return context;
                }
                Thread.sleep(probeInterval);
            }
            log("会话 " + number + " 状态里一直没读到本轮问题，按现有状态判定: " + text);
            return context;
        }

        /**
         * 压缩只砍前缀：第一句还留在原文里的问题之前的轮次都已经进了摘要
         */
        private int compactedThrough(Set<String> context, int current) {
            for (CompactionScript.Turn turn : script.turns().subList(0, current)) {
                if (context.contains(turn.text())) {
                    return turn.index() - 1;
                }
            }
            return current;
        }
    }

    private static Set<String> userTexts(JdbcClient platform, String userId, String conversationId) throws Exception {
        Set<String> texts = new HashSet<>();
        for (List<String> row : platform.queryRows("SELECT c->>'text' FROM t_agent_state s,"
                + " LATERAL jsonb_array_elements(COALESCE(s.payload->'context', '[]'::jsonb)) m,"
                + " LATERAL jsonb_array_elements(COALESCE(m->'content', '[]'::jsonb)) c"
                + " WHERE s.user_id = " + JdbcClient.literal(userId)
                + " AND s.session_id = " + JdbcClient.literal(conversationId)
                + " AND m->>'role' = 'USER' AND COALESCE(m->>'name', '') <> " + JdbcClient.literal(SUMMARY_NAME)
                + " AND c->>'type' = 'text'")) {
            texts.add(row.get(0).strip());
        }
        return texts;
    }

    private static void append(Path file, Map<String, Object> record) throws IOException {
        Files.writeString(file, SimpleJson.stringify(record) + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    /**
     * 每条会话各判一遍再合并：任一会话 FAIL 即 FAIL，一条会话的结论只算一个样本
     */
    static boolean report(Path runDir, InitializerConfig config, CompactionScript script) throws Exception {
        Map<String, Object> facts = SimpleJson.object(SimpleJson.parse(Files.readString(runDir.resolve("facts.json"))));
        List<String> known = new ArrayList<>();
        SimpleJson.array(facts.get("knownAfterSales")).forEach(item -> known.add(String.valueOf(item)));
        CompactionChecks.Facts checkFacts = new CompactionChecks.Facts(SimpleJson.string(facts, "orderNo"),
                SimpleJson.string(facts, "receiveDate"), SimpleJson.string(facts, "budget"), Set.copyOf(known));
        int newWrites = SimpleJson.integer(facts, "newWrites", -1);
        String replayOf = facts.get("replayOf") == null ? null : String.valueOf(facts.get("replayOf"));
        CompactionChecks.Bounds bounds = new CompactionChecks.Bounds(config.requireInt("bound.min-chars"),
                config.requireInt("bound.max-chars"), config.getDouble("bound.steady-growth-ratio", 0.15),
                config.getInt("bound.steady-growth-slack", 100), config.requireInt("agent.memory.context-window-chars"),
                config.getDouble("bound.max-after-ratio", 0.4), config.getInt("bound.min-generations", 5));

        StringBuilder out = new StringBuilder();
        out.append("# 记忆摘要回归报告\n\n产物 ").append(runDir).append("\n压缩提示词 ")
                .append(facts.get("compactionPromptChars")).append(" 字，md5 ").append(facts.get("compactionPromptMd5"))
                .append("\n业务库新增工单/售后单 ").append(newWrites < 0 ? "未观测" : newWrites).append("\n");
        if (replayOf != null) {
            out.append("\n离线重放自 ").append(replayOf).append("：对话冻结在那次实跑，压缩时机与切点照旧，只换压缩提示词。")
                    .append("只判摘要；主 Agent 弹卡、每轮是否正常结束、业务库写入不出结论，定稿前仍要实跑一次。\n");
        }
        Map<CompactionChecks.Check, List<CompactionChecks.Result>> merged = new EnumMap<>(CompactionChecks.Check.class);
        List<Map<String, Object>> metrics = new ArrayList<>();
        List<Path> sessionDirs;
        try (Stream<Path> stream = Files.list(runDir)) {
            sessionDirs = stream.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("session-")).sorted().toList();
        }
        if (sessionDirs.isEmpty()) {
            out.append("\n结论：无法下结论：产物中没有任何会话目录\n");
            Files.writeString(runDir.resolve("report.md"), out.toString(), StandardCharsets.UTF_8);
            System.out.println(out);
            return true;
        }
        for (Path dir : sessionDirs) {
            List<CompactionChecks.TurnRecord> turns = loadTurns(dir);
            List<CompactionChecks.Generation> gens = loadGenerations(dir, script, turns, checkFacts.orderNo());
            out.append("\n## ").append(dir.getFileName()).append("（").append(turns.size()).append(" 轮，")
                    .append(gens.size()).append(" 代）\n\n| 代 | 轮 | 压到 | 摘要字 | 素材字 | 压缩前 | 压缩后 |\n|---|---|---|---|---|---|---|\n");
            for (CompactionChecks.Generation gen : gens) {
                out.append("| ").append(gen.number()).append(" | ").append(gen.turn()).append(" | ")
                        .append(gen.compactedThrough()).append(" | ").append(gen.summaryChars()).append(" | ")
                        .append(gen.materialChars()).append(" | ").append(gen.before()).append(" | ")
                        .append(gen.after()).append(" |\n");
            }
            List<CompactionChecks.Result> results = CompactionChecks.evaluate(script, turns, gens, bounds, checkFacts,
                    dir == sessionDirs.get(0) ? newWrites : -1, replayOf == null);
            out.append('\n');
            for (CompactionChecks.Result result : results) {
                merged.computeIfAbsent(result.check(), key -> new ArrayList<>()).add(result);
                metrics.add(Map.of("session", dir.getFileName().toString(), "check", result.check().name(),
                        "status", result.status().name(), "observed", result.observed(),
                        "failures", result.failures(), "episodes", result.episodes(),
                        "affectedGenerations", result.affectedGenerations(), "longestRun", result.longestRun()));
                if (result.status() != CompactionChecks.Status.PASS) {
                    out.append("- ").append(result.status()).append(" ").append(result.check().label)
                            .append("：").append(result.detail()).append('\n');
                }
            }
        }

        out.append("\n## 合并结论\n\n摘要错误同时统计独立出现（连续失败段）、受影响代数和最长连续代数。")
                .append("连续失败段是按同一检查的相邻代计算的观测指标，不证明模型只新犯了一次错；措辞变化不会重新计数。")
                .append("主 Agent 行为及代数总量检查不适用，显示 —。\n\n")
                .append("| 结论 | 检查 | 观测 | 独立出现 | 影响代数 | 最长连续 | 说明 |\n|---|---|---|---|---|---|---|\n");
        boolean failed = false;
        boolean uncoveredCore = false;
        boolean warned = false;
        for (Map.Entry<CompactionChecks.Check, List<CompactionChecks.Result>> entry : merged.entrySet()) {
            CompactionChecks.Check check = entry.getKey();
            CompactionChecks.Status status = mergedStatus(entry.getValue());
            int observed = entry.getValue().stream().mapToInt(CompactionChecks.Result::observed).sum();
            int episodes = entry.getValue().stream().mapToInt(CompactionChecks.Result::episodes).sum();
            int affected = entry.getValue().stream().mapToInt(CompactionChecks.Result::affectedGenerations).sum();
            int longest = entry.getValue().stream().mapToInt(CompactionChecks.Result::longestRun).max().orElse(0);
            boolean perGeneration = check != CompactionChecks.Check.GENERATIONS
                    && check != CompactionChecks.Check.NO_CARD_ON_HOLD && check != CompactionChecks.Check.CARD_ON_RELEASE
                    && check != CompactionChecks.Check.TURN_OK && check != CompactionChecks.Check.NO_WRITE;
            failed |= status == CompactionChecks.Status.FAIL;
            warned |= status == CompactionChecks.Status.WARN;
            uncoveredCore |= status == CompactionChecks.Status.UNCOVERED && check.core;
            String detail = entry.getValue().stream().filter(result -> result.status() == status)
                    .map(CompactionChecks.Result::detail).findFirst().orElse("");
            out.append("| ").append(status).append(" | ").append(check.label).append(check.hard ? "" : "（软）")
                    .append(" | ").append(observed).append(" | ").append(perGeneration ? episodes : "—")
                    .append(" | ").append(perGeneration ? affected : "—")
                    .append(" | ").append(perGeneration ? longest : "—")
                    .append(" | ").append(detail.replace("|", "\\|").replace("\n", " "))
                    .append(" |\n");
        }
        String verdict = failed ? "不通过：有硬性检查失败"
                : uncoveredCore ? "无法下结论：有核心检查一次都没观测到"
                : warned ? "通过，但有软性告警" : "通过";
        out.append("\n结论：").append(verdict).append('\n');
        Files.writeString(runDir.resolve("report.md"), out.toString(), StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("metrics.json"), SimpleJson.stringify(metrics), StandardCharsets.UTF_8);
        System.out.println(out);
        return failed || uncoveredCore;
    }

    private static CompactionChecks.Status mergedStatus(List<CompactionChecks.Result> results) {
        for (CompactionChecks.Status status : List.of(CompactionChecks.Status.FAIL, CompactionChecks.Status.WARN)) {
            if (results.stream().anyMatch(result -> result.status() == status)) {
                return status;
            }
        }
        return results.stream().allMatch(result -> result.status() == CompactionChecks.Status.UNCOVERED)
                ? CompactionChecks.Status.UNCOVERED : CompactionChecks.Status.PASS;
    }

    private static List<CompactionChecks.TurnRecord> loadTurns(Path dir) throws IOException {
        List<CompactionChecks.TurnRecord> turns = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("turns.jsonl"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> record = SimpleJson.object(SimpleJson.parse(line));
            turns.add(new CompactionChecks.TurnRecord(SimpleJson.integer(record, "index", 0),
                    SimpleJson.string(record, "ref"), strings(record.get("tools")), strings(record.get("results")),
                    strings(record.get("cardTools")), strings(record.get("errors")), SimpleJson.string(record, "status")));
        }
        return turns;
    }

    /**
     * 被压出原文的轮次决定哪些规则生效：标记轮进了摘要，摘要才有义务记住它
     */
    private static List<CompactionChecks.Generation> loadGenerations(Path dir, CompactionScript script,
                                                                     List<CompactionChecks.TurnRecord> turns,
                                                                     String orderNo) throws IOException {
        List<Map<String, Object>> raw = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().matches("gen-\\d+\\.json")).toList()) {
                raw.add(SimpleJson.object(SimpleJson.parse(Files.readString(file, StandardCharsets.UTF_8))));
            }
        }
        raw.sort((left, right) -> Integer.compare(SimpleJson.integer(left, "number", 0),
                SimpleJson.integer(right, "number", 0)));
        List<CompactionChecks.Generation> gens = new ArrayList<>();
        for (Map<String, Object> gen : raw) {
            int through = SimpleJson.integer(gen, "compactedThrough", 0);
            Set<String> refs = new HashSet<>();
            script.refs().forEach((ref, index) -> {
                if (index <= through) {
                    refs.add(ref);
                }
            });
            // 订单列表只有单号和下单时间，查到这笔订单的详情（带签收时间）之后才要求摘要留下单号
            boolean orderQueried = turns.stream().filter(turn -> turn.index() <= through)
                    .flatMap(turn -> turn.results().stream())
                    .anyMatch(result -> result.startsWith("query_order\n") && result.contains("【订单 " + orderNo + "】")
                            && result.contains("签收时间"));
            gens.add(new CompactionChecks.Generation(SimpleJson.integer(gen, "number", 0),
                    SimpleJson.integer(gen, "turn", 0), through, new CompactionSummary(SimpleJson.string(gen, "summary")),
                    SimpleJson.integer(gen, "summaryChars", 0), SimpleJson.integer(gen, "materialChars", 0),
                    SimpleJson.integer(gen, "before", 0), SimpleJson.integer(gen, "after", 0),
                    Set.copyOf(refs), orderQueried));
        }
        return gens;
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value != null) {
            SimpleJson.array(value).forEach(item -> result.add(String.valueOf(item)));
        }
        return List.copyOf(result);
    }

    private static String md5(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static synchronized void log(String message) {
        System.out.println("[compaction] " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                + " " + message);
    }

    private static String require(Map<String, String> arguments, String name) {
        String value = arguments.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少参数 --" + name);
        }
        return value;
    }

    private static Map<String, String> parseArguments(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index++) {
            String token = args[index];
            if (!token.startsWith("--") || index + 1 >= args.length) {
                throw new IllegalArgumentException("参数形如 --name value: " + token);
            }
            result.put(token.substring(2), args[++index].trim());
        }
        return result;
    }

    private static String describe(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank()
                ? ex.getClass().getName() + "（服务或数据库未启动时通常是这个）"
                : message;
    }
}
