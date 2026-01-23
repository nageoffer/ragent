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

package com.nageoffer.ai.ragent.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.Map;

/**
 * 摄取任务节点视图对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestionTaskNodeVO {

    /**
     * ID
     */
    private String id;

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 流水线ID
     */
    private String pipelineId;

    /**
     * 节点ID
     */
    private String nodeId;

    /**
     * 节点类型
     * 如 Fetcher、Parser、Chunker 等
     */
    private String nodeType;

    /**
     * 节点排序
     */
    private Integer nodeOrder;

    /**
     * 状态 (如: Success, Failed, Skipped)
     */
    private String status;

    /**
     * 耗时（毫秒）
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
     * 输出结果
     */
    private Map<String, Object> output;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}
