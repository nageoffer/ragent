/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import com.nageoffer.ai.ragent.initializer.AgentChatClient.AgentTurnResult;
import com.nageoffer.ai.ragent.initializer.AgentStateProbe.Snapshot;
import com.nageoffer.ai.ragent.initializer.MemoryTurnScript.Turn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Agent 记忆回归主入口：按剧本串行提问，逐轮读 t_agent_state，最后出判定与校准表
 */
final class AgentMemoryRegressionMain {

    // AgentMemoryProperties 手抄副本，改了那边这里必须同步
    private static final double TRIM_TRIGGER_RATIO = 0.5D;
    private static final double COMPACT_TRIGGER_RATIO = 0.8D;
    private static final double KEEP_RECENT_RATIO = 0.2D;
    private static final double SUMMARY_MAX_RATIO = 0.1D;
    private static final int SUMMARY_MAX_FLOOR_CHARS = 1500;
    private static final int SUMMARY_MAX_CEIL_CHARS = 6000;

    private AgentMemoryRegressionMain() {
    }

    public static void main(String[] args) {
        RegressionContext.run(args, AgentMemoryRegressionMain::execute);
    }

    private static void execute(RegressionContext context) throws Exception {
        MemoryTurnScript script = MemoryTurnScript.load(context.suiteDir().resolve("turns.properties"));
        String userId = context.login();
        printEnvironment(context, script, userId);

        List<TurnRecord> records = runTurns(context, script);
        printTurnTable(records);
        printCalibration(context, records);

        List<Check> checks = evaluate(context, script, records);
        printChecks(checks);
        printSessions(records);

        long failed = checks.stream().filter(check -> check.status() == Status.FAIL).count();
        long uncovered = checks.stream().filter(check -> check.status() == Status.UNCOVERED).count();
        if (failed > 0) {
            throw new IllegalStateException("记忆回归未通过，失败项 " + failed + " 条，详见上方判定表");
        }
        System.out.println();
        System.out.println("[regression] SUCCESS"
                + (uncovered > 0 ? "（有 " + uncovered + " 项未被本次覆盖，见判定表 UNCOVERED）" : ""));
    }

    // ---------------------------------------------------------------- 执行

    private static List<TurnRecord> runTurns(RegressionContext context, MemoryTurnScript script) throws Exception {
        long interval = Math.max(0, context.config().getInt("turn.interval-seconds", 2)) * 1000L;
        boolean verbose = Boolean.parseBoolean(context.argument("verbose", "false"));
        List<TurnRecord> records = new ArrayList<>();
        String mainSession = null;
        String freshSession = null;

        for (Turn turn : script.turns()) {
            String requested = turn.fresh() ? freshSession : mainSession;
            System.out.printf("[regression] %s (%s/%s) %s%n", turn.ref(), turn.session(), turn.tier(), turn.purpose());
            TurnRecord record;
            try {
                AgentTurnResult result = context.chat().ask(turn.text(), requested, context.turnTimeout());
                if (turn.fresh()) {
                    freshSession = result.conversationId();
                } else {
                    mainSession = result.conversationId();
                }
                Snapshot snapshot = context.probeWithRetry(result.conversationId(), script.anchor());
                record = TurnRecord.of(turn, result, snapshot);
                if (verbose) {
                    System.out.println("    回答: " + abbreviate(result.answer(), 400));
                }
            } catch (Exception ex) {
                // 单轮失败不中断，最终判定在报告里给
                System.out.println("    本轮失败: " + ex.getMessage());
                record = TurnRecord.failed(turn, ex.getMessage());
            }
            records.add(record);
            System.out.println("    " + record.oneLine());
            Thread.sleep(interval);
        }
        return List.copyOf(records);
    }

    // ---------------------------------------------------------------- 判定

