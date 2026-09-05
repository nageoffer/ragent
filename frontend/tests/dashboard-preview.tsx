/**
 * 仅供本地视觉回归的独立入口，不进入生产入口或路由。
 * npm run dev -- --port 5175，然后访问 /tests/dashboard-preview.html。
 * 所有 API 被本地 adapter 截获；不需要登录，不读取/修改任何业务数据。
 */
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { DashboardPage } from "../src/pages/admin/dashboard/DashboardPage";
import { api } from "../src/services/api";
import type {
  AgentDashboardPerformance,
  DashboardOverview,
  DashboardPerformance
} from "../src/services/dashboardService";
import "../src/styles/globals.css";

const params = new URLSearchParams(location.search);
const engine = params.get("engine") === "workflow" ? "workflow" : "agent";
const scenario = params.get("scenario") ?? "sample";
/** console 复刻真实控制台外壳（含 256px 左栏），bare 为无左栏的裸预览 */
const chrome = params.get("chrome") === "console" ? "console" : "bare";
const empty = scenario === "empty";
const end = new Date("2026-09-04T12:30:00+08:00").getTime();
const n = (value: number) => (empty ? 0 : value);
/** deltaPct 逐指标给：需要同时覆盖上涨、下跌（验 B1 的红色分支）和无对比期（验不渲染占位行）。 */
const kpi = (value: number, deltaPct: number | null = 12.8) => ({
  value: n(value),
  delta: n(Math.round((value * (deltaPct ?? 0)) / 100)),
  deltaPct: empty ? null : deltaPct
});

/*
 * 逐工具 count = done + failed + interrupted，且九项分别累加后必须等于 tools 的四个总数：
 * 前端的「其他工具」残余行是用总数减去已展示部分算出来的，量级对不上会算出负数或错误成功率
 * 末位是「最近调用距统计时刻多少分钟」，留一个 null 覆盖后端拿不到时间戳时的破折号分支
 */
const toolRows: Array<[string, string, number, number, number, number | null]> = [
  ["search_knowledge", "知识检索", 312, 308, 2, 12],
  ["order_lookup", "订单查询", 128, 124, 3, 47],
  ["load_skill", "加载技能", 96, 95, 0, 96],
  ["create_ticket", "创建工单", 42, 39, 2, 205],
  ["status_check", "服务状态查询", 36, 34, 2, 388],
  ["refund_apply", "退款申请", 28, 25, 2, 640],
  ["user_profile", "用户资料查询", 21, 20, 1, 902],
  ["notify_push", "消息推送", 12, 11, 1, 1188],
  ["audit_log", "审计日志", 9, 6, 1, null]
];

/*
 * 确认卡按工具拆分。单位是「调用」不是「卡」：一张卡可以裹多个调用，
 * 所以三行 calls 之和（58）必须大于 confirmations.total（52），否则验不到那条口径说明
 */
const confirmToolRows: Array<[string, string, number, number, number]> = [
  ["refund_apply", "退款申请", 31, 21, 4],
  ["create_ticket", "创建工单", 18, 12, 2],
  ["notify_push", "消息推送", 9, 5, 1]
];

const agentData: AgentDashboardPerformance = {
  engine: "agent",
  window: "24h",
  updatedAt: end,
  replies: {
    total: n(468),
    // (468 - 415) / 415 = 12.8%，与概览四个 KPI 走同一个环比口径
    previousTotal: n(415),
    deltaPct: empty ? null : 12.8,
    normal: n(438),
    interrupted: n(21),
    awaitingConfirm: n(9),
    unknown: 0,
    withBlocks: n(442),
    // 三档相加必须等于 withBlocks，前端的占比分母就是它
    directReplies: n(126),
    singleToolReplies: n(148),
    multiToolReplies: n(168)
  },
  tools: {
    total: n(684),
    done: n(662),
    failed: n(14),
    interrupted: n(8),
    other: 0,
    callsPerRecordedReply: empty ? null : 1.55,
    knowledgeSearchCalls: n(312),
    // 后端不截断，这里给 9 个（合计 684）以覆盖前端封顶 6 行 +「其他工具」残余桶
    topTools: empty
      ? []
      : toolRows.map(([name, displayName, count, done, failed, minutesAgo]) => ({
          name,
          displayName,
          count,
          done,
          failed,
          successRate: (done / count) * 100,
          lastCallAt: minutesAgo === null ? null : end - minutesAgo * 60000
        }))
  },
  confirmations: {
    total: n(52),
    approved: n(34),
    denied: n(7),
    pending: n(9),
    expired: n(2),
    other: 0,
    approvalRate: empty ? null : 82.93,
    topTools: empty
      ? []
      : confirmToolRows.map(([name, displayName, calls, approved, denied]) => ({
          name,
          displayName,
          calls,
          approved,
          denied
        }))
  },
  memory: {
    compactions: n(28),
    // 28 次压缩里只有 24 次记了字符数：两个分母不同，「平均每次」除的是后者
    compactionsWithChars: n(24),
    contextReductionPct: empty ? null : 63.4,
    // 字符数而非 token；缩减比例按两个总数算，改一个必须同时改另一个
    contextCharsBefore: n(3_400_000),
    contextCharsAfter: n(1_244_400),
    activeMemories: n(136),
    addedMemories: n(18),
    invalidatedMemories: n(4)
  }
};

