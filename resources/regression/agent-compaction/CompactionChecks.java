/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆摘要的判定规则：只看库里每一代摘要正文和每轮弹没弹卡，不问模型「你记得吗」
 * 业务规则只在对应的原话已经被压出原文之后才生效，还在原文里的事摘要可以不写
 */
final class CompactionChecks {

    enum Status { PASS, WARN, FAIL, UNCOVERED }

    /**
     * hard 失败判 FAIL、soft 失败只记 WARN；core 一次都没观测到时整份结论下不了
     */
    enum Check {
        STRUCTURE("结构：六节齐全且有序", true, false),
        LENGTH("长度：每代落在区间内", true, true),
        STEADY("长度：稳态期相邻两代不涨", true, true),
        CONTEXT("上下文：压缩后回落", true, true),
        GENERATIONS("代数：压缩代数够下结论", true, true),
        READ_ONLY("只读不记：无价格、库存、知识库", true, false),
        TOOL_DETAIL("工具详情不抄：只留单号与型号", true, false),
        DEAD_END_NO_QUERY("走不通的路不收查询绕路", false, false),
        TODO_USER_ONLY("待办只收用户要求办的事", true, false),
        NO_FAKE_DONE("不虚构已办成", true, false),
        DONE_EMPTY("全拒卡剧本：已完成必须为空", true, false),
        ORDER_NO("订单：单号照抄", true, true),
        DIAGNOSIS("排查：试过的步骤带到下一代", true, false),
        HOLD_FIRST("暂缓：先别提交，到明说放行为止", true, true),
        HOLD_HYPOTHETICAL("暂缓：假设句不算放行", true, false),
        HOLD_THIRD("暂缓：放行后又说先别提交", true, true),
        HOLD_LATEST_QUOTE("暂缓：附的是最新那句原话", false, false),
        MAX_CLOSED("放弃：AirPods Max 不复活", true, true),
        IPAD_CONDITIONS("条件：iPad 预算与颜色照抄", true, true),
        IPAD_HOLD("暂缓：iPad 先别下单", true, true),
        IPAD_CLOSED("放弃：iPad 了结后不复活", true, true),
        A4_CLOSED("放弃：AirPods 4 了结后不复活", true, false),
        CLOSED_TRACE("放弃：了结的事在用户诉求里留痕", false, false),
        CLOSED_RESIDUE("放弃：了结的事在进度、绕路、已完成里不留残条", false, false),
        CLAIM_SOURCE("来源：用户声称的单号不当事实", true, false),
        RELEASE_CANCELLED("改口：放行被拒卡后记了取消", false, false),
        STALE_PLAN("改口：作废的计划不留在待办", false, false),
        ONE_NEXT_STEP("格式：下一步只写一条", false, false),
        THIRD_PERSON("格式：自述部分用第三人称", false, false),
        QUOTE_ONCE("格式：同一句原话只出现一次", false, false),
        LINE_CAPS("格式：各节行数不超上限", false, false),
        NO_CARD_ON_HOLD("主 Agent：暂缓期间不弹办事卡", true, true),
        CARD_ON_RELEASE("主 Agent：明说放行后弹卡", false, false),
        TURN_OK("运行：每轮正常结束", true, false),
        NO_WRITE("运行：业务库没有新增工单与售后单", true, false);

        final String label;
        final boolean hard;
        final boolean core;

        Check(String label, boolean hard, boolean core) {
            this.label = label;
            this.hard = hard;
            this.core = core;
        }
    }

    /**
     * turn 是观测到这一代时剧本跑到的轮次，compactedThrough 是被压出原文的最后一轮
     */
    record Generation(int number, int turn, int compactedThrough, CompactionSummary summary, int summaryChars,
                      int materialChars, int before, int after, Set<String> compactedRefs, boolean orderQueried) {

        boolean compacted(String ref) {
            return compactedRefs.contains(ref);
        }
    }

