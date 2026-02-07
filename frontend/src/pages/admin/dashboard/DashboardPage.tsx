import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Activity,
  BarChart3,
  Clock,
  MessageSquare,
  RefreshCw,
  TrendingUp,
  Users,
  Zap,
  AlertCircle,
  FileQuestion
} from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  SimpleLineChart,
  type ChartXAxisMode,
  type ChartYAxisType,
  type TrendSeries
} from "@/components/admin/SimpleLineChart";
import {
  getDashboardOverview,
  getDashboardPerformance,
  getDashboardTrends,
  type DashboardOverview,
  type DashboardPerformance,
  type DashboardTrends
} from "@/services/dashboardService";
import { cn } from "@/lib/utils";

// ============ 类型定义 ============

type TimeWindow = "24h" | "7d" | "30d";

interface TrendData {
  sessions: DashboardTrends | null;
  activeUsers: DashboardTrends | null;
  latency: DashboardTrends | null;
  quality: DashboardTrends | null;
}

interface WindowOption {
  key: TimeWindow;
  label: string;
}

// ============ 工具函数 ============

const WINDOW_OPTIONS: WindowOption[] = [
  { key: "24h", label: "24h" },
  { key: "7d", label: "7d" },
  { key: "30d", label: "30d" }
];

const WINDOW_LABEL_MAP: Record<TimeWindow, string> = {
  "24h": "滚动 24h",
  "7d": "近 7 天",
  "30d": "近 30 天"
};

const COMPARE_LABEL_MAP: Record<TimeWindow, string> = {
  "24h": "前 24h",
  "7d": "前 7 天",
  "30d": "前 30 天"
};

const getTrendGranularity = (window: TimeWindow): "hour" | "day" => {
  return window === "24h" ? "hour" : "day";
};

const getXAxisMode = (window: TimeWindow): ChartXAxisMode => {
  return window === "24h" ? "hour" : "date";
};

const formatNumber = (value?: number | null): string => {
  if (value === null || value === undefined) return "-";
  return value.toLocaleString("zh-CN");
};

