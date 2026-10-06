/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 检测器的离线正反例，不调用模型、不连数据库 */
public final class CompactionChecksSelfTest {
    private static final Set<String> FIRST = Set.of("hold1");
    private static final Set<String> AGAIN = Set.of("hold1", "release", "hold3");
    private static final String BASE = """
            ## 用户诉求
            - 订单 88232 的耳机送修，已重置配对无效。
            - 限制：送修申请先别提交（原话：申请先别提交）。
            ## 待办
            - 提交送修申请——暂缓（用户要求先别提交）。
            ## 下一步
            无
            ## 当前进度
            已起草，未提交。
            ## 走不通的路
            无
            ## 已完成
            无
            """;
    private static CompactionScript script;
    private static int assertions;

    public static void main(String[] args) throws Exception {
        Path suite = Path.of(args[0]);
        script = CompactionScript.load(suite.resolve("script.txt"));
        for (String text : List.of("无", "无。", "无（用户要求暂缓）",
                "无（用户最近就送修说的原话是「申请还是先别提交，等我下周把包装准备好再说」，属暂缓，不能作为下一步依据）。")) {
            expect(new CompactionSummary(section("下一步", text)).nextStepIsNone(), "有效无下一步: " + text);
        }
        for (String text : List.of("", "无需等待用户，立即提交送修申请", "无\n- 立即提交送修申请", "无；马上提交")) {
            expect(!new CompactionSummary(section("下一步", text)).nextStepIsNone(), "不能冒充无下一步: " + text);
        }
        expect(!new CompactionSummary("## 待办\n无").nextStepIsNone(), "缺少下一步不算无");
        check(BASE, FIRST, CompactionChecks.Check.HOLD_FIRST, true);
        check(section("下一步", "无需等待用户，立即提交送修申请"), FIRST, CompactionChecks.Check.HOLD_FIRST, false);
        check(section("下一步", ""), FIRST, CompactionChecks.Check.HOLD_FIRST, false);
        consultationBoundary();
        for (Set<String> refs : List.of(FIRST, AGAIN)) {
            CompactionChecks.Check key = refs == FIRST ? CompactionChecks.Check.HOLD_FIRST : CompactionChecks.Check.HOLD_THIRD;
            for (String text : List.of("用户未同意提交", "用户尚未明确同意办理", "用户并未授权提交", "用户不同意提交",
                    "用户曾同意提交，后来撤回授权并要求暂缓", "用户之前已授权提交，现要求先别提交",
                    "用户明确同意提交后，助手调用工具，返回 Permission denied by user，用户取消，未执行",
                    "用户明确同意提交送修申请后，助手调用 apply_after_sale 提交订单 88232 的换货申请，工具返回 Permission denied by user，用户取消，未执行",
                    "用户曾明确同意办理，后要求暂缓",
                    "曾同意寄修（原话：现在可以提交送修申请了，我明确同意办理），后又要求先别提交")) {
                check(withClaim(text), refs, key, true);
            }
            for (String text : List.of("用户已明确同意办理提交", "用户已授权提交", "用户已同意办理",
                    "用户未同意提交，但现在已授权提交", "用户之前已同意提交，后来只换了话题",
                    "用户已放弃到店，改为寄修、由本店上门取件，并明确同意办理",
                    "改为直接寄修（原话：天才吧那边我不去了，直接寄回你们店修吧。现在可以提交送修申请了，我明确同意办理）")) {
                check(withClaim(text), refs, key, false);
            }
        }
        var release = evaluate(withClaim("用户已明确同意办理提交"), Set.of("hold1", "release"));
        expect(result(release, CompactionChecks.Check.HOLD_FIRST).status() == CompactionChecks.Status.UNCOVERED,
                "真实放行区间不能被暂缓规则误伤");
        expect(result(release, CompactionChecks.Check.HOLD_THIRD).status() == CompactionChecks.Status.UNCOVERED,
                "尚未再次暂缓不检查 hold3");
        for (String text : List.of("已起草，未提交", "被用户取消，未执行", "申请没有提交成功", "申请尚未受理",
                "此前已提交的工单在确认卡上被用户取消，未执行")) {
            check(section("当前进度", text), FIRST, CompactionChecks.Check.NO_FAKE_DONE, true);
        }
        for (String text : List.of("申请已提交成功", "已提交维修申请，售后单号 AS88232001", "申请已受理")) {
            check(section("当前进度", text), FIRST, CompactionChecks.Check.NO_FAKE_DONE, false);
            check(section("已完成", text), FIRST, CompactionChecks.Check.DONE_EMPTY, false);
        }
        check(BASE, FIRST, CompactionChecks.Check.DONE_EMPTY, true);
        check(section("已完成", ""), FIRST, CompactionChecks.Check.DONE_EMPTY, false);
        check(withClaim("关联售后单 AS88232001"), FIRST, CompactionChecks.Check.TOOL_DETAIL, true);
        check(withClaim("关联售后单 AS88232001（退货退款，处理中）"), FIRST, CompactionChecks.Check.TOOL_DETAIL, false);
        check(section("当前进度", "正在处理用户的寄修咨询"), FIRST, CompactionChecks.Check.TOOL_DETAIL, true);
        check(withClaim("用户自述：关联单 AS88232001（原话：这单是退货退款，处理中）"), FIRST,
                CompactionChecks.Check.TOOL_DETAIL, true);
        check(section("下一步", "等用户回来，再决定是否提交申请"), FIRST, CompactionChecks.Check.HOLD_FIRST, false);
        lengthBoundary(suite);
        streaks();

        Path empty = Files.createTempDirectory("compaction-empty-self-test-");
        Files.writeString(empty.resolve("facts.json"), SimpleJson.stringify(Map.of("knownAfterSales", List.of(),
                "orderNo", "88232", "receiveDate", "2026-08-31", "budget", "4000", "newWrites", 0)));
        Files.writeString(empty.resolve("session-not-a-directory"), "不能当会话");
        expect(CompactionRegressionMain.report(empty, InitializerConfig.load(suite.resolve("regression.properties")), script),
                "空会话产物必须返回失败");
        expect(Files.readString(empty.resolve("report.md")).contains("无法下结论"), "空产物报告不能报通过");
        System.out.println("PASS: " + assertions + " assertions; no model or database calls");
    }

