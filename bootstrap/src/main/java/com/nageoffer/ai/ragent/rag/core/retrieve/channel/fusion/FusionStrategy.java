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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel.fusion;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;

import java.util.List;
import java.util.Map;

/**
 * 多通道检索结果融合策略接口
 */
public interface FusionStrategy {

    /**
     * 融合多个通道的检索结果
     *
     * @param rankedChunks 各通道的排序结果列表，key 为通道标识
     * @param weights      各通道的权重(可选，用于加权融合)
     * @return 融合后的 Chunk 列表，按融合分数降序排列
     */
    List<RetrievedChunk> fuse(Map<String, List<RetrievedChunk>> rankedChunks,
                              Map<String, Float> weights);
}
