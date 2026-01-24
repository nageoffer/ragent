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

package com.nageoffer.ai.ragent.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.controller.request.KnowledgeChunkCreateRequest;
import com.nageoffer.ai.ragent.controller.vo.KnowledgeDocumentVO;
import com.nageoffer.ai.ragent.core.chunk.ChunkingMode;
import com.nageoffer.ai.ragent.core.chunk.ChunkingOptions;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategyFactory;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategy;
import com.nageoffer.ai.ragent.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.enums.DocumentStatus;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.extractor.DocumentTextExtractor;
import com.nageoffer.ai.ragent.rag.vector.VectorStoreService;
import com.nageoffer.ai.ragent.service.FileStorageService;
import com.nageoffer.ai.ragent.service.KnowledgeChunkService;
import com.nageoffer.ai.ragent.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeDocumentMapper docMapper;
    private final DocumentTextExtractor textExtractor;
    private final ChunkingStrategyFactory chunkingStrategyFactory;
    private final FileStorageService fileStorageService;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final KnowledgeChunkService knowledgeChunkService;

    @Value("${kb.chunk.semantic.targetChars:1400}")
    private int targetChars;
    @Value("${kb.chunk.semantic.maxChars:1800}")
    private int maxChars;
    @Value("${kb.chunk.semantic.minChars:600}")
    private int minChars;
    @Value("${kb.chunk.semantic.overlapChars:0}")
    private int overlapChars;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocumentVO upload(String kbId, MultipartFile file) {
        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.notNull(kbDO, () -> new ClientException("知识库不存在"));

        StoredFileDTO stored = fileStorageService.upload(kbDO.getCollectionName(), file);

        KnowledgeDocumentDO documentDO = KnowledgeDocumentDO.builder()
                .kbId(Long.parseLong(kbId))
                .docName(stored.getOriginalFilename())
                .enabled(1)
                .chunkCount(0)
                .fileUrl(stored.getUrl())
                .fileType(stored.getDetectedType())
                .fileSize(stored.getSize())
                .status(DocumentStatus.PENDING.getCode())
                .createdBy("")
                .build();
        docMapper.insert(documentDO);

        return BeanUtil.toBean(documentDO, KnowledgeDocumentVO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startChunk(String docId) {
        KnowledgeDocumentDO documentDO = docMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        Assert.isTrue(!DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus()), () -> new ClientException("文档分块进行中"));

        boolean alreadyChunked = knowledgeChunkService.existsByDocId(docId);
        Assert.isFalse(alreadyChunked, () -> new ClientException("文档已分块"));

        patchStatus(documentDO, DocumentStatus.RUNNING);

        try (InputStream is = fileStorageService.openStream(documentDO.getFileUrl())) {
            String text = textExtractor.extract(is, documentDO.getDocName());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("targetChars", targetChars);
            metadata.put("maxChars", maxChars);
            metadata.put("minChars", minChars);

            ChunkingOptions config = ChunkingOptions.builder()
                    .chunkSize(targetChars)
                    .overlapSize(overlapChars)
                    .metadata(metadata)
                    .build();
            // TODO 此处应该获取请求对应的分块策略，目前默认使用结构-aware分块策略
            ChunkingStrategy chunkingStrategy = chunkingStrategyFactory.requireStrategy(ChunkingMode.STRUCTURE_AWARE);

            List<VectorChunk> chunkResults = chunkingStrategy.chunk(text, config);
            List<KnowledgeChunkCreateRequest> chunks = chunkResults.stream()
                    .map(result -> {
                        KnowledgeChunkCreateRequest req = new KnowledgeChunkCreateRequest();
                        req.setChunkId(result.getChunkId());
                        req.setIndex(result.getIndex());
                        req.setContent(result.getContent());
                        return req;
                    })
                    .toList();
            knowledgeChunkService.batchCreate(docId, chunks);

            documentDO.setChunkCount(chunks.size());
            patchStatus(documentDO, DocumentStatus.SUCCESS);
            docMapper.updateById(documentDO);

            List<String> texts = chunkResults.stream().map(VectorChunk::getContent).toList();
            float[][] vectors = new float[texts.size()][];
            for (int i = 0; i < texts.size(); i++) {
                vectors[i] = toArray(embeddingService.embed(texts.get(i)));
            }

            List<VectorChunk> legacyChunks = chunkResults.stream()
                    .map(result -> VectorChunk.builder()
                            .chunkId(result.getChunkId())
                            .index(result.getIndex())
                            .content(result.getContent())
                            .build())
                    .toList();

            vectorStoreService.indexDocumentChunks(String.valueOf(documentDO.getKbId()), docId, legacyChunks, vectors);
        } catch (Exception e) {
            log.error("文件分块失败：docId={}", docId, e);
            patchStatus(documentDO, DocumentStatus.FAILED);
            throw new ServiceException("分块失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String docId) {
        KnowledgeDocumentDO documentDO = docMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        documentDO.setDeleted(1);
        documentDO.setUpdatedBy("");
        docMapper.updateById(documentDO);

        vectorStoreService.deleteDocumentVectors(String.valueOf(documentDO.getKbId()), docId);
    }

    @Override
    public KnowledgeDocumentVO get(String docId) {
        KnowledgeDocumentDO documentDO = docMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        return BeanUtil.toBean(documentDO, KnowledgeDocumentVO.class);
    }

    @Override
    public IPage<KnowledgeDocumentVO> page(String kbId, Page<KnowledgeDocumentVO> page, String status, String keyword) {
        Page<KnowledgeDocumentDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        LambdaQueryWrapper<KnowledgeDocumentDO> qw = new LambdaQueryWrapper<KnowledgeDocumentDO>()
                .eq(KnowledgeDocumentDO::getKbId, kbId)
                .eq(KnowledgeDocumentDO::getDeleted, 0)
                .like(keyword != null && !keyword.isBlank(), KnowledgeDocumentDO::getDocName, keyword)
                .eq(status != null && !status.isBlank(), KnowledgeDocumentDO::getStatus, status)
                .orderByDesc(KnowledgeDocumentDO::getUpdateTime);

        IPage<KnowledgeDocumentDO> result = docMapper.selectPage(mpPage, qw);

        Page<KnowledgeDocumentVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(each -> BeanUtil.toBean(each, KnowledgeDocumentVO.class)).toList());
        return voPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enable(String docId, boolean enabled) {
        KnowledgeDocumentDO documentDO = docMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        documentDO.setEnabled(enabled ? 1 : 0);
        documentDO.setUpdatedBy("");
        docMapper.updateById(documentDO);

        if (!enabled) {
            vectorStoreService.deleteDocumentVectors(String.valueOf(documentDO.getKbId()), docId);
        }
        // TODO 启用文档时，需要读取MySQL数据库Chunk记录，并更新向量库
    }

    private void patchStatus(KnowledgeDocumentDO doc, DocumentStatus status) {
        doc.setStatus(status.getCode());
        doc.setUpdatedBy("");
        docMapper.updateById(doc);
    }

    private static float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }
}
