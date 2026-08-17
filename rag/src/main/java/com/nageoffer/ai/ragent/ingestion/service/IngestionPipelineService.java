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

package com.nageoffer.ai.ragent.ingestion.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineCreateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineUpdateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionPipelineVO;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;

/**
 * 数据清洗流水线服务接口
 */
public interface IngestionPipelineService {

    /**
     * 创建流水线
     *
     * @param request 创建请求
     * @return 流水线VO
     */
    IngestionPipelineVO create(IngestionPipelineCreateRequest request);

    /**
     * 更新流水线
     *
     * @param pipelineId 流水线ID
     * @param request    更新请求
     * @return 流水线VO
     */
    IngestionPipelineVO update(String pipelineId, IngestionPipelineUpdateRequest request);

    /**
     * 获取流水线详情
     *
     * @param pipelineId 流水线ID
     * @return 流水线VO
     */
    IngestionPipelineVO get(String pipelineId);

    /**
     * 分页查询流水线
     *
     * @param page    分页参数
     * @param keyword 关键字
     * @return 分页结果
     */
    IPage<IngestionPipelineVO> page(Page<IngestionPipelineVO> page, String keyword);

    /**
     * 删除流水线
     *
     * @param pipelineId 流水线ID
     */
    void delete(String pipelineId);

    /**
     * 获取流水线定义
     *
     * @param pipelineId 流水线ID
     * @return 流水线定义
     */
    PipelineDefinition getDefinition(String pipelineId);
}
