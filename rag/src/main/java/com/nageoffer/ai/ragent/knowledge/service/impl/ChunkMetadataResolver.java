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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 分块元数据解析器
 * <p>
 * 检索命中的 {@code chunkId}（等于向量库主键，也等于 {@code t_knowledge_chunk.id}）批量回表，
 * 补齐其所属文档信息（文档ID、文档内序号、文档标题），供上下文组装时按文档聚合与标注来源
 * <p>
 * 只对已截断的最终结果集回表，行数小，两次批量查询开销可忽略
 */
@Service
@RequiredArgsConstructor
public class ChunkMetadataResolver {

    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper documentMapper;

    /**
     * 分块所属文档的元数据
     */
    public record ChunkMeta(String docId, Integer chunkIndex, String docName) {
    }

    /**
     * 批量解析分块元数据
     *
     * @param chunkIds 检索命中的分块 ID 集合
     * @return chunkId 到 {@link ChunkMeta} 的映射 未命中的分块不出现在结果中
     */
    public Map<String, ChunkMeta> resolve(Collection<String> chunkIds) {
        if (CollUtil.isEmpty(chunkIds)) {
            return Map.of();
        }
        Set<String> distinctIds = chunkIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        if (distinctIds.isEmpty()) {
            return Map.of();
        }

        List<KnowledgeChunkDO> chunks = chunkMapper.selectByIds(distinctIds);
        if (CollUtil.isEmpty(chunks)) {
            return Map.of();
        }

        Set<String> docIds = chunks.stream()
                .map(KnowledgeChunkDO::getDocId)
                .filter(docId -> docId != null && !docId.isBlank())
                .collect(Collectors.toSet());
        Map<String, String> docNameById = docIds.isEmpty()
                ? Map.of()
                : documentMapper.selectByIds(docIds).stream()
                .filter(doc -> doc.getId() != null && doc.getDocName() != null)
                .collect(Collectors.toMap(KnowledgeDocumentDO::getId, KnowledgeDocumentDO::getDocName, (a, b) -> a));

        Map<String, ChunkMeta> result = new HashMap<>(chunks.size());
        for (KnowledgeChunkDO chunk : chunks) {
            result.put(chunk.getId(), new ChunkMeta(
                    chunk.getDocId(),
                    chunk.getChunkIndex(),
                    docNameById.get(chunk.getDocId())));
        }
        return result;
    }

    /**
     * 按 docId 批量解析文档标题
     * <p>
     * 供图谱等在 {@code t_knowledge_chunk} 无对应行、但已带归属 docId 的证据补真实文档标题，
     * 使其与同源向量证据在上下文里聚合进同一文档块
     *
     * @param docIds 文档 ID 集合
     * @return docId 到文档标题的映射 未命中的不出现在结果中
     */
    public Map<String, String> resolveDocNames(Collection<String> docIds) {
        if (CollUtil.isEmpty(docIds)) {
            return Map.of();
        }
        Set<String> distinctIds = docIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        return documentMapper.selectByIds(distinctIds).stream()
                .filter(doc -> doc.getId() != null && doc.getDocName() != null)
                .collect(Collectors.toMap(KnowledgeDocumentDO::getId, KnowledgeDocumentDO::getDocName, (a, b) -> a));
    }
}
