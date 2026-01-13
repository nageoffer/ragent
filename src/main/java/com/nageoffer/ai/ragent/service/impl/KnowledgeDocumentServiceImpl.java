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
import com.nageoffer.ai.ragent.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.enums.DocumentStatus;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.rag.chunk.Chunk;
import com.nageoffer.ai.ragent.rag.chunk.StructureAwareSemanticChunkService;
import com.nageoffer.ai.ragent.rag.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.extractor.DocumentTextExtractor;
import com.nageoffer.ai.ragent.rag.vector.VectorStoreService;
import com.nageoffer.ai.ragent.service.FileStorageService;
import com.nageoffer.ai.ragent.service.KnowledgeChunkService;
import com.nageoffer.ai.ragent.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeDocumentMapper docMapper;
    private final DocumentTextExtractor textExtractor;
    private final StructureAwareSemanticChunkService chunkService;
    private final FileStorageService fileStorageService;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final KnowledgeChunkService knowledgeChunkService;

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

            List<Chunk> chunks = chunkService.split(text);

            knowledgeChunkService.batchCreate(docId, BeanUtil.copyToList(chunks, KnowledgeChunkCreateRequest.class));
            documentDO.setChunkCount(chunks.size());
            patchStatus(documentDO, DocumentStatus.SUCCESS);
            docMapper.updateById(documentDO);

            List<String> texts = chunks.stream().map(Chunk::getContent).toList();
            float[][] vectors = new float[texts.size()][];
            for (int i = 0; i < texts.size(); i++) {
                vectors[i] = toArray(embeddingService.embed(texts.get(i)));
            }

            vectorStoreService.indexDocumentChunks(String.valueOf(documentDO.getKbId()), docId, chunks, vectors);
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
