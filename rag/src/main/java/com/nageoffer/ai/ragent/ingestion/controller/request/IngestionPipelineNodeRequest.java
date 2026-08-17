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

package com.nageoffer.ai.ragent.ingestion.controller.request;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 摄取管道节点请求对象
 * 用于接收管道中单个节点的配置信息，包括节点标识、类型、设置参数及执行条件
 */
@Data
public class IngestionPipelineNodeRequest {

    /**
     * 节点的唯一标识符
     */
    private String nodeId;

    /**
     * 节点类型
     * 如 fetcher、parser、chunker、indexer 等
     */
    private String nodeType;

    /**
     * 节点的配置参数
     * 不同类型的节点有不同的配置结构
     */
    private JsonNode settings;

    /**
     * 节点执行的条件表达式
     * 满足条件时才执行该节点
     */
    private JsonNode condition;

    /**
     * 下一个节点ID
     * 用于定义管道中节点的执行顺序
     */
    private String nextNodeId;
}
