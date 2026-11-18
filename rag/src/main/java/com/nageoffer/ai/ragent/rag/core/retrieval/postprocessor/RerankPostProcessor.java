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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
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
    private final SearchChannelProperties searchChannelProperties;

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

        List<RetrievedChunk> reranked = rerank(context.getMainQuestion(), chunks, context.getBudget().contextTopK());

        logScoreSpread(reranked);
        logAttribution(chunks, reranked, results);
        return reranked;
    }

    /**
     * 开关开启时，送去打分的副本在正文前补一行文档名：同模板文档的续块正文不带产品名，精排看不出它属于哪一款
     * <p>
     * 结果按精排给的名次与分数输出原件的 toBuilder 副本，原件和 text 都不改；
     * 要求精排客户端只返回送入对象或其 toBuilder 副本（id 与 text 不变），否则找不回原件
     */
    private List<RetrievedChunk> rerank(String question, List<RetrievedChunk> chunks, int topN) {
        Map<String, RetrievedChunk> originalByKey = new HashMap<>();
        List<RetrievedChunk> input = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            RetrievedChunk scored = searchChannelProperties.isIncludeDocName() && StrUtil.isNotBlank(chunk.getDocName())
                    ? chunk.toBuilder().text(chunk.getDocName() + "\n" + chunk.getText()).build()
                    : chunk;
            originalByKey.put(RetrievedChunkKey.of(scored), chunk);
            input.add(scored);
        }

        List<RetrievedChunk> reranked = rerankService.rerank(question, input, topN);
        List<RetrievedChunk> result = new ArrayList<>(reranked.size());
        for (RetrievedChunk hit : reranked) {
            result.add(originalByKey.get(RetrievedChunkKey.of(hit)).toBuilder()
                    .score(hit.getScore())
                    .rerankScore(hit.getRerankScore())
                    .build());
        }
        return result;
    }

    /**
     * 打本批精排分的高低两端，用于校准 {@code rag.search.evidence.min-rerank-score}
     * 不并进下方多通道归因：那段在单通道下整体早退，而闸门关掉时恰恰最需要这行
     */
    private void logScoreSpread(List<RetrievedChunk> reranked) {
        List<Float> scores = reranked.stream()
                .map(RetrievedChunk::getRerankScore)
                .filter(score -> score != null && Float.isFinite(score))
                .toList();
        if (scores.isEmpty()) {
            return;
        }
        log.info("检索归因 - 精排分布: {} 条有分, 最高 {}, 最低 {}",
                scores.size(),
                scores.stream().max(Float::compare).orElseThrow(),
                scores.stream().min(Float::compare).orElseThrow());
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
