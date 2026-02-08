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
  Activity,
  AlertCircle,
  BarChart3,
  Clock,
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
  messages: DashboardTrends | null;
  activeUsers: DashboardTrends | null;
  latency: DashboardTrends | null;
  quality: DashboardTrends | null;
};

type HealthStatus = "healthy" | "attention" | "critical";
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
  latency: { good: 15000, warning: 20000 },
  successRate: { good: 99, warning: 95 },
  errorRate: { good: 1, warning: 5 },
  noDocRate: { good: 10, warning: 30 }
} as const;

const EMPTY_TRENDS: DashboardTrendBundle = {
  sessions: null,
  messages: null,
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

const clampPercent = (value?: number | null) => {
  if (value === null || value === undefined || Number.isNaN(value)) return 0;
  return Math.max(0, Math.min(100, value));
};

const formatRatio = (value?: number | null) => {
  if (value === null || value === undefined || !Number.isFinite(value)) return "-";
  return value.toFixed(2);
};

const formatCompactNumber = (value: number): string => {
  if (value >= 10000) return `${(value / 1000).toFixed(0)}k`;
  if (value >= 1000) return `${(value / 1000).toFixed(1)}k`;
  return Math.round(value).toString();
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
  const requestIdRef = useRef(0);

  const loadData = useCallback(async (windowValue: DashboardTimeWindow) => {
    const requestId = ++requestIdRef.current;
    setLoading(true);
    setError(null);

    const granularity = windowValue === "24h" ? "hour" : "day";

    try {
      const [overviewData, performanceData] = await Promise.all([
        getDashboardOverview(windowValue),
        getDashboardPerformance(windowValue)
      ]);
      if (requestIdRef.current !== requestId) return;
      setOverview(overviewData);
      setPerformance(performanceData);
      setLastUpdated(Date.now());

      try {
        const [sessions, messages, activeUsers, latency, quality] = await Promise.all([
          getDashboardTrends("sessions", windowValue, granularity),
          getDashboardTrends("messages", windowValue, granularity),
          getDashboardTrends("activeUsers", windowValue, granularity),
          getDashboardTrends("avgLatency", windowValue, granularity),
          getDashboardTrends("quality", windowValue, granularity)
        ]);
        if (requestIdRef.current !== requestId) return;
        setTrends({ sessions, messages, activeUsers, latency, quality });
      } catch (trendErr) {
        if (requestIdRef.current !== requestId) return;
        console.error(trendErr);
        setTrends(EMPTY_TRENDS);
        setError("趋势数据加载失败");
      }
    } catch (err) {
      if (requestIdRef.current !== requestId) return;
      console.error(err);
      setError("数据加载失败");
    } finally {
      if (requestIdRef.current !== requestId) return;
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
  const health = useMemo(() => getHealthStatus(performance), [performance]);

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

const DashCard = ({ children, className }: { children: ReactNode; className?: string }) => (
    <div className={cn("rounded-2xl bg-white p-5 shadow-[0_1px_3px_rgba(0,0,0,0.08)]", className)}>
      {children}
    </div>
);

const CardTitle = ({ children }: { children: ReactNode }) => (
    <h3 className="mb-4 text-sm font-semibold text-slate-700">{children}</h3>
);

const LoadingBlock = ({ className }: { className?: string }) => (
    <div className={cn("animate-pulse rounded-lg bg-slate-100", className)} />
);

// ============================================================================
// Header
// ============================================================================

const HEALTH_CONFIG: Record<HealthStatus, { bg: string; text: string; label: string }> = {
  healthy: { bg: "bg-emerald-100", text: "text-emerald-700", label: "运行正常" },
  attention: { bg: "bg-amber-100", text: "text-amber-700", label: "需要关注" },
  critical: { bg: "bg-red-100", text: "text-red-700", label: "风险偏高" }
};

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
    <header className="mb-3 flex items-center justify-between">
      <h1 className="text-4xl font-bold tracking-tight text-slate-900">Dashboard</h1>

      <div className="flex items-center gap-3">
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

        <div className="flex items-center gap-2 text-sm text-slate-400">
          <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
          <span>{formatLastUpdated(lastUpdated)}</span>
        </div>

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

const KPISection = ({ overview }: { overview: DashboardOverview | null }) => {
  const kpis = overview?.kpis;
  const sessionDepth =
      (kpis?.sessions24h.value ?? 0) > 0
          ? (kpis?.messages24h.value ?? 0) / (kpis?.sessions24h.value ?? 1)
          : null;

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
      value: sessionDepth === null ? "-" : formatRatio(sessionDepth),
      label: "会话深度（条/会话）",
      change: undefined,
      icon: <BarChart3 className="h-5 w-5" />,
      iconBg: "#E0F2FE",
      iconColor: "#0284C7"
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
// Area Chart Component (优化版 - 使用 HTML 布局坐标轴)
// ============================================================================

type AreaChartPoint = { ts: number; value: number };

const SimpleAreaChart = ({
                           data,
                           height = 160,
                           timeWindow,
                           valueLabel = "消息数"
                         }: {
  data: AreaChartPoint[];
  height?: number;
  timeWindow: DashboardTimeWindow;
  valueLabel?: string;
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const [dimensions, setDimensions] = useState({ width: 0, height: 0 });
  const [tooltip, setTooltip] = useState<{
    show: boolean;
    x: number;
    y: number;
    value: number;
    label: string;
  } | null>(null);

  // 监听容器尺寸
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const observer = new ResizeObserver((entries) => {
      const entry = entries[0];
      if (entry) {
        setDimensions({
          width: entry.contentRect.width,
          height: entry.contentRect.height
        });
      }
    });

    observer.observe(container);
    return () => observer.disconnect();
  }, []);

  const maxValue = useMemo(() => Math.max(1, ...data.map((d) => d.value)), [data]);

  // Y轴刻度
  const yTicks = useMemo(() => {
    const tickCount = 4;
    const step = maxValue / (tickCount - 1);
    return Array.from({ length: tickCount }, (_, i) => Math.round(step * (tickCount - 1 - i)));
  }, [maxValue]);

  // X轴标签
  const xLabels = useMemo(() => {
    if (data.length === 0) return [];
    const count = timeWindow === "24h" ? 6 : 5;
    const step = Math.max(1, Math.floor((data.length - 1) / (count - 1)));

    return Array.from({ length: count }, (_, i) => {
      const idx = Math.min(i * step, data.length - 1);
      const point = data[idx];
      if (!point) return null;

      const date = new Date(point.ts);
      const label =
          timeWindow === "24h"
              ? date.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false })
              : date.toLocaleDateString("zh-CN", { month: "2-digit", day: "2-digit" });

      return { position: i / (count - 1), label };
    }).filter(Boolean) as Array<{ position: number; label: string }>;
  }, [data, timeWindow]);

  // 生成 SVG 路径 (归一化坐标 0-1)
  const { linePath, areaPath, points } = useMemo(() => {
    if (data.length === 0) return { linePath: "", areaPath: "", points: [] };

    const pts = data.map((d, i) => ({
      x: i / Math.max(1, data.length - 1),
      y: 1 - d.value / maxValue,
      ts: d.ts,
      value: d.value
    }));

    if (pts.length === 1) {
      return {
        linePath: `M ${pts[0].x} ${pts[0].y}`,
        areaPath: `M ${pts[0].x} 1 L ${pts[0].x} ${pts[0].y} L ${pts[0].x} 1 Z`,
        points: pts
      };
    }

    let line = `M ${pts[0].x} ${pts[0].y}`;
    for (let i = 1; i < pts.length; i++) {
      const prev = pts[i - 1];
      const curr = pts[i];
      const cpx1 = prev.x + (curr.x - prev.x) * 0.4;
      const cpx2 = prev.x + (curr.x - prev.x) * 0.6;
      line += ` C ${cpx1} ${prev.y}, ${cpx2} ${curr.y}, ${curr.x} ${curr.y}`;
    }

    const area = `${line} L ${pts[pts.length - 1].x} 1 L ${pts[0].x} 1 Z`;
    return { linePath: line, areaPath: area, points: pts };
  }, [data, maxValue]);

  const formatLabel = (ts: number) => {
    const date = new Date(ts);
    if (timeWindow === "24h") {
      return date.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false });
    }
    return date.toLocaleDateString("zh-CN", { month: "2-digit", day: "2-digit" });
  };

  const handleMouseMove = (e: React.MouseEvent<HTMLDivElement>) => {
    const chartArea = e.currentTarget;
    const rect = chartArea.getBoundingClientRect();
    const relativeX = (e.clientX - rect.left) / rect.width;

    if (relativeX < 0 || relativeX > 1 || points.length === 0) {
      setTooltip(null);
      return;
    }

    let closestIdx = 0;
    let minDist = Infinity;
    points.forEach((pt, i) => {
      const dist = Math.abs(pt.x - relativeX);
      if (dist < minDist) {
        minDist = dist;
        closestIdx = i;
      }
    });

    const pt = points[closestIdx];
    setTooltip({
      show: true,
      x: pt.x * rect.width,
      y: pt.y * rect.height,
      value: pt.value,
      label: formatLabel(pt.ts)
    });
  };

  const handleMouseLeave = () => {
    setTooltip(null);
  };

  const PADDING = { left: 40, right: 8, top: 8, bottom: 32 };

  return (
      <div ref={containerRef} className="relative h-full w-full" style={{ minHeight: height }}>
        {/* Y轴标签 */}
        <div
            className="absolute flex flex-col justify-between"
            style={{
              left: 0,
              top: PADDING.top,
              width: PADDING.left - 4,
              height: `calc(100% - ${PADDING.top + PADDING.bottom}px)`
            }}
        >
          {yTicks.map((tick, i) => (
              <span key={i} className="pr-1 text-right text-[10px] leading-none text-slate-400">
            {formatCompactNumber(tick)}
          </span>
          ))}
        </div>

        {/* Y轴标题 */}
        <div className="absolute left-0 top-0 text-[10px] text-slate-400">{valueLabel}</div>

        {/* 图表区域 */}
        <div
            className="absolute cursor-crosshair"
            style={{
              left: PADDING.left,
              top: PADDING.top,
              right: PADDING.right,
              bottom: PADDING.bottom
            }}
            onMouseMove={handleMouseMove}
            onMouseLeave={handleMouseLeave}
        >
          {/* 水平网格线 */}
          <div className="pointer-events-none absolute inset-0">
            {yTicks.map((_, i) => (
                <div
                    key={i}
                    className="absolute left-0 right-0 border-t border-dashed border-slate-100"
                    style={{ top: `${(i / (yTicks.length - 1)) * 100}%` }}
                />
            ))}
          </div>

          {/* SVG 图表 */}
          <svg
              className="absolute inset-0 h-full w-full overflow-visible"
              viewBox="0 0 1 1"
              preserveAspectRatio="none"
          >
            <defs>
              <linearGradient id="trafficGradient" x1="0%" y1="0%" x2="0%" y2="100%">
                <stop offset="0%" stopColor="#3B82F6" stopOpacity="0.25" />
                <stop offset="100%" stopColor="#3B82F6" stopOpacity="0.02" />
              </linearGradient>
            </defs>
            <path d={areaPath} fill="url(#trafficGradient)" />
            <path
                d={linePath}
                fill="none"
                stroke="#3B82F6"
                strokeWidth="2"
                vectorEffect="non-scaling-stroke"
            />
          </svg>

          {/* Tooltip */}
          {tooltip?.show && (
              <>
                {/* 垂直指示线 */}
                <div
                    className="pointer-events-none absolute top-0 h-full w-px bg-slate-300"
                    style={{ left: tooltip.x }}
                />
                {/* 圆点 */}
                <div
                    className="pointer-events-none absolute h-3 w-3 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-blue-500 bg-white shadow-sm"
                    style={{ left: tooltip.x, top: tooltip.y }}
                />
                {/* 标签 */}
                <div
                    className="pointer-events-none absolute z-10 -translate-x-1/2 whitespace-nowrap rounded-lg bg-slate-800 px-2.5 py-1.5 text-xs text-white shadow-lg"
                    style={{
                      left: tooltip.x,
                      top: Math.max(0, tooltip.y - 48)
                    }}
                >
                  <div className="font-medium">{tooltip.label}</div>
                  <div className="flex items-center gap-1">
                    <span className="h-2 w-2 rounded-sm bg-blue-400" />
                    <span>
                  {valueLabel}: {tooltip.value}
                </span>
                  </div>
                </div>
              </>
          )}
        </div>

        {/* X轴标签 */}
        <div
            className="absolute flex justify-between"
            style={{
              left: PADDING.left,
              right: PADDING.right,
              bottom: 8,
              height: 16
            }}
        >
          {xLabels.map((item, i) => (
              <span
                  key={i}
                  className="text-[10px] text-slate-400"
                  style={{
                    position: "absolute",
                    left: `${item.position * 100}%`,
                    transform: "translateX(-50%)"
                  }}
              >
            {item.label}
          </span>
          ))}
        </div>
      </div>
  );
};

// ============================================================================
// Traffic Overview Section
// ============================================================================

const TrafficOverviewSection = ({
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
  const chartData = useMemo<AreaChartPoint[]>(() => {
    const points = trends.messages?.series?.[0]?.data || [];
    return points.map((p) => ({ ts: p.ts, value: p.value }));
  }, [trends.messages]);

  const totalMessages = overview?.kpis?.messages24h?.value ?? 0;
  const deltaPct = overview?.kpis?.messages24h?.deltaPct;
  const change = toChange(deltaPct);
  const showChange = change.trend !== "flat";
  const isUp = change.trend === "up";

  return (
      <DashCard className={cn("flex flex-col", className)}>
        <div className="mb-3">
          <p className="text-xs font-medium uppercase tracking-wide text-slate-500">流量概览</p>
          <p className="mt-1 text-3xl font-bold text-slate-900">{formatNumber(totalMessages)}</p>
          <p className="text-xs text-slate-400">消息总量</p>
          {showChange && (
              <div className="mt-1 flex items-center gap-1">
                {isUp ? (
                    <TrendingUp className="h-4 w-4 text-emerald-500" />
                ) : (
                    <TrendingDown className="h-4 w-4 text-red-500" />
                )}
                <span className={cn("text-sm font-medium", isUp ? "text-emerald-500" : "text-red-500")}>
              {change.value > 0 ? "+" : ""}
                  {change.value.toFixed(1)}%
            </span>
                <span className="text-sm text-slate-400">较上周期</span>
              </div>
          )}
        </div>

        {loading ? (
            <LoadingBlock className="h-full flex-1" />
        ) : chartData.length === 0 ? (
            <div className="flex flex-1 items-center justify-center text-sm text-slate-400">
              暂无流量数据
            </div>
        ) : (
            <div className="flex-1">
              <SimpleAreaChart data={chartData} timeWindow={timeWindow} valueLabel="" />
            </div>
        )}
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
  const activeSeries = useMemo(
      () => mapSeries(trends.activeUsers, "success"),
      [trends.activeUsers]
  );
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
                { value: DASHBOARD_THRESHOLDS.latency.good, label: "良好 ≤15s", tone: "info" },
                { value: DASHBOARD_THRESHOLDS.latency.warning, label: "警告 >20s", tone: "critical" }
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
      <span className="text-sm font-semibold tabular-nums" style={{ color: STATUS_COLOR[status] }}>
      {value}
    </span>
    </div>
);

const QualitySnapshot = ({
                           performance,
                           windowLabel
                         }: {
  performance: DashboardPerformance | null;
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
  const sessions = overview?.kpis.sessions24h.value ?? 0;
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
  performance: DashboardPerformance | null;
  metricStatus: MetricStatusView;
  health: HealthStatus;
  overview: DashboardOverview | null;
  timeWindowLabel: string;
}) => {
  const healthCfg = HEALTH_CONFIG[health];
  const successRate = performance?.successRate ?? 0;
  const ringColor = successRate >= 95 ? "#10B981" : successRate >= 85 ? "#F59E0B" : "#EF4444";

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
              {formatPercent(successRate)}
            </span>
              <span className="mt-0.5 text-xs text-slate-400">成功率</span>
            </div>
          </div>
        </div>

        <div className="divide-y divide-slate-100">
          <MetricRow
              icon={Timer}
              label="平均响应"
              value={formatDuration(performance?.avgLatencyMs)}
              status={metricStatus.latency}
          />
          <MetricRow
              icon={Clock}
              label="P95 响应"
              value={formatDuration(performance?.p95LatencyMs)}
              status={p95LatencyStatus}
          />
        </div>

        <QualitySnapshot performance={performance} windowLabel={timeWindowLabel} />
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
                          timestamp,
                          className
                        }: {
  performance: DashboardPerformance | null;
  timeWindowLabel: string;
  timestamp: number | null;
  className?: string;
}) => {
  const items = useMemo(
      () => buildInsightList(performance, timeWindowLabel, timestamp),
      [performance, timeWindowLabel, timestamp]
  );

  return (
      <DashCard className={cn("flex flex-col", className)}>
        <CardTitle>运营洞察</CardTitle>
        <div className="flex-1 space-y-3 overflow-y-auto pr-1">
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
      <div className="admin-page">
        <DashboardHeader
            timeWindow={timeWindow}
            lastUpdated={lastUpdated}
            loading={loading}
            onRefresh={() => void refresh()}
            onTimeWindowChange={setTimeWindow}
        />

        <div className="grid gap-5 xl:grid-cols-[1fr_320px]">
          <div className="space-y-5">
            <KPISection overview={overview} />
            <TrafficOverviewSection
                trends={trends}
                overview={overview}
                timeWindow={timeWindow}
                loading={loading}
                className="h-[300px]"
            />
            <TrendSection trends={trends} timeWindow={timeWindow} loading={loading} />
          </div>

          <aside className="space-y-5 xl:sticky xl:top-4 xl:self-start">
            <AIPerformanceCard
                performance={performance}
                metricStatus={metricStatus}
                health={health}
                overview={overview}
                timeWindowLabel={WINDOW_LABEL_MAP[timeWindow]}
            />
            <InsightSection
                performance={performance}
                timeWindowLabel={WINDOW_LABEL_MAP[timeWindow]}
                timestamp={lastUpdated}
                className="h-[360px]"
            />
          </aside>
        </div>
      </div>
  );
}