    private static List<Check> evaluate(RegressionContext context, MemoryTurnScript script,
                                        List<TurnRecord> records) {
        List<Check> checks = new ArrayList<>();
        Snapshot mainPeak = peak(records, false);
        Snapshot mainLast = last(records, false);

        checks.add(new Check("链路", "每一轮都拿到了完整回答", "short-term",
                records.stream().noneMatch(TurnRecord::failedTurn)
                        ? Status.PASS : Status.FAIL,
                records.stream().filter(TurnRecord::failedTurn).count() + " 轮失败"));

        checks.add(new Check("结构", "上下文里没有孤儿 tool_use / tool_result", "short-term",
                records.stream().allMatch(TurnRecord::structurallySound) ? Status.PASS : Status.FAIL,
                "清理只做等长原位替换，压缩只在用户轮起点切；出现孤儿即两层之一越界"));

        // 压缩后锚点应活在摘要里，只判「状态里找得到」
        boolean summaryOn = summaryEnabled(context);
        checks.add(new Check("短期", summaryOn ? "锚点在状态里始终可寻回" : "锚点原文始终留在上下文里", "short-term",
                mainLast != null && mainLast.anchorPresent() ? Status.PASS : Status.FAIL,
                "锚点 " + script.anchor() + (summaryOn
                        ? "，压缩前在原文里、压缩后应被摘要原样带走，两头都丢了才是越界"
                        : "，清理层从不删消息，丢了就是有人越界删了原文")));

        for (TurnRecord record : records) {
            if (record.turn().expectAny().isEmpty()) {
                continue;
            }
            checks.add(checkRecall(record));
        }

        // 撑量轮没检索则上下文涨不起来，阈值校准空跑
        List<String> missedTools = new ArrayList<>();
        for (TurnRecord record : records) {
            String expected = record.turn().expectTool();
            if (!expected.isBlank() && !record.calledTool(expected)) {
                missedTools.add(record.turn().ref());
            }
        }
        checks.add(new Check("撑量", "撑量轮确实触发了知识检索", "short-term",
                missedTools.isEmpty() ? Status.PASS : Status.UNCOVERED,
                missedTools.isEmpty() ? "全部命中 expect-tool"
                        : "未检索的轮次 " + String.join(",", missedTools) + "，多半是知识库没初始化，上下文撑不起来"));

        // 复述关键词不算记住，重查工具才说明没记住
        for (TurnRecord record : records) {
            String forbidden = record.turn().forbidTool();
            if (forbidden.isBlank() || record.failedTurn()) {
                continue;
            }
            boolean clean = !record.calledTool(forbidden);
            checks.add(tierCheck(tierLabel(record.turn().tier()),
                    record.turn().ref() + " 未重查 " + forbidden + "，答案取自记忆",
                    record.turn().tier(), clean,
                    clean ? "本轮没调用该工具" : "本轮又调了一次 " + forbidden + "，说明这段结论没被记住"));
        }

        int evicted = mainLast == null ? 0 : mainLast.evictedToolResults();
        int peakChars = mainPeak == null ? 0 : mainPeak.contextChars();
        // 裁剪不看摘要开关；超过水位且有净缩短的候选就执行
        checks.add(new Check("短期", "工具结果裁剪已实际触发", "short-term",
                evicted > 0 ? Status.PASS : Status.UNCOVERED,
                evicted > 0 ? "已裁剪 " + evicted + " 块"
                        : peakChars <= trimTriggerChars(context)
                        ? "峰值 ≈" + peakChars + " 字符未超过裁剪门 " + trimTriggerChars(context)
                        + "，按 README 调低 context-window-chars 重跑"
                        : "快照峰值已过裁剪门 " + trimTriggerChars(context) + "，末轮未保留裁剪占位；"
                        + "核对检查时的水位、工具白名单、保护窗口及结果替换后是否净缩短，并结合日志确认是否裁过"));

        // 摘要没生成是覆盖度问题，不判死；硬断言在 t09 召回上
        boolean midHit = mainLast != null && mainLast.summaryMessages() > 0;
        checks.add(new Check("中期", "会话摘要资产已生成", "mid-term",
                midHit ? Status.PASS : Status.UNCOVERED,
                !summaryOn ? "agent.memory.summary-enabled=false，本次没跑压缩层"
                        : midHit ? "上下文里有 " + mainLast.summaryMessages() + " 条 __compaction_summary__ 消息"
                        : "峰值 ≈" + peakChars + " 字符未到压缩门 " + compactTriggerChars(context)
                        + "；裁剪先动手，它把水位摁在门下时压缩本就不该跑，"
                        + "要单独验压缩请把 evictable-tools 清空重跑"));

        TurnRecord fresh = records.stream().filter(record -> record.turn().fresh()).findFirst().orElse(null);
        boolean longHit = fresh != null && fresh.matched();
        checks.add(tierCheck("长期", "新会话里仍认得锚点", "long-term", longHit,
                "跨会话命中只能来自长期记忆，上下文在新会话里是空的"));

        return List.copyOf(checks);
    }

