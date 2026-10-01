/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline collection checks. No HTTP, database connection, credentials or business-state mutation. */
public final class MemoryAuditSelfTest {
    private static final MemoryAuditMain.Timing TIMING = new MemoryAuditMain.Timing(60, 90, 5, 20);

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("memory-audit-self-test-");
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        try (PrintStream quiet = new PrintStream(new ByteArrayOutputStream())) {
            System.setOut(quiet);
            System.setErr(quiet);
            noExtraction(root);
            delayedExtraction(root);
            unrelatedExtraction(root);
            processingTimeout(root);
            requiredTimeout(root);
            finalReadFailure(root);
            sessionWriteFailure(root);
            chatTimeout(root);
            initialReadFailure(root);
            argumentValidation(root);
            accountLifecycle(root);
            summaryExport();
            summaryRunner(root);
        } finally { System.setOut(stdout); System.setErr(stderr); }
        System.out.println("PASS: 10 collection checks, account lifecycle checks, summary evidence checks and 7 summary runner checks; temporary evidence: " + root);
    }

    private static void noExtraction(Path root) throws Exception {
        Run run = run(root, "none", false, () -> state(), MemoryAuditSelfTest::answer);
        expect(run.code == 0 && "NOT_OBSERVED".equals(run.turn.get("observationStatus")), "absence is not settled");
    }

    private static void delayedExtraction(Path root) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Run run = run(root, "delayed", true, () -> switch (calls.incrementAndGet()) {
            case 1, 2, 3 -> state();
            case 4 -> state(row("new", "session-id", "PROCESSING"));
            default -> state(row("new", "session-id", "WRITTEN"));
        }, MemoryAuditSelfTest::answer);
        expect(run.code == 0 && "SETTLED".equals(run.turn.get("observationStatus")), "wait for a new terminal extraction");
    }

    private static void unrelatedExtraction(Path root) throws Exception {
        Run run = run(root, "unrelated", false,
                () -> state(row("other", "other-session", "WRITTEN")), MemoryAuditSelfTest::answer);
        expect("NOT_OBSERVED".equals(run.turn.get("observationStatus")), "other sessions are not this turn's evidence");
    }

    private static void processingTimeout(Path root) throws Exception {
        Run run = run(root, "processing", false,
                () -> state(row("old", "session-id", "PROCESSING")), MemoryAuditSelfTest::answer);
        expect(run.code == 3 && "TIMEOUT".equals(run.turn.get("observationStatus")), "existing PROCESSING must time out nonzero");
        expect("COMPLETED".equals(run.turn.get("chatStatus")), "observation timeout does not erase successful chat");
    }

    private static void requiredTimeout(Path root) throws Exception {
        Run run = run(root, "required", true, () -> state(), MemoryAuditSelfTest::answer);
        expect(run.code == 3 && "TIMEOUT".equals(run.turn.get("observationStatus")), "required extraction cannot pass absent");
    }

    private static void finalReadFailure(Path root) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Run run = run(root, "finalfail", true, () -> switch (calls.incrementAndGet()) {
            case 1 -> state();
            case 2 -> state(row("new", "session-id", "NOOP"));
            default -> throw new IOException("final observation unavailable");
        }, MemoryAuditSelfTest::answer);
        expect(run.code == 3 && run.turn.containsKey("afterObservationError"), "record final read failure");
        expect("kept answer".equals(run.turn.get("answer")), "retain response when final read fails");
    }

    private static void sessionWriteFailure(Path root) throws Exception {
        Path out = root.resolve("sessionfail");
        Files.createDirectories(out.resolve("test-sessions.json")); // Deliberately make the sidecar unwritable as a file.
        Run run = run(root, "sessionfail", false, () -> state(), MemoryAuditSelfTest::answer);
        expect(run.code == 4 && "ERROR".equals(run.turn.get("sessionPersistenceStatus")), "session sidecar failure is nonzero");
        expect("kept answer".equals(run.turn.get("answer")), "retain response when session persistence fails");
    }

    private static void chatTimeout(Path root) throws Exception {
        long start = System.nanoTime();
        Run run = run(root, "chatstall", false, () -> state(), () -> {
            Thread.sleep(10_000);
            return answer();
        });
        expect(run.code == 2 && "TIMEOUT".equals(run.turn.get("chatStatus")), "outer deadline bounds a stalled chat");
        expect((System.nanoTime() - start) / 1_000_000 < 2_000, "timeout does not wait for stalled worker");
    }

    private static void initialReadFailure(Path root) throws Exception {
        Run run = run(root, "beforefail", false, () -> { throw new IOException("no database"); },
                () -> { throw new AssertionError("chat must not run without baseline"); });
        expect(run.code == 3 && "NOT_STARTED".equals(run.turn.get("chatStatus")), "baseline failure must not send chat");
        expect(run.turn.containsKey("beforeObservationError"), "baseline failure leaves a record");
    }

    private static void argumentValidation(Path root) throws Exception {
        Path question = root.resolve("question.txt");
        Files.writeString(question, "问题");
        MemoryAuditMain.Request request = MemoryAuditMain.Request.parse(new String[]{"missing-config", "out", "ask", "test", "A", question.toString(), "--require-extraction"});
        expect(request.requireExtraction(), "parse extraction flag");
        for (String[] arguments : List.of(new String[]{"a", "b", "create"},
                new String[]{"a", "b", "invalid"},
                new String[]{"a", "b", "ask", "test", "A", question.toString(), "--typo"})) {
            try { MemoryAuditMain.Request.parse(arguments); throw new AssertionError("invalid CLI accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        Files.writeString(question, "字".repeat(301));
        try { MemoryAuditMain.Request.parse(new String[]{"a", "b", "ask", "test", "A", question.toString()}); throw new AssertionError("long question accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    private static Run run(Path root, String label, boolean required, Callable<Map<String,Object>> observer,
                           Callable<AgentChatClient.AgentTurnResult> chat) throws Exception {
        Path out = root.resolve(label);
        Files.createDirectories(out);
        int code = MemoryAuditMain.runTurn(out, new MemoryAuditMain.Request("ask", "test", "A", "问题", required),
                new LinkedHashMap<>(), TIMING, observer, chat);
        var turn = SimpleJson.object(SimpleJson.parse(Files.readString(out.resolve("test-turns.jsonl"))));
        return new Run(code, turn);
    }

    private static void accountLifecycle(Path root) throws Exception {
        String username = MemoryAuditMain.newUsername(Set.of());
        expect(username.matches("[a-z]+"), "lowercase English username");
        expect(!username.equals(MemoryAuditMain.newUsername(Set.of(username))), "occupied usernames are skipped");
        Path out = root.resolve("account-lifecycle");
        Files.createDirectories(out);
        MemoryAuditMain.requireUnusedIdentity(out, "summary");
        // Losing the credentials must not allow a new user to append to another user's old evidence.
        Files.writeString(out.resolve("summary-turns.jsonl"), "old evidence\n");
        try { MemoryAuditMain.requireUnusedIdentity(out, "summary"); throw new AssertionError("existing evidence reused"); }
        catch (IllegalStateException expected) { }
        expect("old evidence\n".equals(Files.readString(out.resolve("summary-turns.jsonl"))), "old evidence remains unchanged");
        MemoryAuditMain.requireUnusedIdentity(out, "another-scenario");
    }

    private static void summaryExport() {
        var source = Map.<String,Object>of("conversationId", "session-id", "before", Map.of("userId", "user-id"),
                "status", "COMPLETED", "question", "original question", "answer", "original answer", "tools", List.of());
        var state = Map.<String,Object>of("context", List.of(
                Map.of("role", "USER", "content", List.of(Map.of("type", "text", "text", "prefix original question suffix"))),
                Map.of("role", "ASSISTANT", "name", "__compaction_summary__", "content",
                        List.of(Map.of("type", "text", "text", "## 用户诉求\nonly a summary"))),
                Map.of("role", "ASSISTANT", "content", List.of(
                        Map.of("type", "thinking", "thinking", "DO_NOT_EXPORT_THINKING"),
                        Map.of("type", "tool_use", "name", "search_knowledge", "input", Map.of("q", "DO_NOT_EXPORT_INPUT")),
                        Map.of("type", "tool_result", "name", "search_knowledge", "output", "DO_NOT_EXPORT_OUTPUT")))));
        var exported = MemoryAuditSummary.build("user-id", "session-id", state, List.of(source, source), List.of(), List.of());
        String json = SimpleJson.stringify(exported);
        expect(!json.contains("DO_NOT_EXPORT"), "thinking and tool payloads must not be published");
        expect(json.contains("search_knowledge"), "tool names remain available for inspection");
        expect("OBSERVED".equals(exported.get("summaryObservation")), "detect actual summary");
        var seed = SimpleJson.object(SimpleJson.object(exported.get("sourceChecks")).get("seed"));
        expect("PRESENT".equals(seed.get("questionInRetainedNonSummaryText")), "detect source inside retained text");
        expect("ABSENT".equals(seed.get("answerInRetainedNonSummaryText")), "detect missing full source text");
        var missingState = MemoryAuditSummary.build("user-id", "session-id", null, List.of(source), List.of(), List.of());
        expect("UNKNOWN".equals(missingState.get("summaryObservation")), "missing state is unknown");
        var emptyState = MemoryAuditSummary.build("user-id", "session-id", Map.of("context", List.of()), List.of(), List.of(), List.of());
        expect("UNCOVERED".equals(emptyState.get("summaryObservation")), "no summary is uncovered");
        var missingSource = SimpleJson.object(SimpleJson.object(emptyState.get("sourceChecks")).get("draft"));
        expect("UNKNOWN".equals(missingSource.get("questionInRetainedNonSummaryText")), "missing source is not absence");
        var wrongUser = MemoryAuditSummary.build("another-user", "session-id", state, List.of(source), List.of(), List.of());
        expect(Integer.valueOf(0).equals(wrongUser.get("matchingLoggedTurns")), "never borrow another user's source log");
    }

    private static void summaryRunner(Path root) throws Exception {
        Path suite = root.resolve("summary-suite");
        Files.createDirectories(suite);
        List<Map<String,Object>> turns = new ArrayList<>();
        for (int i = 1; i <= 30; i++) turns.add(Map.of("id", "s%02d".formatted(i), "question", "离线问题 " + i));
        Files.writeString(suite.resolve("summary-turns.json"), SimpleJson.stringify(List.of(Map.of("id", "offline", "turns", turns))));

        SummaryRun early = runSummary(root, suite, "summary-early", 29, 3, 0);
        expect(early.code == 0 && "COMPLETED".equals(early.manifest.get("status")), "early summary run completes");
        expect(List.of("s01", "s02", "s03", "s30").equals(early.manifest.get("collectedTurns")), "summary skips remaining setup turns");
        expect("OBSERVED".equals(early.manifest.get("summaryCoverage")), "setup summary is covered");
        expect(List.of("environment-before", "create-summary", "s01", "s02", "s03", "s30", "export-summary", "environment-after").equals(early.commands),
                "summary runner executes expected command order");

        SummaryRun probe = runSummary(root, suite, "summary-probe", 2, 3, 0);
        expect(probe.code == 0 && "OBSERVED".equals(probe.manifest.get("summaryCoverage")), "summary appearing in final probe is covered");
        expect(List.of("s01", "s02", "s30").equals(probe.manifest.get("collectedTurns")), "max turns bounds setup only");

        SummaryRun absent = runSummary(root, suite, "summary-absent", 2, 0, 0);
        expect(absent.code == 0 && "COMPLETED".equals(absent.manifest.get("status")), "uncovered run still completes collection");
        expect("UNCOVERED".equals(absent.manifest.get("summaryCoverage")), "no summary must remain uncovered");

        SummaryRun failed = runSummary(root, suite, "summary-failed", 29, 0, 2);
        expect(failed.code != 0 && "ERROR".equals(failed.manifest.get("status")), "failed observation stops the run");
        expect(List.of("s01", "s02").equals(failed.manifest.get("attemptedTurns")), "failed turn remains attempted");
        expect(List.of("s01").equals(failed.manifest.get("collectedTurns")), "successful chat with failed observation is not collected");
        expect(List.of("environment-before", "create-summary", "s01", "s02").equals(failed.commands), "failure must not send probe or continue exporting");
        expect(Files.readAllLines(root.resolve("summary-failed/summary-turns.jsonl")).size() == 2, "failed turn's raw evidence remains available");

        Path dryOut = root.resolve("summary-dry");
        var dry = MemoryAuditSummaryMain.Options.parse(new String[]{"--suite-dir", suite.toString(), "--output-dir", dryOut.toString(), "--dry-run"});
        int dryCode = MemoryAuditSummaryMain.execute(dry, (label, arguments) -> { throw new AssertionError("dry run invoked a command"); });
        expect(dryCode == 0 && !Files.exists(dryOut), "dry run must not write the output directory");

        Path used = root.resolve("summary-used");
        Files.createDirectories(used);
        Files.writeString(used.resolve("keep.txt"), "existing evidence");
        boolean refused = false;
        try {
            var options = MemoryAuditSummaryMain.Options.parse(new String[]{"--suite-dir", suite.toString(), "--output-dir", used.toString()});
            refused = MemoryAuditSummaryMain.execute(options, (label, arguments) -> { throw new AssertionError("nonempty output invoked a command"); }) != 0;
        } catch (IllegalArgumentException | IOException expected) { refused = true; }
        expect(refused && "existing evidence".equals(Files.readString(used.resolve("keep.txt"))) && !Files.exists(used.resolve("questions")),
                "nonempty output must be rejected without mutation");

        var defaults = MemoryAuditSummaryMain.Options.parse(new String[]{"--output-dir", root.resolve("summary-defaults").toString()});
        expect(defaults.maxTurns() == 29 && !defaults.dryRun(), "summary CLI defaults are preserved");
        for (String[] arguments : List.of(new String[]{}, new String[]{"--output-dir"},
                new String[]{"--output-dir", "out", "--max-turns", "1"},
                new String[]{"--output-dir", "out", "--max-turns", "30"},
                new String[]{"--output-dir", "out", "--typo"})) {
            try { MemoryAuditSummaryMain.Options.parse(arguments); throw new AssertionError("invalid summary CLI accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    private static SummaryRun runSummary(Path root, Path suite, String label, int maxTurns, int observedAt, int failAt) throws Exception {
        Path out = root.resolve(label);
        List<String> commands = new ArrayList<>();
        AtomicInteger asked = new AtomicInteger();
        var options = MemoryAuditSummaryMain.Options.parse(new String[]{"--suite-dir", suite.toString(), "--output-dir", out.toString(),
                "--max-turns", Integer.toString(maxTurns)});
        int code = MemoryAuditSummaryMain.execute(options, (commandLabel, arguments) -> {
            commands.add(commandLabel);
            switch (arguments[0]) {
                case "env" -> expect(arguments.length == 1, "environment command arguments");
                case "create" -> expect(List.of(arguments).equals(List.of("create", "summary")), "isolated summary identity");
                case "export-summary" -> expect(List.of(arguments).equals(List.of("export-summary", "summary", "main")), "export current summary session");
                case "ask" -> {
                    expect(arguments.length == 4 && "summary".equals(arguments[1]) && "main".equals(arguments[2]), "ask current summary session");
                    Path question = Path.of(arguments[3]);
                    expect(question.getFileName().toString().equals(commandLabel + ".txt") && Files.readString(question).startsWith("离线问题 "), "write selected question before asking");
                    int number = asked.incrementAndGet();
                    var turn = Map.of("conversationId", "summary-session", "chatStatus", "COMPLETED", "status", "COMPLETED",
                            "question", Files.readString(question).strip(),
                            "after", Map.of("contexts", List.of(
                                    Map.of("conversationId", "summary-session", "summaryMessages", observedAt > 0 && number >= observedAt ? 1 : 0),
                                    Map.of("conversationId", "another-session", "summaryMessages", 1))));
                    Files.writeString(out.resolve("summary-turns.jsonl"), SimpleJson.stringify(turn) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    if (number == failAt) throw new IOException("chat completed but final observation failed");
                }
                default -> throw new AssertionError("unexpected command: " + arguments[0]);
            }
        });
        return new SummaryRun(code, SimpleJson.object(SimpleJson.parse(Files.readString(out.resolve("summary-run.json")))), commands);
    }

    @SafeVarargs
    private static Map<String,Object> state(List<String>... rows) {
        return Map.of("observedAt", "offline", "extractions", List.of(rows));
    }

    private static List<String> row(String id, String conversation, String status) {
        return List.of(id, conversation, "from", "to", status, "0", "0", "offline");
    }

    private static AgentChatClient.AgentTurnResult answer() {
        return new AgentChatClient.AgentTurnResult("session-id", "task-id", "message-id", "kept answer", List.of(), 0);
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Run(int code, Map<String,Object> turn) { }
    private record SummaryRun(int code, Map<String,Object> manifest, List<String> commands) { }
}
