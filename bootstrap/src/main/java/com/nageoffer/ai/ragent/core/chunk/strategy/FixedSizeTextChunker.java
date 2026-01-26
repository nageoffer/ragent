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

package com.nageoffer.ai.ragent.core.chunk.strategy;

import cn.hutool.core.util.IdUtil;
import com.nageoffer.ai.ragent.core.chunk.AbstractEmbeddingChunker;
import com.nageoffer.ai.ragent.core.chunk.ChunkingMode;
import com.nageoffer.ai.ragent.core.chunk.ChunkingOptions;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingClient;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定大小分块器
 * - 按 chunkSize 切分
 * - 相邻 chunk 保留 overlapSize 重叠
 * - 在边界符（换行/句末标点等）处向前对齐 end
 * 增强：
 * 1) 归一化：修复 URL 内“被换行拆开”的情况，但避免误吞段落换行/列表换行
 * 2) 英文 '.' 不再无条件当边界，避免切烂 URL 域名
 * 3) 边界回退距离 <= overlap（避免出现 chunk 几乎全重复）
 */
@Component
public class FixedSizeTextChunker extends AbstractEmbeddingChunker {

    public FixedSizeTextChunker(ModelSelector modelSelector, List<EmbeddingClient> embeddingClients) {
        super(modelSelector, embeddingClients);
    }

    @Override
    public ChunkingMode getType() {
        return ChunkingMode.FIXED_SIZE;
    }

    @Override
    protected List<VectorChunk> doChunk(String text, ChunkingOptions config) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        // 1) 更保守的归一化：只修 URL 明显断行，不吞正常换行
        String normalized = normalizeText(text);

        int chunkSize = Math.max(1, config.getChunkSize());
        int overlap = Math.max(0, config.getOverlapSize());

        if (chunkSize == Integer.MAX_VALUE) {
            return List.of(VectorChunk.builder()
                    .chunkId(IdUtil.getSnowflakeNextIdStr())
                    .index(0)
                    .content(normalized)
                    .build());
        }

        if (chunkSize > 1) {
            overlap = Math.min(overlap, chunkSize - 1);
        } else {
            overlap = 0;
        }

        int len = normalized.length();
        List<VectorChunk> chunks = new ArrayList<>();

        int index = 0;
        int start = 0;
        int lastEnd = -1;

        while (start < len) {
            int targetEnd = Math.min(start + chunkSize, len);
            int end = adjustToBoundary(normalized, start, targetEnd, overlap);

            // 强制推进，避免回退过头导致重复/停滞
            if (end <= start || end <= lastEnd) {
                end = targetEnd;
            }

            String content = normalized.substring(start, end);
            if (StringUtils.hasText(content.strip())) {
                chunks.add(VectorChunk.builder()
                        .chunkId(IdUtil.getSnowflakeNextIdStr())
                        .index(index++)
                        .content(content)
                        .build());
            }

            lastEnd = end;
            if (end >= len) break;

            int nextStart = Math.max(0, end - overlap);
            if (nextStart <= start) nextStart = end;
            start = nextStart;
        }

