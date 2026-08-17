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
 * 分块产物：不可变，不含向量
 * <p>
 * 构造期强制 {@code embeddingText} 非空，忘记注入章节上下文就构造不出对象
 *
 * @param index         块在文档中的序号，从 0 开始
 * @param content       文档原貌（markdown，标题按原文位置在正文内），回填 LLM 上下文与前端预览用
 * @param embeddingText 向量文本（章节路径 + 正文），不参与展示
 */
public record Chunk(
        String chunkId,
        int index,
        String content,
        String embeddingText,
        ChunkMetadata metadata
) {

    public Chunk {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("chunkId 不能为空");
        }
        if (index < 0) {
            throw new IllegalArgumentException("index 必须 >= 0，实际 " + index);
        }
        if (content == null) {
            throw new IllegalArgumentException("content 不能为 null，chunkId=" + chunkId);
        }
        if (embeddingText == null || embeddingText.isBlank()) {
            throw new IllegalArgumentException("embeddingText 不能为空，chunkId=" + chunkId
                    + "——向量文本由 chunker 基类统一组装章节上下文");
        }
        metadata = metadata == null ? ChunkMetadata.empty() : metadata;
    }

    /**
     * 复制并替换元数据：块级加工（摘要 / 关键词）写扩展位用，向量文本不受影响，向量在此之前已算完
     */
    public Chunk withMetadata(ChunkMetadata newMetadata) {
        return new Chunk(chunkId, index, content, embeddingText, newMetadata);
    }
}
