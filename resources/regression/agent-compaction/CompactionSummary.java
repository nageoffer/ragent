/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一代摘要按 `## ` 小节拆开，供判定规则逐节取行
 * 「无」不算一行；引号与（原话：…）里是用户原话，判定助手措辞时要先剥掉
 */
final class CompactionSummary {

    // 与比特严选 context-compaction.txt 的「输出」一节同源，改了那边这里必须同步
    static final List<String> SECTIONS = List.of("用户诉求", "待办", "下一步", "当前进度", "走不通的路", "已完成");

    private static final Pattern QUOTED = Pattern.compile("「[^」]*」|“[^”]*”|（[^（）]*原话[^）]*）|\\([^()]*原话[^)]*\\)");
    private static final Pattern QUOTE_BODY = Pattern.compile("「([^」]+)」|（原话[：:]\\s*([^）]+)）");

    private final String text;
    private final List<String> headings = new ArrayList<>();
    private final Map<String, List<String>> sections = new LinkedHashMap<>();
    private final Map<String, List<String>> rawSections = new LinkedHashMap<>();
    private static final Pattern NONE = Pattern.compile("无(?:（[^\\r\\n]*）|\\([^\\r\\n]*\\))?[。.]?");

    CompactionSummary(String text) {
        this.text = text == null ? "" : text.strip();
        String current = null;
        for (String raw : this.text.split("\n")) {
            String line = raw.strip();
            if (line.startsWith("## ")) {
                current = line.substring(3).strip();
                headings.add(current);
                sections.putIfAbsent(current, new ArrayList<>());
                rawSections.putIfAbsent(current, new ArrayList<>());
                continue;
            }
            if (current == null || line.isEmpty()) {
                continue;
            }
            String item = line.startsWith("- ") ? line.substring(2).strip() : line;
            rawSections.get(current).add(item);
            if (!item.equals("无") && !item.equals("无。")) {
                sections.get(current).add(item);
            }
        }
    }

    String text() {
        return text;
    }

    List<String> headings() {
        return headings;
    }

    boolean structured() {
        return text.startsWith("## ") && headings.equals(SECTIONS);
    }

    List<String> lines(String section) {
        return sections.getOrDefault(section, List.of());
    }

    String joined(String section) {
        return String.join("\n", lines(section));
    }

    boolean any(String section, Predicate<String> predicate) {
        return lines(section).stream().anyMatch(predicate);
    }

    /**
     * 第一个不满足条件的行，全满足返回 null
     */
    String firstViolation(String section, Predicate<String> predicate) {
        return lines(section).stream().filter(predicate.negate()).findFirst().orElse(null);
    }

    /**
     * 下一步写「无（……）」也算无：括号里的依据说明是提示词要求附的
     */
    boolean nextStepIsNone() {
        return explicitlyNone("下一步");
    }

    boolean explicitlyNone(String section) {
        List<String> raw = rawSections.getOrDefault(section, List.of());
        return raw.size() == 1 && NONE.matcher(raw.get(0)).matches();
    }

    /**
     * 除下一步之外各节引用的用户原话，用来查同一句话被抄了几遍
     */
    List<String> quotesOutsideNextStep() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : sections.entrySet()) {
            if ("下一步".equals(entry.getKey())) {
                continue;
            }
            for (String line : entry.getValue()) {
                Matcher matcher = QUOTE_BODY.matcher(line);
                while (matcher.find()) {
                    String body = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                    if (body.strip().length() >= 6) {
                        result.add(body.strip());
                    }
                }
            }
        }
        return result;
    }

    static String unquoted(String value) {
        return value == null ? "" : QUOTED.matcher(value).replaceAll("");
    }
}