if (scenario === "missing") {
  agentData.replies.withBlocks = 0;
  agentData.replies.directReplies = 0;
  agentData.replies.singleToolReplies = 0;
  agentData.replies.multiToolReplies = 0;
  agentData.tools = {
    total: 0,
    done: 0,
    failed: 0,
    interrupted: 0,
    other: 0,
    callsPerRecordedReply: null,
    knowledgeSearchCalls: 0,
    topTools: []
  };
  agentData.confirmations = {
    total: 0,
    approved: 0,
    denied: 0,
    pending: 0,
    expired: 0,
    other: 0,
    approvalRate: null,
    topTools: []
  };
  agentData.memory = {
    // 压过但一次字符数都没记：验「有次数、无字符」那条分支，它与"一次都没压缩"是两句话
    compactions: 6,
    compactionsWithChars: 0,
    contextReductionPct: null,
    contextCharsBefore: 0,
    contextCharsAfter: 0,
    activeMemories: 136,
    addedMemories: 0,
    invalidatedMemories: 0
  };
}

api.defaults.adapter = async (config) => {
  // 可感知加载状态，并检查被丢弃的异步请求不会覆盖新窗口。
  await new Promise((resolve) => setTimeout(resolve, 180));
  if (!config.url?.startsWith("/admin/dashboard/")) throw new Error("预览禁止访问业务接口");
  if (scenario === "error") throw new Error("测试：统计接口暂不可用");
  const windowValue = config.params?.window ?? "24h";
  let data: unknown;
  if (config.url.endsWith("/overview")) {
    data = {
      engine,
      window: windowValue,
      compareWindow: `prev_${windowValue}`,
      updatedAt: end,
      kpis: {
        totalUsers: kpi(218),
        activeUsers: kpi(72, 8.4),
        totalSessions: kpi(2630),
        sessions24h: kpi(156, -79.4),
        totalMessages: kpi(18420),
        messages24h: kpi(964, 12.8),
        // 无对比期：验证涨跌行整行不渲染，而不是画一个占位破折号
        activeSessions: kpi(184, null)
      }
    } satisfies DashboardOverview;
  } else if (config.url.endsWith("/performance")) {
    data =
      engine === "agent"
        ? { ...agentData, window: windowValue }
        : ({
            engine: "workflow",
            window: windowValue,
            avgLatencyMs: n(2360),
            p95LatencyMs: n(5800),
            successRate: n(99.2),
            errorRate: n(0.8),
            noDocRate: n(4.2),
            slowRate: n(1.2),
            sampleCount: n(468)
          } satisfies DashboardPerformance);
  } else {
    if (scenario === "trend-error") throw new Error("测试：趋势读取失败");
    const metric = config.params?.metric;
    if (engine === "agent" && ["avgLatency", "quality"].includes(metric))
      throw new Error("Agent 不应请求 RAG 性能指标");
    if (engine === "workflow" && ["tools", "replies"].includes(metric))
      throw new Error("Workflow 不应请求 Agent 专属指标");
    /*
     * Agent 分支对 sessions/messages/activeusers 下发两条序列（当前周期 + 上周期），
     * Workflow 分支只有一条：流量图必须两种形状都能画
     */
    const compared = engine === "agent" && ["messages", "sessions", "activeusers"].includes(metric);
    const definitions: Record<string, Array<[string, number]>> = {
      messages: compared
        ? [
            ["当前周期", n(964)],
            ["上周期", n(855)]
          ]
        : [["消息数", n(964)]],
      sessions: compared
        ? [
            ["当前周期", n(156)],
            ["上周期", n(757)]
          ]
        : [["会话数", n(156)]],
      activeusers: compared
        ? [
            ["当前周期", n(72)],
            ["上周期", n(66)]
          ]
        : [["活跃用户", n(72)]],
      tools: [["已记录工具调用", agentData.tools.total]],
      replies: [
        ["正常", agentData.replies.normal],
        ["待确认", agentData.replies.awaitingConfirm],
        ["中断", agentData.replies.interrupted],
        ["其他状态", 0]
      ],
      avgLatency: [["平均响应时间", n(2360)]],
      quality: [
        ["错误率", n(0.8)],
        ["无知识率", n(4.2)]
      ]
    };
    const length = windowValue === "24h" ? 25 : windowValue === "7d" ? 8 : 31;
    const step = windowValue === "24h" ? 3600000 : 86400000;
    data = {
      metric,
      window: windowValue,
      granularity: config.params?.granularity,
      series: (definitions[metric] ?? []).map(([name, total], seriesIndex) => {
        // 上周期给一个相位差，否则两条线完全重合，看不出实线/虚线的层次是否成立
        const phase = seriesIndex === 1 ? 1.1 : 0;
        const weights = Array.from(
          { length },
          (_, index) => 1 + (Math.sin(index * 0.7 + phase) + 1) * 3
        );
        const weightSum = weights.reduce((a, b) => a + b, 0);
        const values = weights.map((w) =>
          metric === "quality" || metric === "avgLatency"
            ? total
            : Math.floor((total * w) / weightSum)
        );
        if (!["quality", "avgLatency"].includes(metric))
          values[values.length - 1] += total - values.reduce((a, b) => a + b, 0);
        return {
          name,
          data: values.map((value, index) => ({ ts: end - (length - 1 - index) * step, value }))
        };
      })
    };
  }
  return { config, data, status: 200, statusText: "OK", headers: {} };
};

