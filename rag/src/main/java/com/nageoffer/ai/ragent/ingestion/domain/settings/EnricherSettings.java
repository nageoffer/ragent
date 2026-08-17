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

import com.nageoffer.ai.ragent.ingestion.domain.enums.ChunkEnrichType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 富集器设置实体类
 * <p>
 * 定义文本块富集节点的配置参数，包括模型ID、是否附加文档元数据以及富集任务列表
 * 富集器用于为分块后的文本块添加额外信息
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnricherSettings {

    /**
     * 用于富集处理的模型ID
     */
    private String modelId;

    /**
     * 是否将文档元数据附加到分块
     */
    private Boolean attachDocumentMetadata;

    /**
     * 要执行的富集任务列表
     */
    private List<ChunkEnrichTask> tasks;

    /**
     * 分块富集任务配置
     * 定义单个块富集任务的类型和提示词配置
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ChunkEnrichTask {

        /**
         * 富集任务类型
         */
        private ChunkEnrichType type;

        /**
         * 系统提示词
         */
        private String systemPrompt;

        /**
         * 用户提示词模板
         * 支持变量替换
         */
        private String userPromptTemplate;
    }
}
