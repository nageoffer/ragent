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

/**
 * 分块器设置实体类
 * 定义文档分块节点的配置参数，包括分块策略、块大小、重叠大小等
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChunkerSettings {

    /**
     * 分块策略（已废弃：保留字段只为管道编辑器 UI 不用改，后端不再读取）
     * <p>
     * 切法由文档结构唯一决定，用户只控预算。原先这个枚举在真实链路上不产生任何差异——
     * 所有解析器都产出结构化 Block，分块一律走 block-aware 分支，策略参数被直接丢弃
     */
    private String strategy;

    /**
     * 块的目标大小（字符数或token数）
     */
    private Integer chunkSize;

    /**
     * 相邻块之间的重叠大小
     * 用于保持上下文连贯性
     */
    private Integer overlapSize;

    /**
     * 自定义分割符
     * 用于指定文本切分的边界字符
     */
    private String separator;

    /**
     * 表格每个 chunk 的最大数据行数（block-aware 链路 TableChunker 的硬上限）
     * 实际块大小主要由 chunkSize 预算驱动，本值仅防止极窄表把过多行堆进一块
     * 可空，为空时由 ChunkerNode 取默认值
     */
    private Integer rowsPerChunk;
}
