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
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionTaskCreateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskNodeVO;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskVO;
import com.nageoffer.ai.ragent.ingestion.domain.result.IngestionResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 数据摄入任务服务接口
 */
public interface IngestionTaskService {

    /**
     * 执行数据摄入任务
     *
     * @param request 创建请求
     * @return 摄入结果
     */
    IngestionResult execute(IngestionTaskCreateRequest request);

    /**
     * 上传文件并执行摄入任务
     *
     * @param pipelineId 流水线ID
     * @param file       上传文件
     * @return 摄入结果
     */
    IngestionResult upload(String pipelineId, MultipartFile file);

    /**
     * 获取任务详情
     *
     * @param taskId 任务ID
     * @return 任务VO
     */
    IngestionTaskVO get(String taskId);

    /**
     * 分页查询任务
     *
     * @param page   分页参数
     * @param status 状态筛选
     * @return 分页结果
     */
    IPage<IngestionTaskVO> page(Page<IngestionTaskVO> page, String status);

    /**
     * 获取任务节点列表
     *
     * @param taskId 任务ID
     * @return 节点列表
     */
    List<IngestionTaskNodeVO> listNodes(String taskId);
}