        return chunks;
    }

    /**
     * 调整分块边界：
     * - 优先：换行
     * - 其次：中文句末标点（。！？）
     * - 再次：英文 .!?（仅当后面是空白/换行/结束 才算边界，避免切 URL 域名点号）
     * <p>
     * 回退距离 <= overlap，避免 chunk 高度重复。
     */
    private int adjustToBoundary(String text, int start, int targetEnd, int overlap) {
        if (targetEnd <= start) return targetEnd;

        int maxLookback = Math.min(overlap, targetEnd - start);
        if (maxLookback <= 0) return targetEnd;

        // 1) 换行
        for (int i = 0; i <= maxLookback; i++) {
            int pos = targetEnd - i - 1;
            if (pos <= start) break;
            if (text.charAt(pos) == '\n') return pos + 1;
        }

        // 2) 中文句末标点
        for (int i = 0; i <= maxLookback; i++) {
            int pos = targetEnd - i - 1;
            if (pos <= start) break;
            char c = text.charAt(pos);
            if (c == '。' || c == '！' || c == '？') return pos + 1;
        }

        // 3) 英文句末标点：后面必须是空白/换行/结束
        for (int i = 0; i <= maxLookback; i++) {
            int pos = targetEnd - i - 1;
            if (pos <= start) break;
            char c = text.charAt(pos);
            if (c == '.' || c == '!' || c == '?') {
                int next = pos + 1;
                if (next >= text.length()) return next;
                if (Character.isWhitespace(text.charAt(next))) return next;
            }
        }

        return targetEnd;
    }

    /**
     * 归一化输入：
     * - 去掉 \r
     * - 修复“URL 被换行拆开”的情况（比如 dingtalk.\ncom、/i/nodes\n/...）
     * - 但如果换行后是“2.” 这种列表项开头，绝不合并（避免吞段落）
     * - URL 结束时保留原始空白（包括空行）
     * - 修复中文词中间软换行（商\n保通 -> 商保通）
     */
    private String normalizeText(String text) {
        if (text == null || text.isEmpty()) return text;

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
                    boolean sawNewline = false;
                    while (j < src.length() && Character.isWhitespace(src.charAt(j))) {
                        if (src.charAt(j) == '\n') sawNewline = true;
                        j++;
                    }

                    char prev = (i > 0) ? src.charAt(i - 1) : 0;
                    char next = (j < src.length()) ? src.charAt(j) : 0;

                    // 只在“很像 URL 被拆开”的情况下合并空白
                    if (sawNewline && next != 0 && shouldJoinBrokenUrl(prev, next, src, j)) {
                        i = j - 1;
                        continue;
                    }

                    // URL 结束：保留原始空白（包括空行）
                    out.append(src, i, j);
                    inUrl = false;
                    i = j - 1;
                    continue;
                }

                out.append(c);

                // 遇到明显不可能属于 URL 的字符，退出 URL 状态
                if (!isUrlChar(c) && !isCommonUrlPunct(c)) {
                    inUrl = false;
                }
                continue;
            }

            // 非 URL 状态：修复中文词中间软换行（商\n保通 -> 商保通）
            if (c == '\n') {
                char prev = (i > 0) ? src.charAt(i - 1) : 0;
                char next = (i + 1 < src.length()) ? src.charAt(i + 1) : 0;

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

    /**
     * 判断：URL 内遇到换行/空白时，是否应该把空白删掉并继续拼接 URL
     * 关键：避免把 “\n2.”（列表项）吞掉。
     */
    private boolean shouldJoinBrokenUrl(char prev, char next, String s, int nextIndex) {
        // 如果下一行像 “2.” “10.” 这种列表项开头 -> 绝不合并
        if (isListItemStart(s, nextIndex)) {
            return false;
        }

        // 典型的 URL 断行场景：在这些字符后面换行，后续大概率还是 URL
        if (prev == '.' && Character.isLetter(next)) return true;                 // dingtalk.\ncom
        if (prev == '/' || prev == '?' || prev == '&' || prev == '='
                || prev == '#' || prev == '%' || prev == '-' || prev == '_'
                || prev == ':') return true;                                       // /i/nodes\n/...  ?\nutm=...

        // 或者下一段本身以 URL 结构符号开头
        if (next == '/' || next == '?' || next == '&' || next == '=' || next == '#') return true;

        // 其他情况更保守：不合并，保留换行
        return false;
    }

    private boolean isListItemStart(String s, int i) {
        // 跳过可能存在的空格/制表符（一般是新行后的缩进）
        int p = i;
        while (p < s.length() && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;

        int start = p;
        while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
        if (p == start) return false;

        // 数字后紧跟 '.' 或 '）' / ')' 也常见
        if (p < s.length() && (s.charAt(p) == '.' || s.charAt(p) == '）' || s.charAt(p) == ')')) {
            return true;
        }
        return false;
    }

    private boolean looksLikeUrlStart(String s, int i) {
        if (i < 0 || i >= s.length()) return false;
        return s.startsWith("http://", i) || s.startsWith("https://", i);
    }

    private boolean isUrlChar(char c) {
        if (c >= 'a' && c <= 'z') return true;
        if (c >= 'A' && c <= 'Z') return true;
        if (c >= '0' && c <= '9') return true;

        return c == '-' || c == '.' || c == '_' || c == '~'
                || c == ':' || c == '/' || c == '?' || c == '#'
                || c == '[' || c == ']' || c == '@'
                || c == '!' || c == '$' || c == '&' || c == '\''
                || c == '(' || c == ')' || c == '*' || c == '+'
                || c == ',' || c == ';' || c == '=' || c == '%';
    }

    private boolean isCommonUrlPunct(char c) {
        return c == '.' || c == '/' || c == '?' || c == '&' || c == '=' || c == '-' || c == '_' || c == '%';
    }

    private boolean isCjkWordChar(char c) {
        if (c == 0) return false;
        if (Character.isWhitespace(c)) return false;
        if (!isCjkOrFullWidthLetterOrDigit(c)) return false;
        return !isCjkPunctuation(c);
    }

    private boolean isCjkOrFullWidthLetterOrDigit(char c) {
        if (c == 0) return false;
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    private boolean isCjkPunctuation(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.GENERAL_PUNCTUATION
                || c == '。' || c == '，' || c == '、' || c == '；' || c == '：'
                || c == '！' || c == '？' || c == '（' || c == '）' || c == '【' || c == '】'
                || c == '《' || c == '》' || c == '“' || c == '”' || c == '‘' || c == '’';
    }
}