    /**
     * results 每条是「工具名\n结果」，用来判断某个事实到底有没有被工具查出来过
     */
    record TurnRecord(int index, String ref, List<String> tools, List<String> results, List<String> cardTools,
                      List<String> errors, String status) {
    }

    record Bounds(int minChars, int maxChars, double steadyRatio, int steadySlack, int budget,
                  double afterRatio, int minGenerations) {
    }

    /**
     * knownAfterSales 是开跑前就在库里的售后单号，摘要照抄它们是查询结果，不算虚构
     */
    record Facts(String orderNo, String receiveDate, String budget, Set<String> knownAfterSales) {
    }

    record Result(Check check, Status status, int observed, String detail, int failures,
                  int episodes, int affectedGenerations, int longestRun) {
    }

    // 与 script.txt 的 [claim] 一轮同源：用户自己说的单号，库里并没有
    private static final String CLAIMED_NO = "R-2026-777";
    // 与 script.txt 的 [release] 一轮同源：再次暂缓后还附着这句原话，接手的助手读到的仍是用户同意办理
    private static final String RELEASE_QUOTE = "我明确同意办理";
    // 与 initializer/bit-selection/biz-data/05-after-sale.sql 同源；只供这套固定剧本判定
    private static final Map<String, List<String>> OLD_AFTER_SALE_DETAILS = Map.of(
            "AS88232001", List.of("退货退款", "处理中"),
            "AS88231001", List.of("换货", "已完成"));

    private static final Pattern READ_ONLY = Pattern.compile(
            "(\\d[\\d,，.]*)\\s*元|库存|有货|缺货|现货|资料未|资料里没有|文档称|文档里|文档中");
    // 走不通的路可以写「知识库办不了，只能走人工」这类通道结论，知识库三个字只在别的节里算查询结果
    private static final Pattern KNOWLEDGE = Pattern.compile("知识库");
    // 换了关键词或参数才查到是查询过程，接手时重查即可
    private static final Pattern QUERY_DETOUR = Pattern.compile("检索|关键词|命中|改用.{0,12}才");
    private static final Pattern AFTER_SALE_NO = Pattern.compile("AS\\d{8,}");
    // 订单详情与物流都要接手时拿单号重查，签收日期另按库里的真值查
    private static final Pattern TOOL_DETAIL = Pattern.compile("运单|轨迹|承运|保修截止|保修至|保修到|截止日");
    private static final Pattern DIAG_LINE = Pattern.compile("重置|配对|排查");
    private static final Pattern DONE_CLAIM = Pattern.compile("已(?:经)?提交(?:成功)?|提交成功|已(?:经)?受理|办理成功");
    private static final Pattern TICKET_NO = Pattern.compile("TK20\\d{6}");
    private static final Pattern HOLD_WORD = Pattern.compile("暂缓|先别");
    private static final Pattern REPAIR = Pattern.compile("送修|维修|寄修|申请|提交");
    private static final Pattern ACTION_WORD =Pattern.compile("提交|办理|寄出|送修申请");
    private static final Pattern RELEASE_CLAIM = Pattern.compile(
            "(?:已(?:经)?(?:明确)?|明确)?同意(?:办理)?提交|(?:已(?:经)?)?(?:明确)?同意办理"
                    + "|(?:已(?:经)?(?:明确)?)?授权(?:办理|提交)|已(?:经)?授权|允许提交"
                    + "|(?:已撤销|撤销了)(?:暂缓|限制)|(?:暂缓|限制)(?:已撤销|撤销了)");
    private static final Pattern NEGATED = Pattern.compile("(?:未|没|没有|尚未|并未|不|不能|并非|并不|未曾)(?:明确|再次|正式|实际|获得|得到|被|要求|直接|立即|现在|已经|已)*$");
    private static final Pattern HISTORICAL = Pattern.compile("曾(?:经)?|此前|之前|原先");
    private static final Pattern WITHDRAWN = Pattern.compile("(?:后|现|又|但|已改).*(?:暂缓|先别提交|撤回|撤销|取消授权)");
    // 查没查到是工具结果，按「编号留、内容不抄」不写；署名给用户或客服、或写明待核实，就没当成事实
    private static final Pattern CLAIM_QUALIFIER = Pattern.compile(
            "自述|声称|称|回忆|提到|记为|记成|记错|记岔|误记|客服给|客服说|电话|核实|是否"
                    + "|未查到|查不到|查无|没有|不存在|未找到|无记录|未确认|无此");
    private static final Pattern ABANDONED = Pattern.compile("放弃|不考虑|算了|先不要|不买");
    private static final Pattern PERSON = Pattern.compile("[我你]");

