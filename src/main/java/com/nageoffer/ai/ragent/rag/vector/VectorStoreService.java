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

package com.nageoffer.ai.ragent.rag.vector;

import com.nageoffer.ai.ragent.rag.chunk.Chunk;

import java.util.List;

public interface VectorStoreService {

    /**
     * 将指定文档的所有 chunk 建立/写入向量索引（实现里请携带 kbId、docId、chunkIndex、text 等元信息）
     */
    void indexDocumentChunks(String kbId, String docId, List<Chunk> chunks, float[][] vectors);

    /**
     * 更新单个 chunk 的向量索引
     */
    void updateChunk(String kbId, String docId, Chunk chunk, float[] vector);

    /**
     * 删除指定文档的所有 chunk 向量索引
     */
    void deleteDocumentVectors(String kbId, String docId);

    /**
     * 删除指定的单个 chunk 向量索引
     */
    void deleteChunkById(String kbId, String chunkId);
}
