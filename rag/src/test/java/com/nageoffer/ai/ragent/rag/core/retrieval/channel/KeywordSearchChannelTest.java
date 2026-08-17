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

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.keyword.KeywordRetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KeywordSearchChannelTest {

    private static final String QUESTION = "报销发票贴哪张表？";
    private static final List<String> ACTIVE = List.of("kb-finance", "kb-hr", "kb-tech");
    private static final List<String> SUPPLEMENT = List.of("kb-hr", "kb-tech");
    /** 生产配置：每通道召回 20、Rerank 候选池 40、最终 10 条 */
    private static final RetrievalBudget PRODUCTION_BUDGET = new RetrievalBudget(20, 40, 10);

    /**
     * 关键词通道发出的一次检索，用于断言名额如何落到实际请求上
     */
    private record Query(List<String> collections, int topK) {
    }

    private final List<Query> queries = new ArrayList<>();
    private KeywordRetrieverService keywordRetriever;
    private SearchChannelProperties properties;

    @BeforeEach
    void setUp() {
        keywordRetriever = mock(KeywordRetrieverService.class);
        properties = new SearchChannelProperties();
        // 遵守 topK 的桩：真实后端返回条数受请求深度约束，固定返回会掩盖名额错配
        when(keywordRetriever.search(anyString(), anyList(), anyInt())).thenAnswer(invocation -> {
            List<String> collections = invocation.getArgument(1);
            int topK = invocation.getArgument(2);
            queries.add(new Query(collections, topK));
            return roundRobin(collections, topK);
        });
    }

    @Test
    @DisplayName("定向作用域下主路与补充路各按名额取数")
    void directedScopeSplitsQuotaAcrossBothPaths() {
        List<RetrievedChunk> chunks = search(directedScope(), PRODUCTION_BUDGET);

        assertEquals(List.of(new Query(List.of("kb-finance"), 15), new Query(SUPPLEMENT, 5)),
                sortedQueries(), "产能即 recallBudget 20，切分为主路 15 / 补充路 5");
        assertEquals(15, count(chunks, "dir"));
        assertEquals(5, count(chunks, "sup"));
    }

    @Test
    @DisplayName("命中库已覆盖全部有效库时不补充")
    void fullCoverageSkipsSupplementQuery() {
        RetrievalScope scope = new RetrievalScope(
                true, 0.9, List.of(intent("kb-finance")), ACTIVE, List.of());

        List<RetrievedChunk> chunks = search(scope, PRODUCTION_BUDGET);

        assertEquals(List.of(new Query(ACTIVE, 20)), queries, "无处可补时名额全归主路");
        assertEquals(20, chunks.size());
    }

    @Test
    @DisplayName("补充比例置零时退化为纯定向")
    void zeroSupplementRatioDisablesSupplement() {
        properties.getScope().setSupplementRatio(0);

        List<RetrievedChunk> chunks = search(directedScope(), PRODUCTION_BUDGET);

        assertEquals(List.of(new Query(List.of("kb-finance"), 20)), queries);
        assertEquals(0, count(chunks, "sup"));
    }

    @Test
    @DisplayName("全局作用域下单次跨全部有效库检索")
    void globalScopeQueriesAllCollectionsOnce() {
        List<RetrievedChunk> chunks = search(RetrievalScope.global(0.3, ACTIVE), PRODUCTION_BUDGET);

        assertEquals(List.of(new Query(ACTIVE, 20)), queries, "全局作用域不切分名额");
        assertEquals(20, chunks.size());
    }

    @Test
    @DisplayName("召回额度极小时主路仍取得到数据")
    void tinyRecallBudgetStillFetchesForPrimary() {
        // 回归：本通道把名额直接当 ES 的 size 用、而非事后截断，主路名额若被补充路的保底挤成 0，
        // 发出的就是 size=0 的查询、主路一条都拿不回来（同一个 0 在向量与图谱通道只是「不截断」，后果不同）
        List<RetrievedChunk> chunks = search(directedScope(), new RetrievalBudget(1, 40, 1));

        assertTrue(queries.stream().allMatch(query -> query.topK() >= 1),
                "任何一路的取数深度都不该为 0");
        assertEquals(1, chunks.size());
    }

    @Test
    @DisplayName("两路候选合并后按相关性降序")
    void mergedOutputStaysSortedByScore() {
        // 补充路的强命中必须能排到主路弱命中之前：下游 RRF 按列表位次取分，
        // 主路在前、补充路拼后等于名额给了、排序又把它按回去
        List<RetrievedChunk> chunks = search(directedScope(), PRODUCTION_BUDGET);

        List<Float> scores = chunks.stream().map(RetrievedChunk::getScore).toList();
        assertEquals(scores.stream().sorted((a, b) -> Float.compare(b, a)).toList(), scores,
                "通道出口须全局有序，否则下游 RRF 的名次基准失真");
    }

    @Test
    @DisplayName("未解析到目标知识库时跳过检索")
    void emptyScopeSkipsRetrieval() {
        List<RetrievedChunk> chunks = search(RetrievalScope.global(0.0, List.of()), PRODUCTION_BUDGET);

        assertTrue(queries.isEmpty());
        assertTrue(chunks.isEmpty());
    }

    @Test
    @DisplayName("补充路失败时命中库证据仍然产出")
    void supplementFailureDoesNotDropPrimaryChunks() {
        // 回归：补充路拿的是兜底名额，它抛出却会让已取回的命中库证据被通道级 catch 一起丢掉。
        // 当前 ES 实现恰好自己吞了异常，但那是实现的偶然、不是通道的保证
        when(keywordRetriever.search(anyString(), anyList(), anyInt())).thenAnswer(invocation -> {
            List<String> collections = invocation.getArgument(1);
            if (SUPPLEMENT.equals(collections)) {
                throw new IllegalStateException("ES 抖动");
            }
            return roundRobin(collections, invocation.getArgument(2));
        });

        List<RetrievedChunk> chunks = search(directedScope(), PRODUCTION_BUDGET);

        assertEquals(15, count(chunks, "dir"), "补充路故障只该损失补充证据");
        assertEquals(0, count(chunks, "sup"));
    }

    @Test
    @DisplayName("后端异常时降级为空结果而非中断整条链路")
    void retrieverFailureDegradesToEmptyResult() {
        when(keywordRetriever.search(anyString(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("ES 不可用"));

        assertTrue(search(directedScope(), PRODUCTION_BUDGET).isEmpty());
    }

    private List<RetrievedChunk> search(RetrievalScope scope, RetrievalBudget budget) {
        SearchContext context = SearchContext.builder()
                .originalQuestion(QUESTION)
                .budget(budget)
                .retrievalScope(scope)
                .build();
        return new KeywordSearchChannel(keywordRetriever, properties, Runnable::run)
                .search(context)
                .getChunks();
    }

    /**
     * 两路并发发出，落到列表里的先后不定，按主路在前归一以便断言
     */
    private List<Query> sortedQueries() {
        return queries.stream()
                .sorted((left, right) -> Boolean.compare(
                        SUPPLEMENT.containsAll(left.collections()), SUPPLEMENT.containsAll(right.collections())))
                .toList();
    }

    private static long count(List<RetrievedChunk> chunks, String prefix) {
        return chunks.stream().filter(chunk -> chunk.getId().startsWith(prefix)).count();
    }

    private static RetrievalScope directedScope() {
        return new RetrievalScope(
                true, 0.9, List.of(intent("kb-finance")), List.of("kb-finance"), SUPPLEMENT);
    }

    private static NodeScore intent(String collection) {
        return NodeScore.builder()
                .node(IntentNode.builder()
                        .id("intent-" + collection)
                        .name(collection)
                        .collectionNames(List.of(collection))
                        .build())
                .score(0.9)
                .build();
    }

    /**
     * 按库轮转填满 topK
     * <p>
     * chunk 的 id 与 BM25 分只能由所属库决定、不能由查询范围决定：真实后端里一条 chunk 归属单个库，
     * 桩若按「这次是不是补充路」派生 id，两路取数深度错配这类缺陷在用例里就看不见。
     * 补充路的分数区间与主路交叠，用来验证合并后是真排序而非拼接
     */
    private static List<RetrievedChunk> roundRobin(List<String> collections, int topK) {
        return IntStream.range(0, topK)
                .mapToObj(index -> {
                    String collection = collections.get(index % collections.size());
                    boolean supplement = SUPPLEMENT.contains(collection);
                    String id = (supplement ? "sup-" : "dir-") + collection + "-" + index / collections.size();
                    return RetrievedChunk.builder()
                            .id(id)
                            .text(id)
                            .score((supplement ? 14.0F : 12.0F) - index)
                            .build();
                })
                .toList();
    }
}