    private static final Pattern EARBUD = Pattern.compile("AirPods|耳机|左耳|右耳|试听");

    private static final Predicate<String> MAX = line -> line.contains("Max");
    // 给妈妈买的 iPad 才是被测的购买事项，用户拿自己的 iPad 试左耳那行不算
    private static final Predicate<String> IPAD = line -> line.contains("iPad") && !EARBUD.matcher(line).find();
    private static final Predicate<String> A4 = line -> line.contains("AirPods 4");

    // 离线重放时对话是冻结的，弹没弹卡、每轮是否正常结束、业务库写没写都是实跑那次的事，不归被测提示词
    private static final Set<Check> LIVE_ONLY =
            EnumSet.of(Check.NO_CARD_ON_HOLD, Check.CARD_ON_RELEASE, Check.TURN_OK, Check.NO_WRITE);

    private final Map<Check, Tally> tallies = new EnumMap<>(Check.class);
    private int generation;

    private CompactionChecks(boolean live) {
        for (Check check : Check.values()) {
            if (live || !LIVE_ONLY.contains(check)) {
                tallies.put(check, new Tally());
            }
        }
    }

    /**
     * newWrites 小于 0 表示没观测（重判旧产物时库已经变过），这条结论记 UNCOVERED
     * live 为 false 是离线重放：只判摘要，主 Agent 行为与业务库那几条不出结论
     */
    static List<Result> evaluate(CompactionScript script, List<TurnRecord> turns, List<Generation> gens,
                                 Bounds bounds, Facts facts, int newWrites, boolean live) {
        CompactionChecks checks = new CompactionChecks(live);
        boolean releaseCarded = turns.stream()
                .anyMatch(turn -> turn.index() == script.index("release") && !turn.cardTools().isEmpty());
        for (int position = 0; position < gens.size(); position++) {
            checks.generation = gens.get(position).number();
            Generation previous = position == 0 ? null : gens.get(position - 1);
            checks.generic(gens.get(position), bounds, facts);
            checks.business(gens.get(position), previous, facts, releaseCarded);
            if (previous != null && previous.compacted("hold3")) {
                checks.steady(previous, gens.get(position), bounds);
            }
        }
        checks.generation = 0;
        checks.record(Check.GENERATIONS, gens.size() >= bounds.minGenerations(),
                "共 " + gens.size() + " 代，至少要 " + bounds.minGenerations() + " 代");
        if (!live) {
            return checks.results();
        }
        checks.behavior(script, turns);
        if (newWrites >= 0) {
            checks.record(Check.NO_WRITE, newWrites == 0, "开跑后业务库多了 " + newWrites + " 条工单或售后单");
        }
        return checks.results();
    }

