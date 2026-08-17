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
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rerank 后置处理器
 * <p>
 * 使用 Rerank 模型对结果进行重排序
 * 这是最后一个处理器，输出最终的 Top-K 结果
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankPostProcessor implements SearchResultPostProcessor {

    private final RerankService rerankService;
    private final RAGConfigProperties ragConfigProperties;

    @Override
    public String getName() {
        return "Rerank";
    }

    @Override
    public int getOrder() {
        return 10;  // 最后执行
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return ragConfigProperties.getRerankEnabled();
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        if (chunks.isEmpty()) {
            log.info("Chunk 列表为空，跳过 Rerank");
            return chunks;
        }

        List<RetrievedChunk> reranked = rerankService.rerank(
                context.getMainQuestion(),
                chunks,
                context.getBudget().contextTopK()
        );

        logAttribution(chunks, reranked, results);
        return reranked;
    }

    /**
     * 归因日志：对比 Rerank 前后各通道的候选数，重点是「图谱证据存活率」
     * <p>
     * 若图谱大量进入 Rerank 却几乎不存活，说明其当前是纯成本（塞候选、占名额、被淘汰），
     * 应下调图谱权重（{@code fusion.channel-weights.graph}）或先优化其长证据的可排性，再决定去留
     */
    private void logAttribution(List<RetrievedChunk> before,
                                List<RetrievedChunk> after,
                                List<SearchChannelResult> results) {
        if (results == null || results.size() <= 1) {
            return;
        }
        Map<String, Set<SearchChannelType>> index = ChannelAttribution.index(results);
        log.info("检索归因 - Rerank 输入按通道: {}, 输出 top{} 按通道: {}",
                ChannelAttribution.format(ChannelAttribution.countByChannel(before, index)),
                after.size(),
                ChannelAttribution.format(ChannelAttribution.countByChannel(after, index)));

        // 按图谱通道在场判断而非 graphIn > 0：0/0 恰是最需要看见的形态——图谱召回了却在融合截断处全军覆没，
        // 按输入量守门会让这行日志在事故发生时恒沉默
        boolean graphChannelPresent = results.stream()
                .anyMatch(result -> result.getChannelType() == SearchChannelType.GRAPH);
        if (graphChannelPresent) {
            long graphIn = ChannelAttribution.countOfChannel(before, index, SearchChannelType.GRAPH);
            long graphOut = ChannelAttribution.countOfChannel(after, index, SearchChannelType.GRAPH);
            log.info("检索归因 - 图谱证据存活: {}/{}", graphOut, graphIn);
        }
    }
}
