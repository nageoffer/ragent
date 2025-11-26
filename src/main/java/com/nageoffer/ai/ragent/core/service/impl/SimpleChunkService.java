package com.nageoffer.ai.ragent.core.service.impl;

import com.nageoffer.ai.ragent.core.service.ChunkService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SimpleChunkService implements ChunkService {

    @Value("${kb.chunk.maxChars:1200}")
    private int maxChars;

    @Value("${kb.chunk.overlap:200}")
    private int overlap;

    private static final String SENTENCE_DELIMS = "[。！？!?；;]\\s*";

    @Override
    public List<Chunk> split(String text) {
        if (text == null || text.isBlank()) return List.of();

        // 先做“句子”粗切
        String[] sentences = text.split(SENTENCE_DELIMS);
        List<String> fragments = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (String s : sentences) {
            if (s == null || s.isBlank()) continue;
            String seg = s.trim();
            if (buf.length() + seg.length() + 1 <= maxChars) {
                if (!buf.isEmpty()) buf.append(' ');
                buf.append(seg);
            } else {
                if (!buf.isEmpty()) {
                    fragments.add(buf.toString());
                    buf.setLength(0);
                }
                // 当前句太长或单句已超限时，硬切
                for (int i = 0; i < seg.length(); i += maxChars) {
                    int end = Math.min(seg.length(), i + maxChars);
                    fragments.add(seg.substring(i, end));
                }
            }
        }
        if (!buf.isEmpty()) fragments.add(buf.toString());

        // 再做“重叠窗”重组
        if (overlap <= 0 || maxChars <= overlap) {
            List<Chunk> out = new ArrayList<>(fragments.size());
            for (int i = 0; i < fragments.size(); i++) out.add(new Chunk(i, fragments.get(i)));
            return out;
        }

        List<Chunk> finalChunks = new ArrayList<>();
        int idx = 0;
        for (String f : fragments) {
            if (f.length() <= maxChars) {
                finalChunks.add(new Chunk(idx++, f));
                continue;
            }
            for (int start = 0; start < f.length(); start += (maxChars - overlap)) {
                int end = Math.min(f.length(), start + maxChars);
                finalChunks.add(new Chunk(idx++, f.substring(start, end)));
                if (end == f.length()) break;
            }
        }
        return finalChunks;
    }
}