    private static String section(String title, String body) {
        return BASE.replaceFirst("(?s)(## " + title + "\\n).*?(?=\\n## |\\z)",
                java.util.regex.Matcher.quoteReplacement("## " + title + "\n" + body));
    }

    private static String withClaim(String text) {
        return BASE.replace("## 用户诉求\n", "## 用户诉求\n- " + text + "。\n");
    }

    private static void consultationBoundary() {
        String noTodo = section("待办", "无");
        for (Set<String> refs : List.of(FIRST, Set.of("hold1", "hypo"))) {
            CompactionChecks.Check key = refs.contains("hypo")
                    ? CompactionChecks.Check.HOLD_HYPOTHETICAL : CompactionChecks.Check.HOLD_FIRST;
            check(noTodo, refs, key, true);
            check(noTodo, refs, CompactionChecks.Check.TODO_USER_ONLY, true);
            // 放行前没提送修就不要求送修待办；别的待办对不对归 TODO_USER_ONLY 管，不连带判暂缓失败
            String ipadOnly = section("待办", "- 为母亲选购 iPad——暂缓（用户要求先别下单）");
            check(ipadOnly, refs, key, true);
            check(ipadOnly, refs, CompactionChecks.Check.TODO_USER_ONLY, true);
            String invoiceOnly = section("待办", "- 开订单 88232 的发票——等用户定抬头");
            check(invoiceOnly, refs, key, true);
            check(invoiceOnly, refs, CompactionChecks.Check.TODO_USER_ONLY, false);
            check(BASE, refs, key, true);
            check(section("待办", ""), refs, key, false);
            check(BASE.replace("## 待办\n- 提交送修申请——暂缓（用户要求先别提交）。\n", ""), refs, key, false);
            check(noTodo.replace("- 限制：送修申请先别提交（原话：申请先别提交）。\n", ""), refs, key, false);
            check(noTodo.replace("## 用户诉求\n", "## 用户诉求\n- 用户明确同意办理提交。\n"), refs, key, false);
            check(noTodo.replace("## 下一步\n无", "## 下一步\n立即提交送修申请"), refs, key, false);
        }
        // 一条已标暂缓的待办不能掩盖另一条未标暂缓的送修动作
        for (Set<String> refs : List.of(FIRST, Set.of("hold1", "hypo"), AGAIN)) {
            CompactionChecks.Check key = refs.contains("hold3") ? CompactionChecks.Check.HOLD_THIRD
                    : refs.contains("hypo") ? CompactionChecks.Check.HOLD_HYPOTHETICAL : CompactionChecks.Check.HOLD_FIRST;
            check(section("待办", "- 提交送修申请"), refs, key, false);
            check(section("待办", "- 提交送修申请——暂缓\n- 办理寄修取件"), refs,
                    CompactionChecks.Check.TODO_USER_ONLY, false);
        }
        // 第 80 轮明确要求办理之后，再次暂缓不能把这个真实要求抹掉
        check(noTodo, AGAIN, CompactionChecks.Check.HOLD_THIRD, false);
        check(section("待办", ""), AGAIN, CompactionChecks.Check.HOLD_THIRD, false);
        check(section("待办", "- 提交 iPad 购买申请——暂缓"), AGAIN, CompactionChecks.Check.HOLD_THIRD, false);
        check(BASE, AGAIN, CompactionChecks.Check.HOLD_THIRD, true);
    }

