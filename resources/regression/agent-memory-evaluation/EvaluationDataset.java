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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versionable UTF-8 JSON array cases; assertions are deterministic string/tool checks. */
final class EvaluationDataset {

    private static final Set<String> CASE_FIELDS = Set.of("id", "tags", "description", "turns");
    private static final Set<String> TURN_FIELDS = Set.of("id", "question", "questionFile", "expectAll",
            "expectAny", "forbidAny", "expectTools", "forbidTools", "phase");

    private EvaluationDataset() {
    }

    record Case(String id, List<String> tags, String description, List<Turn> turns) {
        Case {
            tags = List.copyOf(tags);
            turns = List.copyOf(turns);
        }
    }

    record Turn(String id, String question, List<String> expectAll, List<String> expectAny,
                List<String> forbidAny, List<String> expectTools, List<String> forbidTools,
                String phase) {
        Turn {
            expectAll = List.copyOf(expectAll);
            expectAny = List.copyOf(expectAny);
            forbidAny = List.copyOf(forbidAny);
            expectTools = List.copyOf(expectTools);
            forbidTools = List.copyOf(forbidTools);
        }

        Map<String, Object> evaluate(String answer, List<String> tools) {
            String actual = answer == null ? "" : answer;
            List<String> called = tools == null ? List.of() : tools;
            List<Map<String, Object>> checks = new ArrayList<>();
            for (String expected : expectAll) {
                checks.add(check("expectAll", expected, actual.contains(expected)));
            }
            if (!expectAny.isEmpty()) {
                checks.add(check("expectAny", expectAny, expectAny.stream().anyMatch(actual::contains)));
            }
            for (String forbidden : forbidAny) {
                checks.add(check("forbidAny", forbidden, !actual.contains(forbidden)));
            }
            for (String expected : expectTools) {
                checks.add(check("expectTools", expected, called.contains(expected)));
            }
            for (String forbidden : forbidTools) {
                checks.add(check("forbidTools", forbidden, !called.contains(forbidden)));
            }
            boolean scored = !checks.isEmpty();
            boolean passed = scored && checks.stream().allMatch(value -> Boolean.TRUE.equals(value.get("passed")));
            return Map.of("scored", scored, "passed", passed, "checks", List.copyOf(checks));
        }

        private static Map<String, Object> check(String type, Object expected, boolean passed) {
            return Map.of("type", type, "expected", expected, "passed", passed);
        }
    }

    static List<Case> load(Path file) throws IOException {
        Path source = file.toAbsolutePath().normalize();
        List<Case> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        String json = Files.readString(source, StandardCharsets.UTF_8);
        if (json.startsWith("\uFEFF")) {
            json = json.substring(1);
        }
        Object parsed;
        try {
            parsed = SimpleJson.parse(json);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(source + ": " + exception.getMessage(), exception);
        }
        if (!(parsed instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("数据集必须是非空 JSON 数组: " + source);
        }
        for (int index = 0; index < values.size(); index++) {
            try {
                Map<String, Object> value = object(values.get(index), "case");
                unknownFields(value, CASE_FIELDS, "case");
                String id = identifier(value, "id");
                if (!ids.add(id)) {
                    throw new IllegalArgumentException("重复 case id: " + id);
                }
                List<String> tags = strings(value, "tags");
                String description = optionalString(value, "description", "");
                Object rawTurns = value.get("turns");
                if (!(rawTurns instanceof List<?> turnValues) || turnValues.isEmpty()) {
                    throw new IllegalArgumentException("turns 必须是非空数组");
                }
                List<Turn> turns = new ArrayList<>();
                Set<String> turnIds = new LinkedHashSet<>();
                for (Object rawTurn : turnValues) {
                    Map<String, Object> item = object(rawTurn, "turn");
                    unknownFields(item, TURN_FIELDS, "turn");
                    String turnId = identifier(item, "id");
                    if (!turnIds.add(turnId)) {
                        throw new IllegalArgumentException("case " + id + " 中重复 turn id: " + turnId);
                    }
                    String question = question(source, item);
                    if (question.length() > 500) {
                        throw new IllegalArgumentException("turn " + turnId + " 的问题超过接口上限 500 字符，实际 "
                                + question.length() + "；questionFile 展开后的正文也受此限制");
                    }
                    String phase = optionalString(item, "phase", "measure");
                    if (!Set.of("warmup", "measure").contains(phase)) {
                        throw new IllegalArgumentException("phase 只支持 warmup 或 measure");
                    }
                    turns.add(new Turn(turnId, question, strings(item, "expectAll"), strings(item, "expectAny"),
                            strings(item, "forbidAny"), strings(item, "expectTools"), strings(item, "forbidTools"),
                            phase));
                }
                result.add(new Case(id, tags, description, turns));
            } catch (IllegalArgumentException | IOException exception) {
                throw new IllegalArgumentException(source + " 第 " + (index + 1) + " 个 case: " + exception.getMessage(), exception);
            }
        }
        return List.copyOf(result);
    }

    /** Case ids AND tag filter; tags within the filter are OR. Source order is retained. */
    static List<Case> select(List<Case> cases, String caseIdsCsv, String tagsCsv, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit 不能小于 0；0 表示不限");
        }
        Set<String> ids = csv(caseIdsCsv);
        Set<String> tags = csv(tagsCsv);
        Set<String> knownIds = new LinkedHashSet<>();
        Set<String> knownTags = new LinkedHashSet<>();
        for (Case item : cases) {
            knownIds.add(item.id());
            knownTags.addAll(item.tags());
        }
        requireKnown(ids, knownIds, "case id");
        requireKnown(tags, knownTags, "tag");
        List<Case> selected = new ArrayList<>();
        for (Case item : cases) {
            if ((!ids.isEmpty() && !ids.contains(item.id()))
                    || (!tags.isEmpty() && item.tags().stream().noneMatch(tags::contains))) {
                continue;
            }
            selected.add(item);
            if (limit > 0 && selected.size() >= limit) {
                break;
            }
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("筛选结果为空，请检查 case ids、tags 和数据集");
        }
        return List.copyOf(selected);
    }

