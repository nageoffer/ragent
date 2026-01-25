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

package com.nageoffer.ai.ragent.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档索引结果实体类（DocumentIndexResult）
 * <p>
 * 用途说明：
 * - 用于记录一份业务文档在被切分与向量化后的基础信息
 * - 在文档上传、解析、切片、索引构建完成后返回给前端或用于日志记录
 * <p>
 * 典型使用场景：
 * - 文档上传后的索引结果返回（RAG 后台 → 前端）
 * - 文档管理列表展示
 * - 后台记录文档向量化情况（如 chunk 数、来源类型等）
 * <p>
 * 注意事项：
 * - documentId 为业务唯一标识，与多个 DocumentChunk 存在 1:N 关系
 * - chunkCount 用于展示向量切片粒度，便于排查文档解析是否正常
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DocumentIndexResult {

    /**
     * 业务文档 ID
     * 用途：
     * - 后续执行文档的增删改查操作时使用
     * - 对应所有 Chunk 的 documentId（与其建立关联）
     */
    private String documentId;

    /**
     * 文档名称
     * 示例：
     * - example.pdf
     * - 项目设计说明书.docx
     * - 公司流程规范.md
     * 用途：
     * - 前端展示
     * - 日志与索引记录
     */
    private String name;

    /**
     * 文档来源类型
     * 常见值：
     * - pdf
     * - markdown
     * - doc / docx
     * - text
     * - html
     * 用途：
     * - 便于前端识别展示图标
     * - 便于后端统计文档结构类型
     */
    private String sourceType;

    /**
     * 文档被切片后的 Chunk 数量
     * 说明：
     * - 用于展示向量切片粒度
     * - 可用于判断文档内容是否解析完整（过小或过大都可能有问题）
     */
    private int chunkCount;
}

