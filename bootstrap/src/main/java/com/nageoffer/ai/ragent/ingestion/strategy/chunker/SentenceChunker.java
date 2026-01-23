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

package com.nageoffer.ai.ragent.ingestion.strategy.chunker;

import cn.hutool.core.util.IdUtil;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentChunk;
import com.nageoffer.ai.ragent.ingestion.domain.enums.ChunkStrategy;
import com.nageoffer.ai.ragent.ingestion.domain.settings.ChunkerSettings;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 句子切分策略实现类
 * 该类通过识别句子边界（如标点符号和换行符）将文本切分为多个分块（Chunk）
 */
@Component
public class SentenceChunker implements ChunkingStrategy {

    @Override
    public ChunkStrategy getStrategyType() {
        return ChunkStrategy.SENTENCE;
    }

    @Override
    public List<DocumentChunk> chunk(String text, ChunkerSettings settings) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        int chunkSize = settings != null && settings.getChunkSize() != null ? settings.getChunkSize() : 512;
        int overlap = settings != null && settings.getOverlapSize() != null ? settings.getOverlapSize() : 128;

        List<Span> sentences = splitSentences(text);
        if (sentences.isEmpty()) {
            return List.of();
        }

        List<DocumentChunk> chunks = new ArrayList<>();
        int index = 0;
        int sentenceIndex = 0;
        int nextStart = sentences.get(0).start;

        while (sentenceIndex < sentences.size()) {
            Span first = sentences.get(sentenceIndex);
            int chunkStart = Math.max(nextStart, first.start);
            int chunkEnd = chunkStart;
            int cursor = sentenceIndex;
            while (cursor < sentences.size()) {
                Span span = sentences.get(cursor);
                int candidateEnd = span.end;
                int candidateSize = candidateEnd - chunkStart;
                if (candidateSize > chunkSize && chunkEnd > chunkStart) {
                    break;
                }
                chunkEnd = candidateEnd;
                cursor++;
                if (candidateSize >= chunkSize) {
                    break;
                }
            }

            String content = text.substring(chunkStart, chunkEnd).trim();
            if (StringUtils.hasText(content)) {
                chunks.add(DocumentChunk.builder()
                        .chunkId(IdUtil.getSnowflakeNextIdStr())
                        .index(index++)
                        .content(content)
                        .startOffset(chunkStart)
                        .endOffset(chunkEnd)
                        .build());
            }
            if (chunkEnd >= text.length()) {
                break;
            }
            nextStart = Math.max(chunkEnd - Math.max(0, overlap), chunkStart);
            sentenceIndex = findSentenceIndex(sentences, nextStart);
        }

        return chunks;
    }

    private List<Span> splitSentences(String text) {
        List<Span> spans = new ArrayList<>();
        int len = text.length();
        int start = 0;
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (isBoundary(c)) {
                int end = i + 1;
                spans.add(new Span(start, end));
                start = end;
            }
        }
        if (start < len) {
            spans.add(new Span(start, len));
        }
        return spans;
    }

    private boolean isBoundary(char c) {
        return c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？' || c == '\n';
    }

    private int findSentenceIndex(List<Span> sentences, int start) {
        for (int i = 0; i < sentences.size(); i++) {
            Span span = sentences.get(i);
            if (span.end > start) {
                return i;
            }
        }
        return sentences.size();
    }

    private record Span(int start, int end) {
    }
}
