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

package com.nageoffer.ai.ragent.rag.Intent;

import com.nageoffer.ai.ragent.rag.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.intent.DefaultIntentClassifier;
import com.nageoffer.ai.ragent.rag.intent.NodeScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@Slf4j
@SpringBootTest
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class DefaultIntentClassifierTests {

    private final DefaultIntentClassifier defaultIntentClassifier;

    /**
     * 低于这个就认为“不太像”，可以不走向量检索 / RAG
     */
    private static final double MIN_SCORE = 0.35;

    /**
     * 打印时最多展示的候选数量
     */
    private static final int TOP_N = 5;

    /**
     * 场景 1：考勤 + 处罚 混合语义
     * 示例：早上九点十分打卡，有什么处罚？
     */
    @Test
    public void classifyAttendancePunishment() {
        String question = "早上九点十分打卡，有什么处罚？";
        runCase(question);
    }

    /**
     * 场景 2：典型 IT 支持问题
     * 示例：Mac电脑打印机怎么连？
     */
    @Test
    public void classifyItSupportQuestion() {
        String question = "Mac电脑打印机怎么连？";
        runCase(question);
    }

    /**
     * 场景 3：中间件环境信息（Redis）
     * 示例：测试环境 Redis 地址是多少？
     */
    @Test
    public void classifyMiddlewareRedisQuestion() {
        String question = "测试环境 Redis 地址是多少？";
        runCase(question);
    }

    /**
     * 场景 4：业务系统（OA），功能 + 安全
     * 示例：OA系统主要提供哪些功能？数据安全怎么做的？
     * 期望：高分主要落在 biz-oa-intro / biz-oa-security 上，
     * 不要把 biz-ins-*（保险系统）拉进高分区间
     */
    @Test
    public void classifyBizSystemQuestion() {
        String question = "OA 系统主要提供哪些功能？测试环境 Redis 地址是多少？数据安全怎么做的？";
        runCase(question);
    }

    /**
     * 场景 5：刻意搞一个“非常泛”的问题，看是否整体得分偏低
     */
    @Test
    public void classifyUncorrelatedQuestion() {
        String question = "公司团建一般怎么安排？";
        runCase(question);
    }

    /**
     * 场景 6：刻意搞一个“泛”的问题，看是否能全部收集
     */
    @Test
    public void classifyMultiQuestion() {
        String question = "数据安全怎么做的？";
        runCase(question);
    }

    /**
     * 场景 7：刻意搞一个不相关的问题，看分类场景
     */
    @Test
    public void classifyGeneralQuestion() {
        String question = "阿巴阿巴";
        runCase(question);
    }

    /**
     * 场景 8：咨询Chat场景
     */
    @Test
    public void classifyHelloQuestion() {
        // String question = "Hello";
        // String question = "你是谁？";
        // String question = "你是ChatGPT么？";
        String question = "你底层用的什么模型？";
        runCase(question);
    }

    // ======================== 工具方法 ========================

    private void runCase(String question) {
        long start = System.nanoTime();
        // 一次性调用：内部已经做了“按分数排序 + 过滤 + 截断 TopN”
        List<NodeScore> topKScores = defaultIntentClassifier.topKAboveThreshold(
                question, TOP_N, MIN_SCORE
        );
        long end = System.nanoTime();

        double totalMs = (end - start) / 1_000_000.0;

        // 这里的 maxScore 是“本次返回的候选里的最高分”
        double maxScore = topKScores.stream()
                .mapToDouble(NodeScore::getScore)
                .max()
                .orElse(0.0);

        // 是否需要走 RAG：当前实现等价于 !topKScores.isEmpty()
        boolean needRag = maxScore >= MIN_SCORE;

        System.out.println("==================================================");
        System.out.println("[LlmTreeIntentClassifier] Question: " + question);
        System.out.println("--------------------------------------------------");
        System.out.println("MIN_SCORE : " + MIN_SCORE);
        System.out.println("TOP_N     : " + TOP_N);
        System.out.println("MaxScore  : " + maxScore);
        System.out.println("Need RAG  : " + needRag);
        System.out.println();

        System.out.println(">> Candidates after filter (score >= " + MIN_SCORE + "):");
        if (topKScores.isEmpty()) {
            System.out.println("  (none, 可以考虑不走 RAG 或走 fallback)");
        } else {
            topKScores.forEach(ns -> {
                IntentNode n = ns.getNode();
                System.out.printf("  - %.4f  |  %s  (id=%s)%n",
                        ns.getScore(),
                        safeFullPath(n),
                        n.getId());
            });
        }

        System.out.println();
        System.out.println("---- Perf ----");
        System.out.printf("Total cost: %.2f ms%n", totalMs);
        System.out.println("==================================================\n");
    }

    private String safeFullPath(IntentNode node) {
        if (node == null) return "null";
        return node.getFullPath() != null ? node.getFullPath() : node.getName();
    }
}
