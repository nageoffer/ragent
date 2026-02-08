import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type ComponentType,
  type ReactNode
} from "react";
import {
  Activity,
  AlertCircle,
  AlertTriangle,
  CheckCircle2,
  Clock,
  FileQuestion,
  Info,
  Lightbulb,
  MessageSquare,
  RefreshCw,
  Timer,
  TrendingDown,
  TrendingUp,
  Zap
} from "lucide-react";
import { toast } from "sonner";

import {
  SimpleLineChart,
  type ChartThreshold,
  type ChartXAxisMode,
  type ChartYAxisType,
  type TrendSeries
} from "@/components/admin/SimpleLineChart";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import {
  getDashboardOverview,
  getDashboardPerformance,
  getDashboardTrends,
  type DashboardOverview,
  type DashboardPerformance,
  type DashboardTrends
} from "@/services/dashboardService";

// ============================================================================
// Types
// ============================================================================

type DashboardTimeWindow = "24h" | "7d" | "30d";

type DashboardTrendBundle = {
  sessions: DashboardTrends | null;
  activeUsers: DashboardTrends | null;
  latency: DashboardTrends | null;
  quality: DashboardTrends | null;
};

type HealthStatus = "healthy" | "attention" | "critical";
type MetricTone = "good" | "warning" | "bad";

type HealthStatusView = {
  status: HealthStatus;
  title: string;
  description: string;
};

type MetricStatusView = {
  success: MetricTone;
  latency: MetricTone;
  error: MetricTone;
  noDoc: MetricTone;
};

type KPIChange = {
  value: number;
  trend: "up" | "down" | "flat";
  isPositive: boolean;
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
  latency: { good: 2000, warning: 5000 },
  successRate: { good: 99, warning: 95 },
  errorRate: { good: 1, warning: 5 },
  noDocRate: { good: 10, warning: 30 }
} as const;