    /** Hash resolved questions and assertions, including selected case/turn order. */
    static String fingerprint(List<Case> cases) {
        return sha256(SimpleJson.stringify(canonical(cases)).getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, Object> manifest(Path source, List<Case> selected) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("formatVersion", 1);
        result.put("source", source.toAbsolutePath().normalize().toString());
        result.put("sourceSha256", sha256(Files.readAllBytes(source)));
        result.put("selectedContentSha256", fingerprint(selected));
        result.put("selectedCaseIds", selected.stream().map(Case::id).toList());
        result.put("caseCount", selected.size());
        result.put("turnCount", selected.stream().mapToInt(value -> value.turns().size()).sum());
        result.put("measuredTurnCount", selected.stream().flatMap(value -> value.turns().stream())
                .filter(value -> "measure".equals(value.phase())).count());
        return Collections.unmodifiableMap(result);
    }

    private static List<Object> canonical(List<Case> cases) {
        List<Object> output = new ArrayList<>();
        for (Case item : cases) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", item.id());
            value.put("tags", item.tags());
            value.put("description", item.description());
            List<Object> turns = new ArrayList<>();
            for (Turn turn : item.turns()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", turn.id());
                entry.put("question", turn.question());
                entry.put("expectAll", turn.expectAll());
                entry.put("expectAny", turn.expectAny());
                entry.put("forbidAny", turn.forbidAny());
                entry.put("expectTools", turn.expectTools());
                entry.put("forbidTools", turn.forbidTools());
                entry.put("phase", turn.phase());
                turns.add(entry);
            }
            value.put("turns", turns);
            output.add(value);
        }
        return output;
    }

    private static String question(Path source, Map<String, Object> value) throws IOException {
        boolean inline = value.containsKey("question");
        boolean external = value.containsKey("questionFile");
        if (inline == external) {
            throw new IllegalArgumentException("question 与 questionFile 必须且只能填写一个");
        }
        if (inline) {
            return requiredString(value, "question");
        }
        Path relative = Path.of(requiredString(value, "questionFile"));
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("questionFile 必须是相对数据集文件目录的路径");
        }
        String text = Files.readString(source.getParent().resolve(relative).normalize(), StandardCharsets.UTF_8);
        if (text.isBlank()) {
            throw new IllegalArgumentException("questionFile 正文为空: " + relative);
        }
        return text;
    }

    private static String identifier(Map<String, Object> value, String key) {
        String id = requiredString(value, key);
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException(key + " 必须为 1 至 128 位字母、数字、点、下划线或短横线，并以字母或数字开头");
        }
        return id;
    }

    private static String requiredString(Map<String, Object> value, String key) {
        Object item = value.get(key);
        if (!(item instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " 必须是非空字符串");
        }
        return text;
    }

    private static String optionalString(Map<String, Object> value, String key, String fallback) {
        if (!value.containsKey(key)) {
            return fallback;
        }
        if (!(value.get(key) instanceof String text)) {
            throw new IllegalArgumentException(key + " 必须是字符串");
        }
        return text;
    }

    private static List<String> strings(Map<String, Object> value, String key) {
        if (!value.containsKey(key)) {
            return List.of();
        }
        if (!(value.get(key) instanceof List<?> items)) {
            throw new IllegalArgumentException(key + " 必须是字符串数组");
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : items) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException(key + " 中的元素必须是非空字符串");
            }
            if (!seen.add(text)) {
                throw new IllegalArgumentException(key + " 中重复元素: " + text);
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(name + " 必须是 JSON 对象");
        }
        return SimpleJson.object(value);
    }

    private static void unknownFields(Map<String, Object> value, Set<String> allowed, String name) {
        for (String key : value.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(name + " 存在未知字段: " + key);
            }
        }
    }

    private static Set<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String item : value.split(",", -1)) {
            if (item.isBlank()) {
                throw new IllegalArgumentException("筛选条件不能包含空元素: " + value);
            }
            result.add(item.trim());
        }
        return result;
    }

    private static void requireKnown(Set<String> selected, Set<String> available, String kind) {
        Set<String> unknown = new LinkedHashSet<>(selected);
        unknown.removeAll(available);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("未知 " + kind + ": " + unknown + "；可用值: " + available);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }
}