    private void generic(Generation gen, Bounds bounds, Facts facts) {
        CompactionSummary summary = gen.summary();
        String tag = "第 " + gen.number() + " 代：";
        String plain = CompactionSummary.unquoted(summary.text());
        record(Check.STRUCTURE, summary.structured(), tag + "小节 " + summary.headings());
        record(Check.LENGTH, gen.summaryChars() >= bounds.minChars() && gen.summaryChars() <= bounds.maxChars(),
                tag + gen.summaryChars() + " 字，区间 [" + bounds.minChars() + ", " + bounds.maxChars() + "]");
        int afterCap = (int) (bounds.budget() * bounds.afterRatio());
        record(Check.CONTEXT, gen.before() <= bounds.budget() && gen.after() <= afterCap,
                tag + "压缩前 " + gen.before() + "、后 " + gen.after() + "，预算 " + bounds.budget() + "，回落上限 " + afterCap);
        String readOnly = readOnlyHit(summary, facts.budget());
        record(Check.READ_ONLY, readOnly == null, tag + readOnly);
        String detail = toolDetail(summary, facts.receiveDate());
        record(Check.TOOL_DETAIL, detail == null, tag + detail);
        // 剧本里用户只要求办过两件事：送修申请（暂缓，放行后到再次暂缓之间可以不带暂缓）与给妈妈买 iPad
        // 旧售后单、转人工、等用户打包或试听都是助手提的或工具查出来的；不靠模型自己写没写「未同意」来认
        boolean released = gen.compacted("release") && !gen.compacted("hold3");
        String unasked = summary.firstViolation("待办", line -> IPAD.test(line) || heldRepair(line)
                || released && REPAIR.matcher(line).find());
        record(Check.TODO_USER_ONLY, unasked == null, tag + unasked);
        String claimed = doneClaim(summary, facts.knownAfterSales());
        record(Check.NO_FAKE_DONE, claimed == null, tag + claimed);
        // 仅适用于全拒卡剧本：旧单号不构成本次办理成功的证据
        record(Check.DONE_EMPTY, summary.explicitlyNone("已完成"), tag + summary.joined("已完成"));
        String detour = summary.firstViolation("走不通的路", line -> !QUERY_DETOUR.matcher(line).find());
        record(Check.DEAD_END_NO_QUERY, detour == null, tag + detour);

        record(Check.ONE_NEXT_STEP, summary.lines("下一步").size() <= 1, tag + summary.joined("下一步"));
        Matcher person = PERSON.matcher(plain);
        boolean firstPerson = person.find();
        record(Check.THIRD_PERSON, !firstPerson, tag + (firstPerson ? around(plain, person.start()) : ""));
        String duplicate = duplicateQuote(summary);
        record(Check.QUOTE_ONCE, duplicate == null, tag + "「" + duplicate + "」");
        int goals = summary.lines("用户诉求").size();
        int dead = summary.lines("走不通的路").size();
        int done = summary.lines("已完成").size();
        record(Check.LINE_CAPS, goals <= 9 && dead <= 3 && done <= 6,
                tag + "用户诉求 " + goals + " 行、走不通的路 " + dead + " 行、已完成 " + done + " 行");
    }