const formatDuration = (ms?: number | null): string => {
  if (ms === null || ms === undefined) return "-";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(2)}s`;
  const minutes = Math.floor(ms / 60_000);
  const seconds = ((ms % 60_000) / 1000).toFixed(1);
  return `${minutes}m ${seconds}s`;
};

const formatRate = (value?: number | null): string => {
  if (value === null || value === undefined) return "-";
  return `${value.toFixed(1)}%`;
};

const formatUpdatedAt = (value?: number | null): string => {
  if (!value) return "-";
  return new Date(value).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  });
};

const getDeltaDisplay = (
    delta?: number | null,
    deltaPct?: number | null
): { label: string; tone: string } => {
  const hasPct = deltaPct !== null && deltaPct !== undefined;
  const hasDelta = delta !== null && delta !== undefined;

  if (!hasPct && !hasDelta) {
    return { label: "", tone: "text-slate-400" };
  }

  const value = hasPct ? deltaPct! : delta!;
  const label = hasPct
      ? `${value >= 0 ? "+" : ""}${value.toFixed(1)}%`
      : `${value >= 0 ? "+" : ""}${formatNumber(delta)}`;
  const tone = value > 0 ? "text-emerald-600" : value < 0 ? "text-red-600" : "text-slate-400";

  return { label, tone };
};

// ============ 子组件 ============

interface KpiCardProps {
  title: string;
  value?: number | null;
  delta?: number | null;
  deltaPct?: number | null;
  compareLabel: string;
  icon: React.ComponentType<{ className?: string }>;
  iconColor?: string;
}

function KpiCard({
                   title,
                   value,
                   delta,
                   deltaPct,
                   compareLabel,
                   icon: Icon,
                   iconColor = "text-slate-600 bg-slate-100"
                 }: KpiCardProps) {
  const { label: deltaLabel, tone: deltaTone } = getDeltaDisplay(delta, deltaPct);
  const hasDelta = deltaLabel !== "";

  return (
      <Card className="border-slate-200 shadow-none hover:shadow-sm transition-shadow">
        <CardContent className="p-4">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-medium text-slate-500">{title}</span>
            <div className={cn("flex h-8 w-8 items-center justify-center rounded-full", iconColor)}>
              <Icon className="h-4 w-4" />
            </div>
          </div>
          <div className="text-2xl font-semibold text-slate-900 mb-1">
            {formatNumber(value)}
          </div>
          {hasDelta ? (
              <div className="text-xs text-slate-400">
                <span className={cn("font-medium", deltaTone)}>{deltaLabel}</span>
                <span className="ml-1">较{compareLabel}</span>
              </div>
          ) : (
              <div className="text-xs text-slate-300">&nbsp;</div>
          )}
        </CardContent>
      </Card>
  );
}

interface MetricCardProps {
  title: string;
  value: string;
  note?: string;
  icon?: React.ComponentType<{ className?: string }>;
  variant?: "default" | "success" | "warning" | "error";
}

function MetricCard({
                      title,
                      value,
                      note,
                      icon: Icon,
                      variant = "default"
                    }: MetricCardProps) {
  const variantStyles = {
    default: "text-slate-900",
    success: "text-emerald-600",
    warning: "text-amber-600",
    error: "text-red-600"
  };

  return (
      <Card className="h-full border-slate-200 shadow-none hover:shadow-sm transition-shadow">
        <CardContent className="flex h-full flex-col justify-center p-4 !pt-4 !pb-4">
          <div className="flex items-center gap-1.5 mb-2">
            {Icon && <Icon className="h-3.5 w-3.5 text-slate-400" />}
            <span className="text-xs font-medium text-slate-500">{title}</span>
          </div>
          <div className={cn("text-xl font-semibold mb-1", variantStyles[variant])}>
            {value}
          </div>
          <div className="text-xs text-slate-400">{note || "\u00A0"}</div>
        </CardContent>
      </Card>
  );
}

interface TrendChartCardProps {
  title: string;
  series: TrendSeries[];
  loading?: boolean;
  badge?: string;
  xAxisMode?: ChartXAxisMode;
  yAxisType?: ChartYAxisType;
}

function TrendChartCard({
                          title,
                          series,
                          loading,
                          badge,
                          xAxisMode = "date",
                          yAxisType = "number"
                        }: TrendChartCardProps) {
  return (
      <Card className="border-slate-200 shadow-none">
        <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
          <CardTitle className="text-sm font-semibold text-slate-900">{title}</CardTitle>
          <div className="flex items-center gap-2">
            {badge && (
                <Badge variant="outline" className="text-xs font-normal">
                  {badge}
                </Badge>
            )}
          </div>
        </CardHeader>
        <CardContent className="pt-2">
          {loading ? (
              <div className="flex h-[180px] items-center justify-center">
                <div className="flex items-center gap-2 text-sm text-slate-400">
                  <RefreshCw className="h-4 w-4 animate-spin" />
                  加载中...
                </div>
              </div>
          ) : series.length === 0 ? (
              <div className="flex h-[180px] items-center justify-center text-sm text-slate-400">
                暂无数据
              </div>
          ) : (
              <SimpleLineChart
                  series={series}
                  xAxisMode={xAxisMode}
                  yAxisType={yAxisType}
              />
          )}
        </CardContent>
      </Card>
  );
}

interface TimeRangeSwitcherProps {
  value: TimeWindow;
  disabled?: boolean;
  onChange: (value: TimeWindow) => void;
}

function TimeRangeSwitcher({ value, disabled, onChange }: TimeRangeSwitcherProps) {
  return (
      <div className="inline-flex items-center rounded-md border border-slate-200 bg-white p-0.5">
        {WINDOW_OPTIONS.map((opt) => (
            <Button
                key={opt.key}
                size="sm"
                variant={value === opt.key ? "default" : "ghost"}
                className={cn(
                    "h-7 px-3 text-xs",
                    value !== opt.key && "text-slate-500 hover:text-slate-700"
                )}
                disabled={disabled}
                onClick={() => onChange(opt.key)}
            >
              {opt.label.toUpperCase()}
            </Button>
        ))}
      </div>
  );
}

// ============ 主组件 ============

export function DashboardPage() {
  // 状态
  const [overview, setOverview] = useState<DashboardOverview | null>(null);
  const [performance, setPerformance] = useState<DashboardPerformance | null>(null);
  const [trends, setTrends] = useState<TrendData>({
    sessions: null,
    activeUsers: null,
    latency: null,
    quality: null
  });
  const [timeWindow, setTimeWindow] = useState<TimeWindow>("24h");
  const [loading, setLoading] = useState(false);
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);

  // 统一加载所有模块，确保所有卡片共享同一时间语义
  const loadDashboardData = useCallback(async (window: TimeWindow) => {
    setLoading(true);
    const granularity = getTrendGranularity(window);
    try {
      const [overviewData, performanceData, sessions, activeUsers, latency, quality] = await Promise.all([
        getDashboardOverview(window),
        getDashboardPerformance(window),
        getDashboardTrends("sessions", window, granularity),
        getDashboardTrends("activeUsers", window, granularity),
        getDashboardTrends("avgLatency", window, granularity),
        getDashboardTrends("quality", window, granularity)
      ]);

      setOverview(overviewData);
      setPerformance(performanceData);
      setTrends({ sessions, activeUsers, latency, quality });
      setLastUpdated(overviewData.updatedAt || Date.now());
    } catch (error) {
      toast.error("加载 Dashboard 数据失败");
      console.error("Failed to load dashboard:", error);
    } finally {
      setLoading(false);
    }
  }, []);

  // 刷新所有数据
  const handleRefresh = useCallback(() => {
    void loadDashboardData(timeWindow);
  }, [loadDashboardData, timeWindow]);

  // 切换时间窗口
  const handleWindowChange = useCallback((window: TimeWindow) => {
    setTimeWindow(window);
  }, []);

  // 初始加载
  useEffect(() => {
    void loadDashboardData(timeWindow);
  }, [loadDashboardData, timeWindow]);

  // 趋势图数据映射到语义色，不在数据层硬编码颜色值
  const sessionsSeries = useMemo<TrendSeries[]>(() => {
    if (!trends.sessions?.series) return [];
    return trends.sessions.series.map((series) => ({
      name: series.name,
      data: series.data,
      tone: "primary"
    }));
  }, [trends.sessions]);

  const activeUserSeries = useMemo<TrendSeries[]>(() => {
    if (!trends.activeUsers?.series) return [];
    return trends.activeUsers.series.map((series) => ({
      name: series.name,
      data: series.data,
      tone: "success"
    }));
  }, [trends.activeUsers]);

  const latencySeries = useMemo<TrendSeries[]>(() => {
    if (!trends.latency?.series) return [];
    return trends.latency.series.map((series) => ({
      name: series.name,
      data: series.data,
      tone: "info"
    }));
  }, [trends.latency]);

  const qualitySeries = useMemo<TrendSeries[]>(() => {
    if (!trends.quality?.series) return [];
    return trends.quality.series.map((series) => ({
      name: series.name,
      data: series.data,
      tone: series.name.includes("错误") ? "danger" : "warning"
    }));
  }, [trends.quality]);

  // 快捷访问
  const kpis = overview?.kpis;
  const windowLabel = WINDOW_LABEL_MAP[timeWindow];
  const compareLabel = COMPARE_LABEL_MAP[timeWindow];
  const xAxisMode = getXAxisMode(timeWindow);
  const updatedAtLabel = formatUpdatedAt(lastUpdated);

  return (
      <div className="admin-page">
        {/* 页头 */}
        <div className="admin-page-header">
          <div>
            <h1 className="admin-page-title">Dashboard</h1>
            <p className="admin-page-subtitle">统一时间窗口的大盘与趋势分析</p>
          </div>
          <div className="admin-page-actions flex-wrap gap-2">
            <TimeRangeSwitcher
                value={timeWindow}
                disabled={loading}
                onChange={handleWindowChange}
            />
            <Badge variant="outline" className="text-xs">
              当前窗口：{windowLabel}
            </Badge>
            <Badge variant="outline" className="text-xs">
              <Clock className="h-3 w-3 mr-1" />
              上次更新：{updatedAtLabel}
            </Badge>
            <Button
                variant="outline"
                size="sm"
                onClick={handleRefresh}
                disabled={loading}
            >
              <RefreshCw className={cn("h-4 w-4 mr-1.5", loading && "animate-spin")} />
              刷新
            </Button>
          </div>
        </div>

        {/* KPI 卡片 */}
        <section className="space-y-2">
          <h2 className="text-xs font-medium text-slate-500 uppercase tracking-wide">
            核心指标
          </h2>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
            <KpiCard
                title="总用户数"
                value={kpis?.totalUsers.value}
                delta={kpis?.totalUsers.delta}
                compareLabel={compareLabel}
                icon={Users}
                iconColor="text-blue-600 bg-blue-50"
            />
            <KpiCard
                title={`${timeWindow.toUpperCase()} 活跃用户`}
                value={kpis?.activeUsers.value}
                delta={kpis?.activeUsers.delta}
                deltaPct={kpis?.activeUsers.deltaPct}
                compareLabel={compareLabel}
                icon={Activity}
                iconColor="text-emerald-600 bg-emerald-50"
            />
            <KpiCard
                title="总会话数"
                value={kpis?.totalSessions.value}
                delta={kpis?.totalSessions.delta}
                compareLabel={compareLabel}
                icon={MessageSquare}
                iconColor="text-violet-600 bg-violet-50"
            />
            <KpiCard
                title={`${timeWindow.toUpperCase()} 会话数`}
                value={kpis?.sessions24h.value}
                delta={kpis?.sessions24h.delta}
                deltaPct={kpis?.sessions24h.deltaPct}
                compareLabel={compareLabel}
                icon={BarChart3}
                iconColor="text-amber-600 bg-amber-50"
            />
            <KpiCard
                title="总消息数"
                value={kpis?.totalMessages.value}
                delta={kpis?.totalMessages.delta}
                compareLabel={compareLabel}
                icon={MessageSquare}
                iconColor="text-cyan-600 bg-cyan-50"
            />
            <KpiCard
                title={`${timeWindow.toUpperCase()} 消息数`}
                value={kpis?.messages24h.value}
                delta={kpis?.messages24h.delta}
                deltaPct={kpis?.messages24h.deltaPct}
                compareLabel={compareLabel}
                icon={TrendingUp}
                iconColor="text-rose-600 bg-rose-50"
            />
          </div>
        </section>

        {/* 性能指标 */}
        <section className="space-y-2">
          <h2 className="text-xs font-medium text-slate-500 uppercase tracking-wide">
            AI 性能
          </h2>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5">
            <MetricCard
                title="平均响应时间"
                value={formatDuration(performance?.avgLatencyMs)}
                note="AI 回复平均耗时"
                icon={Clock}
            />
            <MetricCard
                title="P95 响应时间"
                value={formatDuration(performance?.p95LatencyMs)}
                note="95% 请求上限"
                icon={Zap}
            />
            <MetricCard
                title="成功率"
                value={formatRate(performance?.successRate)}
                note="AI 回复成功率"
                icon={Activity}
                variant={
                  performance?.successRate && performance.successRate >= 99
                      ? "success"
                      : performance?.successRate && performance.successRate < 95
                          ? "warning"
                          : "default"
                }
            />
            <MetricCard
                title="错误率"
                value={formatRate(performance?.errorRate)}
                note="AI 回复失败率"
                icon={AlertCircle}
                variant={
                  performance?.errorRate && performance.errorRate > 5
                      ? "error"
                      : performance?.errorRate && performance.errorRate > 1
                          ? "warning"
                          : "default"
                }
            />
            <MetricCard
                title="无知识率"
                value={formatRate(performance?.noDocRate)}
                note="未检索到文档"
                icon={FileQuestion}
                variant={
                  performance?.noDocRate && performance.noDocRate > 20
                      ? "warning"
                      : "default"
                }
            />
          </div>
        </section>

        {/* 趋势图表 */}
        <section className="space-y-2">
          <h2 className="text-xs font-medium text-slate-500 uppercase tracking-wide">
            趋势分析
          </h2>
          <div className="grid gap-4 lg:grid-cols-2">
            <TrendChartCard
                title="会话趋势"
                series={sessionsSeries}
                loading={loading}
                badge={windowLabel}
                xAxisMode={xAxisMode}
            />
            <TrendChartCard
                title="活跃用户趋势"
                series={activeUserSeries}
                loading={loading}
                badge={windowLabel}
                xAxisMode={xAxisMode}
            />
            <TrendChartCard
                title="响应时间趋势"
                series={latencySeries}
                loading={loading}
                badge={windowLabel}
                xAxisMode={xAxisMode}
                yAxisType="duration"
            />
            <TrendChartCard
                title="错误率 / 无知识率"
                series={qualitySeries}
                loading={loading}
                badge={windowLabel}
                xAxisMode={xAxisMode}
                yAxisType="percent"
            />
          </div>
        </section>
      </div>
  );
}
