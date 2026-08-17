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

package com.nageoffer.ai.ragent.core.chunk.model;

/**
 * 分块预算：用户唯一可配的分块自由度
 * <p>
 * 切法由文档结构唯一决定，用户只控制体量与冗余度；per-Block 的细粒度参数（列表项上限等）不对外暴露，
 * 保持为各 chunker 的内部常量
 *
 * @param overlapChars     相邻块重叠字符数，必须小于 maxChars
 * @param rowsPerChunk     表格类每块包含的数据行上限
 * @param toleranceFactor  为保完整性允许超出 maxChars 的倍数，详见 {@link #toleranceChars()}
 */
public record ChunkBudget(int maxChars, int overlapChars, int rowsPerChunk, int toleranceFactor) {

    /**
     * 整文档单块的哨兵值：由 {@link #wholeDocument()} 构造、{@link #isWholeDocument()} 判定，调用方不需要知道它本身
     */
    private static final int WHOLE_DOCUMENT = Integer.MAX_VALUE;

    private static final int DEFAULT_TOLERANCE_FACTOR = 3;

    private static final int DEFAULT_MAX_CHARS = 1024;

    /**
     * 块重叠默认取块大小的几分之一
     */
    public static final int OVERLAP_DIVISOR = 8;

    /** 容忍倍数上限：乘出来的值仍受 {@link #MAX_CHARS_LIMIT} 封顶，此处只拦明显填错的量级 */
    public static final int TOLERANCE_FACTOR_LIMIT = 8;

    /**
     * 块大小上限
     * <p>
     * 真正的硬约束是嵌入模型的输入长度，此处取一个在用模型都容得下的保守值，并在构造期就拦住，
     * 否则超长块要到嵌入那一步才炸
     */
    public static final int MAX_CHARS_LIMIT = 8192;

    /**
     * 表格每块行数上限，同样在构造期校验
     */
    public static final int ROWS_PER_CHUNK_LIMIT = 1000;

    public ChunkBudget {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars 必须 > 0，实际 " + maxChars);
        }
        // 整篇不分块按定义就是"不给预算"，两条上限对它不适用
        if (maxChars != WHOLE_DOCUMENT) {
            if (maxChars > MAX_CHARS_LIMIT) {
                throw new IllegalArgumentException("maxChars 不得超过 " + MAX_CHARS_LIMIT + "，实际 " + maxChars);
            }
            if (rowsPerChunk > ROWS_PER_CHUNK_LIMIT) {
                throw new IllegalArgumentException("rowsPerChunk 不得超过 " + ROWS_PER_CHUNK_LIMIT + "，实际 " + rowsPerChunk);
            }
        }
        if (overlapChars < 0 || overlapChars >= maxChars) {
            throw new IllegalArgumentException("overlapChars 必须落在 [0, maxChars) 区间，实际 " + overlapChars);
        }
        if (rowsPerChunk <= 0) {
            throw new IllegalArgumentException("rowsPerChunk 必须 > 0，实际 " + rowsPerChunk);
        }
        if (toleranceFactor < 1 || toleranceFactor > TOLERANCE_FACTOR_LIMIT) {
            throw new IllegalArgumentException("toleranceFactor 必须落在 [1, " + TOLERANCE_FACTOR_LIMIT
                    + "] 区间，实际 " + toleranceFactor);
        }
    }

    /** 三参构造：容忍倍数取默认值，供尚未开放该配置项的调用方使用 */
    public ChunkBudget(int maxChars, int overlapChars, int rowsPerChunk) {
        this(maxChars, overlapChars, rowsPerChunk, DEFAULT_TOLERANCE_FACTOR);
    }

    /**
     * 给定块大小对应的默认重叠
     * <p>
     * 重叠不只为冗余，它同时是 {@link com.nageoffer.ai.ragent.core.chunk.text.TextSplitter} 回退寻找句末标点的
     * 最大距离，取小了切口会落在句子中间——1024 配 64 对中文长句就常常回退不到句号，故按块大小等比给
     */
    public static int defaultOverlapFor(int maxChars) {
        return Math.max(0, Math.min(maxChars - 1, maxChars / OVERLAP_DIVISOR));
    }

    /**
     * 系统默认预算：全局唯一一份默认值
     * <p>
     * {@code maxChars} 取 1024 而非常见的 512：防语义稀释靠 {@code ChunkPacker} 的章节边界而非把块压小，
     * 而 {@code RetrieveRequest.topK} 默认 5，块太小会让名额被同一章节的碎片占满，它真正切开的只有超长散文段落
     */
    public static ChunkBudget defaults() {
        return new ChunkBudget(DEFAULT_MAX_CHARS, defaultOverlapFor(DEFAULT_MAX_CHARS), 50);
    }

    /**
     * 整文档不切分：全文作为单块
     */
    public static ChunkBudget wholeDocument() {
        return new ChunkBudget(WHOLE_DOCUMENT, 0, WHOLE_DOCUMENT);
    }

    /**
     * 为保完整性允许撑到的字符数：{@code maxChars} 是目标而非硬上限，切开语义单元的代价高于超出目标
     * <p>
     * 两处用它，作用在互不冲突的阶段：各 chunker 据此决定要不要切开一个 Block（表格、代码块），
     * {@code ChunkPacker} 据此决定整个章节能否不拆成一块；乘出来的值仍受 {@link #MAX_CHARS_LIMIT} 封顶
     */
    public int toleranceChars() {
        return isWholeDocument() ? WHOLE_DOCUMENT : Math.min(maxChars * toleranceFactor, MAX_CHARS_LIMIT);
    }

    public boolean isWholeDocument() {
        return maxChars == WHOLE_DOCUMENT;
    }
}
