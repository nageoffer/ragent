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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mzt.logapi.starter.annotation.LogRecord;
import com.nageoffer.ai.ragent.audit.constant.BizChangeBizType;
import com.nageoffer.ai.ragent.audit.constant.BizChangeOperationType;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBasePageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeBaseVO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeBaseCleanupEvent;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceSpec;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final VectorStoreAdmin vectorStoreAdmin;
    private final FileStorageService fileStorageService;
    private final MessageQueueProducer messageQueueProducer;
    private final BizChangeLogContext bizChangeLogContext;

    @Value("knowledge-base-cleanup_topic${unique-name:}")
    private String cleanupTopic;

    @Transactional
    @Override
    @LogRecord(
            success = "创建知识库：{{#requestParam.name}}",
            fail = "创建知识库失败：{{#_errorMsg}}",
            type = BizChangeBizType.KNOWLEDGE_BASE,
            subType = BizChangeOperationType.CREATE,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public String create(KnowledgeBaseCreateRequest requestParam) {
        // 名称重复校验
        String name = requestParam.getName().replaceAll("\\s+", "");
        Long count = knowledgeBaseMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getName, name)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        if (count > 0) {
            throw new ServiceException("知识库名称已存在：" + requestParam.getName());
        }

        // Collection 名重复校验（共享 collection 模型下，向量层不再拦截重复，需在此显式校验）
        Long collectionCount = knowledgeBaseMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getCollectionName, requestParam.getCollectionName())
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        if (collectionCount > 0) {
            throw new ServiceException("Collection 名称已存在：" + requestParam.getCollectionName());
        }

        KnowledgeBaseDO kbDO = KnowledgeBaseDO.builder()
                .name(requestParam.getName())
                .embeddingModel(requestParam.getEmbeddingModel())
                .collectionName(requestParam.getCollectionName())
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .deleted(0)
                .build();

        knowledgeBaseMapper.insert(kbDO);

        // 在全局知识库桶下建立该知识库目录（幂等），collectionName 即目录名
        fileStorageService.createKnowledgeSpace(requestParam.getCollectionName());

        VectorSpaceSpec spaceSpec = VectorSpaceSpec.builder()
                .spaceId(VectorSpaceId.builder()
                        .logicalName(requestParam.getCollectionName())
                        .build())
                .remark(requestParam.getName())
                .build();
        vectorStoreAdmin.ensureVectorSpace(spaceSpec);

        bizChangeLogContext.put(String.valueOf(kbDO.getId()), null, kbDO);
        return String.valueOf(kbDO.getId());
    }

    @Override
    @LogRecord(
            success = "更新知识库：{{#requestParam.id}}",
            fail = "更新知识库失败：{{#_errorMsg}}",
            type = BizChangeBizType.KNOWLEDGE_BASE,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#requestParam.id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void update(KnowledgeBaseUpdateRequest requestParam) {
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(requestParam.getId());
        if (kb == null || kb.getDeleted() != null && kb.getDeleted() == 1) {
            throw new ClientException("知识库不存在：" + requestParam.getId());
        }
        KnowledgeBaseDO before = BeanUtil.copyProperties(kb, KnowledgeBaseDO.class);

        if (StringUtils.hasText(requestParam.getEmbeddingModel())
                && !requestParam.getEmbeddingModel().equals(kb.getEmbeddingModel())) {

            Long docCount = knowledgeDocumentMapper.selectCount(
                    new LambdaQueryWrapper<KnowledgeDocumentDO>()
                            .eq(KnowledgeDocumentDO::getKbId, requestParam.getId())
                            .gt(KnowledgeDocumentDO::getChunkCount, 0)
                            .eq(KnowledgeDocumentDO::getDeleted, 0)
            );
            if (docCount > 0) {
                throw new ClientException("知识库已存在向量化文档，不允许修改嵌入模型");
            }

            kb.setEmbeddingModel(requestParam.getEmbeddingModel());
        }

        if (StringUtils.hasText(requestParam.getName())) {
            kb.setName(requestParam.getName());
        }

        kb.setUpdatedBy(UserContext.getUsername());
        knowledgeBaseMapper.updateById(kb);
        bizChangeLogContext.put(requestParam.getId(), before, knowledgeBaseMapper.selectById(requestParam.getId()));
    }

    @Override
    @LogRecord(
            success = "重命名知识库：{{#kbId}}",
            fail = "重命名知识库失败：{{#_errorMsg}}",
            type = BizChangeBizType.KNOWLEDGE_BASE,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#kbId}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void rename(String kbId, KnowledgeBaseUpdateRequest requestParam) {
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null || kb.getDeleted() != null && kb.getDeleted() == 1) {
            throw new ClientException("知识库不存在");
        }
        KnowledgeBaseDO before = BeanUtil.copyProperties(kb, KnowledgeBaseDO.class);

        if (!StringUtils.hasText(requestParam.getName())) {
            throw new ClientException("知识库名称不能为空");
        }

        // 名称重复校验（排除当前知识库）
        String name = requestParam.getName().replaceAll("\\s+", "");
        Long count = knowledgeBaseMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .eq(KnowledgeBaseDO::getName, name)
                        .ne(KnowledgeBaseDO::getId, kbId)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        if (count > 0) {
            throw new ServiceException("知识库名称已存在：" + requestParam.getName());
        }

        kb.setName(requestParam.getName());
        kb.setUpdatedBy(UserContext.getUsername());
        knowledgeBaseMapper.updateById(kb);
        bizChangeLogContext.put(kbId, before, knowledgeBaseMapper.selectById(kbId));

        log.info("成功重命名知识库, kbId={}, newName={}", kbId, requestParam.getName());
    }

    @Override
    @LogRecord(
            success = "删除知识库：{{#kbId}}",
            fail = "删除知识库失败：{{#_errorMsg}}",
            type = BizChangeBizType.KNOWLEDGE_BASE,
            subType = BizChangeOperationType.DELETE,
            bizNo = "{{#kbId}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void delete(String kbId) {
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        if (kbDO == null || kbDO.getDeleted() != null && kbDO.getDeleted() == 1) {
            throw new ClientException("知识库不存在");
        }
        KnowledgeBaseDO before = BeanUtil.copyProperties(kbDO, KnowledgeBaseDO.class);

        Long docCount = knowledgeDocumentMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                        .eq(KnowledgeDocumentDO::getKbId, kbId)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
        );
        if (docCount != null && docCount > 0) {
            throw new ClientException("当前知识库下还有文档，请删除文档");
        }

        String operator = UserContext.getUsername();
        KnowledgeBaseCleanupEvent event = KnowledgeBaseCleanupEvent.builder()
                .kbId(kbId)
                .collectionName(kbDO.getCollectionName())
                .operator(operator)
                .build();

        // 事务消息：本地事务软删知识库，提交后由消费者异步回收底层物理资源（Milvus collection / bucket / 残留向量）
        messageQueueProducer.sendInTransaction(
                cleanupTopic,
                kbId,
                "知识库删除清理",
                event,
                arg -> {
                    kbDO.setUpdatedBy(operator);
                    int rows = knowledgeBaseMapper.deleteById(kbDO);
                    if (rows == 0) {
                        throw new ClientException("知识库不存在或已删除");
                    }
                }
        );
        bizChangeLogContext.put(kbId, before, null);
    }

    @Override
    public KnowledgeBaseVO queryById(String kbId) {
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        if (kbDO == null || kbDO.getDeleted() != null && kbDO.getDeleted() == 1) {
            throw new ClientException("知识库不存在");
        }
        return BeanUtil.toBean(kbDO, KnowledgeBaseVO.class);
    }

    @Override
    public IPage<KnowledgeBaseVO> pageQuery(KnowledgeBasePageRequest requestParam) {
        LambdaQueryWrapper<KnowledgeBaseDO> queryWrapper = Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                .like(StringUtils.hasText(requestParam.getName()), KnowledgeBaseDO::getName, requestParam.getName())
                .eq(KnowledgeBaseDO::getDeleted, 0)
                .orderByDesc(KnowledgeBaseDO::getUpdateTime);

        Page<KnowledgeBaseDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<KnowledgeBaseDO> result = knowledgeBaseMapper.selectPage(page, queryWrapper);
        Map<String, Long> docCountMap = new HashMap<>();
        if (CollUtil.isNotEmpty(result.getRecords())) {
            List<String> kbIds = result.getRecords().stream()
                    .map(KnowledgeBaseDO::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (!kbIds.isEmpty()) {
                List<Map<String, Object>> rows = knowledgeDocumentMapper.selectMaps(
                        Wrappers.query(KnowledgeDocumentDO.class)
                                .select("kb_id", "COUNT(1) AS doc_count")
                                .in("kb_id", kbIds)
                                .eq("deleted", 0)
                                .groupBy("kb_id")
                );
                for (Map<String, Object> row : rows) {
                    Object kbIdValue = row.get("kb_id");
                    Object countValue = row.get("doc_count");
                    if (kbIdValue == null || countValue == null) {
                        continue;
                    }
                    docCountMap.put(kbIdValue.toString(), ((Number) countValue).longValue());
                }
            }
        }
        return result.convert(each -> {
            KnowledgeBaseVO vo = BeanUtil.toBean(each, KnowledgeBaseVO.class);
            Long docCount = docCountMap.get(each.getId());
            vo.setDocumentCount(docCount != null ? docCount : 0L);
            return vo;
        });
    }
}
