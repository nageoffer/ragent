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

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mzt.logapi.starter.annotation.LogRecord;
import com.nageoffer.ai.ragent.audit.constant.BizChangeBizType;
import com.nageoffer.ai.ragent.audit.constant.BizChangeOperationType;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkBatchRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeChunkVO;
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkAssembler;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.core.ingest.embed.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.knowledge.support.VectorTargetResolver;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.StringUtils;

import cn.hutool.crypto.SecureUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库 Chunk 服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeChunkServiceImpl implements KnowledgeChunkService {

    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final ChunkEmbeddingService chunkEmbeddingService;
    private final VectorTargetResolver vectorTargetResolver;
    private final TokenCounterService tokenCounterService;
    private final VectorStoreService vectorStoreService;
    private final TransactionOperations transactionOperations;
    private final BizChangeLogContext bizChangeLogContext;

    @Override
    public IPage<KnowledgeChunkVO> pageQuery(String docId, KnowledgeChunkPageRequest requestParam) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        LambdaQueryWrapper<KnowledgeChunkDO> queryWrapper = new LambdaQueryWrapper<KnowledgeChunkDO>()
                .eq(KnowledgeChunkDO::getDocId, docId)
                .eq(requestParam.getEnabled() != null, KnowledgeChunkDO::getEnabled, requestParam.getEnabled())
                .orderByAsc(KnowledgeChunkDO::getChunkIndex);

        Page<KnowledgeChunkDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<KnowledgeChunkDO> result = chunkMapper.selectPage(page, queryWrapper);
        return result.convert(each -> BeanUtil.toBean(each, KnowledgeChunkVO.class));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "新增 Chunk：{{#_ret.id}}",
            fail = "新增 Chunk 失败：{{#_errorMsg}}",
            type = BizChangeBizType.KNOWLEDGE_CHUNK,
            subType = BizChangeOperationType.CREATE,
            bizNo = "{{#bizChangeBizId != null ? #bizChangeBizId : #docId}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public KnowledgeChunkVO create(String docId, KnowledgeChunkCreateRequest requestParam) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块处理中，暂不支持新增 Chunk");
        }
        if (!Integer.valueOf(1).equals(documentDO.getEnabled())) {
            throw new ClientException("文档未启用，暂不支持新增 Chunk");
        }

        String content = requestParam.getContent();
        Assert.notBlank(content, () -> new ClientException("Chunk 内容不能为空"));

        KnowledgeChunkDO latest = chunkMapper.selectOne(
                Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                        .eq(KnowledgeChunkDO::getDocId, docId)
                        .orderByDesc(KnowledgeChunkDO::getChunkIndex)
                        .last("LIMIT 1")
        );
        int chunkIndex = requestParam.getIndex() != null
                ? requestParam.getIndex()
                : (latest != null ? latest.getChunkIndex() + 1 : 0);

        String contentHash = SecureUtil.sha256(content);
        int charCount = content.length();
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        String embeddingModel = kbDO.getEmbeddingModel();
        String collectionName = kbDO.getCollectionName();
        Integer tokenCount = resolveTokenCount(content);

        KnowledgeChunkDO chunkDO = KnowledgeChunkDO.builder()
                .id(requestParam.getChunkId())
                .kbId(documentDO.getKbId())
                .docId(docId)
                .chunkIndex(chunkIndex)
                .content(content)
                .contentHash(contentHash)
                .charCount(charCount)
                .tokenCount(tokenCount)
                // 人工块没有结构信息，向量文本等于正文；显式写下而不是留空，重建时才不必猜
                .embeddingText(content)
                .enabled(1)
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();

        chunkMapper.insert(chunkDO);
        log.info("新增 Chunk 成功, kbId={}, docId={}, chunkId={}, chunkIndex={}", documentDO.getKbId(), docId, chunkDO.getId(), chunkIndex);

        documentMapper.update(Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getId, docId)
                .setSql("chunk_count = chunk_count + 1"));

        // 同步写入向量库
        syncChunkToVector(collectionName, docId, chunkDO, vectorTargetResolver.resolve(kbDO));

        bizChangeLogContext.put(String.valueOf(chunkDO.getId()), null, chunkDO);
        return BeanUtil.toBean(chunkDO, KnowledgeChunkVO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "更新 Chunk：{{#chunkId}}",
            fail = "更新 Chunk 失败：{{#_errorMsg}}",
            type = BizChangeBizType.KNOWLEDGE_CHUNK,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#chunkId}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void update(String docId, String chunkId, KnowledgeChunkUpdateRequest requestParam) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块处理中，暂不支持修改 Chunk");
        }

        KnowledgeChunkDO chunkDO = chunkMapper.selectById(chunkId);
        Assert.notNull(chunkDO, () -> new ClientException("Chunk 不存在"));
        Assert.isTrue(chunkDO.getDocId().equals(docId), () -> new ClientException("Chunk 不属于该文档"));
        KnowledgeChunkDO before = BeanUtil.copyProperties(chunkDO, KnowledgeChunkDO.class);

        String newContent = requestParam.getContent();
        Assert.notBlank(newContent, () -> new ClientException("Chunk 内容不能为空"));

        if (newContent.equals(chunkDO.getContent())) {
            bizChangeLogContext.skip();
            return;
        }

        chunkDO.setContent(newContent);
        chunkDO.setContentHash(SecureUtil.sha256(newContent));
        chunkDO.setCharCount(newContent.length());
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        String embeddingModel = kbDO.getEmbeddingModel();
        String collectionName = kbDO.getCollectionName();
        chunkDO.setTokenCount(resolveTokenCount(newContent));
        // 向量文本必须跟着正文一起改：否则向量按新正文更新、库里那份还是旧文本，下次重建就用错的文本
        chunkDO.setEmbeddingText(newContent);
        chunkDO.setUpdatedBy(UserContext.getUsername());

        chunkMapper.updateById(chunkDO);

        log.info("更新 Chunk 成功, kbId={}, docId={}, chunkId={}", documentDO.getKbId(), docId, chunkId);

        // 同步向量数据库
        vectorStoreService.updateChunk(collectionName, docId,
                embedPersisted(List.of(chunkDO), vectorTargetResolver.resolve(kbDO)).get(0));
        bizChangeLogContext.put(chunkId, before, chunkMapper.selectById(chunkId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "删除 Chunk：{{#chunkId}}",
            fail = "删除 Chunk 失败：{{#_errorMsg}}",
            type = BizChangeBizType.KNOWLEDGE_CHUNK,
            subType = BizChangeOperationType.DELETE,
            bizNo = "{{#chunkId}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void delete(String docId, String chunkId) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块处理中，暂不支持删除 Chunk");
        }

        KnowledgeChunkDO chunkDO = chunkMapper.selectById(chunkId);
        Assert.notNull(chunkDO, () -> new ClientException("Chunk 不存在"));
        Assert.isTrue(chunkDO.getDocId().equals(docId), () -> new ClientException("Chunk 不属于该文档"));
        KnowledgeChunkDO before = BeanUtil.copyProperties(chunkDO, KnowledgeChunkDO.class);

        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        Assert.notNull(kbDO, () -> new ServiceException("知识库不存在"));
        String collectionName = kbDO.getCollectionName();

        chunkMapper.deleteById(chunkId);

        documentMapper.update(Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getId, docId)
                .setSql("chunk_count = CASE WHEN chunk_count > 0 THEN chunk_count - 1 ELSE 0 END"));

        log.info("删除 Chunk 成功, kbId={}, docId={}, chunkId={}", documentDO.getKbId(), docId, chunkId);

        deleteChunkFromVector(collectionName, chunkId);
        bizChangeLogContext.put(chunkId, before, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "{{#enabled ? '启用' : '禁用'}} Chunk：{{#chunkId}}",
            fail = "修改 Chunk 启用状态失败：{{#_errorMsg}}",
            type = BizChangeBizType.KNOWLEDGE_CHUNK,
            subType = "{{#enabled ? 'ENABLE' : 'DISABLE'}}",
            bizNo = "{{#chunkId}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void enableChunk(String docId, String chunkId, boolean enabled) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块处理中，暂不支持修改 Chunk 状态");
        }
        validateDocumentEnabledForChunkEnable(documentDO, enabled);

        KnowledgeChunkDO chunkDO = chunkMapper.selectById(chunkId);
        Assert.notNull(chunkDO, () -> new ClientException("Chunk 不存在"));
        Assert.isTrue(chunkDO.getDocId().equals(docId), () -> new ClientException("Chunk 不属于该文档"));
        KnowledgeChunkDO before = BeanUtil.copyProperties(chunkDO, KnowledgeChunkDO.class);

        // 如果状态没变，直接返回
        int enabledValue = enabled ? 1 : 0;
        if (chunkDO.getEnabled().equals(enabledValue)) {
            bizChangeLogContext.skip();
            return;
        }

        chunkDO.setEnabled(enabledValue);
        chunkDO.setUpdatedBy(UserContext.getUsername());
        chunkMapper.updateById(chunkDO);

        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        String collectionName = kbDO.getCollectionName();
        log.info("{}Chunk 成功, kbId={}, docId={}, chunkId={}", enabled ? "启用" : "禁用", documentDO.getKbId(), docId, chunkId);

        if (enabled) {
            String embeddingModel = kbDO.getEmbeddingModel();
            syncChunkToVector(collectionName, docId, chunkDO, vectorTargetResolver.resolve(kbDO));
        } else {
            deleteChunkFromVector(collectionName, chunkId);
        }
        bizChangeLogContext.put(chunkId, before, chunkMapper.selectById(chunkId));
    }

    @Override
    @LogRecord(
            success = "批量{{#enabled ? '启用' : '禁用'}} Chunk：{{#docId}}",
            fail = "批量修改 Chunk 启用状态失败：{{#_errorMsg}}",
            type = BizChangeBizType.KNOWLEDGE_CHUNK,
            subType = "{{#enabled ? 'ENABLE' : 'DISABLE'}}",
            bizNo = "{{#docId}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void batchToggleEnabled(String docId, KnowledgeChunkBatchRequest requestParam, boolean enabled) {
        if (requestParam == null || CollUtil.isEmpty(requestParam.getChunkIds())) {
            throw new ClientException("请指定需要操作的 Chunk，全量启用/禁用请使用文档启用接口");
        }
        List<String> requestedIds = requestParam.getChunkIds();
        if (requestedIds.size() > 500) {
            throw new ClientException("单次批量操作 Chunk 数量不能超过 500");
        }

        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块处理中，暂不支持批量修改 Chunk 状态");
        }
        validateDocumentEnabledForChunkEnable(documentDO, enabled);

        List<KnowledgeChunkDO> found = chunkMapper.selectByIds(requestedIds);
        if (found.size() != requestedIds.size()) {
            throw new ClientException("存在无效的 Chunk ID，请求 " + requestedIds.size() + " 个，实际找到 " + found.size() + " 个");
        }
        found.forEach(c -> {
            if (!c.getDocId().equals(docId)) {
                throw new ClientException("Chunk " + c.getId() + " 不属于文档 " + docId);
            }
        });
        List<String> targetIds = found.stream().map(KnowledgeChunkDO::getId).collect(Collectors.toList());

        if (CollUtil.isEmpty(targetIds)) {
            bizChangeLogContext.skip();
            return;
        }

        int enabledValue = enabled ? 1 : 0;
        List<KnowledgeChunkDO> needUpdateChunks = chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunkDO>()
                        .in(KnowledgeChunkDO::getId, targetIds)
                        .ne(KnowledgeChunkDO::getEnabled, enabledValue)
        );
        List<String> needUpdateIds = needUpdateChunks.stream().map(KnowledgeChunkDO::getId).collect(Collectors.toList());

        if (CollUtil.isEmpty(needUpdateIds)) {
            throw new ClientException(enabled ? "所有 Chunk 已全部启用，无需重复操作" : "所有 Chunk 已全部禁用，无需重复操作");
        }
        List<KnowledgeChunkDO> before = needUpdateChunks.stream()
                .map(each -> BeanUtil.copyProperties(each, KnowledgeChunkDO.class))
                .collect(Collectors.toList());

        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        String collectionName = kbDO.getCollectionName();

        if (enabled) {
            List<EmbeddedChunk> vectorChunks = embedPersisted(needUpdateChunks, vectorTargetResolver.resolve(kbDO));

            transactionOperations.executeWithoutResult(status -> {
                chunkMapper.update(
                        Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
                                .in(KnowledgeChunkDO::getId, needUpdateIds)
                                .set(KnowledgeChunkDO::getEnabled, 1)
                                .set(KnowledgeChunkDO::getUpdatedBy, UserContext.getUsername())
                );
                vectorStoreService.indexDocumentChunks(collectionName, docId, vectorChunks);
            });
        } else {
            transactionOperations.executeWithoutResult(status -> {
                chunkMapper.update(
                        Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
                                .in(KnowledgeChunkDO::getId, needUpdateIds)
                                .set(KnowledgeChunkDO::getEnabled, 0)
                                .set(KnowledgeChunkDO::getUpdatedBy, UserContext.getUsername())
                );
                vectorStoreService.deleteChunksByIds(collectionName, needUpdateIds);
            });
        }

        log.info("批量{}Chunk 成功, kbId={}, docId={}, count={}", enabled ? "启用" : "禁用",
                documentDO.getKbId(), docId, needUpdateIds.size());
        bizChangeLogContext.put(docId, before, chunkMapper.selectByIds(needUpdateIds));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateEnabledByDocId(String docId, String kbId, boolean enabled) {
        int enabledValue = enabled ? 1 : 0;
        chunkMapper.update(
                Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
                        .eq(KnowledgeChunkDO::getDocId, docId)
                        .set(KnowledgeChunkDO::getEnabled, enabledValue)
                        .set(KnowledgeChunkDO::getUpdatedBy, UserContext.getUsername())
        );
        log.info("根据文档ID更新所有Chunk启用状态, kbId={}, docId={}, enabled={}", kbId, docId, enabled);
    }

    @Override
    public List<EmbeddedChunk> embedPersistedChunks(String docId, VectorTarget target) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        List<KnowledgeChunkDO> chunkDOList = chunkMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                        .eq(KnowledgeChunkDO::getDocId, docId)
                        .orderByAsc(KnowledgeChunkDO::getChunkIndex)
        );
        if (CollUtil.isEmpty(chunkDOList)) {
            return List.of();
        }
        return embedPersisted(chunkDOList, target);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocId(String docId) {
        if (docId == null) {
            return;
        }
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkDO>().eq(KnowledgeChunkDO::getDocId, docId));
    }

    // ==================== 私有方法 ====================

    /**
     * 启用 chunk 前必须保证所属文档为启用状态
     */
    private void validateDocumentEnabledForChunkEnable(KnowledgeDocumentDO documentDO, boolean enableChunk) {
        if (!enableChunk) {
            return;
        }
        if (!Integer.valueOf(1).equals(documentDO.getEnabled())) {
            throw new ClientException("文档未启用，无法启用Chunk，请先启用文档");
        }
    }

    /**
     * 将单个 chunk 同步到向量库
     */
    private void syncChunkToVector(String collectionName, String docId, KnowledgeChunkDO chunkDO,
                                   VectorTarget target) {
        EmbeddedChunk chunk = embedPersisted(List.of(chunkDO), target).get(0);
        vectorStoreService.indexDocumentChunks(collectionName, docId, List.of(chunk));

        log.debug("同步 Chunk 到向量库成功, collectionName={}, docId={}, chunkId={}", collectionName, docId, chunkDO.getId());
    }

    /**
     * 从向量库删除单个 chunk
     */
    private void deleteChunkFromVector(String collectionName, String chunkId) {
        vectorStoreService.deleteChunkById(collectionName, chunkId);
        log.debug("从向量库删除 Chunk, collectionName={}, chunkId={}", collectionName, chunkId);
    }

    /**
     * 已入库块重新向量化：向量文本取库里那一份，块 ID 沿用关系库主键
     * <p>
     * 全系统"行 → 向量"的唯一入口，见 {@link ChunkAssembler#restore}
     */
    private List<EmbeddedChunk> embedPersisted(List<KnowledgeChunkDO> rows, VectorTarget target) {
        List<Chunk> chunks = rows.stream()
                .map(each -> ChunkAssembler.restore(each.getId(),
                        each.getChunkIndex() == null ? 0 : each.getChunkIndex(),
                        each.getContent(), each.getEmbeddingText()))
                .toList();
        return chunkEmbeddingService.embed(chunks, target);
    }

    private Integer resolveTokenCount(String content) {
        if (!StringUtils.hasText(content)) {
            return 0;
        }
        return tokenCounterService.countTokens(content);
    }
}
