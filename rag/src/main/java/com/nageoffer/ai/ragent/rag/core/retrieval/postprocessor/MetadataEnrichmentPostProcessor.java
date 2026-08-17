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

package com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.service.impl.ChunkMetadataResolver;
import com.nageoffer.ai.ragent.knowledge.service.impl.ChunkMetadataResolver.ChunkMeta;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 元数据富化后置处理器
 * <p>
 * 处于处理链末端（Rerank 之后），对最终 Top-K 结果按 chunkId 回表补齐文档归属信息
 * （文档ID、文档内序号、文档标题），供上下文组装时按文档聚合与标注来源
 * <p>
 * 只富化、不重排：保持进入时的相关性顺序不变
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataEnrichmentPostProcessor implements SearchResultPostProcessor {

    private final ChunkMetadataResolver chunkMetadataResolver;
    private final RAGConfigProperties ragConfigProperties;

    @Override
    public String getName() {
        return "MetadataEnrichment";
    }

    @Override
    public int getOrder() {
        return 20;  // Rerank(10) 之后，链末执行
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return ragConfigProperties.getContextEnrichEnabled();
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        if (chunks.isEmpty()) {
            return chunks;
        }

        List<String> chunkIds = chunks.stream().map(RetrievedChunk::getId).toList();
        Map<String, ChunkMeta> metaById = chunkMetadataResolver.resolve(chunkIds);

        // 1）按 chunkId 富化：向量 / 关键词证据的 chunk.id 即向量库主键，回表补齐 docId / 序号 / 标题
        // 原地富化，保持相关性顺序不变
        for (RetrievedChunk chunk : chunks) {
            ChunkMeta meta = metaById.get(chunk.getId());
            if (meta == null) {
                continue;
            }
            chunk.setDocId(meta.docId());
            chunk.setChunkIndex(meta.chunkIndex());
            chunk.setDocName(meta.docName());
        }

        // 2）按 docId 补标题：图谱证据的 chunk.id 非向量库主键、上一步未命中，但已带归属 docId，
        // 据此补真实文档标题，使其与同源向量证据在上下文里聚合进同一文档块
        fillDocNamesByDocId(chunks);
        return chunks;
    }

    /**
     * 对上一步按 chunkId 未补到标题、但已带 docId 的证据（典型为图谱证据）按 docId 回表补真实文档标题
     */
    private void fillDocNamesByDocId(List<RetrievedChunk> chunks) {
        List<String> pendingDocIds = chunks.stream()
                .filter(c -> StrUtil.isBlank(c.getDocName()) && StrUtil.isNotBlank(c.getDocId()))
                .map(RetrievedChunk::getDocId)
                .toList();
        if (pendingDocIds.isEmpty()) {
            return;
        }
        Map<String, String> docNameById = chunkMetadataResolver.resolveDocNames(pendingDocIds);
        if (docNameById.isEmpty()) {
            return;
        }
        for (RetrievedChunk chunk : chunks) {
            if (StrUtil.isBlank(chunk.getDocName()) && StrUtil.isNotBlank(chunk.getDocId())) {
                String docName = docNameById.get(chunk.getDocId());
                if (StrUtil.isNotBlank(docName)) {
                    chunk.setDocName(docName);
                }
            }
        }
    }
}
