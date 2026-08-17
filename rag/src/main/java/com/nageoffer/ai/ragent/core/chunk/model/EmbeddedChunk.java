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
 * 已向量化的块：索引层的唯一入参
 * <p>
 * 与 {@link Chunk} 分开是为了让未向量化的块在类型上就进不了索引层；record 的 equals / hashCode 对数组按引用比较，
 * 此类型仅作数据传输，不应放进 Set 或作为 Map 键
 *
 * @param embedding 向量，维度由部署级配置固定，写入前已在向量化阶段校验
 */
public record EmbeddedChunk(Chunk chunk, float[] embedding) {

    public EmbeddedChunk {
        if (chunk == null) {
            throw new IllegalArgumentException("chunk 不能为 null");
        }
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("embedding 不能为空，chunkId=" + chunk.chunkId());
        }
    }

    public String chunkId() {
        return chunk.chunkId();
    }

    public int index() {
        return chunk.index();
    }

    public String content() {
        return chunk.content();
    }

    public String embeddingText() {
        return chunk.embeddingText();
    }

    public ChunkMetadata metadata() {
        return chunk.metadata();
    }

    public int dimension() {
        return embedding.length;
    }
}
