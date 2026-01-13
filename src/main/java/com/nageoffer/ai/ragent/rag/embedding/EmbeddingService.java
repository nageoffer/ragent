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

package com.nageoffer.ai.ragent.rag.embedding;

import java.util.List;

/**
 * 向量化服务接口（EmbeddingService）
 * <p>
 * 用途说明：
 * - 提供文本向量化能力，是 RAG 系统的核心基础组件
 * - 封装底层 Embedding 模型的调用逻辑（如 Ollama、DeepSeek、Qwen、本地推理服务等）
 * - 对外提供统一的向量生成接口，屏蔽具体模型差异
 * <p>
 * 使用场景：
 * - 文档切片后进行向量化写入向量库（Indexing）
 * - 查询问题向量化，用于检索相关 Chunk（Retrieval）
 * <p>
 * 注意事项：
 * - 实现类需保证向量维度一致（dimension() 固定）
 * - 批量向量化应进行模型级优化，例如减少 RPC / 本地推理调用次数
 * - 文本需在向量化前进行清洗（trim、空过滤、控制符处理等）
 */
public interface EmbeddingService {

    /**
     * 对单个文本进行向量化（Embedding）
     * <p>
     * 说明：
     * - 通常用于查询向量生成（Query Embedding）
     * - 输出为 Float 向量列表，例如：[0.123f, -0.078f, ...]
     *
     * @param text 待向量化文本
     * @return 文本对应的向量（长度固定，如 4096）
     */
    List<Float> embed(String text);

    /**
     * 对多个文本进行批量向量化
     * <p>
     * 说明：
     * - 常用于文档索引构建（Indexing），性能优于单次调用 embed()
     * - 返回结果与输入 texts 顺序一致
     * - 实现类可利用模型的批量计算能力提升吞吐
     *
     * @param texts 文本列表
     * @return 向量列表，每项对应输入文本的向量
     */
    List<List<Float>> embedBatch(List<String> texts);

    /**
     * 返回向量维度（Embedding Dimension）
     * <p>
     * 说明：
     * - 根据底层模型决定（如 Qwen3-Embedding 维度为 4096）
     * - 用于校验向量长度、向量库 schema 定义等
     *
     * @return 向量维度（如 4096、768 等）
     */
    default int dimension() {
        return 0;
    }
}
