package com.nageoffer.ai.ragent.rag.chunk;

import java.util.List;

public interface ChunkService {

    /**
     * 依据固定长度与重叠窗口进行分块，自动按照标点做轻量断句优化
     */
    List<Chunk> split(String text);
}
