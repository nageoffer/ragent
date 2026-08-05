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
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievedChunkKey;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 去重后置处理器
 * <p>
 * 合并多个通道的结果并按 key 去重：同一 Chunk 多路命中时保留首次出现的实例
 * 不在此处比较跨通道分数（量纲不可比），最终名次统一交由下游 RRF 融合赋分
 */
@Slf4j
@Component
public class DeduplicationPostProcessor implements SearchResultPostProcessor {

    @Override
    public String getName() {
        return "Deduplication";
    }

    @Override
    public int getOrder() {
        return 1;  // 最先执行
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;  // 始终启用
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        // 按 key 做集合去并：同一 Chunk 多路命中时保留首次出现的实例即可
        // 不比较各通道原始分——BM25/余弦/图谱分跨量纲不可比，且最终名次由下游 RRF 融合统一赋分
        // 通道遍历顺序不影响结果：下游融合（RRF 累加）与归因（从 results 重算）均与顺序无关
        Map<String, RetrievedChunk> chunkMap = new LinkedHashMap<>();
        for (SearchChannelResult result : results) {
            for (RetrievedChunk chunk : result.getChunks()) {
                chunkMap.putIfAbsent(generateChunkKey(chunk), chunk);
            }
        }
        return new ArrayList<>(chunkMap.values());
    }

    /**
     * 生成 Chunk 唯一键
     */
    private String generateChunkKey(RetrievedChunk chunk) {
        return RetrievedChunkKey.of(chunk);
    }
}
