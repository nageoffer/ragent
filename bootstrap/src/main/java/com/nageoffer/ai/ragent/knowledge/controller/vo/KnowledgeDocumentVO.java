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

import java.time.LocalDateTime;

/**
 * 知识库文档视图对象
 */
@Data
public class KnowledgeDocumentVO {

    /**
     * 文档唯一标识
     */
    private String id;

    /**
     * 知识库ID
     */
    private Long kbId;

    /**
     * 文档名称
     */
    private String docName;

    /**
     * 来源类型
     */
    private String sourceType;

    /**
     * 来源位置
     */
    private String sourceLocation;

    /**
     * 是否开启定时拉取
     */
    private Integer scheduleEnabled;

    /**
     * 定时表达式
     */
    private String scheduleCron;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 切片数量
     */
    private Integer chunkCount;

    /**
     * 文件URL
     */
    private String fileUrl;

    /**
     * 文件类型
     */
    private String fileType;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 分块策略
     */
    private String chunkStrategy;

    /**
     * 分块参数配置（JSON）
     */
    private String chunkConfig;

    /**
     * 状态（如：解析中、已解析、解析失败等）
     */
    private String status;

    /**
     * 创建人
     */
    private String createdBy;

    /**
     * 更新人
     */
    private String updatedBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
