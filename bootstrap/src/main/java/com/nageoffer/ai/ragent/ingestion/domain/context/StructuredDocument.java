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

package com.nageoffer.ai.ragent.ingestion.domain.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 结构化文档实体类
 * <p>
 * 表示经过解析后的结构化文档，包含文档的纯文本内容、章节结构、表格以及元数据等信息
 * 通过结构化解析，可以更好地保留文档的原始结构信息
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StructuredDocument {

    /**
     * 文档的纯文本内容
     */
    private String text;

    /**
     * 文档的章节结构列表
     * 按层级组织的标题和内容
     */
    private List<StructuredSection> sections;

    /**
     * 文档中的表格列表
     */
    private List<StructuredTable> tables;

    /**
     * 文档的元数据信息
     * 如作者、创建时间、页数等
     */
    private Map<String, Object> metadata;

    /**
     * 文档章节结构
     * 表示文档中的一个章节或段落，包含标题、层级、内容以及在原文中的位置信息
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StructuredSection {

        /**
         * 章节标题
         */
        private String title;

        /**
         * 章节层级（如1表示一级标题，2表示二级标题）
         */
        private Integer level;

        /**
         * 章节的文本内容
         */
        private String content;

        /**
         * 章节在原始文档中的起始偏移量
         */
        private Integer startOffset;

        /**
         * 章节在原始文档中的结束偏移量
         */
        private Integer endOffset;
    }

    /**
     * 文档表格结构
     * 表示文档中的一个表格，包含表格标题、行数据以及在原文中的位置信息
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StructuredTable {

        /**
         * 表格标题或名称
         */
        private String title;

        /**
         * 表格数据行
         * 每行是一个字符串列表，表示各单元格的内容
         */
        private List<List<String>> rows;

        /**
         * 表格在原始文档中的起始偏移量
         */
        private Integer startOffset;

        /**
         * 表格在原始文档中的结束偏移量
         */
        private Integer endOffset;
    }
}
