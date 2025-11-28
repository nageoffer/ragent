package com.nageoffer.ai.ragent.rag.chunk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class SimpleChunkService implements ChunkService {

    @Value("${kb.chunk.max-chars:800}")
    private int chunkSize;

    @Value("${kb.chunk.overlap:200}")
    private int overlap;

    @Override
    public List<Chunk> split(String text) {
        List<String> pieces = splitIntoChunks(text, chunkSize, overlap);
        List<Chunk> out = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            out.add(new Chunk(i, pieces.get(i)));
        }
        return out;
    }

    /**
     * 简单的按长度切 chunk，带 overlap（防止 overlap >= chunkSize 导致卡死）
     */
    private List<String> splitIntoChunks(String text, int chunkSize, int overlap) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        // 防呆：确保步长 > 0
        int step = Math.max(1, chunkSize - Math.max(0, overlap));

        List<String> chunks = new ArrayList<>();
        int len = text.length();

        for (int start = 0; start < len; start += step) {
            int end = Math.min(start + chunkSize, len);
            String chunk = text.substring(start, end).trim();
            if (StringUtils.hasText(chunk)) {
                chunks.add(chunk);
            }
            if (end == len) {
                break;
            }
        }
        return chunks;
    }
}

