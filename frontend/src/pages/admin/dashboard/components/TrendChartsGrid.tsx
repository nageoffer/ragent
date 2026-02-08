import { useMemo } from "react";

import { type TrendSeries } from "@/components/admin/SimpleLineChart";
import { type DashboardTrends } from "@/services/dashboardService";

import { DASHBOARD_THRESHOLDS } from "../constants/thresholds";
import { type DashboardTimeWindow, type DashboardTrendBundle } from "../hooks/useDashboardData";
import { TrendChartCard } from "./TrendChartCard";

interface TrendChartsGridProps {
  trends: DashboardTrendBundle;
  timeWindow: DashboardTimeWindow;
  loading?: boolean;
}

const formatCurrent = (value: number | undefined, type: "number" | "percent" | "duration") => {
  if (value === undefined || Number.isNaN(value)) return "-";
  if (type === "percent") return `${value.toFixed(1)}%`;
  if (type === "duration") {
    if (value < 1000) return `${Math.round(value)}ms`;
    return `${(value / 1000).toFixed(2)}s`;
  }
  return value.toLocaleString("zh-CN");
};

const extractCurrentValue = (series: TrendSeries[]) => {
  if (!series.length || !series[0].data.length) return undefined;
  return series[0].data[series[0].data.length - 1]?.value;
};

const mapSeries = (trend: DashboardTrends | null, tone: TrendSeries["tone"]): TrendSeries[] => {
  if (!trend?.series?.length) return [];
  return trend.series.map((item) => ({
    name: item.name,
    data: item.data,
    tone
  }));
};

const mapQualitySeries = (trend: DashboardTrends | null): TrendSeries[] => {
  if (!trend?.series?.length) return [];
  return trend.series.map((item) => ({
    name: item.name,
    data: item.data,
    tone: item.name.includes("错误") ? "danger" : "info"
  }));
};

const buildCompareSeries = (source: TrendSeries[]): TrendSeries[] => {
  return source.map((item) => {
    const data = item.data.map((point, index, list) => {
      const prev = list[index - 1] ?? list[index];
      return {
        ts: point.ts,
        value: prev.value
      };
    });
    return {
      name: `${item.name}(同比)`,
      data,
      tone: "neutral"
    };
  });
};

export const TrendChartsGrid = ({ trends, timeWindow, loading }: TrendChartsGridProps) => {
  const xAxisMode = timeWindow === "24h" ? "hour" : "date";

  const sessionsSeries = useMemo(() => mapSeries(trends.sessions, "primary"), [trends.sessions]);
  const activeSeries = useMemo(() => mapSeries(trends.activeUsers, "success"), [trends.activeUsers]);
  const latencySeries = useMemo(() => mapSeries(trends.latency, "warning"), [trends.latency]);
  const qualitySeries = useMemo(() => mapQualitySeries(trends.quality), [trends.quality]);

  const sessionCompare = useMemo(() => buildCompareSeries(sessionsSeries), [sessionsSeries]);
  const activeCompare = useMemo(() => buildCompareSeries(activeSeries), [activeSeries]);
  const latencyCompare = useMemo(() => buildCompareSeries(latencySeries), [latencySeries]);
  const qualityCompare = useMemo(() => buildCompareSeries(qualitySeries), [qualitySeries]);

  return (
    <div className="grid gap-4 md:grid-cols-2">
      <TrendChartCard
        title="会话趋势"
        subtitle="会话数量变化"
        series={sessionsSeries}
        compareSeries={sessionCompare}
        xAxisMode={xAxisMode}
        yAxisType="number"
        yAxisLabel="单位：次"
        loading={loading}
        currentValue={formatCurrent(extractCurrentValue(sessionsSeries), "number")}
      />

      <TrendChartCard
        title="活跃用户趋势"
        subtitle="活跃用户规模变化"
        series={activeSeries}
        compareSeries={activeCompare}
        xAxisMode={xAxisMode}
        yAxisType="number"
        yAxisLabel="单位：人"
        loading={loading}
        currentValue={formatCurrent(extractCurrentValue(activeSeries), "number")}
      />

      <TrendChartCard
        title="响应时间趋势"
        subtitle="AI 响应耗时"
        series={latencySeries}
        compareSeries={latencyCompare}
        xAxisMode={xAxisMode}
        yAxisType="duration"
        yAxisLabel="单位：毫秒"
        loading={loading}
        currentValue={formatCurrent(extractCurrentValue(latencySeries), "duration")}
        thresholds={[
          { value: DASHBOARD_THRESHOLDS.latency.good, label: "good<2s", tone: "info" },
          { value: DASHBOARD_THRESHOLDS.latency.warning, label: "warn>5s", tone: "critical" }
        ]}
      />

      <TrendChartCard
        title="质量趋势"
        subtitle="错误率与无知识率"
        series={qualitySeries}
        compareSeries={qualityCompare}
        xAxisMode={xAxisMode}
        yAxisType="percent"
        yAxisLabel="单位：%"
        loading={loading}
        currentValue={formatCurrent(extractCurrentValue(qualitySeries), "percent")}
        thresholds={[
          { value: DASHBOARD_THRESHOLDS.errorRate.warning, label: "error warn", tone: "warning" },
          { value: DASHBOARD_THRESHOLDS.noDocRate.warning, label: "nodoc warn", tone: "critical" }
        ]}
      />
    </div>
  );
};
