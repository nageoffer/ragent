package com.nageoffer.ai.ragent.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.dto.kb.KnowledgeDocumentVO;
import com.nageoffer.ai.ragent.enums.DocumentStatus;
import com.nageoffer.ai.ragent.service.FileStorageService;
import com.nageoffer.ai.ragent.service.KnowledgeDocumentService;
import com.nageoffer.ai.ragent.service.rag.chunk.Chunk;
import com.nageoffer.ai.ragent.service.rag.chunk.StructureAwareSemanticChunkService;
import com.nageoffer.ai.ragent.service.rag.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.service.rag.extractor.DocumentTextExtractor;
import com.nageoffer.ai.ragent.service.rag.vector.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocumentVO upload(String kbId, MultipartFile file) {
        KnowledgeBaseDO kb = kbMapper.selectById(kbId);
        Assert.notNull(kb, "知识库不存在");

        FileStorageService.StoredFile stored = fileStorageService.save(kbId, file);

        KnowledgeDocumentDO doc = new KnowledgeDocumentDO();
        doc.setKbId(Long.parseLong(kbId));
        doc.setDocName(stored.originalFilename());
        doc.setEnabled(0);
        doc.setChunkCount(0);
        doc.setFileUrl(stored.url());
        doc.setFileType(stored.detectedType());
        doc.setFileSize(stored.size());
        doc.setStatus(DocumentStatus.PENDING.getCode());
        doc.setCreatedBy("");
        docMapper.insert(doc);

        return BeanUtil.toBean(doc, KnowledgeDocumentVO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startChunk(String kbId, String docId) {
        KnowledgeDocumentDO doc = docMapper.selectById(docId);
        Assert.notNull(doc, "文档不存在");
        Assert.isTrue(Objects.equals(kbId, String.valueOf(doc.getKbId())), "kbId 与文档不匹配");
        Assert.isTrue(!DocumentStatus.RUNNING.getCode().equals(doc.getStatus()), "文档分块进行中");

        // 标记 running
        patchStatus(doc, DocumentStatus.RUNNING);

        try {
            // 1) 读取文本
            Path localPath = fileStorageService.localPathFromUrl(doc.getFileUrl());
            String text = textExtractor.extract(localPath, doc.getDocName());

            // 2) 分块
            List<Chunk> chunks = chunkService.split(text);

            List<String> texts = chunks.stream().map(Chunk::getContent).toList();
            float[][] vectors = new float[texts.size()][];
            for (int i = 0; i < texts.size(); i++) {
                vectors[i] = toArray(embeddingService.embed(texts.get(i)));
            }
            vectorStoreService.upsert(String.valueOf(doc.getKbId()), String.valueOf(doc.getId()), chunks, vectors);

            // 4) 更新计数 & 状态
            doc.setChunkCount(chunks.size());
            patchStatus(doc, DocumentStatus.SUCCESS);
            docMapper.updateById(doc);
        } catch (Exception e) {
            log.error("Chunk failed. kbId={}, docId={}", kbId, docId, e);
            patchStatus(doc, DocumentStatus.FAILED);
            throw new RuntimeException("分块失败：" + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String kbId, String docId, boolean purgeVectors) {
        KnowledgeDocumentDO doc = docMapper.selectById(docId);
        Assert.notNull(doc, "文档不存在");
        Assert.isTrue(Objects.equals(kbId, doc.getKbId()), "kbId 与文档不匹配");

        // 1) 逻辑删除记录
        doc.setDeleted(1);
        doc.setUpdatedBy("");
        docMapper.updateById(doc);

        // 2) 可选：清理向量库
        if (purgeVectors) {
            vectorStoreService.removeByDocId(String.valueOf(doc.getKbId()), String.valueOf(doc.getId()));
        }
    }

    @Override
    public KnowledgeDocumentVO get(String kbId, String docId) {
        KnowledgeDocumentDO doc = docMapper.selectById(docId);
        Assert.notNull(doc, "文档不存在");
        Assert.isTrue(Objects.equals(kbId, doc.getKbId()), "kbId 与文档不匹配");
        return BeanUtil.toBean(doc, KnowledgeDocumentVO.class);
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
    public void enable(String kbId, String docId, boolean enabled) {
        KnowledgeDocumentDO doc = docMapper.selectById(docId);
        Assert.notNull(doc, "文档不存在");
        Assert.isTrue(Objects.equals(kbId, doc.getKbId()), "kbId 与文档不匹配");
        doc.setEnabled(0);
        doc.setUpdatedBy("");
        docMapper.updateById(doc);

        vectorStoreService.markEnabled(String.valueOf(doc.getKbId()), String.valueOf(doc.getId()), enabled);
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
