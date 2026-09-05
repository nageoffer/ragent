import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ComponentType,
  type ReactNode
} from "react";
import {
  AlertCircle,
  Clock,
  Info,
  Lightbulb,
  MessageSquare,
  RefreshCw,
  ShieldCheck,
  Sparkles,
  Timer,
  TrendingDown,
  TrendingUp,
  Users,
  Wrench
} from "lucide-react";
import { toast } from "sonner";

import { CardHead, DashCard, Hint } from "@/components/admin/DashboardCard";
import {
  ChartLegend,
  SimpleLineChart,
  type ChartThreshold,
  type ChartXAxisMode,
  type ChartYAxisType,
  type TrendSeries
} from "@/components/admin/SimpleLineChart";
import { VIZ_SURFACE } from "@/components/admin/vizTokens";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import {
  getDashboardOverview,
  getDashboardPerformance,
  getDashboardTrends,
  type AgentDashboardPerformance,
  type DashboardOverview,
  type DashboardPerformance,
  type WorkflowDashboardPerformance,
  type DashboardTrends
} from "@/services/dashboardService";
import type { EngineType } from "@/stores/engineStore";
import {
  AgentConfirmations,
  AgentMemoryContext,
  AgentRunHealth,
  AgentToolAnalysis
} from "./AgentDashboardSections";

// ============================================================================
// Types
// ============================================================================

type DashboardTimeWindow = "24h" | "7d" | "30d";

type DashboardTrendBundle = {
  sessions: DashboardTrends | null;
  messages: DashboardTrends | null;
  activeUsers: DashboardTrends | null;
  latency: DashboardTrends | null;
  quality: DashboardTrends | null;
  tools: DashboardTrends | null;
  replies: DashboardTrends | null;
};

/** 流量卡的三个标签页共用一套渲染，差异只在取哪条趋势、配哪个 KPI。 */
type TrafficMetric = "messages" | "sessions" | "activeUsers";

type HealthStatus = "healthy" | "attention" | "critical" | "unknown";
type MetricTone = "good" | "warning" | "bad";

type MetricStatusView = {
  success: MetricTone;
  latency: MetricTone;
  error: MetricTone;
  noDoc: MetricTone;
};

type KPIChange = {
  value: number;
  trend: "up" | "down" | "flat";
};

type InsightCardData = {
  type: "anomaly" | "trend" | "recommendation";
  severity: "info" | "warning" | "critical";
  title: string;
  metric: string;
  change: string;
  context: string;
  action?: string;
  timestamp: string;
};

// ============================================================================
// Constants
// ============================================================================

const WINDOW_OPTIONS: Array<{ value: DashboardTimeWindow; label: string }> = [
  { value: "24h", label: "24h" },
  { value: "7d", label: "7d" },
  { value: "30d", label: "30d" }
];

const WINDOW_LABEL_MAP: Record<DashboardTimeWindow, string> = {
  "24h": "滚动 24h",
  "7d": "近 7 天",
  "30d": "近 30 天"
};

const DASHBOARD_THRESHOLDS = {
  latency: { good: 10000, warning: 15000 },
  successRate: { good: 99, warning: 95 },
  errorRate: { good: 1, warning: 5 },
  noDocRate: { good: 10, warning: 30 }
} as const;

const EMPTY_TRENDS: DashboardTrendBundle = {
  sessions: null,
  messages: null,
  activeUsers: null,
  latency: null,
  quality: null,
  tools: null,
  replies: null
};

const TRAFFIC_TABS: Array<{ value: TrafficMetric; label: string }> = [
  { value: "messages", label: "消息" },
  { value: "sessions", label: "新建会话" },
  { value: "activeUsers", label: "活跃用户" }
];

// ============================================================================
// Utils
// ============================================================================

const getMetricStatus = (
    metric: "latency" | "successRate" | "errorRate" | "noDocRate",
    value?: number | null
): MetricTone => {
  if (value === null || value === undefined) return "warning";

  if (metric === "latency") {
    if (value < DASHBOARD_THRESHOLDS.latency.good) return "good";
    if (value < DASHBOARD_THRESHOLDS.latency.warning) return "warning";
    return "bad";
  }

  if (metric === "successRate") {
    if (value >= DASHBOARD_THRESHOLDS.successRate.good) return "good";
    if (value >= DASHBOARD_THRESHOLDS.successRate.warning) return "warning";
    return "bad";
  }

  if (metric === "errorRate") {
    if (value <= DASHBOARD_THRESHOLDS.errorRate.good) return "good";
    if (value <= DASHBOARD_THRESHOLDS.errorRate.warning) return "warning";
    return "bad";
  }

  if (value <= DASHBOARD_THRESHOLDS.noDocRate.good) return "good";
  if (value <= DASHBOARD_THRESHOLDS.noDocRate.warning) return "warning";
  return "bad";
};

const getHealthStatus = (
    performance?: {
      successRate?: number | null;
      errorRate?: number | null;
      noDocRate?: number | null;
    } | null,
    windowMessages?: number
): HealthStatus => {
  if (!performance || !windowMessages) return "unknown";
  if ((performance.errorRate ?? 0) > DASHBOARD_THRESHOLDS.errorRate.warning) return "critical";
  if ((performance.successRate ?? 0) < DASHBOARD_THRESHOLDS.successRate.warning) return "critical";
  if ((performance.noDocRate ?? 0) > 20) return "attention";
  return "healthy";
};

const getLatencyStatus = (value?: number | null): MetricTone => {
  if (value === null || value === undefined) return "warning";
  if (value <= DASHBOARD_THRESHOLDS.latency.good) return "good";
  if (value <= DASHBOARD_THRESHOLDS.latency.warning) return "warning";
  return "bad";
};

const formatLastUpdated = (timestamp: number | null) => {
  if (!timestamp) return "-";
  return new Date(timestamp).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  });
};

const formatTime = (timestamp: number | null) => {
  if (!timestamp) return "-";
  return new Date(timestamp).toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  });
};

const formatPercent = (value?: number | null) => {
  if (value === null || value === undefined) return "-";
  return `${value.toFixed(1)}%`;
};

const formatDuration = (value?: number | null) => {
  if (value === null || value === undefined) return "-";
  if (value < 1000) return `${Math.round(value)}ms`;
  return `${(value / 1000).toFixed(2)}s`;
};

const formatNumber = (value?: number | null) => {
  if (value === null || value === undefined) return "-";
  return value.toLocaleString("zh-CN");
};

const clampPercent = (value?: number | null) => {
  if (value === null || value === undefined || Number.isNaN(value)) return 0;
  return Math.max(0, Math.min(100, value));
};

const formatRatio = (value?: number | null) => {
  if (value === null || value === undefined || !Number.isFinite(value)) return "-";
  return value.toFixed(2);
};

// ============================================================================
// Hooks
// ============================================================================