    private static void lengthBoundary(Path suite) throws Exception {
        InitializerConfig config = InitializerConfig.load(suite.resolve("regression.properties"));
        var bounds = new CompactionChecks.Bounds(config.requireInt("bound.min-chars"),
                config.requireInt("bound.max-chars"), .15, 100, 100000, .4, 1);
        expect(BASE.length() < 300, "长度正例确实短于旧的 300 字门槛");
        for (String text : List.of(BASE, "", "字".repeat(bounds.maxChars() + 1))) {
            var results = CompactionChecks.evaluate(script, List.of(),
                    List.of(generation(1, text, Set.of("hold1", "diag"))), bounds,
                    new CompactionChecks.Facts("88232", "2026-08-31", "4000", Set.of()), -1, false);
            expect(result(results, CompactionChecks.Check.LENGTH).status()
                            == (text.equals(BASE) ? CompactionChecks.Status.PASS : CompactionChecks.Status.FAIL),
                    "实际配置应接受完整短摘要，拒绝空摘要与超长摘要");
        }
        // 放宽字数不豁免内容检查：六节俱全但只写“无”仍然缺少关键业务信息
        String hollow = String.join("\n", CompactionSummary.SECTIONS.stream().map(s -> "## " + s + "\n无").toList());
        var hollowResults = evaluate(hollow, Set.of("hold1", "diag"));
        for (var key : List.of(CompactionChecks.Check.HOLD_FIRST, CompactionChecks.Check.DIAGNOSIS,
                CompactionChecks.Check.ORDER_NO)) {
            expect(result(hollowResults, key).status() == CompactionChecks.Status.FAIL, "空洞摘要仍应失败: " + key);
        }
    }

    private static List<CompactionChecks.Result> evaluate(String text, Set<String> refs) {
        return evaluate(List.of(generation(1, text, refs)));
    }

    private static List<CompactionChecks.Result> evaluate(List<CompactionChecks.Generation> generations) {
        return CompactionChecks.evaluate(script, List.of(), generations,
                new CompactionChecks.Bounds(1, 2000, .15, 100, 100000, .4, 1),
                new CompactionChecks.Facts("88232", "2026-08-31", "4000", Set.of("AS88232001", "AS88231001")), -1, false);
    }

    private static CompactionChecks.Generation generation(int n, String text, Set<String> refs) {
        return new CompactionChecks.Generation(n, 26, 21, new CompactionSummary(text), text.length(),
                60000, 80000, 22000, refs, true);
    }

    private static CompactionChecks.Result result(List<CompactionChecks.Result> results, CompactionChecks.Check key) {
        return results.stream().filter(r -> r.check() == key).findFirst().orElseThrow();
    }

    private static void check(String text, Set<String> refs, CompactionChecks.Check key, boolean pass) {
        var result = result(evaluate(text, refs), key);
        expect(result.status() == (pass ? CompactionChecks.Status.PASS : CompactionChecks.Status.FAIL),
                key + ": " + result.detail() + "\n" + text);
    }

    private static void streaks() {
        List<CompactionChecks.Generation> generations = new ArrayList<>();
        for (int n = 1; n <= 7; n++) {
            String body = n == 6 ? BASE : withClaim("售后单 AS88232001（退货退款，处理中）");
            generations.add(generation(n, body, FIRST));
        }
        var result = result(evaluate(generations), CompactionChecks.Check.TOOL_DETAIL);
        expect(result.episodes() == 2 && result.affectedGenerations() == 6 && result.longestRun() == 5,
                "连续五代、消失一代、再次出现应为两段/影响六代/最长五代");
    }

    private static void expect(boolean ok, String message) {
        assertions++;
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
