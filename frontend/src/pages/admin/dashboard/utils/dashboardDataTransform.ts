import { type DashboardOverview, type DashboardPerformance, type DashboardTrends } from "@/services/dashboardService";

export interface BarChartPoint {
  label: string;
  value: number;
}

export interface DonutSegment {
  name: string;
  value: number;
  color: string;
}

export interface AreaChartPoint {
  label: string;
  value: number;
}

export const trendToBarData = (trend: DashboardTrends | null, maxPoints = 12): BarChartPoint[] => {
  if (!trend?.series?.length) return [];
  const series = trend.series[0];
  const data = series.data.slice(-maxPoints);
  return data.map((point) => {
    const date = new Date(point.ts);
    const label = date.toLocaleString("zh-CN", {
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hour12: false
    });
    return { label, value: point.value };
  });
};

export const trendToAreaData = (trend: DashboardTrends | null, maxPoints = 12): AreaChartPoint[] => {
  if (!trend?.series?.length) return [];
  const series = trend.series[0];
  const data = series.data.slice(-maxPoints);
  return data.map((point) => {
    const date = new Date(point.ts);
    const label = date.toLocaleString("zh-CN", {
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hour12: false
    });
    return { label, value: point.value };
  });
};

export const performanceToDonut = (performance: DashboardPerformance | null): DonutSegment[] => {
  if (!performance) return [];
  const successRate = performance.successRate ?? 0;
  const errorRate = performance.errorRate ?? 0;
  const otherRate = Math.max(0, 100 - successRate - errorRate);
  return [
    { name: "成功", value: successRate, color: "#10B981" },
    { name: "错误", value: errorRate, color: "#EF4444" },
    { name: "其他", value: otherRate, color: "#E2E8F0" }
  ];
};

export interface KPIRowItem {
  label: string;
  value: string;
  deltaPct: number | undefined;
  icon: string;
}

export const overviewToKPIRow = (overview: DashboardOverview | null): KPIRowItem[] => {
  if (!overview) return [];
  const kpis = overview.kpis;
  const fmt = (v?: number | null) => (v === null || v === undefined ? "-" : v.toLocaleString("zh-CN"));
  return [
    { label: "总用户数", value: fmt(kpis.totalUsers.value), deltaPct: kpis.totalUsers.deltaPct, icon: "users" },
    { label: "活跃用户", value: fmt(kpis.activeUsers.value), deltaPct: kpis.activeUsers.deltaPct, icon: "activity" },
    { label: "总会话数", value: fmt(kpis.totalSessions.value), deltaPct: kpis.totalSessions.deltaPct, icon: "message" },
    { label: "窗口消息", value: fmt(kpis.messages24h.value), deltaPct: kpis.messages24h.deltaPct, icon: "zap" }
  ];
};