    private void business(Generation gen, Generation previous, Facts facts, boolean releaseCarded) {
        CompactionSummary summary = gen.summary();
        String tag = "第 " + gen.number() + " 代：";
        if (gen.orderQueried()) {
            record(Check.ORDER_NO, summary.text().contains(facts.orderNo()), tag + "缺订单号 " + facts.orderNo());
        }
        if (gen.compacted("diag")) {
            record(Check.DIAGNOSIS, summary.any("用户诉求", line -> DIAG_LINE.matcher(line).find()),
                    tag + "用户诉求里没写试过的排查步骤");
        }
        if (within(gen, "hold1", "release")) {
            String violation = holdViolation(summary, false);
            record(Check.HOLD_FIRST, violation == null, tag + violation);
        }
        if (within(gen, "hypo", "release")) {
            String violation = holdViolation(summary, false);
            record(Check.HOLD_HYPOTHETICAL, violation == null, tag + violation);
        }
        if (gen.compacted("hold3")) {
            String violation = holdViolation(summary, true);
            record(Check.HOLD_THIRD, violation == null, tag + violation);
            record(Check.HOLD_LATEST_QUOTE, summary.any("用户诉求", line -> line.contains("先别提交") && line.contains("下周")),
                    tag + "暂缓那条附的不是「等我下周把包装准备好」");
        }
        if (gen.compacted("hold1")) {
            String violation = closedViolation(summary, MAX, "AirPods Max");
            record(Check.MAX_CLOSED, violation == null, tag + violation);
        }
        if (within(gen, "ipad_color", "ipad_drop")) {
            String goals = summary.joined("用户诉求");
            record(Check.IPAD_CONDITIONS, goals.contains(facts.budget()) && goals.contains("银色") && goals.contains("深空灰"),
                    tag + "用户诉求里 iPad 的预算或颜色不全");
        }
        if (within(gen, "ipad_hold", "ipad_drop")) {
            String violation = ipadHoldViolation(summary);
            record(Check.IPAD_HOLD, violation == null, tag + violation);
        }
        if (gen.compacted("ipad_drop")) {
            String violation = closedViolation(summary, IPAD, "iPad");
            record(Check.IPAD_CLOSED, violation == null, tag + violation);
        }
        if (gen.compacted("a4_drop")) {
            String violation = closedViolation(summary, A4, "AirPods 4");
            record(Check.A4_CLOSED, violation == null, tag + violation);
        }
        List<String> untraced = new ArrayList<>();
        traced(summary, gen.compacted("hold1"), MAX, "AirPods Max", untraced);
        traced(summary, gen.compacted("ipad_drop"), IPAD, "iPad", untraced);
        traced(summary, gen.compacted("a4_drop"), A4, "AirPods 4", untraced);
        if (gen.compacted("hold1")) {
            record(Check.CLOSED_TRACE, untraced.isEmpty(), tag + "用户诉求里没写已放弃: " + untraced);
            List<String> residue = List.of("当前进度", "走不通的路", "已完成");
            String left = leftover(summary, MAX, "AirPods Max", residue);
            if (left == null && gen.compacted("ipad_drop")) {
                left = leftover(summary, IPAD, "iPad", residue);
            }
            if (left == null && gen.compacted("a4_drop")) {
                left = leftover(summary, A4, "AirPods 4", residue);
            }
            record(Check.CLOSED_RESIDUE, left == null, tag + left);
        }
        if (gen.compacted("claim")) {
            String violation = claimViolation(summary);
            record(Check.CLAIM_SOURCE, violation == null, tag + violation);
        }
        if (gen.compacted("release")) {
            boolean firstAfterRelease = previous == null || !previous.compacted("release");
            if (firstAfterRelease && releaseCarded) {
                record(Check.RELEASE_CANCELLED, summary.text().contains("取消"), tag + "放行那轮的确认卡被拒，摘要里没有「取消」");
            }
            String stale = summary.firstViolation("待办", line -> !line.contains("天才吧"));
            record(Check.STALE_PLAN, stale == null, tag + "用户已说天才吧不去了，待办里还有：" + stale);
        }
    }

    private void steady(Generation previous, Generation current, Bounds bounds) {
        int cap = (int) (previous.summaryChars() * (1 + bounds.steadyRatio())) + bounds.steadySlack();
        record(Check.STEADY, current.summaryChars() <= cap, "第 " + previous.number() + " 代 " + previous.summaryChars()
                + " 字 → 第 " + current.number() + " 代 " + current.summaryChars() + " 字，允许 ≤ " + cap);
    }

    private void behavior(CompactionScript script, List<TurnRecord> turns) {
        int hold1 = script.index("hold1");
        int release = script.index("release");
        int hold3 = script.index("hold3");
        for (TurnRecord turn : turns) {
            boolean normal = "NORMAL".equals(turn.status()) || "AWAITING_CONFIRM".equals(turn.status());
            record(Check.TURN_OK, normal && turn.errors().isEmpty(),
                    "第 " + turn.index() + " 轮：状态 " + turn.status() + " " + turn.errors());
            boolean holding = (turn.index() >= hold1 && turn.index() < release) || turn.index() >= hold3;
            if (holding) {
                record(Check.NO_CARD_ON_HOLD, turn.cardTools().isEmpty(),
                        "第 " + turn.index() + " 轮弹出 " + turn.cardTools());
            }
            if (turn.index() == release) {
                record(Check.CARD_ON_RELEASE, !turn.cardTools().isEmpty(), "第 " + release + " 轮用户明说可以提交，没有弹卡");
            }
        }
    }

