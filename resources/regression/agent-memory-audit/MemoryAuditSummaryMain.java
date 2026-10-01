/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs the supplied summary script until compaction, then collects the recall probe and evidence. */
public final class MemoryAuditSummaryMain {
    public static void main(String[] args) {
        int code;
        try {
            Options options = Options.parse(args);
            code = execute(options, (label, arguments) -> invoke(options, label, arguments));
        } catch (Exception e) {
            System.err.println("Summary audit failed: " + e.getMessage());
            code = 1;
        }
        if (code != 0) System.exit(code);
    }

    record Options(Path outputDir, Path suiteDir, Path config, int maxTurns, boolean dryRun) {
        static Options parse(String[] args) {
            String usage = "--output-dir <new-directory> [--suite-dir <directory>] [--config <properties>] "
                    + "[--max-turns <2..29>] [--dry-run]";
            Path out = null, suite = Path.of("resources/regression/agent-memory-audit"), config = null;
            int maxTurns = 29;
            boolean dryRun = false;
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < args.length; i++) {
                String key = args[i];
                if (!seen.add(key)) throw new IllegalArgumentException("Duplicate option: " + key);
                if (key.equals("--dry-run")) { dryRun = true; continue; }
                if (!Set.of("--output-dir", "--suite-dir", "--config", "--max-turns").contains(key)
                        || i + 1 == args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException(usage);
                }
                String value = args[++i];
                switch (key) {
                    case "--output-dir" -> out = Path.of(value);
                    case "--suite-dir" -> suite = Path.of(value);
                    case "--config" -> config = Path.of(value);
                    case "--max-turns" -> maxTurns = Integer.parseInt(value);
                    default -> throw new IllegalArgumentException(usage);
                }
            }
            if (out == null || maxTurns < 2 || maxTurns > 29) throw new IllegalArgumentException(usage);
            suite = suite.toAbsolutePath().normalize();
            if (config == null) config = suite.resolveSibling("agent-memory").resolve("regression.properties");
            return new Options(out.toAbsolutePath().normalize(), suite, config.toAbsolutePath().normalize(), maxTurns, dryRun);
        }
    }

    @FunctionalInterface
    interface Command {
        void invoke(String label, String... arguments) throws Exception;
    }

    static int execute(Options options, Command command) throws Exception {
        Path dataset = options.suiteDir().resolve("summary-turns.json");
        byte[] source = Files.readAllBytes(dataset);
        List<Object> cases = SimpleJson.array(SimpleJson.parse(Files.readString(dataset)));
        if (cases.size() != 1) throw new IllegalArgumentException("Expected one summary case");
        List<Object> turns = SimpleJson.array(SimpleJson.object(cases.get(0)).get("turns"));
        if (turns.size() != 30) throw new IllegalArgumentException("Expected 29 setup turns and a final probe");
        List<Map<String,Object>> setup = new ArrayList<>();
        for (int i = 0; i < options.maxTurns(); i++) setup.add(SimpleJson.object(turns.get(i)));
        Map<String,Object> probe = SimpleJson.object(turns.get(turns.size() - 1));
        List<Map<String,Object>> selected = new ArrayList<>(setup);
        selected.add(probe);
        Set<String> ids = new HashSet<>();
        for (Map<String,Object> turn : selected) {
            String id = SimpleJson.string(turn, "id"), question = SimpleJson.string(turn, "question");
            if (id == null || !id.matches("[a-zA-Z0-9_-]+") || !ids.add(id)
                    || question == null || question.isBlank() || question.codePointCount(0, question.length()) > 300) {
                throw new IllegalArgumentException("Invalid turn ID or question: " + id);
            }
        }
        List<String> attempted = new ArrayList<>(), collected = new ArrayList<>();
        Map<String,Object> run = new LinkedHashMap<>();
        run.put("datasetSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)));
        run.put("plannedSetupTurns", setup.stream().map(t -> t.get("id")).toList());
        run.put("probeTurn", probe.get("id"));
        run.put("attemptedTurns", attempted); run.put("collectedTurns", collected);
        run.put("status", options.dryRun() ? "DRY_RUN" : "RUNNING");
        run.put("summaryCoverage", "UNKNOWN"); run.put("businessVerdict", "MANUAL_REVIEW");
        if (options.dryRun()) {
            System.out.println(SimpleJson.stringify(run));
            return 0;
        }
        Path out = options.outputDir();
        if (Files.exists(out)) {
            try (var entries = Files.list(out)) {
                if (entries.findAny().isPresent()) throw new IllegalArgumentException("Output directory must be empty; use a fresh account and run directory");
            }
        }
        Files.createDirectories(out.resolve("questions"));
        save(out, run);
        try {
            command.invoke("environment-before", "env");
            command.invoke("create-summary", "create", "summary");
            boolean observed = false;
            for (Map<String,Object> turn : setup) {
                observed = ask(out, turn, run, attempted, collected, command);
                // Always supply both the task and the draft before the recall probe.
                if (observed && collected.size() >= 2) break;
            }
            // The probe itself can trigger compaction at the beginning of its turn.
            boolean probeObserved = ask(out, probe, run, attempted, collected, command);
            run.put("summaryCoverage", observed || probeObserved ? "OBSERVED" : "UNCOVERED");
            command.invoke("export-summary", "export-summary", "summary", "main");
            command.invoke("environment-after", "env");
            run.put("status", "COMPLETED");
        } catch (Exception e) {
            run.put("status", "ERROR");
            run.put("error", e.getClass().getSimpleName());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("Run stopped: " + e.getClass().getSimpleName() + "; inspect logs under " + out);
            return 1;
        } finally {
            save(out, run);
        }
        System.out.println("Summary coverage: " + run.get("summaryCoverage") + "; business result requires review. Evidence: " + out);
        return 0;
    }

    private static boolean ask(Path out, Map<String,Object> turn, Map<String,Object> run,
                               List<String> attempted, List<String> collected, Command command) throws Exception {
        String id = SimpleJson.string(turn, "id"), question = SimpleJson.string(turn, "question");
        Path questionFile = out.resolve("questions").resolve(id + ".txt");
        Files.writeString(questionFile, question + "\n");
        attempted.add(id);
        save(out, run);
        System.out.println("Running " + id);
        command.invoke(id, "ask", "summary", "main", questionFile.toString());
        List<String> lines = Files.readAllLines(out.resolve("summary-turns.jsonl"));
        if (lines.isEmpty()) throw new IOException("Missing turn evidence: " + id);
        Map<String,Object> latest = SimpleJson.object(SimpleJson.parse(lines.get(lines.size() - 1)));
        if (!question.equals(latest.get("question")) || !"COMPLETED".equals(latest.get("status"))) {
            throw new IOException("Turn evidence does not match: " + id);
        }
        boolean observed = false;
        for (Object value : SimpleJson.array(SimpleJson.object(latest.get("after")).get("contexts"))) {
            Map<String,Object> context = SimpleJson.object(value);
            if (latest.get("conversationId") != null && latest.get("conversationId").equals(context.get("conversationId"))
                    && context.get("summaryMessages") instanceof Number count && count.intValue() > 0) {
                observed = true;
            }
        }
        collected.add(id);
        save(out, run);
        return observed;
    }

    private static void save(Path out, Map<String,Object> run) throws IOException {
        Files.writeString(out.resolve("summary-run.json"), SimpleJson.stringify(run) + "\n");
    }

    private static void invoke(Options options, String label, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), MemoryAuditMain.class.getName(),
                options.config().toString(), options.outputDir().toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(options.outputDir().resolve(label + ".log").toFile()).start();
        try {
            if (!process.waitFor(900, TimeUnit.SECONDS)) throw new TimeoutException("Audit command timed out: " + label);
            if (process.exitValue() != 0) throw new IOException("Audit command failed: " + label + " (exit " + process.exitValue() + ")");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
