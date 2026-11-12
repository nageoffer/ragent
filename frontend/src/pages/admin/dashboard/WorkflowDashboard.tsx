/**
 * Workflow 分支的整页正文：KPI 带 + 流量/趋势/洞察左列 + AI 性能右列。
 * 排布与 Agent 分支零重叠——这里全走各自组件上的 Tailwind 视口断点，
 * globals.css 里那套容器查询与纵向余量分配挂在 dashboard-page--agent 下，一条也落不到这一页
 */
import { useMemo, type ComponentType } from "react";
import { AlertCircle, Clock, Info, Lightbulb, Sparkles, Timer, Wrench } from "lucide-react";

import { DashCard, Hint } from "@/components/admin/DashboardCard";
import {
  SimpleLineChart,
  type ChartThreshold,
  type ChartXAxisMode,
  type ChartYAxisType,
  type TrendSeries
} from "@/components/admin/SimpleLineChart";
import { cn } from "@/lib/utils";
import type {
  DashboardOverview,
  DashboardTrends,
  WorkflowDashboardPerformance
} from "@/services/dashboardService";

import {
  KpiBand,
  LoadingBlock,
  TrafficCard,
  WINDOW_LABEL_MAP,
  baseKpiItems,
  formatNumber,
  formatPercent,
  mapSeries,
  toChange,
  type DashboardTimeWindow,
  type DashboardTrendBundle,
  type KPICardProps
} from "./DashboardCommon";


// ==========================================================================
// Types
// ==========================================================================

type HealthStatus = "healthy" | "attention" | "critical" | "unknown";

type MetricTone = "good" | "warning" | "bad";

type MetricStatusView = {
  success: MetricTone;
  latency: MetricTone;
  error: MetricTone;
  noDoc: MetricTone;
};

type InsightCardData = {
  type: "anomaly" | "trend" | "recommendation";
  severity: "info" | "warning" | "critical";
  title: string;
  metric: string;
  change: string;
  context: string;
  action?: string;
};

// ==========================================================================
// Constants
// ==========================================================================

const DASHBOARD_THRESHOLDS = {
  latency: { good: 10000, warning: 15000 },
  successRate: { good: 99, warning: 95 },
  errorRate: { good: 1, warning: 5 },
  noDocRate: { good: 10, warning: 30 }
} as const;

// ==========================================================================
// Utils
// ==========================================================================

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

const formatTime = (timestamp: number | null) => {
  if (!timestamp) return "-";
  return new Date(timestamp).toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  });
};

const formatDuration = (value?: number | null) => {
  if (value === null || value === undefined) return "-";
  if (value < 1000) return `${Math.round(value)}ms`;
  return `${(value / 1000).toFixed(2)}s`;
};

const clampPercent = (value?: number | null) => {
  if (value === null || value === undefined || Number.isNaN(value)) return 0;
  return Math.max(0, Math.min(100, value));
};

const formatRatio = (value?: number | null) => {
  if (value === null || value === undefined || !Number.isFinite(value)) return "-";
  return value.toFixed(2);
};

// ==========================================================================
// Hooks
// ==========================================================================

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

// ==========================================================================
// KPI Cards
// ==========================================================================

/**
 * 后两格：消息数与链路成功率。前两格与 Agent 分支同源，从 baseKpiItems 接过来
 */
const workflowKpiItems = (
    overview: DashboardOverview | null,
    performance: WorkflowDashboardPerformance | null
): KPICardProps[] => [
  ...baseKpiItems(overview),
  {
    value: formatNumber(overview?.kpis.messages24h.value),
    label: "消息数",
    change: toChange(overview?.kpis.messages24h.deltaPct),
    upIsGood: true,
    icon: <Sparkles className="h-[18px] w-[18px]" />
  },
  {
    value: performance?.sampleCount ? formatPercent(performance.successRate) : null,
    label: "链路成功率",
    upIsGood: true,
    icon: <Wrench className="h-[18px] w-[18px]" />,
    sub: performance?.sampleCount ? `错误率 ${formatPercent(performance.errorRate)}` : "暂无追踪样本"
  }
];

