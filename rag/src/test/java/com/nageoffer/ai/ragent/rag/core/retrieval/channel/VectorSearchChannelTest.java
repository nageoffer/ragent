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
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorSearchChannelTest {

    private static final String QUESTION = "报销发票贴哪张表？";
    private static final List<String> SUPPLEMENT = List.of("kb-hr", "kb-tech");
    private static final float[] QUERY_VECTOR = {0.6F, 0.8F};
    /** 生产配置：每通道召回 20、Rerank 候选池 40、最终 10 条 */
    private static final RetrievalBudget PRODUCTION_BUDGET = new RetrievalBudget(20, 40, 10);

    private VectorRetrieverService retrieverService;
    private SearchChannelProperties properties;

    @BeforeEach
    void setUp() {
        retrieverService = mock(VectorRetrieverService.class);
        when(retrieverService.embedAndNormalize(QUESTION)).thenReturn(QUERY_VECTOR);
        when(retrieverService.supportsGlobalRetrieval()).thenReturn(true);
        // 遵守 topK 的桩：真实后端返回条数受请求深度约束，否则测不出「主路产能 < 名额」这类错配
        when(retrieverService.retrieveByVector(any(float[].class), any(RetrieveRequest.class)))
                .thenAnswer(invocation -> {
                    RetrieveRequest request = invocation.getArgument(1);
                    return roundRobin(request.getEffectiveCollectionNames(), request.getTopK());
                });
        properties = new SearchChannelProperties();
    }

    @Test
    @DisplayName("定向作用域下并行补一路未命中库，占通道产能的保底名额")
    void supplementQueryTakesReservedQuota() {
        search(directedScope(), PRODUCTION_BUDGET);

        RetrieveRequest supplement = captureRequests().stream()
                .filter(request -> request.getEffectiveCollectionNames().equals(SUPPLEMENT))
                .findFirst()
                .orElseThrow();
        assertEquals(5, supplement.getTopK(), "单意图产能 20，补充路应拿其中 25%");
    }

    @Test
    @DisplayName("主补切分只由通道召回额度与比例决定，不随意图数与候选池上限漂移")
    void supplementShareStaysConstantAcrossIntentCountAndCandidateLimit() {
        // 定向路收敛为逐库取数后，通道产出额度恒为 recallBudget，与关键词/图谱通道同一口径；
        // 旧机制产能是意图数 × recallBudget，曾把补充占比从 25% 漂到 56%
        assertSupplementShare(1, 40, 15, 5);
        assertSupplementShare(2, 40, 15, 5);
        assertSupplementShare(3, 40, 15, 5);
        assertSupplementShare(1, 100, 15, 5);
    }

    @Test
    @DisplayName("意图节点自定义 topK 时覆盖召回深度，且受候选池上限钳制")
    void customNodeTopKOverridesDepthClampedByCandidateLimit() {
        NodeScore deepIntent = NodeScore.builder()
                .node(IntentNode.builder()
                        .id("finance")
                        .name("财务")
                        .collectionNames(List.of("kb-finance"))
                        .topK(100)
                        .build())
                .score(0.9)
                .build();
        RetrievalScope scope = new RetrievalScope(
                true, 0.9, List.of(deepIntent), List.of("kb-finance"), SUPPLEMENT);

        List<RetrievedChunk> chunks = search(scope, PRODUCTION_BUDGET).getChunks();

        // 深度 100 顶到候选池上限 40 再切分，深挖意图不被误砍到 15、超池部分也不空转
        assertEquals(30, count(chunks, "dir"));
        assertEquals(10, count(chunks, "sup"));
    }

    @Test
    @DisplayName("多意图命中时召回深度取各意图的最大值，缺省者按通道额度参与比较")
    void multiIntentDepthTakesMaxWithRecallBudgetDefault() {
        NodeScore shallow = NodeScore.builder()
                .node(IntentNode.builder()
                        .id("faq").name("FAQ")
                        .collectionNames(List.of("kb-faq"))
                        .topK(5)
                        .build())
                .score(0.9)
                .build();
        RetrievalScope scope = new RetrievalScope(true, 0.9,
                List.of(shallow, intent("kb-finance")),
                List.of("kb-faq", "kb-finance"), SUPPLEMENT);

        List<RetrievedChunk> chunks = search(scope, PRODUCTION_BUDGET).getChunks();

        // max(5, 缺省 20) = 20：一次查询只有一个深度，取大只放宽召回、交给精排收敛
        assertEquals(15, count(chunks, "dir"));
        assertEquals(5, count(chunks, "sup"));
    }

    @Test
    @DisplayName("单意图 topK 小于通道额度时按绝对深度收窄")
    void smallNodeTopKNarrowsDepthAbsolutely() {
        NodeScore shallow = NodeScore.builder()
                .node(IntentNode.builder()
                        .id("faq").name("FAQ")
                        .collectionNames(List.of("kb-faq"))
                        .topK(5)
                        .build())
                .score(0.9)
                .build();
        RetrievalScope scope = new RetrievalScope(
                true, 0.9, List.of(shallow), List.of("kb-faq"), SUPPLEMENT);

        List<RetrievedChunk> chunks = search(scope, PRODUCTION_BUDGET).getChunks();

        // topK 是绝对深度不是下限：小库精配 5 就只召 5，切分后主路 4 补充 1
        assertEquals(4, count(chunks, "dir"));
        assertEquals(1, count(chunks, "sup"));
    }

    @Test
    @DisplayName("命中库已覆盖全部有效库时不补充")
    void fullCoverageSkipsSupplementQuery() {
        RetrievalScope scope = new RetrievalScope(
                true, 0.9, List.of(intent("kb-finance")), List.of("kb-finance"), List.of());

        List<RetrievedChunk> chunks = search(scope, PRODUCTION_BUDGET).getChunks();

        assertEquals(List.of(List.of("kb-finance")),
                captureRequests().stream().map(RetrieveRequest::getEffectiveCollectionNames).toList());
        assertEquals(20, chunks.size(), "无处可补时名额全归主路");
    }

    @Test
    @DisplayName("补充比例置零时退化为纯定向")
    void zeroSupplementRatioDisablesSupplement() {
        properties.getScope().setSupplementRatio(0);

        List<RetrievedChunk> chunks = search(directedScope(), PRODUCTION_BUDGET).getChunks();

        assertTrue(captureRequests().stream()
                .noneMatch(request -> request.getEffectiveCollectionNames().equals(SUPPLEMENT)));
        assertEquals(20, chunks.size(), "关闭补充路后主路拿满产能");
    }

    @Test
    @DisplayName("定向路与补充路共用一次Query向量")
    void directedAndSupplementShareOneQueryVector() {
        search(directedScope(), PRODUCTION_BUDGET);

        ArgumentCaptor<float[]> vectorCaptor = ArgumentCaptor.forClass(float[].class);
        verify(retrieverService, times(2)).retrieveByVector(vectorCaptor.capture(), any(RetrieveRequest.class));
        vectorCaptor.getAllValues().forEach(vector -> assertSame(QUERY_VECTOR, vector));
        verify(retrieverService, times(1)).embedAndNormalize(QUESTION);
    }

    @Test
    @DisplayName("全局作用域下单次跨全部有效库检索，取数深度与其他通道同源")
    void globalScopeQueriesAllCollectionsOnce() {
        // 全局路取数只受 recallBudget 管：候选池上限是 RRF 之后的闸门而非取数目标，
        // 拿它当取数条数会让「调 Rerank 池」顺带改写向量库查询深度，一份配置两个职责
        RetrievalScope scope = RetrievalScope.global(0.3, List.of("kb-finance", "kb-hr", "kb-tech"));

        search(scope, PRODUCTION_BUDGET);

        List<RetrieveRequest> requests = captureRequests();
        assertEquals(1, requests.size());
        assertEquals(List.of("kb-finance", "kb-hr", "kb-tech"), requests.get(0).getEffectiveCollectionNames());
        assertEquals(20, requests.get(0).getTopK());
    }

    @Test
    @DisplayName("不同意图节点绑定同一个库时只发一次查询")
    void intentsOnSameCollectionShareOneQuery() {
        // 查询以库集合为单位、不再按意图扇出：同库多意图在机制上只可能产生一次查询，
        // 旧机制下这里要靠「查询身份去重」兜住，否则同一 chunk 占两个名次被 RRF 双计
        RetrievalScope scope = new RetrievalScope(true, 0.9,
                List.of(intent("报销", "kb-finance"), intent("发票", "kb-finance")),
                List.of("kb-finance"), SUPPLEMENT);

        List<RetrievedChunk> chunks = search(scope, PRODUCTION_BUDGET).getChunks();

        assertEquals(1, captureRequests().stream()
                .filter(request -> request.getEffectiveCollectionNames().equals(List.of("kb-finance")))
                .count());
        assertEquals(15, count(chunks, "dir"));
        assertEquals(5, count(chunks, "sup"));
    }

    @Test
    @DisplayName("意图库范围部分重叠时通道出口无重复名次")
    void overlappingIntentCollectionsProduceNoDuplicateRanks() {
        // 两个节点范围部分重叠（[财务,制度] 与 [财务]），主路对命中库并集取数、每库只查一次，
        // 旧机制按意图各查一次会让交叠 chunk 占两个名次，须在出口 distinct 兜底——该风险已随机制消失
        RetrievalScope scope = new RetrievalScope(true, 0.9,
                List.of(intent("综合", "kb-finance", "kb-policy"), intent("报销", "kb-finance")),
                List.of("kb-finance", "kb-policy"), List.of());

        List<RetrievedChunk> chunks = search(scope, PRODUCTION_BUDGET).getChunks();

        assertEquals(1, captureRequests().size(), "并集单查，范围重叠不再产生重复查询");
        assertEquals(chunks.stream().map(RetrievedChunk::getId).distinct().count(), chunks.size(),
                "同一 chunk 占两个名次会被下游 RRF 按名次累加成双倍分");
        assertEquals(20, chunks.size(), "命中库覆盖全部有效库时无补充，主路拿满通道额度");
    }

    @Test
    @DisplayName("后端不支持跨库单查时补充路名额仍被兑现")
    void fanOutFallbackStillHonoursSupplementQuota() {
        // 回归：fan-out 兜底下预算是每库上限，不截断则 2 个补充库把 5 个名额撑成 10 条；
        // 两个现有后端都支持跨库单查，故这条今天不触发，但接口默认值是 false，新接入后端不覆写就会踩中
        when(retrieverService.supportsGlobalRetrieval()).thenReturn(false);

        List<RetrievedChunk> chunks = search(directedScope(), PRODUCTION_BUDGET).getChunks();

        assertEquals(5, count(chunks, "sup"), "补充路名额是总量，不随补充库数放大");
        assertEquals(15, count(chunks, "dir"));
    }

    @Test
    @DisplayName("补充路失败时定向证据仍然产出")
    void supplementFailureDoesNotDropDirectedChunks() {
        // 回归：补充路拿的是兜底名额，它抛出却会让已取回的定向证据被通道级 catch 一起丢掉，
        // 等于兜底路把主路带走——鲁棒性方向正好反了
        when(retrieverService.retrieveByVector(any(float[].class), any(RetrieveRequest.class)))
                .thenAnswer(invocation -> {
                    RetrieveRequest request = invocation.getArgument(1);
                    if (request.getEffectiveCollectionNames().equals(SUPPLEMENT)) {
                        throw new IllegalStateException("向量库抖动");
                    }
                    return roundRobin(request.getEffectiveCollectionNames(), request.getTopK());
                });

        List<RetrievedChunk> chunks = search(directedScope(), PRODUCTION_BUDGET).getChunks();

        assertEquals(15, count(chunks, "dir"), "补充路故障只该损失补充证据");
        assertEquals(0, count(chunks, "sup"));
    }

    @Test
    @DisplayName("后端返回乱序时通道出口仍按相关性降序")
    void backendOutOfOrderIsResortedAtChannelExit() {
        // 回归：PG 开了 hnsw.iterative_scan=relaxed_order，pgvector 在该模式下允许轻微乱序且规划器不补 Sort，
        // 全局路曾原样返回后端列表，是唯一一条不重排的通道出口
        when(retrieverService.retrieveByVector(any(float[].class), any(RetrieveRequest.class)))
                .thenReturn(List.of(chunk("a", 0.5F), chunk("b", 0.9F), chunk("c", 0.7F)));

        List<RetrievedChunk> chunks = search(
                RetrievalScope.global(0.3, List.of("kb-finance")), PRODUCTION_BUDGET).getChunks();

        assertEquals(List.of("b", "c", "a"), chunks.stream().map(RetrievedChunk::getId).toList(),
                "下游 RRF 按列表位次取分，出口乱序等于名次基准失真");
    }

    /**
     * 断言给定意图数与候选池上限下，主路 / 补充路各自的实际产出条数
     */
    private void assertSupplementShare(int intentCount, int candidateLimit, int expectedPrimary, int expectedSupplement) {
        setUp();
        List<NodeScore> intents = IntStream.range(0, intentCount)
                .mapToObj(index -> intent("kb-" + index))
                .toList();
        List<String> targets = intents.stream()
                .map(nodeScore -> nodeScore.getNode().getCollectionNames().get(0))
                .toList();
        RetrievalScope scope = new RetrievalScope(true, 0.9, intents, targets, SUPPLEMENT);

        List<RetrievedChunk> chunks = search(scope, new RetrievalBudget(20, candidateLimit, 10)).getChunks();

        String label = String.format("意图数=%d，候选池上限=%d", intentCount, candidateLimit);
        assertEquals(expectedPrimary, count(chunks, "dir"), label + " 的主路条数");
        assertEquals(expectedSupplement, count(chunks, "sup"), label + " 的补充条数");
    }

    private static long count(List<RetrievedChunk> chunks, String prefix) {
        return chunks.stream().filter(chunk -> chunk.getId().startsWith(prefix)).count();
    }

    private SearchChannelResult search(RetrievalScope scope, RetrievalBudget budget) {
        SearchContext context = SearchContext.builder()
                .originalQuestion(QUESTION)
                .budget(budget)
                .retrievalScope(scope)
                .build();
        return new VectorSearchChannel(retrieverService, properties, Runnable::run).search(context);
    }

    private List<RetrieveRequest> captureRequests() {
        ArgumentCaptor<RetrieveRequest> captor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrieverService, atLeastOnce()).retrieveByVector(any(float[].class), captor.capture());
        return captor.getAllValues();
    }

    private static RetrievalScope directedScope() {
        return new RetrievalScope(true, 0.9, List.of(intent("kb-finance")), List.of("kb-finance"), SUPPLEMENT);
    }

    private static NodeScore intent(String collection) {
        return intent(collection, collection);
    }

    /**
     * 意图 id 与库名解耦：真实配置里两个不同意图完全可能挂同一个库，或范围部分重叠
     */
    private static NodeScore intent(String id, String... collections) {
        return NodeScore.builder()
                .node(IntentNode.builder()
                        .id(id)
                        .name(id)
                        .collectionNames(List.of(collections))
                        .build())
                .score(0.9)
                .build();
    }

    private static RetrievedChunk chunk(String id, float score) {
        return RetrievedChunk.builder().id(id).text(id).score(score).build();
    }

    /**
     * 按库轮转填满 topK
     * <p>
     * chunk 的 id 与分数只能由所属库决定、不能由查询范围决定：范围重叠的两次查询本就该返回交叠的 chunk，
     * 逐库 fan-out 与单次跨库查询也该返回同一批 chunk。桩若按「这次是不是补充路」派生 id，
     * 「范围重叠占两个名次」「fan-out 下名额被放大」这两类缺陷在用例里就永远看不见
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
                            .score((supplement ? 0.5F : 0.9F) - index * 0.001F)
                            .build();
                })
                .toList();
    }
}
