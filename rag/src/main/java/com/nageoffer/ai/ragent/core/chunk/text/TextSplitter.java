/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.core.chunk.text;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 边界感知的文本切分：按预算切开长文本，切点落在自然边界而非下标处
 * <p>
 * 边界回溯顺序为换行 → 中文句末标点 → 英文句末标点，另做 URL 断行修复与 CJK 软换行合并，
 * 换用裸 {@code substring} 会把句子、URL、数字腰斩
 */
public final class TextSplitter {

    private TextSplitter() {
    }

    /**
     * 切分文本，空文本返回空列表
     *
     * @param overlapChars 相邻片重叠字符数，同时作为边界回溯的最大距离
     */
    public static List<String> split(String text, int maxChars, int overlapChars) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String normalized = normalize(text);
        if (normalized.length() <= maxChars) {
            return List.of(normalized);
        }

        int chunkSize = Math.max(1, maxChars);
        int overlap = chunkSize > 1 ? Math.min(Math.max(0, overlapChars), chunkSize - 1) : 0;
        int len = normalized.length();

        List<String> pieces = new ArrayList<>();
        int start = 0;
        int lastEnd = -1;
        while (start < len) {
            int targetEnd = Math.min(start + chunkSize, len);
            int end = adjustToBoundary(normalized, start, targetEnd, overlap);
            // 强制推进：回退过头会导致片段重复甚至停滞
            if (end <= start || end <= lastEnd) {
                end = targetEnd;
            }
            String piece = normalized.substring(start, end);
            if (StringUtils.hasText(piece.strip())) {
                pieces.add(piece);
            }
            lastEnd = end;
            if (end >= len) {
                break;
            }
            int nextStart = Math.max(0, end - overlap);
            if (nextStart <= start) {
                nextStart = end;
            }
            start = nextStart;
        }
        return pieces;
    }

    /**
     * 边界回溯：优先换行，其次中文句末标点，最后英文句末标点
     * <p>
     * 英文点号必须后接空白或结尾才算边界，否则会把 URL 的域名点切开；回溯距离不超过 overlap，避免相邻片高度重复
     */
    private static int adjustToBoundary(String text, int start, int targetEnd, int overlap) {
        if (targetEnd <= start) {
            return targetEnd;
        }
        int maxLookback = Math.min(overlap, targetEnd - start);
        if (maxLookback <= 0) {
            return targetEnd;
        }

        for (int i = 0; i <= maxLookback; i++) {
            int pos = targetEnd - i - 1;
            if (pos <= start) {
                break;
            }
            if (text.charAt(pos) == '\n') {
                return pos + 1;
            }
        }
        for (int i = 0; i <= maxLookback; i++) {
            int pos = targetEnd - i - 1;
            if (pos <= start) {
                break;
            }
            char c = text.charAt(pos);
            if (c == '。' || c == '！' || c == '？') {
                return pos + 1;
            }
        }
        for (int i = 0; i <= maxLookback; i++) {
            int pos = targetEnd - i - 1;
            if (pos <= start) {
                break;
            }
            char c = text.charAt(pos);
            if (c == '.' || c == '!' || c == '?') {
                int next = pos + 1;
                if (next >= text.length() || Character.isWhitespace(text.charAt(next))) {
                    return next;
                }
            }
        }
        return targetEnd;
    }

    /**
     * 归一化：去 {@code \r}、修复被换行拆开的 URL、合并中文词中间的软换行
     * <p>
     * 两处绝不合并：跨空行（空行是段落分隔，合并会把图片链接与其后的标题粘连）、下一行像列表项开头（{@code 2.} / {@code 10)}）
     */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String src = text.replace("\r", "");
        StringBuilder out = new StringBuilder(src.length());
        boolean inUrl = false;

        for (int i = 0; i < src.length(); i++) {
            if (!inUrl && looksLikeUrlStart(src, i)) {
                inUrl = true;
            }
            char c = src.charAt(i);

            if (inUrl) {
                if (Character.isWhitespace(c)) {
                    int j = i;
                    int newlineCount = 0;
                    while (j < src.length() && Character.isWhitespace(src.charAt(j))) {
                        if (src.charAt(j) == '\n') {
                            newlineCount++;
                        }
                        j++;
                    }
                    boolean sawNewline = newlineCount > 0;
                    boolean blankLine = newlineCount >= 2;
                    char prev = i > 0 ? src.charAt(i - 1) : 0;
                    char next = j < src.length() ? src.charAt(j) : 0;

                    if (sawNewline && !blankLine && next != 0 && shouldJoinBrokenUrl(prev, next, src, j)) {
                        i = j - 1;
                        continue;
                    }
                    out.append(src, i, j);
                    inUrl = false;
                    i = j - 1;
                    continue;
                }
                out.append(c);
                if (!isUrlChar(c) && !isCommonUrlPunct(c)) {
                    inUrl = false;
                }
                continue;
            }

            if (c == '\n') {
                char prev = i > 0 ? src.charAt(i - 1) : 0;
                char next = i + 1 < src.length() ? src.charAt(i + 1) : 0;
                // 中文词被软换行拆开：商\n保通 → 商保通
                if (isCjkWordChar(prev) && isCjkWordChar(next)) {
                    continue;
                }
                out.append('\n');
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean shouldJoinBrokenUrl(char prev, char next, String s, int nextIndex) {
        if (isListItemStart(s, nextIndex)) {
            return false;
        }
        if (prev == '.' && Character.isLetter(next)) {
            return true;
        }
        if (prev == '/' || prev == '?' || prev == '&' || prev == '='
                || prev == '#' || prev == '%' || prev == '-' || prev == '_' || prev == ':') {
            return true;
        }
        return next == '/' || next == '?' || next == '&' || next == '=' || next == '#';
    }

    private static boolean isListItemStart(String s, int i) {
        int p = i;
        while (p < s.length() && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) {
            p++;
        }
        int start = p;
        while (p < s.length() && Character.isDigit(s.charAt(p))) {
            p++;
        }
        if (p == start) {
            return false;
        }
        return p < s.length() && (s.charAt(p) == '.' || s.charAt(p) == '）' || s.charAt(p) == ')');
    }

    private static boolean looksLikeUrlStart(String s, int i) {
        return s.startsWith("http://", i) || s.startsWith("https://", i);
    }

    private static boolean isUrlChar(char c) {
        if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
            return true;
        }
        return c == '-' || c == '.' || c == '_' || c == '~'
                || c == ':' || c == '/' || c == '?' || c == '#'
                || c == '[' || c == ']' || c == '@'
                || c == '!' || c == '$' || c == '&' || c == '\''
                || c == '(' || c == ')' || c == '*' || c == '+'
                || c == ',' || c == ';' || c == '=' || c == '%';
    }

    private static boolean isCommonUrlPunct(char c) {
        return c == '.' || c == '/' || c == '?' || c == '&' || c == '=' || c == '-' || c == '_' || c == '%';
    }

    private static boolean isCjkWordChar(char c) {
        if (c == 0 || Character.isWhitespace(c)) {
            return false;
        }
        return isCjkOrFullWidthLetterOrDigit(c) && !isCjkPunctuation(c);
    }

    private static boolean isCjkOrFullWidthLetterOrDigit(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    private static boolean isCjkPunctuation(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.GENERAL_PUNCTUATION
                || c == '。' || c == '，' || c == '、' || c == '；' || c == '：'
                || c == '！' || c == '？' || c == '（' || c == '）' || c == '【' || c == '】'
                || c == '《' || c == '》' || c == '“' || c == '”' || c == '‘' || c == '’';
    }
}
