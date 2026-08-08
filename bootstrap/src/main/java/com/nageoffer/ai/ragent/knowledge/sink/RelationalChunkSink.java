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

package com.nageoffer.ai.ragent.knowledge.sink;

import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.ingest.DocumentRef;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.core.ingest.sink.ChunkSink;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 关系库落点：写 {@code t_knowledge_chunk}，展示文本与向量文本一并落库
 * <p>
 * {@code embedding_text} 落库不是为了展示：它让换嵌入模型时可以直接重嵌入而不必重新解析（省掉版面
 * 解析与视觉模型的重复成本），也让人工编辑单块后能正确重算向量文本
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RelationalChunkSink implements ChunkSink {

    private final KnowledgeChunkMapper chunkMapper;
    private final TokenCounterService tokenCounterService;
    private final KnowledgeDocumentMapper documentMapper;

    @Override
    public void replaceDocument(VectorTarget target, DocumentRef doc, List<EmbeddedChunk> chunks) {
        // 分块可能执行超过恢复阈值：恢复任务将 RUNNING 改为 FAILED 后，用户可以取得删除权；
        // 此时原分块任务仍可能在执行，并在完成 Embedding 后重新写入数据。
        // 这里通过持有文档行锁，使删除状态 CAS 等待到分块写入事务结束，避免删除过程中被旧任务重新写入数据。
        String status = documentMapper.selectStatusForUpdate(doc.docId());
        if (!DocumentStatus.RUNNING.getCode().equals(status)) {
            throw new IllegalStateException(
                    "文档状态已变更，放弃分块写入: docId=" + doc.docId() + ", status=" + status
            );
        }
        deleteDocument(target, doc);
        if (chunks.isEmpty()) {
            return;
        }
        String username = UserContext.getUsername();
        List<KnowledgeChunkDO> rows = new ArrayList<>(chunks.size());
        for (EmbeddedChunk chunk : chunks) {
            String content = chunk.content();
            rows.add(KnowledgeChunkDO.builder()
                    .id(chunk.chunkId())
                    .kbId(doc.kbId())
                    .docId(doc.docId())
                    .chunkIndex(chunk.index())
                    .content(content)
                    .contentHash(SecureUtil.sha256(content))
                    .charCount(content.length())
                    .tokenCount(StringUtils.hasText(content) ? tokenCounterService.countTokens(content) : 0)
                    .embeddingText(chunk.embeddingText())
                    .enabled(1)
                    .createdBy(username)
                    .updatedBy(username)
                    .build());
        }
        chunkMapper.insert(rows);
        log.debug("关系库块写入完成 docId={} 块数={}", doc.docId(), rows.size());
    }

    @Override
    public void deleteDocument(VectorTarget target, DocumentRef doc) {
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkDO>()
                .eq(KnowledgeChunkDO::getDocId, doc.docId()));
    }
}
