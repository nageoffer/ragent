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
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkBatchRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeChunkVO;

import java.util.List;

/**
 * 知识库 Chunk 服务接口
 */
public interface KnowledgeChunkService {

    /**
     * 查询文档是否已 Chunk
     */
    Boolean existsByDocId(String docId);

    /**
     * 分页查询 Chunk 列表
     */
    IPage<KnowledgeChunkVO> pageQuery(String docId, KnowledgeChunkPageRequest requestParam);

    /**
     * 新增 Chunk
     */
    KnowledgeChunkVO create(String docId, KnowledgeChunkCreateRequest requestParam);

    /**
     * 批量新增
     */
    void batchCreate(String docId, List<KnowledgeChunkCreateRequest> requestParams);

    /**
     * 更新 Chunk 内容
     */
    void update(String docId, String chunkId, KnowledgeChunkUpdateRequest requestParam);

    /**
     * 删除 Chunk
     */
    void delete(String docId, String chunkId);

    /**
     * 启用/禁用单条 Chunk
     */
    void enableChunk(String docId, String chunkId, boolean enabled);

    /**
     * 批量启用 Chunk
     */
    void batchEnable(String docId, KnowledgeChunkBatchRequest requestParam);

    /**
     * 批量禁用 Chunk
     */
    void batchDisable(String docId, KnowledgeChunkBatchRequest requestParam);

    /**
     * 重建文档向量（以 MySQL enabled=1 的 chunk 为准）
     */
    void rebuildByDocId(String docId);
}
