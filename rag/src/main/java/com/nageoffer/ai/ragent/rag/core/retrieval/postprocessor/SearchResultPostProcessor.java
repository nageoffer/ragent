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

package com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;

import java.util.List;

/**
 * 检索结果后置处理器接口
 * <p>
 * 对多通道检索结果进行统一的后处理，例如：
 * - 去重
 * - 版本过滤
 * - 分数归一化
 * - Rerank
 * <p>
 * 处理器按照 order 顺序依次执行，形成处理链
 */
public interface SearchResultPostProcessor {

    /**
     * 处理器名称
     */
    String getName();

    /**
     * 处理器优先级（数字越小越先执行）
     */
    int getOrder();

    /**
     * 是否启用该处理器
     *
     * @param context 检索上下文
     * @return true 表示启用，false 表示跳过
     */
    boolean isEnabled(SearchContext context);

    /**
     * 处理检索结果
     *
     * @param chunks  当前的 Chunk 列表（可能是上一个处理器的输出）
     * @param results 原始的多通道检索结果（用于获取元信息）
     * @param context 检索上下文
     * @return 处理后的 Chunk 列表
     */
    List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                 List<SearchChannelResult> results,
                                 SearchContext context);
}
