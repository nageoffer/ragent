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
 * 按段落进行文本分块的策略实现类
 */
@Component
public class ParagraphChunker implements ChunkingStrategy {

    @Override
    public ChunkStrategy getStrategyType() {
        return ChunkStrategy.PARAGRAPH;
    }

    @Override
    public List<DocumentChunk> chunk(String text, ChunkerSettings settings) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        int chunkSize = settings != null && settings.getChunkSize() != null ? settings.getChunkSize() : 512;
        int overlap = settings != null && settings.getOverlapSize() != null ? settings.getOverlapSize() : 128;

        List<Span> paragraphs = splitParagraphs(text);
        if (paragraphs.isEmpty()) {
            return List.of();
        }

        List<DocumentChunk> chunks = new ArrayList<>();
        int index = 0;
        int paraIndex = 0;
        int nextStart = paragraphs.get(0).start;

        while (paraIndex < paragraphs.size()) {
            Span first = paragraphs.get(paraIndex);
            int chunkStart = Math.max(nextStart, first.start);
            int chunkEnd = chunkStart;
            int cursor = paraIndex;
            while (cursor < paragraphs.size()) {
                Span span = paragraphs.get(cursor);
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
            paraIndex = findParagraphIndex(paragraphs, nextStart);
        }

        return chunks;
    }

    private List<Span> splitParagraphs(String text) {
        List<Span> spans = new ArrayList<>();
        int len = text.length();
        int start = 0;
        int i = 0;
        while (i < len) {
            if (text.charAt(i) == '\n') {
                int j = i;
                while (j < len && text.charAt(j) == '\n') {
                    j++;
                }
                if (j - i >= 2) {
                    spans.add(new Span(start, i));
                    start = j;
                }
                i = j;
            } else {
                i++;
            }
        }
        if (start < len) {
            spans.add(new Span(start, len));
        }
        return spans;
    }

    private int findParagraphIndex(List<Span> spans, int start) {
        for (int i = 0; i < spans.size(); i++) {
            Span span = spans.get(i);
            if (span.end > start) {
                return i;
            }
        }
        return spans.size();
    }

    private record Span(int start, int end) {
    }
}
