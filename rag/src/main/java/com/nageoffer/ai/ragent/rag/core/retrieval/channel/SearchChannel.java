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

import java.util.List;

/**
 * 检索通道接口
 * <p>
 * 每个通道负责一种检索策略，例如：
 * - 向量全局检索
 * - 意图定向检索
 * - ES 关键词检索
 * <p>
 * 多个通道可以并行执行，最后统一合并结果
 */
public interface SearchChannel {

    /**
     * 通道名称（用于日志和监控）
     */
    String getName();

    /**
     * 是否启用该通道
     *
     * @param context 检索上下文
     * @return true 表示启用，false 表示跳过
     */
    boolean isEnabled(SearchContext context);

    /**
     * 执行检索
     *
     * @param context 检索上下文
     * @return 检索结果
     */
    SearchChannelResult search(SearchContext context);

    /**
     * 通道类型
     */
    SearchChannelType getType();

    /**
     * 空结果交卷：检索失败或无数据时的降级形态，只带通道身份与耗时
     * 引擎的超时降级与各通道的异常兜底共用，保证空结果的形状全站一致
     */
    default SearchChannelResult emptyResult(long latencyMs) {
        return SearchChannelResult.builder()
                .channelType(getType())
                .channelName(getName())
                .chunks(List.of())
                .latencyMs(latencyMs)
                .build();
    }
}
