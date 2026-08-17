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
import com.nageoffer.ai.ragent.rag.core.graph.GraphFileSource;
import com.nageoffer.ai.ragent.rag.core.graph.LightRagClient;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 向量写入的图谱同步装饰器
 * <p>
 * 与 {@link KeywordSyncingVectorStoreService} 同构，包裹真实的 {@link VectorStoreService}，写入 / 删除
 * 成功后同步维护 LightRAG 知识图谱；图谱抽取是文档级（跨 chunk 合并实体）故按文档同步，写入以在手全量
 * 分块拼成全文，删除按文档清除，重建路径「先删后建」的既有调用顺序天然构成 upsert；单块粒度的
 * updateChunk / deleteChunkById / deleteChunksByIds 不同步图谱，整文重摄即可刷新，不为它引入
 * chunkId→docId 反查；图谱写入 best-effort，失败只记日志、不回滚向量、不中断主链路
 */
@Slf4j
public class GraphSyncingVectorStoreService implements VectorStoreService {

    private final VectorStoreService delegate;
    private final LightRagClient lightRagClient;

    public GraphSyncingVectorStoreService(VectorStoreService delegate, LightRagClient lightRagClient) {
        this.delegate = delegate;
        this.lightRagClient = lightRagClient;
    }

    @Override
    public void indexDocumentChunks(String collectionName, String docId, List<EmbeddedChunk> chunks) {
        delegate.indexDocumentChunks(collectionName, docId, chunks);
        syncGraph(docId, () -> lightRagClient.insertText(concatContent(chunks), fileSource(collectionName, docId)));
    }

    @Override
    public void updateChunk(String collectionName, String docId, EmbeddedChunk chunk) {
        delegate.updateChunk(collectionName, docId, chunk);
        // 子文档粒度，Phase1 不单独同步图谱（见类注释）
    }

    @Override
    public void deleteDocumentVectors(String collectionName, String docId) {
        delegate.deleteDocumentVectors(collectionName, docId);
        syncGraph(docId, () -> lightRagClient.deleteByDoc(docId));
    }

    @Override
    public void deleteChunkById(String collectionName, String chunkId) {
        delegate.deleteChunkById(collectionName, chunkId);
        // 子文档粒度，Phase1 不单独同步图谱（见类注释）
    }

    @Override
    public void deleteChunksByIds(String collectionName, List<String> chunkIds) {
        delegate.deleteChunksByIds(collectionName, chunkIds);
        // 子文档粒度，Phase1 不单独同步图谱（见类注释）
    }

    /**
     * 图谱 file_source 编码，读取侧的归属判定与删除匹配依赖同一格式，见 {@link GraphFileSource}
     */
    private String fileSource(String collectionName, String docId) {
        return GraphFileSource.encode(collectionName, docId);
    }

    /**
     * 拼接分块正文为文档全文，空白分块跳过
     */
    private String concatContent(List<EmbeddedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        return chunks.stream()
                .map(EmbeddedChunk::content)
                .filter(c -> c != null && !c.isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * best-effort 执行图谱同步，失败仅告警，不影响向量主链路
     */
    private void syncGraph(String docId, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("图谱同步失败，已跳过 docId={}", docId, e);
        }
    }
}
