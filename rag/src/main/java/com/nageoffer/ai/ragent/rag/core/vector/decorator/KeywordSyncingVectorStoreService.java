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

package com.nageoffer.ai.ragent.rag.core.vector.decorator;

import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.rag.core.keyword.KeywordIndexService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 向量写入的关键词同步装饰器
 * <p>
 * 包裹真实的 {@link VectorStoreService}，在向量写入 / 更新 / 删除成功后同步维护 ES 关键词索引，一处
 * 覆盖全部向量写调用点；写入为 best-effort，失败只记日志、不回滚向量、不中断主链路，要求强一致时
 * 应改为监听 binlog（CDC）异步写，而非在此同步双写
 */
@Slf4j
public class KeywordSyncingVectorStoreService implements VectorStoreService {

    private final VectorStoreService delegate;
    private final KeywordIndexService keywordIndexService;

    public KeywordSyncingVectorStoreService(VectorStoreService delegate,
                                            KeywordIndexService keywordIndexService) {
        this.delegate = delegate;
        this.keywordIndexService = keywordIndexService;
    }

    @Override
    public void indexDocumentChunks(String collectionName, String docId, List<EmbeddedChunk> chunks) {
        delegate.indexDocumentChunks(collectionName, docId, chunks);
        syncKeyword(docId, () -> keywordIndexService.indexDocumentChunks(collectionName, docId, chunks));
    }

    @Override
    public void updateChunk(String collectionName, String docId, EmbeddedChunk chunk) {
        delegate.updateChunk(collectionName, docId, chunk);
        syncKeyword(docId, () -> keywordIndexService.updateChunk(collectionName, docId, chunk));
    }

    @Override
    public void deleteDocumentVectors(String collectionName, String docId) {
        delegate.deleteDocumentVectors(collectionName, docId);
        syncKeyword(docId, () -> keywordIndexService.deleteDocumentIndex(collectionName, docId));
    }

    @Override
    public void deleteChunkById(String collectionName, String chunkId) {
        delegate.deleteChunkById(collectionName, chunkId);
        syncKeyword(chunkId, () -> keywordIndexService.deleteChunkById(collectionName, chunkId));
    }

    @Override
    public void deleteChunksByIds(String collectionName, List<String> chunkIds) {
        delegate.deleteChunksByIds(collectionName, chunkIds);
        syncKeyword(null, () -> keywordIndexService.deleteChunksByIds(collectionName, chunkIds));
    }

    /**
     * best-effort 执行关键词同步，失败仅告警，不影响向量主链路
     */
    private void syncKeyword(String docId, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("关键词索引同步失败，已跳过 docId={}", docId, e);
        }
    }
}