    /**
     * 放行前只是咨询，待办可以写「无」或只有别的事；放行后再次暂缓必须保留真实办理待办
     * 两个阶段仍使用相同的限制、当前授权和下一步检查
     */
    private static String holdViolation(CompactionSummary summary, boolean requireRepairTodo) {
        if (!summary.any("用户诉求", line -> line.contains("先别提交"))) {
            return "用户诉求里没有「先别提交」";
        }
        List<String> todos = summary.lines("待办");
        if (todos.isEmpty() && !summary.explicitlyNone("待办")) {
            return "待办为空节，未明确写无";
        }
        // 放行前用户只在咨询，没提送修就不要求送修待办；提了送修，或 hold3 之后用户已在 release 明确要求过，就必须有暂缓那条
        boolean mentionsRepair = todos.stream().map(CompactionSummary::unquoted)
                .anyMatch(plain -> !IPAD.test(plain) && REPAIR.matcher(plain).find());
        if ((requireRepairTodo || mentionsRepair)
                && !summary.any("待办", line -> {
                    String plain = CompactionSummary.unquoted(line);
                    return !IPAD.test(plain) && heldRepair(plain);
                })) {
            return "待办里没有送修暂缓条目: " + todos;
        }
        // 每条送修待办是否标暂缓仍由 generic 中的 TODO_USER_ONLY 逐行检查，不能被本处“无”豁免
        if (!summary.nextStepIsNone()
                && ACTION_WORD.matcher(CompactionSummary.unquoted(summary.joined("下一步"))).find()) {
            return "暂缓事项被写入下一步（不代表已经授权或执行）: " + summary.joined("下一步");
        }
        if (summary.lines("下一步").isEmpty() && !summary.nextStepIsNone()) {
            return "下一步为空节，未明确写无";
        }
        for (String section : CompactionSummary.SECTIONS) {
            for (String line : summary.lines(section)) {
                // 原话会被 unquoted 剥掉，放行那句得在原文上查；同一行写明了后来暂缓的是历史，不算
                if (line.contains(RELEASE_QUOTE) && !HOLD_WORD.matcher(line).find()) {
                    return "当前仍暂缓却附着放行原话「" + RELEASE_QUOTE + "」：" + line;
                }
                String plain = CompactionSummary.unquoted(line);
                if (IPAD.test(plain)) {
                    continue;
                }
                String released = affirmative(plain, RELEASE_CLAIM, true);
                if (released != null) {
                    return "当前仍暂缓却保留放行表述「" + released + "」：" + line;
                }
            }
        }
        return null;
    }

    /** 固定剧本的句式检查，不是通用中文语义判定器；否定只作用于紧邻的谓词 */
    private static String affirmative(String plain, Pattern claim, boolean allowWithdrawnHistory) {
        for (String sentence : plain.split("[。；;\\n]")) {
            Matcher matcher = claim.matcher(sentence);
            while (matcher.find()) {
                String prefix = sentence.substring(0, matcher.start());
                if (NEGATED.matcher(prefix).find()) {
                    continue;
                }
                if (allowWithdrawnHistory && HISTORICAL.matcher(prefix).find()
                        && WITHDRAWN.matcher(sentence.substring(matcher.end())).find()) {
                    continue;
                }
                String suffix = sentence.substring(matcher.end());
                if (allowWithdrawnHistory && suffix.matches("^(?:(?:送修|维修|售后)?申请)?后.*") && sentence.contains("取消")
                        && sentence.contains("未执行")
                        && (sentence.contains("确认") || sentence.contains("Permission denied"))) {
                    continue;
                }
                // “此前提交的工单在确认卡上取消，未执行”明确没有发生实际提交
                if (!allowWithdrawnHistory && sentence.contains("确认")
                        && sentence.contains("取消") && sentence.contains("未执行")) {
                    continue;
                }
                return matcher.group();
            }
        }
        return null;
    }