// ==========================================================================
// Trend Charts
// ==========================================================================

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
          三张图的 x 轴基线就此错开。所以由内容撑高 + mt-auto，同一行的卡片被拉平后基线自然对齐。
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

// ==========================================================================
// AI Performance
// ==========================================================================

const HEALTH_CONFIG: Record<HealthStatus, { bg: string; text: string; label: string }> = {
  healthy: { bg: "bg-emerald-100", text: "text-emerald-700", label: "运行正常" },
  attention: { bg: "bg-amber-100", text: "text-amber-700", label: "需要关注" },
  critical: { bg: "bg-red-100", text: "text-red-700", label: "风险偏高" },
  unknown: { bg: "bg-slate-100", text: "text-slate-500", label: "暂无数据" }
};

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
                             timeWindowLabel,
                             className
                           }: {
  performance: WorkflowDashboardPerformance | null;
  metricStatus: MetricStatusView;
  health: HealthStatus;
  overview: DashboardOverview | null;
  timeWindowLabel: string;
  className?: string;
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
      <DashCard className={cn("flex flex-col", className)}>
        <div className="mb-4 flex items-center justify-between">
          <h3 className="text-sm font-semibold text-slate-700">AI 性能</h3>
          <span
              className={cn("rounded-full px-2.5 py-1 text-xs font-medium", healthCfg.bg, healthCfg.text)}
          >
          {healthCfg.label}
        </span>
        </div>

        {/*
          环区吃掉整列拉齐后多出来的高：洞察卡进左列后左列更高，这一列被 stretch 拉长，
          余量落在这里让环重新居中，堆在卡底就是一块说不出所以然的空白
        */}
        <div className="flex flex-1 items-center justify-center py-3">
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

// ==========================================================================
// Insights
// ==========================================================================

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

/**
 * 洞察条横着排，所以内容压到四行以内：类型片与标题同行，取数时刻挪进卡头。
 * 三条给的本来就是同一个时刻，逐条重复三遍等于在同一件事上花三份高度
 */
const InsightCard = ({ item }: { item: InsightCardData }) => {
  const Icon = TYPE_ICON[item.type];

  return (
      <div className="rounded-xl bg-slate-50 p-3">
        <div className="flex items-center gap-2">
        <span
            className={cn(
                "inline-flex shrink-0 items-center gap-1 rounded-md px-2 py-0.5 text-xs font-medium",
                TYPE_STYLE[item.type]
            )}
        >
          <Icon className="h-3.5 w-3.5" />
          {TYPE_LABEL[item.type]}
        </span>
          <p className="min-w-0 truncate text-sm font-semibold text-slate-800">{item.title}</p>
        </div>
        <p className="mt-1.5 text-xs text-slate-500">
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
    overview: DashboardOverview | null
): InsightCardData[] => {
  const windowMessages = overview?.kpis?.messages24h?.value;

  if (!performance || !windowMessages) {
    return [
      {
        type: "trend",
        severity: "info",
        title: "暂无会话数据",
        metric: "Dashboard",
        change: timeWindowLabel,
        context: "当前窗口内暂无消息记录，各项指标将在会话产生后自动更新"
      }
    ];
  }

  if (!performance.sampleCount) {
    return [{ type: "trend", severity: "info", title: "暂无追踪样本", metric: "运行质量", change: timeWindowLabel,
      context: "已有消息记录，但该窗口内没有可用的链路追踪样本，暂不评估运行质量" }];
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
      action: "优先查看失败请求分布与超时节点"
    });
  } else {
    items.push({
      type: "trend",
      severity: "info",
      title: "系统可用性稳定",
      metric: "成功率",
      change: `${performance.successRate.toFixed(1)}%`,
      context: "当前窗口整体可用性处于健康区间"
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
      action: "优化索引覆盖率与检索重排策略"
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
      action: "排查慢节点与模型并发配置"
    });
  }

  if (items.length < 3) {
    items.push({
      type: "recommendation",
      severity: "info",
      title: "继续保持当前策略",
      metric: "运营状态",
      change: timeWindowLabel,
      context: "当前窗口内未发现显著异常趋势"
    });
  }

  return items.slice(0, 3);
};