    private static Check checkRecall(TurnRecord record) {
        Turn turn = record.turn();
        String detail = "期望命中 " + String.join(" / ", turn.expectAny());
        if (record.failedTurn()) {
            return new Check(tierLabel(turn.tier()), turn.ref() + " " + turn.purpose(), turn.tier(),
                    turn.enforced() ? Status.FAIL : Status.PENDING, "本轮未拿到回答");
        }
        return tierCheck(tierLabel(turn.tier()), turn.ref() + " " + turn.purpose(), turn.tier(),
                record.matched(), detail);
    }

    /**
     * 未实现的层不判死，答得出需要人复核路径
     */
    private static Check tierCheck(String scope, String name, String tier, boolean hit, String detail) {
        boolean implemented = MemoryTurnScript.IMPLEMENTED_TIERS.contains(tier);
        Status status;
        if (implemented) {
            status = hit ? Status.PASS : Status.FAIL;
        } else {
            status = hit ? Status.UNEXPECTED : Status.PENDING;
        }
        String suffix = implemented ? "" : "（" + tier + " 尚未实现）";
        return new Check(scope, name, tier, status, detail + suffix);
    }

    /**
     * 默认值跟服务端 summaryEnabled 一致
     */
    private static boolean summaryEnabled(RegressionContext context) {
        return context.config().getBoolean("agent.memory.summary-enabled", true);
    }

    private static String tierLabel(String tier) {
        return switch (tier) {
            case "short-term" -> "短期";
            case "mid-term" -> "中期";
            case "long-term" -> "长期";
            default -> tier;
        };
    }

    // ---------------------------------------------------------------- 输出

    private static void printEnvironment(RegressionContext context, MemoryTurnScript script, String userId) {
        InitializerConfig config = context.config();
        System.out.println("=== 环境 ===");
        System.out.println("  服务地址        " + config.require("server.base-url"));
        System.out.println("  执行架构        " + config.get("execution.engine-type", "(未知)")
                + "（必须是 agent，workflow 档位没有 Agent 记忆）");
        System.out.println("  登录用户        " + config.require("auth.username") + " / userId=" + userId);
        System.out.println("  上下文预算      " + config.get("agent.memory.context-window-chars", "(未知)")
                + " 字符（服务端唯一要配的数，两道门按固定比例从它派生）");
        boolean summaryOn = summaryEnabled(context);
        System.out.println("  摘要压缩        " + (summaryOn ? "on" : "off")
                + "（off 时只留裁剪那一层；on 也不影响裁剪，两层按水位先后动手）");
        System.out.println("  派生门限        裁剪 " + trimTriggerChars(context)
                + " → 压缩 " + compactTriggerChars(context)
                + "，压缩后保留 " + keepRecentChars(context)
                + "，摘要正文上限 " + summaryMaxChars(context));
        System.out.println("  裁剪条件        超过裁剪水位后，清理白名单内、保护窗口外且替换后净缩短的工具结果");
        // 压缩层的素材过半判定写死在 AgentContextCompactor 里
        System.out.println("  压缩层另一道门  素材字符须过总量的一半，否则打「可换出字符不过半」跳过本轮");
        System.out.println("  剧本            " + script.turns().size() + " 轮，锚点 " + script.anchor());
        System.out.println("  已实现记忆层    " + String.join(", ", MemoryTurnScript.IMPLEMENTED_TIERS));
        System.out.println();
    }

    private static void printTurnTable(List<TurnRecord> records) {
        System.out.println();
        System.out.println("=== 逐轮观测 ===");
        System.out.printf("  %-5s %-6s %-10s %7s %6s %6s %7s %6s %7s %5s %9s  %s%n",
                "轮次", "会话", "层", "回答字数", "消息数", "循环数", "≈字符", "结果块", "已清理", "摘要", "payload", "命中");
        for (TurnRecord record : records) {
            Snapshot snapshot = record.snapshot();
            System.out.printf("  %-5s %-6s %-10s %7s %6s %6s %7s %6s %7s %5s %9s  %s%n",
                    record.turn().ref(), record.turn().session(), record.turn().tier(),
                    record.failedTurn() ? "-" : record.answerLength(),
                    number(snapshot == null ? -1 : snapshot.messageCount()),
                    number(snapshot == null ? -1 : snapshot.toolCycles()),
                    number(snapshot == null ? -1 : snapshot.contextChars()),
                    number(snapshot == null ? -1 : snapshot.toolResultBlocks()),
                    number(snapshot == null ? -1 : snapshot.evictedToolResults()),
                    number(snapshot == null ? -1 : snapshot.summaryMessages()),
                    number(snapshot == null ? -1 : snapshot.payloadBytes()),
                    record.hitLabel());
        }
        System.out.println("  说明：「摘要」列从 0 变 1 的那一轮就是压缩落点，同一轮的「消息数」与「≈字符」会同时掉下来；");
        System.out.println("        ≈字符按 AgentContextTrimmer 的口径在 SQL 侧复算，tool_use 入参长度算法不同，属近似值，");
        System.out.println("        精确值以服务端日志「上下文裁剪完成 / 上下文压缩完成」为准。");
    }