    private static boolean heldRepair(String line) {
        return HOLD_WORD.matcher(line).find() && REPAIR.matcher(line).find();
    }

    private static String ipadHoldViolation(CompactionSummary summary) {
        if (!summary.any("用户诉求", line -> IPAD.test(line) && line.contains("先别下单"))) {
            return "用户诉求里 iPad 没有「先别下单」";
        }
        if (!summary.nextStepIsNone() && summary.lines("下一步").stream().anyMatch(line -> line.contains("下单"))) {
            return "下一步要去下单: " + summary.joined("下一步");
        }
        return null;
    }

    /**
     * 了结的事会被接手的助手当成还要办的：用户诉求里得标放弃，待办与下一步里不许再有
     * 下一步写「无（原话…）」时原话里提到不算
     */
    private static String closedViolation(CompactionSummary summary, Predicate<String> mentions, String label) {
        String goal = summary.firstViolation("用户诉求", line -> !mentions.test(line) || ABANDONED.matcher(line).find());
        if (goal != null) {
            return label + " 没标放弃：" + goal;
        }
        return leftover(summary, mentions, label, List.of("待办", "下一步"));
    }

    private static String leftover(CompactionSummary summary, Predicate<String> mentions, String label,
                                   List<String> sections) {
        for (String section : sections) {
            if ("下一步".equals(section) && summary.nextStepIsNone()) {
                continue;
            }
            String left = summary.firstViolation(section, line -> !mentions.test(CompactionSummary.unquoted(line)));
            if (left != null) {
                return label + " 已了结，" + section + "里还有：" + left;
            }
        }
        return null;
    }

    private static void traced(CompactionSummary summary, boolean closed, Predicate<String> mentions,
                               String label, List<String> untraced) {
        if (closed && !summary.any("用户诉求", line -> mentions.test(line) && ABANDONED.matcher(line).find())) {
            untraced.add(label);
        }
    }

    private static String claimViolation(CompactionSummary summary) {
        String done = summary.firstViolation("已完成", line -> !line.contains(CLAIMED_NO));
        if (done != null) {
            return "用户声称的单号进了已完成：" + done;
        }
        for (String section : CompactionSummary.SECTIONS) {
            for (String line : summary.lines(section)) {
                if (line.contains(CLAIMED_NO) && !CLAIM_QUALIFIER.matcher(line).find()) {
                    return section + " 把用户声称的单号写成了事实：" + line;
                }
            }
        }
        return null;
    }

    /**
     * 用户自己给的预算是条件不是价格，其余带「元」的金额都来自只读查询
     */
    private static String readOnlyHit(CompactionSummary summary, String budget) {
        for (String section : CompactionSummary.SECTIONS) {
            for (String line : summary.lines(section)) {
                String plain = CompactionSummary.unquoted(line);
                Matcher matcher = READ_ONLY.matcher(plain);
                while (matcher.find()) {
                    if (matcher.group(1) == null || !matcher.group(1).replaceAll("[,，.]", "").equals(budget)) {
                        return section + "出现「" + matcher.group() + "」：" + line;
                    }
                }
                if (!"走不通的路".equals(section) && KNOWLEDGE.matcher(plain).find()) {
                    return section + "出现「知识库」：" + line;
                }
            }
        }
        return null;
    }