const scenarioNav = (
  <nav aria-label="测试场景" className="mb-5 flex flex-wrap gap-3 text-xs text-slate-500">
    <span className="font-semibold">隔离预览 · 全部为测试数据</span>
    <a href={`?engine=agent&chrome=${chrome}`}>Agent</a>
    <a href={`?engine=workflow&chrome=${chrome}`}>Workflow</a>
    {["sample", "empty", "missing", "error", "trend-error"].map((value) => (
      <a key={value} href={`?engine=${engine}&scenario=${value}&chrome=${chrome}`}>
        {value}
      </a>
    ))}
    <a href={`?engine=${engine}&scenario=${scenario}&chrome=${chrome === "console" ? "bare" : "console"}`}>
      chrome: {chrome}
    </a>
  </nav>
);

const breadcrumbs = (
  <nav className="admin-breadcrumbs" aria-label="面包屑">
    <span>首页</span>
    <span>/</span>
    <span className="text-slate-700">Dashboard</span>
  </nav>
);

/*
 * 两种外壳：
 * bare —— 不摆 admin-content，也没有左栏，横向留给页面本身，量卡内排布时干扰最少。
 * console —— 逐字复刻 AdminLayout 的外壳（w-64 左栏 + admin-main + admin-content 的 px-8），
 *   分栏宽度只有在这个外壳里量才等于用户真实看到的宽度；侧栏只要几何不要内容，故为空壳。
 * 隐藏面包屑那条规则是 .admin-main:has(.dashboard-page) .admin-breadcrumbs，少了 admin-main 这层祖先就验不到。
 */
createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <BrowserRouter>
      {chrome === "console" ? (
        <div className="admin-layout flex h-screen">
          <aside className="admin-sidebar" aria-hidden="true" />
          <div className="admin-main flex min-h-screen flex-1 flex-col overflow-auto">
            <div className="admin-content">
              {scenarioNav}
              {breadcrumbs}
              <DashboardPage />
            </div>
          </div>
        </div>
      ) : (
        <div className="admin-layout min-h-screen bg-slate-100 px-4 py-6 sm:px-8">
          <div className="mx-auto max-w-[1360px]">
            {scenarioNav}
            <div className="admin-main">
              {breadcrumbs}
              <DashboardPage />
            </div>
          </div>
        </div>
      )}
    </BrowserRouter>
  </StrictMode>
);
