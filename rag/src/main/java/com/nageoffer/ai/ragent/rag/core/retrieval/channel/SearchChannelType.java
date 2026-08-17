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

package com.nageoffer.ai.ragent.rag.core.retrieval.channel;

/**
 * 检索通道类型枚举
 */
public enum SearchChannelType {

    /**
     * 向量检索
     * 一条向量模态通道，按 KB 意图置信度在通道内决定作用域：
     * 有足够置信的 KB 意图时收窄到命中库（意图定向），否则退化为全库检索（全局）
     */
    VECTOR,

    /**
     * 关键词检索
     * 基于全文检索引擎（如 Elasticsearch）的关键词分词检索，后端为实现细节
     */
    KEYWORD,

    /**
     * 知识图谱检索
     * 基于实体与关系的图谱召回（预留，尚未实现）
     */
    GRAPH,

    /**
     * 联网检索
     * 基于外部 Web 搜索 API（如 You.com Search）的实时网络召回，与本地知识库通道互补
     */
    WEB_SEARCH,

    /**
     * 混合检索
     * 结合多种检索策略
     */
    HYBRID
}
