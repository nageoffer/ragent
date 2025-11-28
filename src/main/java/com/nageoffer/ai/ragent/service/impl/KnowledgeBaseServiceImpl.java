package com.nageoffer.ai.ragent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.dto.kb.KnowledgeBaseCreateReqDTO;
import com.nageoffer.ai.ragent.dto.kb.KnowledgeBaseUpdateReqDTO;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.service.KnowledgeBaseService;
import com.nageoffer.ai.ragent.rag.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.vector.VectorSpaceSpec;
import com.nageoffer.ai.ragent.rag.vector.VectorStoreAdmin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final VectorStoreAdmin vectorStoreAdmin;

    @Override
    public String create(KnowledgeBaseCreateReqDTO requestParam) {
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

        VectorSpaceSpec spaceSpec = VectorSpaceSpec.builder()
                .spaceId(VectorSpaceId.builder()
                        .logicalName(requestParam.getCollectionName())
                        .build())
                .remark(requestParam.getName())
                .build();
        vectorStoreAdmin.ensureVectorSpace(spaceSpec);

        KnowledgeBaseDO kbDO = KnowledgeBaseDO.builder()
                .name(requestParam.getName())
                .embeddingModel(requestParam.getEmbeddingModel())
                .collectionName(requestParam.getCollectionName())
                .createdBy("")
                .updatedBy("")
                .deleted(0)
                .build();

        knowledgeBaseMapper.insert(kbDO);
        return String.valueOf(kbDO.getId());
    }

    @Override
    public void update(KnowledgeBaseUpdateReqDTO requestParam) {
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(requestParam.getId());
        if (kb == null || kb.getDeleted() != null && kb.getDeleted() == 1) {
            throw new IllegalArgumentException("知识库不存在：" + requestParam.getId());
        }

        if (StringUtils.hasText(requestParam.getEmbeddingModel())
                && !requestParam.getEmbeddingModel().equals(kb.getEmbeddingModel())) {

            Long docCount = knowledgeDocumentMapper.selectCount(
                    new LambdaQueryWrapper<KnowledgeDocumentDO>()
                            .eq(KnowledgeDocumentDO::getKbId, requestParam.getId())
                            .gt(KnowledgeDocumentDO::getChunkCount, 0)
                            .eq(KnowledgeDocumentDO::getDeleted, 0)
            );
            if (docCount > 0) {
                throw new IllegalStateException("知识库已存在向量化文档，不允许修改嵌入模型");
            }

            kb.setEmbeddingModel(requestParam.getEmbeddingModel());
        }

        if (StringUtils.hasText(requestParam.getName())) {
            kb.setName(requestParam.getName());
        }

        kb.setUpdatedBy("");
        knowledgeBaseMapper.updateById(kb);
    }

    @Override
    public void delete(String id) {
        // 可选：限制删除前需要确保没有文档
        Long docCount = knowledgeDocumentMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeDocumentDO>()
                        .eq(KnowledgeDocumentDO::getKbId, id)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
        );
        if (docCount > 0) {
            throw new IllegalStateException("知识库下仍有关联文档，无法删除");
        }

        knowledgeBaseMapper.deleteById(id);
    }

    @Override
    public KnowledgeBaseDO getById(String id) {
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(id);
        if (kb == null || kb.getDeleted() != null && kb.getDeleted() == 1) {
            return null;
        }
        return kb;
    }
}
