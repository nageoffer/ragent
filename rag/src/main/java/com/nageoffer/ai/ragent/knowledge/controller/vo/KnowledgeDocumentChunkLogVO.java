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

package com.nageoffer.ai.ragent.knowledge.controller.vo;

import lombok.Data;

import java.util.Date;

/**
 * 知识库文档分块日志 VO
 */
@Data
public class KnowledgeDocumentChunkLogVO {

    private String id;

    private String docId;

    /**
     * 执行状态：running / success / failed
     */
    private String status;

    /**
     * 处理模式：chunk / pipeline
     */
    private String processMode;

    /**
     * 分块策略（仅 chunk 模式）
     */
    private String parseProfile;

    /**
     * Pipeline ID（仅 pipeline 模式）
     */
    private String pipelineId;

    /**
     * Pipeline 名称（仅 pipeline 模式）
     */
    private String pipelineName;

    /**
     * 文本提取耗时（毫秒）
     */
    private Long extractDuration;

    /**
     * 分块耗时（毫秒）
     */
    private Long chunkDuration;

    /**
     * 嵌入 API 耗时（毫秒）
     */
    private Long embedDuration;

    /**
     * DB 持久化耗时（毫秒）
     */
    private Long persistDuration;

    /**
     * 其他耗时（毫秒）
     */
    private Long otherDuration;

    /**
     * 总耗时（毫秒）
     */
    private Long totalDuration;

    /**
     * 生成的分块数量
     */
    private Integer chunkCount;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 开始时间
     */
    private Date startTime;

    /**
     * 结束时间
     */
    private Date endTime;

    private Date createTime;
}
