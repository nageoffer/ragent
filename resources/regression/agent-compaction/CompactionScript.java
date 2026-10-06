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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * script.txt 的对象化：一行一问，行首 [ref] 是判定规则引用的标记轮
 * 问题文本互不相同，判定靠「这句话还在不在原文里」推断它有没有被压进摘要
 */
record CompactionScript(List<Turn> turns, Map<String, Integer> refs) {

    private static final Pattern REF_LINE = Pattern.compile("^\\[([a-z0-9_]+)]\\s*(.+)$");

    static CompactionScript load(Path file) throws IOException {
        List<Turn> turns = new ArrayList<>();
        Map<String, Integer> refs = new LinkedHashMap<>();
        Set<String> texts = new HashSet<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String ref = null;
            String text = line;
            Matcher matcher = REF_LINE.matcher(line);
            if (matcher.matches()) {
                ref = matcher.group(1);
                text = matcher.group(2).strip();
            }
            if (!texts.add(text)) {
                throw new IllegalArgumentException("剧本里有重复的问题，会让压缩判定失准: " + text);
            }
            int index = turns.size() + 1;
            if (ref != null && refs.putIfAbsent(ref, index) != null) {
                throw new IllegalArgumentException("剧本里标记轮重复: [" + ref + "]");
            }
            turns.add(new Turn(index, ref, text));
        }
        if (turns.isEmpty()) {
            throw new IllegalArgumentException("剧本里没有任何轮次: " + file);
        }
        return new CompactionScript(List.copyOf(turns), Map.copyOf(refs));
    }

    int index(String ref) {
        Integer index = refs.get(ref);
        if (index == null) {
            throw new IllegalArgumentException("剧本缺少标记轮 [" + ref + "]");
        }
        return index;
    }

    record Turn(int index, String ref, String text) {
    }
}