    private static void printCalibration(RegressionContext context, List<TurnRecord> records) {
        Snapshot peak = peak(records, false);
        System.out.println();
        System.out.println("=== 阈值校准 ===");
        if (peak == null) {
            System.out.println("  没有拿到任何会话状态，无法校准");
            return;
        }
        List<Integer> chars = new ArrayList<>(peak.toolResultChars());
        Collections.sort(chars);
        System.out.println("  ① tool_result 体量  条数 " + chars.size()
                + "，min " + percentile(chars, 0)
                + "，p50 " + percentile(chars, 50)
                + "，p90 " + percentile(chars, 90)
                + "，max " + percentile(chars, 100)
                + "（已清理块按占位长度计入）");
        System.out.println("  ② 上下文总量        峰值 ≈" + peak.contextChars() + " 字符 / payload "
                + peak.payloadBytes() + " 字节；预算 " + contextWindow(context)
                + " → 裁剪门 " + trimTriggerChars(context) + "（" + TRIM_TRIGGER_RATIO
                + "）/ 压缩门 " + compactTriggerChars(context) + "（" + COMPACT_TRIGGER_RATIO + "）");
        // 字符/token 比不在这里算：分子只含上下文，分母含人设与工具 schema
        System.out.println("  ③ 输入 token 峰值   " + peak.maxInputTokens()
                + "（供应商回填，权威读数；含人设与工具 schema，不可直接除②算折算比）");
        System.out.println("  ④ 命中缓存峰值      " + peak.maxCachedTokens()
                + " token；两层改写前缀可能影响缓存命中，单看命中下降不能判断收益，需结合实际费用与延迟");
        System.out.println("  ⑤ 工具循环          " + peak.toolCycles() + " 个循环 / "
                + peak.toolUseBlocks() + " 次调用，thinking 块 " + peak.thinkingBlocks() + " 个（永不清理）");
        int sum = 0;
        for (int value : chars) {
            sum += value;
        }
        System.out.println("  工具结果存量        tool_result 合计 " + sum
                + " 字符；含受保护、非白名单及已清理块，不等于本轮可回收量");

        Snapshot last = last(records, false);
        // 压缩门 0.8、保留段 0.2，越过门时素材天然过半，卡住只因尾段太肥
        System.out.println("  ⑥ 压缩落点          保留段 " + keepRecentChars(context)
                + " 字符，峰值 ≈" + peak.contextChars() + " 字符；越过压缩门即素材过半，"
                + "卡住只会是尾段太肥把切点顶到了头部");
        System.out.println("  ⑦ 摘要产物          末轮摘要消息 " + (last == null ? 0 : last.summaryMessages())
                + " 条，正文上限 " + summaryMaxChars(context)
                + " 字符（按预算派生，调试预算下会夹到下限 " + SUMMARY_MAX_FLOOR_CHARS
                + "）；实际长度与内容用 AgentMemoryProbeMain 打开末轮状态核对");
    }

    private static void printChecks(List<Check> checks) {
        System.out.println();
        System.out.println("=== 判定 ===");
        for (Check check : checks) {
            System.out.printf("  [%-10s] %-4s %-38s %s%n",
                    check.status().name(), check.scope(), check.name(), check.detail());
        }
        System.out.println("  PASS=通过  FAIL=回归  PENDING=该层未实现，答不出符合预期"
                + "  UNEXPECTED=未实现却命中，需复核  UNCOVERED=本次没跑到该分支");
    }

    private static void printSessions(List<TurnRecord> records) {
        System.out.println();
        System.out.println("=== 本次会话 ===");
        String main = sessionId(records, false);
        String fresh = sessionId(records, true);
        System.out.println("  主会话   " + (main == null ? "(未建立)" : main));
        System.out.println("  新会话   " + (fresh == null ? "(未建立)" : fresh));
        System.out.println("  会话不自动清理，可用 AgentMemoryProbeMain --session <会话ID> 反复复查同一条状态");
    }

    // ---------------------------------------------------------------- 工具

