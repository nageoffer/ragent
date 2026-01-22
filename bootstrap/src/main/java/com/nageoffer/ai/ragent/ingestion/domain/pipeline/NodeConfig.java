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

package com.nageoffer.ai.ragent.ingestion.domain.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管道节点配置实体类
 * 定义摄取管道中单个节点的配置信息，包括节点标识、类型、设置参数以及执行条件等
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NodeConfig {

    /**
     * 节点的唯一标识符
     */
    private String nodeId;

    /**
     * 节点类型
     * <p>对应 NodeType 枚举值，如 FETCHER、PARSER 等</p>
     */
    private String nodeType;

    /**
     * 节点的配置参数
     * <p>不同类型的节点有不同的配置结构</p>
     */
    private JsonNode settings;

    /**
     * 节点执行的条件表达式
     * <p>满足条件时才执行该节点</p>
     */
    private JsonNode condition;

    /**
     * 下一个节点ID
     * <p>用于定义管道中节点的执行顺序</p>
     */
    private String nextNodeId;
}
