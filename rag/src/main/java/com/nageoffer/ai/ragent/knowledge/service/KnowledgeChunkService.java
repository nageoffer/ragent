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

package com.nageoffer.ai.ragent.knowledge.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkBatchRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeChunkVO;

import java.util.List;

/**
 * 知识库分片服务接口
 */
public interface KnowledgeChunkService {

    /**
     * 分页查询指定文档的分片列表
     *
     * @param docId        文档 ID
     * @param requestParam 分页查询参数
     * @return 分片分页信息
     */
    IPage<KnowledgeChunkVO> pageQuery(String docId, KnowledgeChunkPageRequest requestParam);

    /**
     * 为指定文档新增分片
     *
     * @param docId        文档 ID
     * @param requestParam 新增分片请求参数
     * @return 新增的分片视图对象
     */
    KnowledgeChunkVO create(String docId, KnowledgeChunkCreateRequest requestParam);



    /**
     * 更新指定文档的特定分片内容
     *
     * @param docId        文档 ID
     * @param chunkId      分片 ID
     * @param requestParam 更新分片请求参数
     */
    void update(String docId, String chunkId, KnowledgeChunkUpdateRequest requestParam);

    /**
     * 删除指定文档的特定分片
     *
     * @param docId   文档 ID
     * @param chunkId 分片 ID
     */
    void delete(String docId, String chunkId);

    /**
     * 启用或禁用单个分片
     *
     * @param docId   文档 ID
     * @param chunkId 分片 ID
     * @param enabled 是否启用
     */
    void enableChunk(String docId, String chunkId, boolean enabled);

    /**
     * 批量启用或禁用文档分片
     *
     * @param docId        文档 ID
     * @param requestParam 批量处理请求参数（为空表示操作全部）
     * @param enabled      true=启用，false=禁用
     */
    void batchToggleEnabled(String docId, KnowledgeChunkBatchRequest requestParam, boolean enabled);

    /**
     * 根据文档 ID 批量更新所有分片的启用状态
     *
     * @param docId   文档 ID
     * @param kbId    知识库 ID（用于日志，避免重复查询）
     * @param enabled 是否启用
     */
    void updateEnabledByDocId(String docId, String kbId, boolean enabled);

    /**
     * 把文档已入库的所有分片重新向量化：文档启用时重建向量用
     * <p>
     * 不经过控制层 VO——向量文本是索引侧的内在数据，不对外暴露
     *
     * @param docId  文档 ID
     * @param target 向量落点（模型 + 维度）
     * @return 已向量化的分片，按 chunkIndex 升序
     */
    List<EmbeddedChunk> embedPersistedChunks(String docId, VectorTarget target);

    /**
     * 删除指定文档的所有分片
     *
     * @param docId 文档 ID
     */
    void deleteByDocId(String docId);
}