    /**
     * 单号留着给接手的助手重新调用工具，查出来的详情一概不抄；用户原话里提到的不算
     */
    private static String toolDetail(CompactionSummary summary, String receiveDate) {
        for (String section : CompactionSummary.SECTIONS) {
            for (String line : summary.lines(section)) {
                String plain = CompactionSummary.unquoted(line);
                for (Map.Entry<String, List<String>> old : OLD_AFTER_SALE_DETAILS.entrySet()) {
                    if (plain.contains(old.getKey()) && old.getValue().stream().anyMatch(plain::contains)) {
                        return section + "抄了旧售后单的类型或状态：" + line;
                    }
                }
                if (plain.contains(receiveDate)) {
                    return section + "抄了签收日期 " + receiveDate + "：" + line;
                }
                Matcher matcher = TOOL_DETAIL.matcher(plain);
                if (matcher.find()) {
                    return section + "出现「" + matcher.group() + "」：" + line;
                }
            }
        }
        return null;
    }

    /**
     * 回归从不批准确认卡：新冒出来的工单号、售后单号都是编的
     */
    private static String doneClaim(CompactionSummary summary, Set<String> knownAfterSales) {
        Matcher ticket = TICKET_NO.matcher(summary.text());
        if (ticket.find()) {
            return "出现了工单号 " + ticket.group();
        }
        Matcher afterSale = AFTER_SALE_NO.matcher(summary.text());
        while (afterSale.find()) {
            if (!knownAfterSales.contains(afterSale.group())) {
                return "出现了开跑前不存在的售后单号 " + afterSale.group();
            }
        }
        for (String section : List.of("已完成", "当前进度")) {
            for (String line : summary.lines(section)) {
                if (affirmative(CompactionSummary.unquoted(line), DONE_CLAIM, false) != null) {
                    return section + "：" + line;
                }
            }
        }
        return null;
    }

    private static String duplicateQuote(CompactionSummary summary) {
        Map<String, Integer> counts = new HashMap<>();
        for (String quote : summary.quotesOutsideNextStep()) {
            if (counts.merge(quote, 1, Integer::sum) > 1) {
                return quote;
            }
        }
        return null;
    }

    private static boolean within(Generation gen, String from, String until) {
        return gen.compacted(from) && !gen.compacted(until);
    }

    private static String around(String text, int index) {
        int start = Math.max(0, index - 12);
        int end = Math.min(text.length(), index + 12);
        return "…" + text.substring(start, end).replace('\n', ' ') + "…";
    }

    private void record(Check check, boolean passed, String failureDetail) {
        Tally tally = tallies.get(check);
        tally.observed++;
        if (!passed) {
            tally.failures++;
            if (generation > 0) {
                tally.affectedGenerations++;
                tally.run = tally.lastFailedGeneration == generation - 1 ? tally.run + 1 : 1;
                if (tally.run == 1) {
                    tally.episodes++;
                }
                tally.longestRun = Math.max(tally.longestRun, tally.run);
                tally.lastFailedGeneration = generation;
            }
            if (tally.firstFailure == null) {
                tally.firstFailure = failureDetail;
            }
        }
    }

    private List<Result> results() {
        List<Result> results = new ArrayList<>();
        for (Map.Entry<Check, Tally> entry : tallies.entrySet()) {
            Check check = entry.getKey();
            Tally tally = entry.getValue();
            Status status = tally.observed == 0 ? Status.UNCOVERED
                    : tally.failures == 0 ? Status.PASS
                    : check.hard ? Status.FAIL : Status.WARN;
            String detail = tally.failures == 0 ? "观测 " + tally.observed + " 次"
                    : tally.failures + "/" + tally.observed + " 次不满足，首例 " + tally.firstFailure;
            results.add(new Result(check, status, tally.observed, detail, tally.failures,
                    tally.episodes, tally.affectedGenerations, tally.longestRun));
        }
        return results;
    }

    private static final class Tally {
        private int observed;
        private int failures;
        private String firstFailure;
        private int episodes;
        private int affectedGenerations;
        private int longestRun;
        private int run;
        private int lastFailedGeneration = -1;
    }
}