    private static int contextWindow(RegressionContext context) {
        return context.config().getInt("agent.memory.context-window-chars", 0);
    }

    private static int trimTriggerChars(RegressionContext context) {
        return (int) (contextWindow(context) * TRIM_TRIGGER_RATIO);
    }

    private static int compactTriggerChars(RegressionContext context) {
        return (int) (contextWindow(context) * COMPACT_TRIGGER_RATIO);
    }

    private static int keepRecentChars(RegressionContext context) {
        return (int) (contextWindow(context) * KEEP_RECENT_RATIO);
    }

    /**
     * 调试预算下会夹到下限 1500，属预期
     */
    private static int summaryMaxChars(RegressionContext context) {
        int derived = (int) (contextWindow(context) * SUMMARY_MAX_RATIO);
        return Math.min(Math.max(derived, SUMMARY_MAX_FLOOR_CHARS), SUMMARY_MAX_CEIL_CHARS);
    }

    private static Snapshot peak(List<TurnRecord> records, boolean fresh) {
        Snapshot result = null;
        for (TurnRecord record : records) {
            if (record.turn().fresh() != fresh || record.snapshot() == null) {
                continue;
            }
            if (result == null || record.snapshot().contextChars() > result.contextChars()) {
                result = record.snapshot();
            }
        }
        return result;
    }

    private static Snapshot last(List<TurnRecord> records, boolean fresh) {
        Snapshot result = null;
        for (TurnRecord record : records) {
            if (record.turn().fresh() == fresh && record.snapshot() != null) {
                result = record.snapshot();
            }
        }
        return result;
    }

    private static String sessionId(List<TurnRecord> records, boolean fresh) {
        for (TurnRecord record : records) {
            if (record.turn().fresh() == fresh && record.sessionId() != null) {
                return record.sessionId();
            }
        }
        return null;
    }

    private static String percentile(List<Integer> sorted, int percent) {
        if (sorted.isEmpty()) {
            return "-";
        }
        int index = (int) Math.ceil(percent / 100.0 * sorted.size()) - 1;
        return String.valueOf(sorted.get(Math.max(0, Math.min(sorted.size() - 1, index))));
    }

    private static String number(int value) {
        return value < 0 ? "-" : String.valueOf(value);
    }

    private static String abbreviate(String value, int limit) {
        String single = value == null ? "" : value.replace('\n', ' ');
        return single.length() <= limit ? single : single.substring(0, limit) + "...";
    }

    private enum Status {
        PASS, FAIL, PENDING, UNEXPECTED, UNCOVERED
    }

    private record Check(String scope, String name, String tier, Status status, String detail) {
    }

    /**
     * 一轮的观测结果
     */
    private record TurnRecord(Turn turn, String sessionId, AgentTurnResult result, Snapshot snapshot,
                              boolean matched, String failure) {

        static TurnRecord of(Turn turn, AgentTurnResult result, Snapshot snapshot) {
            return new TurnRecord(turn, result.conversationId(), result, snapshot,
                    turn.matched(result.answer()), null);
        }

        static TurnRecord failed(Turn turn, String failure) {
            return new TurnRecord(turn, null, null, null, false, failure);
        }

        boolean failedTurn() {
            return failure != null;
        }

        boolean calledTool(String name) {
            return result != null && result.tools().contains(name);
        }

        int answerLength() {
            return result == null || result.answer() == null ? 0 : result.answer().length();
        }

        /**
         * 拿不到状态不算违规，可能是落库慢
         */
        boolean structurallySound() {
            return snapshot == null || snapshot.structurallySound();
        }

        String hitLabel() {
            if (failedTurn()) {
                return "失败";
            }
            if (turn.expectAny().isEmpty()) {
                return result.tools().isEmpty() ? "-" : String.join(",", result.tools());
            }
            return matched ? "命中" : "未命中";
        }

        String oneLine() {
            if (failedTurn()) {
                return "结果: 失败";
            }
            return "结果: 回答 " + answerLength() + " 字"
                    + (result.thinkChars() > 0 ? "（思考 " + result.thinkChars() + " 字）" : "")
                    + "，工具 " + (result.tools().isEmpty() ? "无" : String.join(",", result.tools()))
                    + (snapshot == null ? "，状态未落库"
                    : "，上下文 " + snapshot.messageCount() + " 条 / ≈" + snapshot.contextChars() + " 字符"
                    + "，已清理 " + snapshot.evictedToolResults() + " 块");
        }
    }
}
