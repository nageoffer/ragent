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

package com.nageoffer.ai.ragent.ingestion.node;

import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;

/**
 * 摄取节点接口，定义了数据摄取流程中的基本单元
 * 每个节点负责执行特定的处理逻辑
 */
public interface IngestionNode {

    /**
     * 获取节点类型标识
     *
     * @return 节点类型的字符串表示
     */
    String getNodeType();

    /**
     * 执行节点的具体逻辑
     *
     * @param context 摄取过程中的上下文信息，包含共享状态和数据
     * @param config  当前节点的配置信息
     * @return 节点执行的结果
     */
    NodeResult execute(IngestionContext context, NodeConfig config);
}
