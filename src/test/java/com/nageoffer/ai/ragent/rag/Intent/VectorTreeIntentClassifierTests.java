package com.nageoffer.ai.ragent.rag.Intent;

import com.nageoffer.ai.ragent.service.rag.intent.IntentNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@Slf4j
@SpringBootTest
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class VectorTreeIntentClassifierTests {

    private final VectorIntentClassifier intentClassifier;

    /**
     * 场景 1：考勤 + 处罚 混合语义
     * 期望：能命中某个人事实体节点（比如“人事-考勤制度”之类的叶子节点）。
     */
    @Test
    public void classifyAttendancePunishment() {
        String question = "早上九点十分打卡，有什么处罚？";
        runCase(question);
    }

    /**
     * 场景 2：典型 IT 支持问题
     */
    @Test
    public void classifyItSupportQuestion() {
        String question = "Mac电脑打印机怎么连？";
        runCase(question);
    }

    /**
     * 场景 3：中间件环境信息（Redis）
     */
    @Test
    public void classifyMiddlewareRedisQuestion() {
        String question = "测试环境 Redis 地址是多少？";
        runCase(question);
    }

    /**
     * 场景 4：业务系统（OA）
     */
    @Test
    public void classifyBizSystemQuestion() {
        String question = "OA系统主要提供哪些功能？";
        runCase(question);
    }

    // ======================== 工具方法 ========================

    private void runCase(String question) {
        // 你可以根据实际情况调这两个参数
        double MIN_SCORE = 0.35; // 低于这个就认为“不太像”，可以不检索
        int TOP_N = 5;           // 最多只看前 5 个候选

        long start = System.nanoTime();
        List<VectorIntentClassifier.NodeScore> allScores = intentClassifier.classifyTargets(question);
        long end = System.nanoTime();

        double totalMs = (end - start) / 1_000_000.0;
        double maxScore = allScores.isEmpty() ? 0.0 : allScores.get(0).score();

        System.out.println("==================================================");
        System.out.println("[TreeIntentClassifier] Question: " + question);
        System.out.println("--------------------------------------------------");
        System.out.println("MaxScore : " + maxScore);
        System.out.println("Need RAG : " + (maxScore >= MIN_SCORE));
        System.out.println("Top " + TOP_N + " targets (score >= " + MIN_SCORE + "):");

        allScores.stream()
                .filter(ns -> ns.score() >= MIN_SCORE)
                .limit(TOP_N)
                .forEach(ns -> {
                    IntentNode n = ns.node();
                    System.out.printf("  - %.4f  |  %s  (id=%s)%n",
                            ns.score(),
                            safeFullPath(n),
                            n.getId());
                });

        if (allScores.stream().noneMatch(ns -> ns.score() >= MIN_SCORE)) {
            System.out.println("  (no target above threshold, 可以考虑不走向量检索或走 fallback)");
        }

        System.out.println("---- Perf ----");
        System.out.println("Total cost: " + totalMs + " ms");
        System.out.println("==================================================\n");
    }

    private String safeFullPath(IntentNode node) {
        if (node == null) return "null";
        return node.getFullPath() != null ? node.getFullPath() : node.getName();
    }
}

