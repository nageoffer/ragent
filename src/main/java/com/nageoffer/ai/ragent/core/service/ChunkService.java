package com.nageoffer.ai.ragent.core.service;

import java.util.List;

public interface ChunkService {

    record Chunk(int index, String content) {
    }

    /**
     * 依据固定长度与重叠窗口进行分块，自动按照标点做轻量断句优化
     */
    List<Chunk> split(String text);
}
