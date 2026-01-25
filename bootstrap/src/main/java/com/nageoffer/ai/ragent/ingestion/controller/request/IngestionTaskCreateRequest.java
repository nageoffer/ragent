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

import com.nageoffer.ai.ragent.controller.request.DocumentSourceRequest;
import com.nageoffer.ai.ragent.rag.vector.VectorSpaceId;
import lombok.Data;

import java.util.Map;

/**
 * 摄取任务创建请求对象
 * 用于接收创建新摄取任务的请求参数，包括管道ID、文档源信息及元数据。
 */
@Data
public class IngestionTaskCreateRequest {

    /**
     * 执行本次摄取的管道ID
     */
    private String pipelineId;

    /**
     * 文档源信息
     */
    private DocumentSourceRequest source;

    /**
     * 摄取任务的元数据信息
     * 自定义的附加属性键值对
     */
    private Map<String, Object> metadata;

    /**
     * 向量空间ID，指定向量数据写入的目标集合
     * 如果不指定，则使用默认的向量空间
     */
    private VectorSpaceId vectorSpaceId;
}
