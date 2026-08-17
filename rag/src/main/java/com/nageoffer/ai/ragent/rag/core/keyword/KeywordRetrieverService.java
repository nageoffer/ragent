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

package com.nageoffer.ai.ragent.rag.core.keyword;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;

import java.util.List;

/**
 * 关键词检索服务 SPI
 * <p>
 * 与向量检索的 {@link com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService} 对称，
 * 负责基于分词的关键词（全文）检索，本期实现为 Elasticsearch（BM25）
 * <p>
 * 通过 rag.keyword.type 选择实现，none（默认）时无任何实现被注册，
 * 关键词检索通道也随之不注册，系统自动退化为纯向量检索
 * <p>
 * 返回类型复用 {@link RetrievedChunk}，与向量结果在通道出口处同构，融合层无需区分来源
 */
public interface KeywordRetrieverService {

    /**
     * 在共享索引内做关键词检索，按 collection 过滤
     *
     * @param query           用户问题（已重写）
     * @param collectionNames 目标知识库 collection 集合（作为 collection_name 过滤条件），空表示不限库（全局）
     * @param topK            召回数量
     * @return 命中 Chunk 列表，按相关性（BM25）倒序，id 与向量库主键 chunkId 对齐
     */
    List<RetrievedChunk> search(String query, List<String> collectionNames, int topK);
}
