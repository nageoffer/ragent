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

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.GraphProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.graph.GraphEvidence;
import com.nageoffer.ai.ragent.rag.core.graph.LightRagClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识图谱检索通道
 * <p>
 * 基于 LightRAG 的图谱召回：擅长多跳关系推理与实体为中心的聚合，与向量 / 关键词互补
 * 仅当开启图谱后端（rag.graph.type=lightrag）时才注册，否则整个通道不存在，引擎自动退化为无图谱检索
 * <p>
 * 与其他通道并行执行，结果统一进 RRF 融合，通道间无先后与优先级之分
 * <p>
 * 说明：LightRAG /query 无 per-request workspace，单实例即单图，故本通道查全图后在结果侧按 file_path 归属切分；
 * 真正的子图隔离需多实例，留待后续阶段
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.graph", name = "type", havingValue = "lightrag")
public class GraphSearchChannel implements SearchChannel {

    /**
     * 定向过滤时向 LightRAG 的请求量上浮倍数
     * 结果侧过滤在 top_k 截断后再筛掉跨库证据，命中库证据可能变少，多取以补召回；全局过滤只筛残留，不上浮
     */
    private static final int FILTER_TOPK_BOOST = 3;

    private final LightRagClient lightRagClient;
    private final GraphProperties graphProperties;
    private final SearchChannelProperties properties;

    @Override
    public String getName() {
        return "GraphSearch";
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.GRAPH;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getChannels().getGraph().isEnabled();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        try {
            // 作用域由引擎统一解析：定向为命中库，全局为全部有效库
            // 全局也下发库集做结果侧过滤：图谱删除是 best-effort，已删库的残留只能在这里拦住
            RetrievalScope scope = context.getRetrievalScope();
            List<String> collections = scope.targetCollections();
            if (CollUtil.isEmpty(collections)) {
                log.info("图谱检索未解析到有效知识库，跳过");
                return emptyResult(System.currentTimeMillis() - startTime);
            }

            int baseTopK = context.getBudget().recallBudget();
            int topK = scope.directed() ? baseTopK * FILTER_TOPK_BOOST : baseTopK;
            String queryMode = graphProperties.getLightrag().getQueryMode();

            GraphEvidence evidence = lightRagClient.retrieveByScope(
                    context.getMainQuestion(), queryMode, topK, collections);
            if (!scope.directed() && !evidence.unmatched().isEmpty()) {
                log.warn("图谱全局检索过滤掉 {} 条无主证据（已删库残留或解析失败），残留会挤占 top_k 名额，建议清理图谱",
                        evidence.unmatched().size());
            }
            ScopeQuota quota = ScopeQuota.split(scope, baseTopK, properties.getScope().getSupplementRatio());
            List<RetrievedChunk> primary = ScopeQuota.cap(evidence.matched(), quota.primary());
            List<RetrievedChunk> supplement = ScopeQuota.cap(evidence.unmatched(), quota.supplement());

            long latency = System.currentTimeMillis() - startTime;
            log.info("图谱检索完成，范围={}，命中 {} 条，补充 {} 条，耗时 {}ms",
                    scope.directed() ? collections : "全局(" + collections.size() + "库)",
                    primary.size(), supplement.size(), latency);

            // 两份候选的分数同出一个全图名次序，按分混排即还原图谱自己的排序
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.GRAPH)
                    .channelName(getName())
                    .chunks(ChunkRanking.mergeByScore(primary, supplement))
                    .latencyMs(latency)
                    .build();
        } catch (Exception e) {
            log.error("图谱检索失败", e);
            return emptyResult(System.currentTimeMillis() - startTime);
        }
    }
}
