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

package com.nageoffer.ai.ragent.ingestion.domain.settings;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 索引器设置实体类
 * <p>
 * 定义向量索引节点的配置参数，包括嵌入模型等
 * 索引器负责将处理后的文本块存储到向量数据库中
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IndexerSettings {

    /**
     * 用于生成向量嵌入的模型标识
     */
    private String embeddingModel;

    /**
     * 是否将增强后的内容一并索引
     */
    private Boolean includeEnhancedContent;

    /**
     * 要存储的元数据字段列表
     * 指定哪些元数据字段应被存储到向量数据库中
     */
    private List<String> metadataFields;
}
