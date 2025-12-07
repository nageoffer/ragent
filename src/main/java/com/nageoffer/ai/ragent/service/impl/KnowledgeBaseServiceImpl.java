package com.nageoffer.ai.ragent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.controller.request.KnowledgeBaseCreateRequest;
import com.nageoffer.ai.ragent.controller.request.KnowledgeBaseUpdateRequest;
import com.nageoffer.ai.ragent.controller.vo.KnowledgeBaseVO;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.service.KnowledgeBaseService;
import com.nageoffer.ai.ragent.rag.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.vector.VectorSpaceSpec;
import com.nageoffer.ai.ragent.rag.vector.VectorStoreAdmin;
import cn.hutool.core.bean.BeanUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.S3Client;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final VectorStoreAdmin vectorStoreAdmin;
    private final S3Client s3Client;

    @Override
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

        String bucketName = requestParam.getCollectionName();
        s3Client.createBucket(builder -> builder.bucket(bucketName));

        log.info("成功创建RestFS存储桶，Bucket名称: {}", bucketName);

        return String.valueOf(kbDO.getId());
    }

    @Override
    public void update(KnowledgeBaseUpdateRequest requestParam) {
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
    public void rename(String kbId, KnowledgeBaseUpdateRequest requestParam) {
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null || kb.getDeleted() != null && kb.getDeleted() == 1) {
            throw new ClientException("知识库不存在");
        }

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
        kb.setUpdatedBy("");
        knowledgeBaseMapper.updateById(kb);

        log.info("成功重命名知识库, kbId={}, newName={}", kbId, requestParam.getName());
    }

    @Override
    public void delete(String kbId) {
        // 可选：限制删除前需要确保没有文档
        Long docCount = knowledgeDocumentMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                        .eq(KnowledgeDocumentDO::getKbId, kbId)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
        );
        if (docCount > 0) {
            throw new IllegalStateException("知识库下仍有关联文档，无法删除");
        }

        knowledgeBaseMapper.deleteById(kbId);
    }

    @Override
    public KnowledgeBaseVO queryById(String kbId) {
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        if (kbDO == null || kbDO.getDeleted() != null && kbDO.getDeleted() == 1) {
            throw new ClientException("知识库不存在");
        }
        return BeanUtil.toBean(kbDO, KnowledgeBaseVO.class);
    }
}