/**
 * 有几条就分几列：一条时铺满整行（"暂无数据"那句最长，分栏会把它折成两行），
 * 两条三条各占一半、三分之一。条数变化不再改变卡片高度，它始终是一行
 */
const INSIGHT_COLS: Record<number, string> = {
  1: "",
  2: "md:grid-cols-2",
  3: "md:grid-cols-3"
};

/**
 * 运营洞察落在左列末尾、趋势区下方。它不锁高度，也不套滚动壳：
 * 一行放得下全部条目，卡片高度由内容定，剩余高由右侧的 AI 性能卡去补齐
 */
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
      () => buildInsightList(performance, timeWindowLabel, overview),
      [performance, timeWindowLabel, overview]
  );

  return (
      <DashCard className={cn("flex flex-col", className)}>
        <div className="mb-4 flex items-center justify-between gap-3">
          <h3 className="text-base font-semibold leading-6 text-[#101828]">运营洞察</h3>
          <span className="text-[11px] text-[#98A2B3]">{formatTime(timestamp)}</span>
        </div>
        <div className={cn("grid flex-1 gap-3", INSIGHT_COLS[items.length])}>
          {items.map((item, i) => (
              <InsightCard key={`${item.title}-${i}`} item={item} />
          ))}
        </div>
      </DashCard>
  );
};

// ==========================================================================
// Workflow Dashboard
// ==========================================================================

/**
 * 洞察卡进左列后左列比右列高一截，两列靠栅格默认的 stretch 拉齐，
 * 余量由 AI 性能卡的环区吃掉——不能用 xl:items-start，它把矮的一列留在原地，底下空出约 149px 的洞
 */
export const WorkflowDashboard = ({
                                    overview,
                                    performance,
                                    trends,
                                    timeWindow,
                                    lastUpdated,
                                    loading
                                  }: {
  overview: DashboardOverview;
  performance: WorkflowDashboardPerformance | null;
  trends: DashboardTrendBundle;
  timeWindow: DashboardTimeWindow;
  lastUpdated: number | null;
  loading?: boolean;
}) => {
  const { health, metricStatus } = useHealthStatus(performance, overview);
  const windowLabel = WINDOW_LABEL_MAP[timeWindow];

  return (
      /* 重新取数时保留上一次的渲染，整体降透明度表示"在刷新"，避免整页塌成骨架造成布局跳动 */
      <div className={cn("space-y-3", loading && "opacity-60 transition-opacity")}>
        <KpiBand items={workflowKpiItems(overview, performance)} className="sm:grid-cols-2 xl:grid-cols-4" />

        <div className="grid gap-4 xl:grid-cols-12">
          <div className="flex min-w-0 flex-col gap-4 xl:col-span-8">
            <TrafficCard trends={trends} overview={overview} timeWindow={timeWindow} loading={loading} />
            <TrendSection trends={trends} timeWindow={timeWindow} loading={loading} />
            {/* flex-1 只在右列更高时才起作用：左列自己更高时它就是自然高 */}
            <InsightSection
                className="flex-1"
                performance={performance}
                overview={overview}
                timeWindowLabel={windowLabel}
                timestamp={lastUpdated}
            />
          </div>
          <div className="min-w-0 xl:col-span-4">
            <AIPerformanceCard
                className="h-full"
                performance={performance}
                metricStatus={metricStatus}
                health={health}
                overview={overview}
                timeWindowLabel={windowLabel}
            />
          </div>
        </div>
      </div>
  );
};