const useDashboardData = () => {
  // 默认落在 7d：24h 的样本量常常小到一次异常就把成功率打穿，日粒度的七个点才看得出趋势
  const [timeWindow, setTimeWindow] = useState<DashboardTimeWindow>("7d");
  const [overview, setOverview] = useState<DashboardOverview | null>(null);
  const [performance, setPerformance] = useState<DashboardPerformance | null>(null);
  const [trends, setTrends] = useState<DashboardTrendBundle>(EMPTY_TRENDS);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);
  const [engine, setEngine] = useState<EngineType | null>(null);
  const requestIdRef = useRef(0);

  const loadData = useCallback(async (windowValue: DashboardTimeWindow) => {
    const requestId = ++requestIdRef.current;
    setLoading(true);
    setError(null);
    // 保留上一次的渲染结果：切换时间窗时整页塌成骨架会让布局跳动，加载态由整体降透明度承载。
    const granularity = windowValue === "24h" ? "hour" : "day";

    try {
      const [overviewData, performanceData] = await Promise.all([
        getDashboardOverview(windowValue),
        getDashboardPerformance(windowValue)
      ]);
      if (requestIdRef.current !== requestId) return;
      if (!(["agent", "workflow"] as string[]).includes(overviewData.engine) || performanceData.engine !== overviewData.engine) {
        throw new Error("统计接口的引擎标识不一致，请确认前后端版本一致");
      }
      setEngine(overviewData.engine);
      setOverview(overviewData);
      setPerformance(performanceData);
      setLastUpdated(overviewData.updatedAt);

      try {
        const isAgent = overviewData.engine === "agent";
        // 两个引擎都支持 sessions/messages/activeusers，流量卡的三个标签页在两边都能画
        const [sessions, messages, activeUsers, firstEngineTrend, secondEngineTrend] =
          await Promise.all([
            getDashboardTrends("sessions", windowValue, granularity),
            getDashboardTrends("messages", windowValue, granularity),
            getDashboardTrends("activeusers", windowValue, granularity),
            getDashboardTrends(isAgent ? "tools" : "avgLatency", windowValue, granularity),
            getDashboardTrends(isAgent ? "replies" : "quality", windowValue, granularity)
          ]);
        if (requestIdRef.current !== requestId) return;
        setTrends({ sessions, messages, activeUsers,
          latency: isAgent ? null : firstEngineTrend, quality: isAgent ? null : secondEngineTrend,
          tools: isAgent ? firstEngineTrend : null, replies: isAgent ? secondEngineTrend : null });
      } catch (trendErr) {
        if (requestIdRef.current !== requestId) return;
        console.error(trendErr);
        setTrends(EMPTY_TRENDS);
        setError("趋势数据加载失败");
      }
    } catch (err) {
      if (requestIdRef.current !== requestId) return;
      console.error(err);
      setError(err instanceof Error ? err.message : "数据加载失败");
    } finally {
      if (requestIdRef.current === requestId) {
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    void loadData(timeWindow);
    return () => { requestIdRef.current += 1; };
  }, [loadData, timeWindow]);

  const refresh = useCallback(async () => {
    await loadData(timeWindow);
  }, [loadData, timeWindow]);

  return {
    engine,
    timeWindow,
    setTimeWindow,
    loading,
    error,
    lastUpdated,
    overview,
    performance,
    trends,
    refresh
  };
};

const useHealthStatus = (performance: WorkflowDashboardPerformance | null, overview: DashboardOverview | null) => {
  const windowMessages = overview?.kpis?.messages24h?.value;
  const health = useMemo(() => performance?.sampleCount ? getHealthStatus(performance, windowMessages) : "unknown", [performance, windowMessages]);

  const metricStatus = useMemo<MetricStatusView>(
      () => ({
        success: getMetricStatus("successRate", performance?.successRate),
        latency: getMetricStatus("latency", performance?.avgLatencyMs),
        error: getMetricStatus("errorRate", performance?.errorRate),
        noDoc: getMetricStatus("noDocRate", performance?.noDocRate)
      }),
      [performance]
  );

  return { health, metricStatus };
};

// ============================================================================
// Base Components
// ============================================================================

const CardTitle = ({ children }: { children: ReactNode }) => (
    <h3 className="mb-4 text-base font-semibold leading-6 text-[#101828]">{children}</h3>
);

const LoadingBlock = ({ className }: { className?: string }) => (
    <div className={cn("motion-safe:animate-pulse rounded-lg bg-[#F1F3F7]", className)} />
);

/**
 * 分段器：选中态用主色浅底而不是深底反白。
 * 深底那一格在浅色页面里是全页最重的一块，而它只是个筛选器，不该比它筛出来的数字更响。
 */
/**
 * Agent 分支两层分栏共用的列定义。右列给到 360px 硬下限而不是按比例分：
 * 真实控制台里左侧栏吃掉 256px，纯比例分栏会把右列压到放不下「12 / 34」这类并排读数的宽度；
 * 容器再窄时右列钉在 360，左列自己让位，不出现横向溢出。
 *
 * 比例从 2 : 0.95 收到 1.55 : 1：左列原来宽到让流量图的绘图区接近 3.4 : 1，
 * 那个比例下每一段折线都被横向拉平成锯齿，卡也读成一条横幅而不是一块内容
 */
const SPLIT_COLS = "xl:grid-cols-[minmax(0,1.55fr)_minmax(360px,1fr)]";

const SEGMENT_TRACK = "inline-flex rounded-lg bg-[#F2F4F7] p-0.5";
const segmentItem = (active: boolean) =>
    cn(
        "rounded-[7px] px-2.5 py-1 text-xs font-medium transition-colors",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#4F6EF7]",
        active ? "bg-[#EEF2FF] text-[#4F6EF7]" : "text-[#667085] hover:text-[#101828]"
    );

// ============================================================================
// Header
// ============================================================================

const HEALTH_CONFIG: Record<HealthStatus, { bg: string; text: string; label: string }> = {
  healthy: { bg: "bg-emerald-100", text: "text-emerald-700", label: "运行正常" },
  attention: { bg: "bg-amber-100", text: "text-amber-700", label: "需要关注" },
  critical: { bg: "bg-red-100", text: "text-red-700", label: "风险偏高" },
  unknown: { bg: "bg-slate-100", text: "text-slate-500", label: "暂无数据" }
};

const DashboardHeader = ({
                           engine,
                           timeWindow,
                           lastUpdated,
                           loading,
                           onRefresh,
                           onTimeWindowChange
                         }: {
  engine: EngineType | null;
  timeWindow: DashboardTimeWindow;
  lastUpdated: number | null;
  loading?: boolean;
  onRefresh: () => void;
  onTimeWindowChange: (window: DashboardTimeWindow) => void;
}) => (
    <header className="flex flex-wrap items-start justify-between gap-x-3 gap-y-2">
      {/*
        标题、引擎标签共占一行，副标题已删：原来那句「了解 X 使用情况、执行轨迹与需要关注的问题」
        没有一个字是读者看完能拿去做事的，它只是把标题换个说法再讲一遍，而它连着行距要吃掉 24px——
        这一页要在不下拉的前提下放下四层，这 24px 是全页最先该让出来的。
        面包屑同理不恢复：它只是把左侧导航已选中的那一项又念一遍
      */}
      <div className="min-w-0">
        <div className="flex min-w-0 flex-wrap items-center gap-x-2.5 gap-y-1">
          <h1 className="text-[22px] font-[650] leading-7 tracking-[-0.01em] text-[#101828]">
            {engine === "workflow" ? "Workflow 运行概览" : engine === "agent" ? "Agent 运行概览" : "运行概览"}
          </h1>
          {/* 标签只说"这些数字是谁的"，不带状态语义，所以走主色浅底而不是任何一档状态色 */}
          <span className="rounded-md bg-[#EEF2FF] px-2 py-0.5 text-xs font-medium leading-5 text-[#405FE8]">
            {engine ? (engine === "agent" ? "Agent · 自主执行" : "Workflow · RAG 编排") : loading ? "正在读取引擎" : "引擎信息不可用"}
          </span>
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <div className={SEGMENT_TRACK} role="group" aria-label="统计时间范围">
          {WINDOW_OPTIONS.map((opt) => (
              <button
                  key={opt.value}
                  onClick={() => onTimeWindowChange(opt.value)}
                  disabled={loading}
                  aria-pressed={timeWindow === opt.value}
                  className={segmentItem(timeWindow === opt.value)}
              >
                {opt.label}
              </button>
          ))}
        </div>

        {/*
          归组与刷新两段口径挂在"数据更新于"旁边而不是各卡上：它们限定的是这一整页数字的时间坐标，
          挂到某一张卡上等于宣称只有那张卡按这个规则归组
        */}
        <span className="flex items-center gap-1.5 text-xs text-[#98A2B3]">
          数据更新于 {formatLastUpdated(lastUpdated)}
          <Hint>
            按回复创建时间归组，状态随后续处理更新。缓存最长 30 秒。暂无精确执行耗时指标。
          </Hint>
        </span>

        <Button
            variant="outline"
            size="icon"
            onClick={onRefresh}
            disabled={loading}
            aria-label="刷新统计"
            className="h-8 w-8 rounded-lg border-[#EAECF0] bg-white text-[#667085] hover:border-[#D9DDE7] hover:text-[#101828]"
        >
          <RefreshCw className={cn("h-4 w-4", loading && "motion-safe:animate-spin")} />
        </Button>
      </div>
    </header>
);

// ============================================================================
// KPI Cards
// ============================================================================

type KPICardProps = {
  /** null 表示这一格算不出来（缺分母等），渲染成灰破折号而不是 0 */
  value: string | null;
  label: string;
  change?: KPIChange;
  /** 该指标"向上是不是好事"。涨跌配色 = 变化方向 × 这个值，不能由 delta 自身的符号推导。 */
  upIsGood: boolean;
  icon: ReactNode;
  /** 末行右侧的从属读数，补一个环比说不出来的绝对量；没有对比期时它独占末行 */
  sub?: string;
  /**
   * 图标片默认全蓝，只有真正带"成功"语义的那一格才允许转绿。
   * 一格一色时四个色相互相争，读者会以为蓝色那格和琥珀色那格分属两类东西
   */
  chipTone?: "accent" | "good";
};

const KPICardItem = ({ value, label, change, upIsGood, icon, sub, chipTone = "accent" }: KPICardProps) => {
  const showChange = change !== undefined && change.trend !== "flat";
  const isUp = change?.trend === "up";
  /*
   * 涨跌用的绿/红比状态色深一档：状态色（#12B76A / #F04438）是给条与点定的，
   * 对白底只有 2.6 / 3.8，落到 12px 文字上过不了 4.5 的门。色相一致、明度压深，语义不变
   */
  const changeColor = isUp === upIsGood ? "text-emerald-700" : "text-red-600";

  /*
   * 图标独占左列，标题、数字、涨跌共用右列的同一条左边界。
   * 图标压在标题行里、数字却退回卡片左边缘时，一张卡内会出现两条起始线，
   * 四格并排更明显——这是"图标行下面直接放数字"读起来不协调的根。
   *
   * 高度下限 108、内边距 16、数字行高 32（都比原来紧一档）：这一层在页面上只出现一次，
   * 省下的 12px 是整页四层里最便宜的一刀——三行内容（标题 / 数字 / 环比）一行没少，字号也没动
   */
  return (
      <DashCard className="min-h-[108px] p-4">
        <div className="flex gap-3">
          <span
              className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-[10px]"
              style={{
                backgroundColor: chipTone === "good" ? VIZ_SURFACE.goodSoft : VIZ_SURFACE.accentSoft,
                color: chipTone === "good" ? "#12B76A" : "#4F6EF7"
              }}
          >
            {icon}
          </span>
          <div className="min-w-0 flex-1">
            <p className="truncate text-[13px] leading-5 text-[#667085]">{label}</p>
            {/*
              从属读数贴着数字收尾而不是挤进末行：它补的是这个数字的绝对量，挨着数字才读得成一句。
              末行于是只剩环比，四格都是"标题 / 数字 / 环比"三行。
              数字位数多到与它并排放不下时整行换行（四格同步长高），不截半个词
            */}
            <div className="mt-1 flex flex-wrap items-baseline justify-between gap-x-2">
              <p className="text-[30px] font-semibold leading-8 tracking-[-0.02em] text-[#101828]">
                {value === null ? <span className="text-[#B0B8C4]">—</span> : value}
              </p>
              {sub && <span className="min-w-0 truncate text-xs text-[#98A2B3]">{sub}</span>}
            </div>
            {/* 没有对比期时这行空着不塌：四格的底边由它对齐 */}
            <div className="mt-1 flex min-h-4 items-center text-xs">
              {showChange && (
                  <span className="flex shrink-0 items-center gap-1">
                    {isUp ? (
                        <TrendingUp className={cn("h-3.5 w-3.5", changeColor)} />
                    ) : (
                        <TrendingDown className={cn("h-3.5 w-3.5", changeColor)} />
                    )}
                    <span className={cn("font-medium tabular-nums", changeColor)}>
                      {change.value > 0 ? "+" : ""}
                      {change.value.toFixed(1)}%
                    </span>
                    <span className="text-[#98A2B3]">较上周期</span>
                  </span>
              )}
            </div>
          </div>
        </div>
      </DashCard>
  );
};

const toChange = (deltaPct?: number | null): KPIChange | undefined => {
  if (deltaPct === null || deltaPct === undefined) return undefined;
  if (deltaPct > 0) return { value: deltaPct, trend: "up" };
  if (deltaPct < 0) return { value: deltaPct, trend: "down" };
  return { value: 0, trend: "flat" };
};

/**
 * KPI 带：四格并排，前两格两个引擎共用，后两格换成各自的主体与成功率。
 * 消息数与新建会话不占格——它们是流量卡的标签页，在那里连带趋势一起读。
 */
const KpiBand = ({
                   overview,
                   agentPerformance,
                   workflowPerformance
                 }: {
  overview: DashboardOverview | null;
  agentPerformance: AgentDashboardPerformance | null;
  workflowPerformance: WorkflowDashboardPerformance | null;
}) => {
  const kpis = overview?.kpis;
  const toolSuccess =
      agentPerformance && agentPerformance.tools.total > 0
          ? (agentPerformance.tools.done / agentPerformance.tools.total) * 100
          : null;

  const items: KPICardProps[] = [
    {
      value: formatNumber(kpis?.activeUsers.value),
      label: "活跃用户",
      change: toChange(kpis?.activeUsers.deltaPct),
      upIsGood: true,
      icon: <Users className="h-[18px] w-[18px]" />,
      sub: `累计 ${formatNumber(kpis?.totalUsers.value)} 人`
    },
    {
      value: formatNumber(kpis?.activeSessions.value),
      label: "活跃会话",
      change: toChange(kpis?.activeSessions.deltaPct),
      upIsGood: true,
      icon: <MessageSquare className="h-[18px] w-[18px]" />,
      sub: `新建 ${formatNumber(kpis?.sessions24h.value)} 个`
    },
    agentPerformance
        ? {
          value: formatNumber(agentPerformance.replies.total),
          label: "助手回复",
          // 环比与前两格同一个口径（本周期 - 上周期 ÷ 上周期），上周期没有回复时后端返 null，这格就只剩从属读数
          change: toChange(agentPerformance.replies.deltaPct),
          upIsGood: true,
          icon: <Sparkles className="h-[18px] w-[18px]" />,
          sub: `${formatNumber(agentPerformance.replies.withBlocks)} 条有轨迹`
        }
        : {
          value: formatNumber(kpis?.messages24h.value),
          label: "消息数",
          change: toChange(kpis?.messages24h.deltaPct),
          upIsGood: true,
          icon: <Sparkles className="h-[18px] w-[18px]" />
        },
    agentPerformance
        ? {
          value: formatPercent(toolSuccess) === "-" ? null : formatPercent(toolSuccess),
          label: "工具成功率",
          upIsGood: true,
          // 这一格读的是"成不成"而不是"用了什么工具"，盾牌比扳手更贴那层语义，也与绿色片对得上
          icon: <ShieldCheck className="h-[18px] w-[18px]" />,
          chipTone: "good",
          // 没有轨迹时不能写「失败 0 次」：那是在断言零失败，而实际只是没记录可查
          sub:
              agentPerformance.tools.total > 0
                  ? `失败 ${formatNumber(agentPerformance.tools.failed)} 次`
                  : "暂无工具轨迹"
        }
        : {
          value: workflowPerformance?.sampleCount
              ? formatPercent(workflowPerformance.successRate)
              : null,
          label: "链路成功率",
          upIsGood: true,
          icon: <Wrench className="h-[18px] w-[18px]" />,
          sub: workflowPerformance?.sampleCount
              ? `错误率 ${formatPercent(workflowPerformance.errorRate)}`
              : "暂无追踪样本"
        }
  ];

  return (
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {items.map((item) => (
            <KPICardItem key={item.label} {...item} />
        ))}
      </div>
  );
};

// ============================================================================
// Traffic Overview Section
// ============================================================================

/**
 * 序列 → 图表色调。「上周期」在全页只认一个 tone，具体画成底槽还是折线由图表按横轴坐标制自己定：
 * 按桶计数的流量卡给底槽，逐时刻取值的响应时间给折线。这里只管别让它跟主序列同色。
 * 空序列直接丢掉：它画不出东西，却会在图例里留一个说不出所以然的条目。
 * currentKind 只作用在当前周期上：参照物无论主序列画成什么，都还是那件压低了的背景。
 */
const mapSeries = (
    trend: DashboardTrends | null,
    tone: TrendSeries["tone"],
    currentKind: TrendSeries["kind"] = "line"
): TrendSeries[] =>
    (trend?.series ?? [])
        .filter((item) => item.data.length > 0)
        .map((item) =>
            item.name === "上周期"
                ? { name: item.name, data: item.data, tone: "reference" as const }
                : { name: item.name, data: item.data, tone, kind: currentKind }
        );

/**
 * 消息流量趋势：全页唯一的主图，三个标签页换的是同一张图的度量。
 * Agent 分支下发「当前周期 + 上周期」两条序列，Workflow 只有一条，两种形状都要能画。
 */
const TrafficCard = ({
                       trends,
                       overview,
                       timeWindow,
                       loading,
                       className
                     }: {
  trends: DashboardTrendBundle;
  overview: DashboardOverview | null;
  timeWindow: DashboardTimeWindow;
  loading?: boolean;
  className?: string;
}) => {
  const [metric, setMetric] = useState<TrafficMetric>("messages");
  const kpis = overview?.kpis;

  const current = {
    messages: { trend: trends.messages, kpi: kpis?.messages24h, unit: "条", caption: "当前消息总数" },
    sessions: { trend: trends.sessions, kpi: kpis?.sessions24h, unit: "个", caption: "当前新建会话数" },
    activeUsers: { trend: trends.activeUsers, kpi: kpis?.activeUsers, unit: "人", caption: "当前活跃用户数" }
  }[metric];

  /*
   * 当前周期画柱、上周期仍是线：这三个度量（消息数/新建会话数/活跃用户数）本来就是按桶计数的，
   * 一根柱就是那一天（或那一小时）发生的量，高度可以直接互相比长短；折线在两点之间画出的那段斜坡
   * 则暗示中间有过渡值，而这里并没有。参照物留在线上，两者形状不同，叠在一起也不会看成同一族
   */
  const series = useMemo(() => mapSeries(current.trend, "primary", "bar"), [current.trend]);

  const change = toChange(current.kpi?.deltaPct);

  return (
      /*
       * 下限 268 只是空态兜底，与右邻的健康卡取同一个数；这张卡实际多高由内容决定（288），
       * 第二层的高度就是它。原来写死 300 时，左边把图从 208 压到 140 一点没省下来——
       * 下限比内容还高时，压内容是压不动布局的
       */
      <DashCard className={cn("flex min-h-[268px] flex-col", className)}>
        <CardHead
            title="消息流量趋势"
            hint="消息包含用户与助手双方记录。浅色底槽为上一周期对比，仅供参照。"
            action={
              <div className={SEGMENT_TRACK} role="group" aria-label="流量度量">
                {TRAFFIC_TABS.map((tab) => (
                    <button
                        key={tab.value}
                        onClick={() => setMetric(tab.value)}
                        aria-pressed={metric === tab.value}
                        className={segmentItem(metric === tab.value)}
                    >
                      {tab.label}
                    </button>
                ))}
              </div>
            }
        />

        {/* 标题、读数、图三段之间只留 6~8px：这一列是主图，间距一放大就把图挤出首屏 */}
        <div className="mb-2 mt-1.5 flex flex-wrap items-end justify-between gap-x-4 gap-y-1.5">
          <div className="min-w-0">
            {/*
              度量名压到数字上方单独一行：大数字自己不说自己是什么，而它跟在数字后面时，
              要和单位、环比排成一串，读者得横着扫过三段小字才知道这个数是什么
            */}
            <p className="truncate text-xs text-[#667085]">{current.caption}</p>
            <div className="mt-0.5 flex items-end gap-2">
              <span className="text-[30px] font-semibold leading-9 tracking-[-0.02em] text-[#101828]">
                {formatNumber(current.kpi?.value)}
              </span>
              <span className="pb-1.5 text-xs text-[#98A2B3]">{current.unit}</span>
              {change && change.trend !== "flat" && (
                  /* 环比给药丸底：它是这行里唯一带方向的读数，纯文字放在 30px 数字旁边会被压没 */
                  <span
                      className={cn(
                          "mb-1 inline-flex shrink-0 items-center gap-0.5 rounded-full px-1.5 py-0.5 text-xs font-medium tabular-nums",
                          change.trend === "up"
                              ? "bg-emerald-50 text-emerald-700"
                              : "bg-red-50 text-red-600"
                      )}
                  >
                    {change.trend === "up" ? (
                        <TrendingUp className="h-3 w-3" />
                    ) : (
                        <TrendingDown className="h-3 w-3" />
                    )}
                    {change.value > 0 ? "+" : ""}
                    {change.value.toFixed(1)}%
                  </span>
              )}
            </div>
          </div>
          {/*
            图例收进这一行的右端：读数行本来就有空位，而图内图例要在图上方再吃掉一行高。
            窗口与粒度不再重复——页头的分段器已经写了窗口，横轴自己就带着粒度
          */}
          <ChartLegend series={series} className="pb-1.5" />
        </div>

        {/* 空态与加载态都占住 140px，卡片高度不随数据有无跳动 */}
        {loading ? (
            <LoadingBlock className="h-[140px]" />
        ) : series.length === 0 || current.kpi?.value === 0 ? (
            <div className="flex h-[140px] items-center justify-center text-sm text-[#98A2B3]">
              暂无流量数据
            </div>
        ) : (
            /*
             * 140 是"整页不下拉"给这张图的配额，且这张卡就是第二层的高度本身（右邻的健康卡比它矮）。
             * 上下留白吃掉 40，绘图区还剩 100：默认窗口 7 天只有 8 根柱，一格 80 以上，
             * 柱高差读得出来。24h 的 24 个桶在这个高度会比 208 时矮——那是为一屏付的价。
             * 不再开面积：柱本身就是填充，两层叠着会分不清哪块色是读数
             */
            <SimpleLineChart
                series={series}
                xAxisMode={timeWindow === "24h" ? "hour" : "date"}
                height={140}
                theme="light"
                yAxisTickCount={4}
                showLegend={false}
            />
        )}
      </DashCard>
  );
};

// ============================================================================
// Trend Charts
// ============================================================================


const mapQualitySeries = (trend: DashboardTrends | null): TrendSeries[] => {
  if (!trend?.series?.length) return [];
  return trend.series.map((s) => ({
    name: s.name,
    data: s.data,
    tone: s.name.includes("错误") ? "danger" : "secondary"
  }));
};

/**
 * 趋势区的单张图。三张同规格并排，靠统一的内边距、轴样式与图高互相可比，
 * 而不是各自撑成不同高度——所以口径走标题旁的 ⓘ，不再在标题下压一行小字把图挤矮。
 */
const TrendChartItem = ({
                          title,
                          series,
                          thresholds = [],
                          xAxisMode,
                          yAxisType = "number",
                          hint,
                          loading
                        }: {
  title: string;
  series: TrendSeries[];
  thresholds?: ChartThreshold[];
  xAxisMode: ChartXAxisMode;
  yAxisType?: ChartYAxisType;
  hint?: string;
  loading?: boolean;
}) => {
  if (loading) {
    return (
        <DashCard className="flex min-h-[196px] flex-col">
          <LoadingBlock className="h-4 w-24" />
          <LoadingBlock className="mt-auto h-[118px] w-full" />
        </DashCard>
    );
  }

  return (
      <DashCard className="flex min-h-[196px] flex-col">
        <div className="flex items-center gap-1.5 text-[13px] font-medium text-[#101828]">
          {title}
          {hint && <Hint>{hint}</Hint>}
        </div>
        {/*
          图表贴底且不锁死高度：图例在组件内部、位于 svg 之上，外层写死高度会让多序列图溢出，
          三张图的 x 轴基线就此错开。改成内容撑高 + mt-auto，同一行的卡片被拉平后基线自然对齐。
        */}
        <div className="mt-auto pt-3">
          <SimpleLineChart
              series={series}
              xAxisMode={xAxisMode}
              yAxisType={yAxisType}
              thresholds={thresholds}
              height={118}
              theme="light"
              yAxisTickCount={3}
          />
        </div>
      </DashCard>
  );
};

/**
 * 趋势区只服务 Workflow 分支。Agent 分支的三张趋势图（新建会话/工具调用/回复状态）本轮整段删除：
 * 它们读的是 KPI 带与流量卡已经给过的同一批量，同一页里对两次相同的数只会拉长下拉距离。
 */
const TrendSection = ({
                        trends,
                        timeWindow,
                        loading
                      }: {
  trends: DashboardTrendBundle;
  timeWindow: DashboardTimeWindow;
  loading?: boolean;
}) => {
  const xAxisMode = timeWindow === "24h" ? "hour" : "date";

  // 响应时间沿用改造前的琥珀：它只出现在 Workflow 分支，这轮动的是排布不是那条线的配色
  const latencySeries = useMemo(() => mapSeries(trends.latency, "warning"), [trends.latency]);
  const qualitySeries = useMemo(() => mapQualitySeries(trends.quality), [trends.quality]);

  /*
   * 段头不套卡：它是这一段的标题而不是一块内容，给它描边会让下面两张图读成"卡里的卡"。
   * 层级由字号与间距拉开
   */
  return (
      <section className="space-y-4">
        <div>
          <h2 className="text-base font-semibold leading-6 text-[#101828]">趋势分析</h2>
          <p className="mt-1 text-[13px] text-[#667085]">观察响应时间与质量指标随时间的变化</p>
        </div>
        {/* 小倍数图：共用同一条 x 轴与同一套编码，横向扫比纵向翻页更好比 */}
        <div className="grid gap-4 lg:grid-cols-2">
          <TrendChartItem
              title="响应时间趋势"
              series={latencySeries}
              xAxisMode={xAxisMode}
              yAxisType="duration"
              hint="单位：毫秒。"
              loading={loading}
              thresholds={[
                { value: DASHBOARD_THRESHOLDS.latency.good, label: "良好 ≤10s", tone: "info" },
                { value: DASHBOARD_THRESHOLDS.latency.warning, label: "警告 >15s", tone: "critical" }
              ]}
          />
          <TrendChartItem
              title="质量趋势"
              series={qualitySeries}
              xAxisMode={xAxisMode}
              yAxisType="percent"
              hint="单位：%。"
              loading={loading}
              thresholds={[
                { value: DASHBOARD_THRESHOLDS.errorRate.warning, label: "错误警告", tone: "warning" },
                { value: DASHBOARD_THRESHOLDS.noDocRate.warning, label: "无知识警告", tone: "critical" }
              ]}
          />
        </div>
      </section>
  );
};

// ============================================================================
// AI Performance
// ============================================================================

const STATUS_COLOR: Record<MetricTone, string> = {
  good: "#10B981",
  warning: "#F59E0B",
  bad: "#EF4444"
};

const QUALITY_SNAPSHOT_META = [
  { label: "错误率", toneClass: "bg-red-500", valueClass: "text-red-600", target: "阈值 ≤5%" },
  { label: "无知识率", toneClass: "bg-amber-500", valueClass: "text-amber-600", target: "阈值 ≤20%" },
  {
    label: "慢响应率（>20s）",
    toneClass: "bg-sky-500",
    valueClass: "text-sky-600",
    target: "阈值 ≤20%"
  }
] as const;

const MetricRow = ({
                     icon: Icon,
                     label,
                     value,
                     status
                   }: {
  icon: ComponentType<{ className?: string }>;
  label: string;
  value: string;
  status: MetricTone;
}) => (
    <div className="flex items-center justify-between py-2.5">
    <span className="flex items-center gap-2.5 text-sm text-slate-600">
      <Icon className="h-4 w-4 text-slate-400" />
      {label}
    </span>
      <span className="text-sm font-semibold tabular-nums" style={{ color: value === "—" ? "#94a3b8" : STATUS_COLOR[status] }}>
      {value}
    </span>
    </div>
);

const QualitySnapshot = ({
                           performance,
                           windowLabel
                         }: {
  performance: WorkflowDashboardPerformance | null;
  windowLabel: string;
}) => {
  const items = [
    { ...QUALITY_SNAPSHOT_META[0], value: performance?.errorRate },
    { ...QUALITY_SNAPSHOT_META[1], value: performance?.noDocRate },
    { ...QUALITY_SNAPSHOT_META[2], value: performance?.slowRate }
  ];

  return (
      <div className="mt-4 rounded-xl border border-slate-100 bg-slate-50 p-3.5">
        <div className="mb-3 flex items-center justify-between">
          <p className="text-xs font-medium text-slate-600">质量快照（柱状）</p>
          <span className="text-[11px] text-slate-400">{windowLabel}</span>
        </div>
        <div className="grid grid-cols-3 gap-2.5">
          {items.map((item) => {
            const hasValue = item.value !== null && item.value !== undefined;
            const normalized = clampPercent(item.value);
            const barHeight = `${Math.max(normalized, hasValue ? 4 : 0)}%`;
            return (
                <div key={item.label} className="space-y-1.5">
                  <div className="flex h-24 items-end rounded-md border border-slate-200 bg-white p-1.5">
                    <div
                        className={cn(
                            "w-full rounded-sm transition-[height] duration-500",
                            item.toneClass
                        )}
                        style={{ height: barHeight }}
                    />
                  </div>
                  <div
                      className={cn("text-center text-xs font-semibold tabular-nums", item.valueClass)}
                  >
                    {formatPercent(item.value)}
                  </div>
                  <div className="text-center text-[11px] text-slate-500">{item.label}</div>
                  <div className="text-center text-[10px] text-slate-400">{item.target}</div>
                </div>
            );
          })}
        </div>
      </div>
  );
};

const EfficiencySnapshot = ({
                              overview,
                              windowLabel
                            }: {
  overview: DashboardOverview | null;
  windowLabel: string;
}) => {
  const activeUsers = overview?.kpis.activeUsers.value ?? 0;
  const sessions = overview?.kpis.activeSessions.value ?? 0;
  const messages = overview?.kpis.messages24h.value ?? 0;

  const metrics = [
    { label: "人均会话", value: activeUsers > 0 ? sessions / activeUsers : null, unit: "次/人" },
    { label: "单会话消息", value: sessions > 0 ? messages / sessions : null, unit: "条/会话" },
    { label: "人均消息", value: activeUsers > 0 ? messages / activeUsers : null, unit: "条/人" }
  ];

  return (
      <div className="mt-4 rounded-xl border border-slate-100 bg-slate-50 p-3.5">
        <div className="mb-1.5 flex items-center justify-between">
          <p className="text-xs font-medium text-slate-600">运营效率</p>
          <span className="text-[11px] text-slate-400">{windowLabel}</span>
        </div>
        <div className="divide-y divide-slate-100">
          {metrics.map((metric) => {
            const valueText =
                metric.value === null ? "-" : `${formatRatio(metric.value)} ${metric.unit}`;
            return (
                <div key={metric.label} className="flex items-center justify-between py-2">
                  <span className="text-xs text-slate-500">{metric.label}</span>
                  <span className="text-sm font-semibold tabular-nums text-slate-700">{valueText}</span>
                </div>
            );
          })}
        </div>
      </div>
  );
};

const AIPerformanceCard = ({
                             performance,
                             metricStatus,
                             health,
                             overview,
                             timeWindowLabel
                           }: {
  performance: WorkflowDashboardPerformance | null;
  metricStatus: MetricStatusView;
  health: HealthStatus;
  overview: DashboardOverview | null;
  timeWindowLabel: string;
}) => {
  const healthCfg = HEALTH_CONFIG[health];
  const hasSamples = (performance?.sampleCount ?? 0) > 0;
  const successRate = hasSamples ? performance?.successRate ?? 0 : 0;
  const ringColor = !hasSamples ? "#94a3b8" : successRate >= 95 ? "#10B981" : successRate >= 85 ? "#F59E0B" : "#EF4444";

  const p95LatencyStatus = getLatencyStatus(performance?.p95LatencyMs);

  const radius = 50;
  const circumference = 2 * Math.PI * radius;
  const progress = (Math.min(successRate, 100) / 100) * circumference;

  return (
      <DashCard>
        <div className="mb-4 flex items-center justify-between">
          <h3 className="text-sm font-semibold text-slate-700">AI 性能</h3>
          <span
              className={cn("rounded-full px-2.5 py-1 text-xs font-medium", healthCfg.bg, healthCfg.text)}
          >
          {healthCfg.label}
        </span>
        </div>

        <div className="flex justify-center py-3">
          <div className="relative">
            <svg className="-rotate-90" viewBox="0 0 120 120" width="120" height="120">
              <circle cx="60" cy="60" r={radius} fill="none" stroke="#F1F5F9" strokeWidth={8} />
              <circle
                  cx="60"
                  cy="60"
                  r={radius}
                  fill="none"
                  stroke={ringColor}
                  strokeWidth={8}
                  strokeLinecap="round"
                  strokeDasharray={circumference}
                  strokeDashoffset={circumference - progress}
                  className="transition-all duration-700 ease-out"
              />
            </svg>
            <div className="absolute inset-0 flex flex-col items-center justify-center">
            <span className="text-2xl font-bold" style={{ color: ringColor }}>
              {hasSamples ? formatPercent(successRate) : "—"}
            </span>
              <span className="mt-0.5 text-xs text-slate-400">{hasSamples ? "成功率" : "暂无追踪样本"}</span>
            </div>
          </div>
        </div>

        <div className="divide-y divide-slate-100">
          <MetricRow
              icon={Timer}
              label="平均响应"
              value={performance?.avgLatencyMs ? formatDuration(performance.avgLatencyMs) : "—"}
              status={metricStatus.latency}
          />
          <MetricRow
              icon={Clock}
              label="P95 响应"
              value={performance?.p95LatencyMs ? formatDuration(performance.p95LatencyMs) : "—"}
              status={p95LatencyStatus}
          />
        </div>

        <QualitySnapshot performance={hasSamples ? performance : null} windowLabel={timeWindowLabel} />
        <EfficiencySnapshot overview={overview} windowLabel={timeWindowLabel} />
      </DashCard>
  );
};

// ============================================================================
// Insights
// ============================================================================

const TYPE_LABEL: Record<InsightCardData["type"], string> = {
  anomaly: "异常",
  trend: "趋势",
  recommendation: "建议"
};

const TYPE_ICON: Record<InsightCardData["type"], typeof Info> = {
  anomaly: AlertCircle,
  trend: Info,
  recommendation: Lightbulb
};

const TYPE_STYLE: Record<InsightCardData["type"], string> = {
  anomaly: "bg-red-50 text-red-600",
  trend: "bg-blue-50 text-blue-600",
  recommendation: "bg-amber-50 text-amber-600"
};

const InsightCard = ({ item }: { item: InsightCardData }) => {
  const Icon = TYPE_ICON[item.type];

  return (
      <div className="rounded-xl bg-slate-50 p-3.5">
        <div className="mb-2 flex items-center justify-between">
        <span
            className={cn(
                "inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-xs font-medium",
                TYPE_STYLE[item.type]
            )}
        >
          <Icon className="h-3.5 w-3.5" />
          {TYPE_LABEL[item.type]}
        </span>
          <span className="text-[11px] text-slate-400">{item.timestamp}</span>
        </div>
        <p className="text-sm font-semibold text-slate-800">{item.title}</p>
        <p className="mt-1 text-xs text-slate-500">
          {item.metric}: {item.change}
        </p>
        <p className="mt-0.5 text-xs text-slate-400">归因：{item.context}</p>
        {item.action && (
            <p className="mt-1 text-xs font-medium text-slate-600">建议：{item.action}</p>
        )}
      </div>
  );
};

const buildInsightList = (
    performance: WorkflowDashboardPerformance | null,
    timeWindowLabel: string,
    timestamp: number | null,
    overview: DashboardOverview | null
): InsightCardData[] => {
  const t = formatTime(timestamp);
  const windowMessages = overview?.kpis?.messages24h?.value;

  if (!performance || !windowMessages) {
    return [
      {
        type: "trend",
        severity: "info",
        title: "暂无会话数据",
        metric: "Dashboard",
        change: timeWindowLabel,
        context: "当前窗口内暂无消息记录，各项指标将在会话产生后自动更新",
        timestamp: t
      }
    ];
  }

  if (!performance.sampleCount) {
    return [{ type: "trend", severity: "info", title: "暂无追踪样本", metric: "运行质量", change: timeWindowLabel,
      context: "已有消息记录，但该窗口内没有可用的链路追踪样本，暂不评估运行质量", timestamp: t }];
  }

  const items: InsightCardData[] = [];

  if (performance.errorRate > 5 || performance.successRate < 95) {
    items.push({
      type: "anomaly",
      severity: "critical",
      title: "链路稳定性触发告警",
      metric: "成功率/错误率",
      change: `${performance.successRate.toFixed(1)}% / ${performance.errorRate.toFixed(1)}%`,
      context: "成功率低于 95% 或错误率高于 5%",
      action: "优先查看失败请求分布与超时节点",
      timestamp: t
    });
  } else {
    items.push({
      type: "trend",
      severity: "info",
      title: "系统可用性稳定",
      metric: "成功率",
      change: `${performance.successRate.toFixed(1)}%`,
      context: "当前窗口整体可用性处于健康区间",
      timestamp: t
    });
  }

  if (performance.noDocRate > 20) {
    items.push({
      type: "recommendation",
      severity: "warning",
      title: "召回质量需优化",
      metric: "无知识率",
      change: `${performance.noDocRate.toFixed(1)}%`,
      context: "无知识率超过 20%，用户命中体验存在风险",
      action: "优化索引覆盖率与检索重排策略",
      timestamp: t
    });
  }

  if (performance.avgLatencyMs > 15000) {
    items.push({
      type: "recommendation",
      severity: "warning",
      title: "响应性能需要关注",
      metric: "平均响应时间",
      change: `${(performance.avgLatencyMs / 1000).toFixed(2)}s`,
      context: "平均延迟高于 15s，影响交互体验",
      action: "排查慢节点与模型并发配置",
      timestamp: t
    });
  }

  if (items.length < 3) {
    items.push({
      type: "recommendation",
      severity: "info",
      title: "继续保持当前策略",
      metric: "运营状态",
      change: timeWindowLabel,
      context: "当前窗口内未发现显著异常趋势",
      timestamp: t
    });
  }

  return items.slice(0, 3);
};

const InsightSection = ({
                          performance,
                          overview,
                          timeWindowLabel,
                          timestamp,
                          className
                        }: {
  performance: WorkflowDashboardPerformance | null;
  overview: DashboardOverview | null;
  timeWindowLabel: string;
  timestamp: number | null;
  className?: string;
}) => {
  const items = useMemo(
      () => buildInsightList(performance, timeWindowLabel, timestamp, overview),
      [performance, timeWindowLabel, timestamp, overview]
  );
  const contentRef = useRef<HTMLDivElement | null>(null);
  const [isScrollable, setIsScrollable] = useState(false);
  const [showScrollbar, setShowScrollbar] = useState(false);
  const hideScrollbarTimerRef = useRef<number | null>(null);

  const handleScroll = useCallback(() => {
    if (!isScrollable) return;
    setShowScrollbar(true);

    if (hideScrollbarTimerRef.current !== null) {
      window.clearTimeout(hideScrollbarTimerRef.current);
    }

    hideScrollbarTimerRef.current = window.setTimeout(() => {
      setShowScrollbar(false);
      hideScrollbarTimerRef.current = null;
    }, 500);
  }, [isScrollable]);

  useEffect(() => {
    const el = contentRef.current;
    if (!el) return;

    const updateScrollable = () => {
      setIsScrollable((prev) => {
        const next = el.scrollHeight > el.clientHeight + 1;
        return prev === next ? prev : next;
      });
    };

    updateScrollable();
    const resizeObserver = new ResizeObserver(updateScrollable);
    resizeObserver.observe(el);
    window.addEventListener("resize", updateScrollable);

    return () => {
      resizeObserver.disconnect();
      window.removeEventListener("resize", updateScrollable);
    };
  }, [items]);

  useEffect(
      () => () => {
        if (hideScrollbarTimerRef.current !== null) {
          window.clearTimeout(hideScrollbarTimerRef.current);
          hideScrollbarTimerRef.current = null;
        }
      },
      []
  );

  useEffect(() => {
    if (isScrollable) return;
    setShowScrollbar(false);

    if (hideScrollbarTimerRef.current !== null) {
      window.clearTimeout(hideScrollbarTimerRef.current);
      hideScrollbarTimerRef.current = null;
    }
  }, [isScrollable]);

  return (
      <DashCard className={cn("flex flex-col", className)}>
        <CardTitle>运营洞察</CardTitle>
        <div
            ref={contentRef}
            onScroll={handleScroll}
            className={cn(
                "flex-1 space-y-3",
                isScrollable
                    ? cn("overflow-y-auto pr-1 insight-scroll-shell", showScrollbar && "is-scrollbar-visible")
                    : "overflow-y-hidden"
            )}
        >
          {items.map((item, i) => (
              <InsightCard key={`${item.title}-${i}`} item={item} />
          ))}
        </div>
      </DashCard>
  );
};

// ============================================================================
// Main Page
// ============================================================================

export function DashboardPage() {
  const {
    engine,
    timeWindow,
    setTimeWindow,
    loading,
    error,
    lastUpdated,
    overview,
    performance,
    trends,
    refresh
  } = useDashboardData();

  const workflowPerformance = performance?.engine === "workflow" ? performance : null;
  const agentPerformance = performance?.engine === "agent" ? performance : null;
  const { health, metricStatus } = useHealthStatus(workflowPerformance, overview);
  // 四张 Agent 卡各自的弹框都要复述这个范围，统一从页头这一处取，两边不会说出两个窗口
  const windowLabel = WINDOW_LABEL_MAP[timeWindow];

  useEffect(() => {
    if (error) toast.error(error);
  }, [error]);

  return (
      <div className="admin-page dashboard-page">
        <DashboardHeader
            engine={engine}
            timeWindow={timeWindow}
            lastUpdated={lastUpdated}
            loading={loading}
            onRefresh={() => void refresh()}
            onTimeWindowChange={setTimeWindow}
        />

        {error && <div role="alert" className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
          <span>{error}。{overview ? "已显示可用的概览，趋势可稍后重试。" : "统计暂不可用，请刷新重试。"}</span>
          <Button variant="ghost" size="sm" onClick={() => void refresh()} disabled={loading}>重新加载</Button>
        </div>}

        {loading && !overview ? <div role="status" aria-label="正在加载统计" className="space-y-4">
          <LoadingBlock className="h-[128px]" /><LoadingBlock className="h-[300px]" />
        </div> : overview && engine ? <>
        {/* 重新取数时保留上一次的渲染，整体降透明度表示"在刷新"，避免整页塌成骨架造成布局跳动 */}
        <div className={cn("space-y-3", loading && "opacity-60 transition-opacity")}>
        <KpiBand
            overview={overview}
            agentPerformance={agentPerformance}
            workflowPerformance={workflowPerformance}
        />
        {agentPerformance ? <>
        {/*
          第二层分栏：两张卡都是栅格的直接子元素，靠 grid 默认的 stretch 把矮的那张拉到同高，
          上下沿才能同时对齐。中间再包一层 div 会把拉伸吃掉，卡片重新变成各撑各的高度
        */}
        <div className={cn("grid gap-x-4 gap-y-3", SPLIT_COLS)}>
          <TrafficCard
              trends={trends}
              overview={overview}
              timeWindow={timeWindow}
              loading={loading}
          />
          <AgentRunHealth data={agentPerformance} windowLabel={windowLabel} />
        </div>

        {/*
          第三层沿用同一套分栏：两层的列边缘落在同一条线上，页面才只有一套栅格。
          右列是两张卡叠一列，本身撑不到左列那么高，所以整列拉满再让两张卡各占一半剩余高度，
          否则左卡的底沿会孤零零地掉在右列下方几十像素处
        */}
        <div className={cn("grid gap-x-4 gap-y-3", SPLIT_COLS)}>
          <div className="min-w-0">
            <AgentToolAnalysis className="xl:h-full" data={agentPerformance} windowLabel={windowLabel} />
          </div>
          {/*
            右列这道 12px 的缝直接进第三层的高度：这一层的高 = max(左边工具卡, 确认 + 缝 + 记忆)，
            而右列一路都是它更高。缝收一档，整层就矮一档
          */}
          <div className="flex min-w-0 flex-col gap-3">
            <AgentConfirmations className="xl:flex-1" data={agentPerformance} windowLabel={windowLabel} />
            <AgentMemoryContext className="xl:flex-1" data={agentPerformance} windowLabel={windowLabel} />
          </div>
        </div>
        </> : <>
        {/*
          Workflow 分支不套用上面那套等高分栏：性能卡比流量卡高一倍有余，
          按等高拉齐会在流量图下方空出 400 多像素。它仍走同一套 12 列，只是左列继续堆趋势区
        */}
        <div className="grid gap-4 xl:grid-cols-12 xl:items-start">
          <div className="min-w-0 space-y-4 xl:col-span-8">
            <TrafficCard
                trends={trends}
                overview={overview}
                timeWindow={timeWindow}
                loading={loading}
            />
            <TrendSection trends={trends} timeWindow={timeWindow} loading={loading} />
          </div>
          <div className="min-w-0 xl:col-span-4">
            <AIPerformanceCard
                performance={workflowPerformance}
                metricStatus={metricStatus}
                health={health}
                overview={overview}
                timeWindowLabel={WINDOW_LABEL_MAP[timeWindow]}
            />
          </div>
        </div>

        <InsightSection
            performance={workflowPerformance}
            overview={overview}
            timeWindowLabel={WINDOW_LABEL_MAP[timeWindow]}
            timestamp={lastUpdated}
        />
        </>}
        </div>
        {/* 页尾贴着最后一段走：它是这一页的限定语，不是新的一段内容，段间距会让它读成孤立的一条 */}
        <p className="!mt-2 text-xs text-[#98A2B3]">
          仅统计当前 {engine === "agent" ? "Agent" : "Workflow"} 引擎产生的记录{engine === "workflow" && "；消息含用户与助手记录，活跃会话消息均值 = 窗口内消息数 ÷ 窗口内有消息的会话数"}。
        </p>
        </> : null}
      </div>
  );
}