const EMPTY_TRENDS: DashboardTrendBundle = {
  sessions: null,
  activeUsers: null,
  latency: null,
  quality: null
};

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
    } | null
): HealthStatus => {
  if (!performance) return "attention";
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

// ============================================================================
// Hooks
// ============================================================================

const useDashboardData = () => {
  const [timeWindow, setTimeWindow] = useState<DashboardTimeWindow>("24h");
  const [overview, setOverview] = useState<DashboardOverview | null>(null);
  const [performance, setPerformance] = useState<DashboardPerformance | null>(null);
  const [trends, setTrends] = useState<DashboardTrendBundle>(EMPTY_TRENDS);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);

  const loadData = useCallback(async (windowValue: DashboardTimeWindow) => {
    setLoading(true);
    setError(null);

    const granularity = windowValue === "24h" ? "hour" : "day";

    try {
      const [overviewData, performanceData, sessions, activeUsers, latency, quality] = await Promise.all([
        getDashboardOverview(windowValue),
        getDashboardPerformance(windowValue),
        getDashboardTrends("sessions", windowValue, granularity),
        getDashboardTrends("activeUsers", windowValue, granularity),
        getDashboardTrends("avgLatency", windowValue, granularity),
        getDashboardTrends("quality", windowValue, granularity)
      ]);

      setOverview(overviewData);
      setPerformance(performanceData);
      setTrends({ sessions, activeUsers, latency, quality });
      setLastUpdated(Date.now());
    } catch (err) {
      console.error(err);
      setError("数据加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadData(timeWindow);
  }, [loadData, timeWindow]);

  const refresh = useCallback(async () => {
    await loadData(timeWindow);
  }, [loadData, timeWindow]);

  return {
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

const useHealthStatus = (performance: DashboardPerformance | null) => {
  const health = useMemo<HealthStatusView>(() => {
    const status = getHealthStatus(performance);

    if (status === "critical") {
      return { status, title: "系统风险偏高", description: "错误率或成功率触发告警阈值" };
    }
    if (status === "attention") {
      return { status, title: "系统需要关注", description: "召回质量或性能波动接近阈值" };
    }
    return { status, title: "系统运行健康", description: "核心质量指标保持稳定" };
  }, [performance]);

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

/** 统一卡片样式 */
const DashCard = ({ children, className }: { children: ReactNode; className?: string }) => (
    <div className={cn("rounded-2xl bg-white p-5 shadow-[0_1px_3px_rgba(0,0,0,0.08)]", className)}>
      {children}
    </div>
);

/** 卡片内标题 */
const CardTitle = ({ children }: { children: ReactNode }) => (
    <h3 className="mb-4 text-sm font-semibold text-slate-700">{children}</h3>
);

/** Loading 占位块 */
const LoadingBlock = ({ className }: { className?: string }) => (
    <div className={cn("animate-pulse rounded-lg bg-slate-100", className)} />
);

// ============================================================================
// Header
// ============================================================================

const DashboardHeader = ({
                           timeWindow,
                           lastUpdated,
                           loading,
                           onRefresh,
                           onTimeWindowChange
                         }: {
  timeWindow: DashboardTimeWindow;
  lastUpdated: number | null;
  loading?: boolean;
  onRefresh: () => void;
  onTimeWindowChange: (window: DashboardTimeWindow) => void;
}) => (
    <header className="mb-5 flex items-center justify-between">
      {/* 左侧标题 */}
      <h1 className="text-2xl font-bold tracking-tight text-slate-900">Dashboard</h1>

      {/* 右侧控制区 */}
      <div className="flex items-center gap-3">
        {/* 时间窗口切换 */}
        <div className="inline-flex rounded-lg bg-white p-1 shadow-sm">
          {WINDOW_OPTIONS.map((opt) => (
              <button
                  key={opt.value}
                  onClick={() => onTimeWindowChange(opt.value)}
                  disabled={loading}
                  className={cn(
                      "rounded-md px-3 py-1.5 text-sm font-medium transition-all",
                      timeWindow === opt.value
                          ? "bg-slate-900 text-white"
                          : "text-slate-500 hover:text-slate-700"
                  )}
              >
                {opt.label}
              </button>
          ))}
        </div>

        {/* 更新时间 */}
        <div className="flex items-center gap-2 text-sm text-slate-400">
          <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
          <span>{formatLastUpdated(lastUpdated)}</span>
        </div>

        {/* 刷新按钮 */}
        <Button
            variant="outline"
            size="icon"
            onClick={onRefresh}
            disabled={loading}
            className="h-9 w-9 rounded-lg border-slate-200 bg-white text-slate-500 hover:text-slate-700"
        >
          <RefreshCw className={cn("h-4 w-4", loading && "animate-spin")} />
        </Button>
      </div>
    </header>
);

// ============================================================================
// Health Alert
// ============================================================================

const STATUS_CONFIG: Record<
    HealthStatus,
    { bg: string; border: string; text: string; icon: typeof CheckCircle2 }
> = {
  healthy: {
    bg: "bg-emerald-50",
    border: "border-emerald-200",
    text: "text-emerald-700",
    icon: CheckCircle2
  },
  attention: {
    bg: "bg-amber-50",
    border: "border-amber-200",
    text: "text-amber-700",
    icon: Info
  },
  critical: {
    bg: "bg-red-50",
    border: "border-red-200",
    text: "text-red-700",
    icon: AlertTriangle
  }
};

const HealthSection = ({ health }: { health: HealthStatusView }) => {
  const cfg = STATUS_CONFIG[health.status];
  const Icon = cfg.icon;

  return (
      <DashCard>
        <CardTitle>系统健康</CardTitle>
        <div className={cn("flex items-center gap-3 rounded-xl border px-4 py-3", cfg.bg, cfg.border)}>
          <Icon className={cn("h-5 w-5 shrink-0", cfg.text)} />
          <div className={cn("text-sm", cfg.text)}>
            <span className="font-semibold">{health.title}</span>
            <span className="mx-2 opacity-60">·</span>
            <span className="opacity-80">{health.description}</span>
          </div>
        </div>
      </DashCard>
  );
};

// ============================================================================
// KPI Cards
// ============================================================================

type KPICardProps = {
  value: string | number;
  label: string;
  change?: KPIChange;
  icon: ReactNode;
  iconBg: string;
  iconColor: string;
};

const KPICardItem = ({ value, label, change, icon, iconBg, iconColor }: KPICardProps) => {
  const showChange = change && change.trend !== "flat";
  const isUp = change?.trend === "up";
  const changePositive = change?.isPositive;

  const changeColor =
      showChange && ((isUp && changePositive) || (!isUp && !changePositive))
          ? "text-emerald-600"
          : "text-red-500";

  return (
      <div className="rounded-xl bg-slate-50 p-4">
        <div className="flex items-start justify-between">
          <div>
            <p className="text-2xl font-bold tracking-tight text-slate-900">{value}</p>
            <p className="mt-1 text-sm text-slate-500">{label}</p>
          </div>
          <div
              className="flex h-10 w-10 items-center justify-center rounded-xl"
              style={{ backgroundColor: iconBg, color: iconColor }}
          >
            {icon}
          </div>
        </div>

        <div className="mt-3 flex items-center gap-1.5 text-sm">
          {showChange ? (
              <>
                {isUp ? (
                    <TrendingUp className={cn("h-4 w-4", changeColor)} />
                ) : (
                    <TrendingDown className={cn("h-4 w-4", changeColor)} />
                )}
                <span className={cn("font-medium", changeColor)}>
              {change!.value > 0 ? "+" : ""}
                  {change!.value.toFixed(1)}%
            </span>
                <span className="text-slate-400">较上周期</span>
              </>
          ) : (
              <span className="text-slate-400">--</span>
          )}
        </div>
      </div>
  );
};

const toChange = (deltaPct?: number | null): KPIChange => {
  if (deltaPct === null || deltaPct === undefined) {
    return { value: 0, trend: "flat", isPositive: true };
  }
  if (deltaPct > 0) return { value: deltaPct, trend: "up", isPositive: true };
  if (deltaPct < 0) return { value: deltaPct, trend: "down", isPositive: false };
  return { value: 0, trend: "flat", isPositive: true };
};

const KPISection = ({
                      overview,
                      performance
                    }: {
  overview: DashboardOverview | null;
  performance: DashboardPerformance | null;
}) => {
  const kpis = overview?.kpis;

  const items: KPICardProps[] = [
    {
      value: formatNumber(kpis?.activeUsers.value),
      label: "活跃用户",
      change: toChange(kpis?.activeUsers.deltaPct),
      icon: <Activity className="h-5 w-5" />,
      iconBg: "#DBEAFE",
      iconColor: "#2563EB"
    },
    {
      value: formatNumber(kpis?.sessions24h.value),
      label: "会话数",
      change: toChange(kpis?.sessions24h.deltaPct),
      icon: <MessageSquare className="h-5 w-5" />,
      iconBg: "#E0E7FF",
      iconColor: "#4F46E5"
    },
    {
      value: formatNumber(kpis?.messages24h.value),
      label: "消息数",
      change: toChange(kpis?.messages24h.deltaPct),
      icon: <Zap className="h-5 w-5" />,
      iconBg: "#FEF3C7",
      iconColor: "#D97706"
    },
    {
      value: performance ? `${performance.successRate.toFixed(1)}%` : "-",
      label: "成功率",
      change: undefined,
      icon: <CheckCircle2 className="h-5 w-5" />,
      iconBg: "#D1FAE5",
      iconColor: "#059669"
    }
  ];

  return (
      <DashCard>
        <CardTitle>核心指标</CardTitle>
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          {items.map((item) => (
              <KPICardItem key={item.label} {...item} />
          ))}
        </div>
      </DashCard>
  );
};

// ============================================================================
// Trend Charts
// ============================================================================

const mapSeries = (trend: DashboardTrends | null, tone: TrendSeries["tone"]): TrendSeries[] => {
  if (!trend?.series?.length) return [];
  return trend.series.map((s) => ({ name: s.name, data: s.data, tone }));
};

const mapQualitySeries = (trend: DashboardTrends | null): TrendSeries[] => {
  if (!trend?.series?.length) return [];
  return trend.series.map((s) => ({
    name: s.name,
    data: s.data,
    tone: s.name.includes("错误") ? "danger" : "info"
  }));
};

const TrendChartItem = ({
                          title,
                          series,
                          thresholds = [],
                          xAxisMode,
                          yAxisType = "number",
                          yAxisLabel,
                          loading
                        }: {
  title: string;
  series: TrendSeries[];
  thresholds?: ChartThreshold[];
  xAxisMode: ChartXAxisMode;
  yAxisType?: ChartYAxisType;
  yAxisLabel?: string;
  loading?: boolean;
}) => {
  if (loading) {
    return (
        <div className="rounded-xl bg-slate-50 p-4">
          <LoadingBlock className="mb-3 h-4 w-24" />
          <LoadingBlock className="h-48 w-full" />
        </div>
    );
  }

  return (
      <div className="rounded-xl bg-slate-50 p-4">
        <div className="mb-1 text-xs font-medium text-slate-500">{title}</div>
        {yAxisLabel && <p className="mb-2 text-[11px] text-slate-400">{yAxisLabel}</p>}
        <div className="h-48">
          <SimpleLineChart
              series={series}
              xAxisMode={xAxisMode}
              yAxisType={yAxisType}
              thresholds={thresholds}
              height={192}
              theme="light"
              yAxisTickCount={4}
          />
        </div>
      </div>
  );
};

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

  const sessionsSeries = useMemo(() => mapSeries(trends.sessions, "primary"), [trends.sessions]);
  const activeSeries = useMemo(() => mapSeries(trends.activeUsers, "success"), [trends.activeUsers]);
  const latencySeries = useMemo(() => mapSeries(trends.latency, "warning"), [trends.latency]);
  const qualitySeries = useMemo(() => mapQualitySeries(trends.quality), [trends.quality]);

  return (
      <DashCard>
        <CardTitle>趋势分析</CardTitle>
        <div className="grid gap-4 lg:grid-cols-2">
          <TrendChartItem
              title="会话趋势"
              series={sessionsSeries}
              xAxisMode={xAxisMode}
              yAxisLabel="单位：次"
              loading={loading}
          />
          <TrendChartItem
              title="活跃用户趋势"
              series={activeSeries}
              xAxisMode={xAxisMode}
              yAxisLabel="单位：人"
              loading={loading}
          />
          <TrendChartItem
              title="响应时间趋势"
              series={latencySeries}
              xAxisMode={xAxisMode}
              yAxisType="duration"
              yAxisLabel="单位：毫秒"
              loading={loading}
              thresholds={[
                { value: DASHBOARD_THRESHOLDS.latency.good, label: "良好 <2s", tone: "info" },
                { value: DASHBOARD_THRESHOLDS.latency.warning, label: "警告 >5s", tone: "critical" }
              ]}
          />
          <TrendChartItem
              title="质量趋势"
              series={qualitySeries}
              xAxisMode={xAxisMode}
              yAxisType="percent"
              yAxisLabel="单位：%"
              loading={loading}
              thresholds={[
                { value: DASHBOARD_THRESHOLDS.errorRate.warning, label: "错误警告", tone: "warning" },
                { value: DASHBOARD_THRESHOLDS.noDocRate.warning, label: "无知识警告", tone: "critical" }
              ]}
          />
        </div>
      </DashCard>
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
      <span className="text-sm font-semibold tabular-nums" style={{ color: STATUS_COLOR[status] }}>
      {value}
    </span>
    </div>
);

const AIPerformanceCard = ({
                             performance,
                             metricStatus
                           }: {
  performance: DashboardPerformance | null;
  metricStatus: MetricStatusView;
}) => {
  const successRate = performance?.successRate ?? 0;
  const ringColor = successRate >= 95 ? "#10B981" : successRate >= 85 ? "#F59E0B" : "#EF4444";

  const avgLatencyStatus = getLatencyStatus(performance?.avgLatencyMs);
  const p95LatencyStatus = getLatencyStatus(performance?.p95LatencyMs);

  const radius = 50;
  const circumference = 2 * Math.PI * radius;
  const progress = (Math.min(successRate, 100) / 100) * circumference;

  return (
      <DashCard>
        <CardTitle>AI 性能</CardTitle>

        {/* 成功率环形图 */}
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
              {formatPercent(successRate)}
            </span>
              <span className="mt-0.5 text-xs text-slate-400">成功率</span>
            </div>
          </div>
        </div>

        {/* 指标列表 */}
        <div className="divide-y divide-slate-100">
          <MetricRow
              icon={Timer}
              label="平均响应"
              value={formatDuration(performance?.avgLatencyMs)}
              status={avgLatencyStatus}
          />
          <MetricRow
              icon={Clock}
              label="P95 响应"
              value={formatDuration(performance?.p95LatencyMs)}
              status={p95LatencyStatus}
          />
          <MetricRow
              icon={AlertCircle}
              label="错误率"
              value={formatPercent(performance?.errorRate)}
              status={metricStatus.error}
          />
          <MetricRow
              icon={FileQuestion}
              label="无知识率"
              value={formatPercent(performance?.noDocRate)}
              status={metricStatus.noDoc}
          />
        </div>
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
        {item.action && <p className="mt-1 text-xs font-medium text-slate-600">建议：{item.action}</p>}
      </div>
  );
};

const buildInsightList = (
    performance: DashboardPerformance | null,
    timeWindowLabel: string,
    timestamp: number | null
): InsightCardData[] => {
  const t = formatTime(timestamp);

  if (!performance) {
    return [
      {
        type: "trend",
        severity: "info",
        title: "等待数据回传",
        metric: "Dashboard",
        change: timeWindowLabel,
        context: "当前窗口尚未返回完整性能数据",
        timestamp: t
      }
    ];
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

  if (performance.avgLatencyMs > 3000) {
    items.push({
      type: "recommendation",
      severity: "warning",
      title: "响应性能需要关注",
      metric: "平均响应时间",
      change: `${(performance.avgLatencyMs / 1000).toFixed(2)}s`,
      context: "平均延迟高于 3s，影响交互体验",
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
                          timeWindowLabel,
                          timestamp
                        }: {
  performance: DashboardPerformance | null;
  timeWindowLabel: string;
  timestamp: number | null;
}) => {
  const items = useMemo(
      () => buildInsightList(performance, timeWindowLabel, timestamp),
      [performance, timeWindowLabel, timestamp]
  );

  return (
      <DashCard>
        <CardTitle>运营洞察</CardTitle>
        <div className="space-y-3">
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

  const { health, metricStatus } = useHealthStatus(performance);

  useEffect(() => {
    if (error) toast.error(error);
  }, [error]);

  return (
      <div className="px-6 pb-6 pt-2">
        <div className="mx-auto max-w-[1920px]">
          {/* Header */}
          <DashboardHeader
              timeWindow={timeWindow}
              lastUpdated={lastUpdated}
              loading={loading}
              onRefresh={() => void refresh()}
              onTimeWindowChange={setTimeWindow}
          />

          {/* Main Grid */}
          <div className="grid gap-5 xl:grid-cols-[1fr_320px]">
            {/* 左侧主区域 */}
            <div className="space-y-5">
              <HealthSection health={health} />
              <KPISection overview={overview} performance={performance} />
              <TrendSection trends={trends} timeWindow={timeWindow} loading={loading} />
            </div>

            {/* 右侧边栏 */}
            <aside className="space-y-5 xl:sticky xl:top-4 xl:self-start">
              <AIPerformanceCard performance={performance} metricStatus={metricStatus} />
              <InsightSection
                  performance={performance}
                  timeWindowLabel={WINDOW_LABEL_MAP[timeWindow]}
                  timestamp={lastUpdated}
              />
            </aside>
          </div>
        </div>
      </div>
  );
}
