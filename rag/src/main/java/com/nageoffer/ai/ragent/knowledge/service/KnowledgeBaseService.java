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
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBasePageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeBaseVO;

/**
 * 知识库服务接口
 */
public interface KnowledgeBaseService {

    /**
     * 创建知识库
     *
     * @param requestParam 创建知识库请求参数
     * @return 知识库ID
     */
    String create(KnowledgeBaseCreateRequest requestParam);

    /**
     * 更新知识库
     *
     * @param requestParam 更新知识库请求参数
     */
    void update(KnowledgeBaseUpdateRequest requestParam);

    /**
     * 重命名知识库
     *
     * @param kbId         知识库ID
     * @param requestParam 重命名请求参数
     */
    void rename(String kbId, KnowledgeBaseUpdateRequest requestParam);

    /**
     * 删除知识库
     *
     * @param kbId 知识库ID
     */
    void delete(String kbId);

    /**
     * 根据ID查询知识库详情
     *
     * @param kbId 知识库ID
     * @return 知识库详细信息
     */
    KnowledgeBaseVO queryById(String kbId);

    /**
     * 分页查询知识库
     *
     * @param requestParam 分页查询请求参数
     * @return 知识库分页结果
     */
    IPage<KnowledgeBaseVO> pageQuery(KnowledgeBasePageRequest requestParam);
}
