/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 在线审计：真实 HTTP 对话，只读观察数据库，用户由接口新建、互相隔离
 */
public final class MemoryAuditMain {
    public static void main(String[] args) throws Exception {
        int code = execute(args);
        if (code != 0) System.exit(code);
    }

    private static int execute(String[] args) throws Exception {
        Request request = Request.parse(args); // Validate all CLI inputs before login or user creation.
        InitializerConfig config = InitializerConfig.load(Path.of(args[0]));
        Timing timing = Timing.from(config);
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        String mode = request.mode();
        try (RagentHttpClient http = new RagentHttpClient(config); JdbcClient jdbc = new JdbcClient(config, "database")) {
            if (mode.equals("env")) {
                http.login(config.require("auth.username"), config.require("auth.password"));
                Map<String, Object> env = new LinkedHashMap<>();
                env.put("observedAt", Instant.now().toString());
                env.put("configurationScope", "local yaml declaration; not runtime override verification");
                for (String key : List.of("server.base-url", "execution.engine-type", "agent.chat.provider", "agent.chat.model",
                        "agent.memory.context-window-chars", "agent.memory.summary-enabled", "agent.memory.long-term-enabled")) {
                    env.put(key, config.get(key, "unknown"));
                }
                env.put("databaseTime", jdbc.queryRows("SELECT CURRENT_TIMESTAMP::text"));
                env.put("profiles", jdbc.queryRows("SELECT id, name, active::text, builtin::text FROM t_agent_profile WHERE deleted = 0 AND (active = 1 OR builtin = 1)"));
                try (RedisRespClient redis = new RedisRespClient(config)) {
                    Object cached = redis.command("GET", "ragent:agent:resolved-prompts:v2");
                    if (cached != null) {
                        Map<String,Object> prompts = SimpleJson.object(SimpleJson.parse(cached.toString()));
                        Map<String,Object> selected = new LinkedHashMap<>();
                        for (String slot : List.of("AGENT_CONTEXT_COMPACTION", "AGENT_MEMORY_EXTRACTION", "AGENT_MEMORY_TOOL_DESCRIPTION")) selected.put(slot, prompts.get(slot));
                        write(out.resolve("resolved-memory-prompts.json"), selected);
                        env.put("promptCachePresent", true);
                    } else env.put("promptCachePresent", false);
                }
                write(out.resolve("environment.json"), env);
                System.out.println(SimpleJson.stringify(env));
                return 0;
            }
            String identity = request.identity();
            Path secretDir = out.resolve(".credentials");
            Files.createDirectories(secretDir);
            Files.setPosixFilePermissions(secretDir, PosixFilePermissions.fromString("rwx------"));
            Path identityFile = secretDir.resolve(identity + ".json");
            if (mode.equals("create")) {
                requireUnusedIdentity(out, identity);
                Set<String> occupiedNames = new HashSet<>();
                // 逻辑删除的用户名仍占着唯一键，也要避开
                for (List<String> row : jdbc.queryRows("SELECT username FROM t_user")) occupiedNames.add(row.get(0));
                String username = newUsername(occupiedNames);
                http.login(config.require("auth.username"), config.require("auth.password"));
                String password = UUID.randomUUID().toString();
                Object userId = http.postJson("/users", Map.of("username", username, "password", password, "role", "user"));
                Files.createFile(identityFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                write(identityFile, Map.of("username", username, "password", password, "userId", userId));
                Map<String,Object> publicIdentity = Map.of("label", identity, "username", username, "userId", userId, "createdAt", Instant.now().toString());
                write(out.resolve(identity + "-identity.json"), publicIdentity);
                System.out.println(SimpleJson.stringify(publicIdentity));
                return 0;
            }
            Map<String,Object> account = SimpleJson.object(SimpleJson.parse(Files.readString(identityFile)));
            Path sessionsFile = out.resolve(identity + "-sessions.json");
            Map<String,Object> sessions = Files.exists(sessionsFile)
                    ? SimpleJson.object(SimpleJson.parse(Files.readString(sessionsFile))) : new LinkedHashMap<>();
            if (mode.equals("export-summary") && (!(sessions.get(request.session()) instanceof String value) || value.isBlank())) {
                throw new IllegalArgumentException("unknown session: " + request.session());
            }
            String userId = http.login(account.get("username").toString(), account.get("password").toString()).userId();
            if (!userId.equals(account.get("userId").toString())) throw new IllegalStateException("identity mismatch");
            if (mode.equals("snapshot")) {
                Map<String,Object> observed = observe(jdbc, userId, sessions);
                write(out.resolve(identity + "-latest.json"), observed);
                System.out.println(SimpleJson.stringify(observed));
                return 0;
            }
            if (mode.equals("export-summary")) {
                Object conversationId = sessions.get(request.session());
                MemoryAuditSummary.export(jdbc, userId, conversationId.toString(), out, identity);
                return 0;
            }
            String conversationId = (String) sessions.get(request.session());
            return runTurn(out, request, sessions, timing,
                    () -> observe(jdbc, userId, sessions),
                    () -> new AgentChatClient(http, config).ask(request.question(), conversationId,
                            Duration.ofMillis(timing.chatTimeoutMillis())));
        }
    }

    static String newUsername(Set<String> occupiedNames) {
        for (String surname : List.of("smith", "johnson", "brown", "wilson", "taylor", "anderson", "thomas", "moore", "martin", "clark")) {
            for (String givenName : List.of("james", "emma", "oliver", "emily", "henry", "alice", "jack", "grace", "daniel", "lucy",
                    "michael", "sarah", "david", "anna", "william", "sophie", "thomas", "chloe", "george", "charlotte")) {
                String username = givenName + surname;
                if (!occupiedNames.contains(username)) return username;
            }
        }
        throw new IllegalStateException("英文姓名已全部占用，请补充姓名候选后重试");
    }

    static void requireUnusedIdentity(Path out, String identity) {
        List<Path> evidence = List.of(out.resolve(".credentials").resolve(identity + ".json"),
                out.resolve(identity + "-identity.json"), out.resolve(identity + "-sessions.json"),
                out.resolve(identity + "-turns.jsonl"), out.resolve(identity + "-latest.json"),
                out.resolve(identity + "-context-evidence.json"));
        if (evidence.stream().anyMatch(Files::exists)) {
            throw new IllegalStateException("identity already has credentials or evidence; use a new output directory for a fresh run, or ask/snapshot to continue the old run");
        }
    }

    record Request(String mode, String identity, String session, String question, boolean requireExtraction) {
        static Request parse(String[] args) throws Exception {
            String usage = "config output-dir env | create <identity> | snapshot <identity> | "
                    + "export-summary <identity> <session> | ask <identity> <session> <question-file> [--require-extraction]";
            if (args.length < 3) throw new IllegalArgumentException(usage);
            String mode = args[2];
            int expected = switch (mode) {
                case "env" -> 3;
                case "create", "snapshot" -> 4;
                case "export-summary" -> 5;
                case "ask" -> 6;
                default -> throw new IllegalArgumentException(usage);
            };
            boolean required = mode.equals("ask") && args.length == 7 && args[6].equals("--require-extraction");
            if (args.length != expected && !(required && args.length == 7)) throw new IllegalArgumentException(usage);
            String identity = expected >= 4 ? args[3] : null;
            String session = expected >= 5 ? args[4] : null;
            if (identity != null && !identity.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("invalid identity label");
            if (session != null && !session.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("invalid session label");
            String question = mode.equals("ask") ? Files.readString(Path.of(args[5])).strip() : null;
            if (question != null && (question.isBlank() || question.codePointCount(0, question.length()) > 300)) {
                throw new IllegalArgumentException("question must contain 1 to 300 characters");
            }
            return new Request(mode, identity, session, question, required);
        }
    }

    record Timing(long chatTimeoutMillis, long observationTimeoutMillis, long pollIntervalMillis,
                  long discoveryWindowMillis) {
        Timing {
            if (chatTimeoutMillis <= 0 || observationTimeoutMillis <= 0 || pollIntervalMillis <= 0
                    || discoveryWindowMillis <= 0 || discoveryWindowMillis > observationTimeoutMillis) {
                throw new IllegalArgumentException("audit timeouts must be positive; discovery window must not exceed observation timeout");
            }
        }
        static Timing from(InitializerConfig config) {
            return new Timing(config.getInt("audit.chat-timeout-millis", 600_000),
                    config.getInt("audit.observation-timeout-millis", 45_000),
                    config.getInt("audit.poll-interval-millis", 1_500),
                    config.getInt("audit.discovery-window-millis", 3_000));
        }
    }

    // 观察和对话都从参数注入，离线自检不起服务也能走真实的采集与失败路径
    static int runTurn(Path out, Request request, Map<String,Object> sessions, Timing timing,
                       Callable<Map<String,Object>> observer,
                       Callable<AgentChatClient.AgentTurnResult> chat) throws Exception {
        Map<String,Object> turn = new LinkedHashMap<>();
        turn.put("identity", request.identity()); turn.put("session", request.session()); turn.put("question", request.question());
        turn.put("startedAt", Instant.now().toString());
        turn.put("status", "NOT_STARTED"); turn.put("chatStatus", "NOT_STARTED");
        turn.put("observationStatus", "NOT_STARTED");
        turn.put("requireExtraction", request.requireExtraction());
        turn.put("sessionPersistenceStatus", "NOT_ATTEMPTED");
        long start = System.nanoTime();
        Map<String,Object> before = null;
        System.out.println("START " + request.identity() + "/" + request.session()
                + " chars=" + request.question().codePointCount(0, request.question().length()));
        try {
            try {
                before = observer.call();
                turn.put("before", before);
            } catch (Exception e) {
                observationFailure(turn, "before", e);
            }
            if (before != null) {
                AgentChatClient.AgentTurnResult result = null;
                try {
                    result = withTimeout(chat, timing.chatTimeoutMillis());
                    // 先把回答记下，后面落盘或读库失败也不丢
                    turn.put("conversationId", result.conversationId()); turn.put("taskId", result.taskId());
                    turn.put("answer", result.answer()); turn.put("tools", result.tools()); turn.put("thinkChars", result.thinkChars());
                    turn.put("messageId", result.messageId()); turn.put("status", "COMPLETED");
                    turn.put("chatStatus", "COMPLETED");
                } catch (Exception e) {
                    turn.put("status", "ERROR");
                    turn.put("chatStatus", e instanceof TimeoutException ? "TIMEOUT" : "ERROR");
                    turn.put("error", describe(e));
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                }
                if (result != null) {
                    sessions.put(request.session(), result.conversationId());
                    try {
                        write(out.resolve(request.identity() + "-sessions.json"), sessions);
                        turn.put("sessionPersistenceStatus", "SAVED");
                    } catch (Exception e) {
                        turn.put("sessionPersistenceStatus", "ERROR");
                        turn.put("sessionPersistenceError", describe(e));
                    }
                    try {
                        awaitMemory(turn, before, result.conversationId(), request.requireExtraction(), timing, observer);
                    } catch (Exception e) {
                        observationFailure(turn, "await", e);
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    }
                }
            }
        } finally {
            try {
                turn.put("after", observer.call());
            } catch (Exception e) {
                observationFailure(turn, "after", e);
            }
            // JSONL 是主证据，latest/sessions 旁路文件写失败不能连累这一轮
            if (turn.containsKey("after")) {
                try { write(out.resolve(request.identity() + "-latest.json"), turn.get("after")); }
                catch (Exception e) { turn.put("latestPersistenceError", describe(e)); }
            }
            turn.put("elapsedMillis", (System.nanoTime() - start) / 1_000_000);
            turn.put("finishedAt", Instant.now().toString());
            turn.put("exitCode", exitCode(turn));
            String json = SimpleJson.stringify(turn);
            try {
                Files.writeString(out.resolve(request.identity() + "-turns.jsonl"), json + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } finally {
                // 输出盘写失败时也打到标准输出留证，退出码照样非零
                System.out.println(json);
            }
        }
        return exitCode(turn);
    }

    static <T> T withTimeout(Callable<T> action, long timeoutMillis) throws Exception {
        var executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "memory-audit-http"); thread.setDaemon(true); return thread;
        });
        var future = executor.submit(action);
        try { return future.get(timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        } finally { future.cancel(true); executor.shutdownNow(); }
    }

    private static void awaitMemory(Map<String,Object> turn, Map<String,Object> before, String conversationId,
                                    boolean required, Timing timing, Callable<Map<String,Object>> observer) throws Exception {
        Set<String> previousIds = new HashSet<>();
        for (List<String> row : extractionRows(before)) previousIds.add(row.get(0));
        long start = System.nanoTime();
        boolean first = true;
        while (true) {
            Map<String,Object> state = observer.call();
            if (first) { turn.put("immediate", state); first = false; }
            turn.put("memoryObservedAt", state.get("observedAt"));
            List<List<String>> added = new ArrayList<>();
            boolean pending = false;
            for (List<String> row : extractionRows(state)) {
                if (row.get(4).equals("PROCESSING")) pending = true;
                if (row.get(1).equals(conversationId) && !previousIds.contains(row.get(0))) added.add(row);
            }
            turn.put("observedNewExtractions", added);
            boolean settled = !added.isEmpty() && added.stream()
                    .allMatch(row -> Set.of("WRITTEN", "NOOP", "DROPPED", "CONFLICT").contains(row.get(4)));
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            turn.put("observationElapsedMillis", elapsed);
            if (settled && !pending) {
                turn.put("observationStatus", "SETTLED");
                return;
            }
            if (!required && added.isEmpty() && !pending && elapsed >= timing.discoveryWindowMillis()) {
                turn.put("observationStatus", "NOT_OBSERVED");
                return;
            }
            if (elapsed >= timing.observationTimeoutMillis()) {
                turn.put("observationStatus", "TIMEOUT");
                return;
            }
            Thread.sleep(Math.min(timing.pollIntervalMillis(), timing.observationTimeoutMillis() - elapsed));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> extractionRows(Map<String,Object> state) {
        return (List<List<String>>) state.get("extractions");
    }

    private static void observationFailure(Map<String,Object> turn, String stage, Exception error) {
        turn.put("observationStatus", "ERROR");
        turn.put(stage + "ObservationError", describe(error));
    }

    private static String describe(Exception e) { return e.getClass().getSimpleName() + ": " + e.getMessage(); }

    private static int exitCode(Map<String,Object> turn) {
        if (!"COMPLETED".equals(turn.get("chatStatus")) && !"NOT_STARTED".equals(turn.get("chatStatus"))) return 2;
        if (Set.of("ERROR", "TIMEOUT").contains(turn.get("observationStatus"))) return 3;
        if ("ERROR".equals(turn.get("sessionPersistenceStatus")) || turn.containsKey("latestPersistenceError")) return 4;
        return 0;
    }

    private static Map<String,Object> observe(JdbcClient jdbc, String userId, Map<String,Object> sessions) throws Exception {
        String uid = JdbcClient.literal(userId);
        Map<String,Object> state = new LinkedHashMap<>();
        state.put("observedAt", Instant.now().toString());
        state.put("userId", userId);
        state.put("memories", jdbc.queryRows("SELECT id, content, source_type, COALESCE(invalid_at::text,''), COALESCE(superseded_by,''), create_time::text FROM t_agent_memory WHERE user_id = " + uid + " ORDER BY create_time, id"));
        state.put("extractions", jdbc.queryRows("SELECT id, conversation_id, from_message_id, to_message_id, status, decision_count::text, attempt_count::text, create_time::text FROM t_agent_memory_extraction WHERE user_id = " + uid + " ORDER BY create_time,id"));
        state.put("compactions", jdbc.queryRows("SELECT conversation_id, generation::text, summary_chars::text, context_chars_before::text, context_chars_after::text, summary, create_time::text FROM t_agent_context_compaction WHERE user_id = " + uid + " ORDER BY create_time,id"));
        List<Object> contexts = new ArrayList<>();
        AgentStateProbe probe = new AgentStateProbe(jdbc);
        for (var entry : sessions.entrySet()) {
            var snapshot = probe.snapshot(userId, entry.getValue().toString(), "AUDIT-ANCHOR");
            Map<String,Object> context = new LinkedHashMap<>();
            context.put("label", entry.getKey()); context.put("conversationId", entry.getValue());
            context.put("contextChars", snapshot.contextChars()); context.put("summary", snapshot.summary());
            context.put("summaryMessages", snapshot.summaryMessages()); context.put("messageCount", snapshot.messageCount());
            context.put("maxInputTokens", snapshot.maxInputTokens());
            contexts.add(context);
        }
        state.put("contexts", contexts);
        return state;
    }

    private static void write(Path path, Object value) throws Exception {
        Files.writeString(path, SimpleJson.stringify(value) + "\n");
    }
}
