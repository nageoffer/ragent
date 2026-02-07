import { api } from "@/services/api";

export type DashboardKpi = {
  value: number;
  delta?: number;
  deltaPct?: number;
};

export type DashboardOverview = {
  window: string;
  compareWindow: string;
  updatedAt: number;
  kpis: {
    totalUsers: DashboardKpi;
    activeUsers: DashboardKpi;
    totalSessions: DashboardKpi;
    sessions24h: DashboardKpi;
    totalMessages: DashboardKpi;
    messages24h: DashboardKpi;
  };
};

export type DashboardPerformance = {
  window: string;
  avgLatencyMs: number;
  p95LatencyMs: number;
  successRate: number;
  errorRate: number;
  noDocRate: number;
};

export type DashboardTrendPoint = {
  ts: number;
  value: number;
};

export type DashboardTrendSeries = {
  name: string;
  data: DashboardTrendPoint[];
};

export type DashboardTrends = {
  metric: string;
  window: string;
  granularity: string;
  series: DashboardTrendSeries[];
};

export async function getDashboardOverview(window: string = "24h"): Promise<DashboardOverview> {
  return api.get<DashboardOverview, DashboardOverview>("/admin/dashboard/overview", {
    params: { window }
  });
}

export async function getDashboardPerformance(window: string = "24h"): Promise<DashboardPerformance> {
  return api.get<DashboardPerformance, DashboardPerformance>("/admin/dashboard/performance", {
    params: { window }
  });
}

export async function getDashboardTrends(
  metric: string,
  window: string = "7d",
  granularity: string = "day"
): Promise<DashboardTrends> {
  return api.get<DashboardTrends, DashboardTrends>("/admin/dashboard/trends", {
    params: { metric, window, granularity }
  });
}
