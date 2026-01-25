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

package com.nageoffer.ai.ragent.ingestion.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 知识库数据接入任务节点实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_ingestion_task_node")
public class IngestionTaskNodeDO {

    /**
     * ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 任务ID
     */
    private Long taskId;

    /**
     * 流水线ID
     */
    private Long pipelineId;

    /**
     * 节点ID
     */
    private String nodeId;

    /**
     * 节点类型
     * 如 fetcher、parser、chunker 等
     */
    private String nodeType;

    /**
     * 节点顺序
     */
    private Integer nodeOrder;

    /**
     * 状态 (如: success, failed, skipped)
     */
    private String status;

    /**
     * 持续时间（毫秒）
     */
    private Long durationMs;

    /**
     * 消息
     */
    private String message;

    /**
     * 错误消息
     */
    private String errorMessage;

    /**
     * 输出JSON
     */
    private String outputJson;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 删除标记
     */
    @TableLogic
    private Integer deleted;
}
