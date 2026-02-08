import { useCallback, useEffect, useState } from "react";

import {
  getDashboardOverview,
  getDashboardPerformance,
  getDashboardTrends,
  type DashboardOverview,
  type DashboardPerformance,
  type DashboardTrends
} from "@/services/dashboardService";

export type DashboardTimeWindow = "24h" | "7d" | "30d";

export interface DashboardTrendBundle {
  sessions: DashboardTrends | null;
  activeUsers: DashboardTrends | null;
  latency: DashboardTrends | null;
  quality: DashboardTrends | null;
}

interface UseDashboardDataResult {
  timeWindow: DashboardTimeWindow;
  setTimeWindow: (value: DashboardTimeWindow) => void;
  loading: boolean;
  error: string | null;
  lastUpdated: number | null;
  overview: DashboardOverview | null;
  performance: DashboardPerformance | null;
  trends: DashboardTrendBundle;
  refresh: () => Promise<void>;
}

const EMPTY_TRENDS: DashboardTrendBundle = {
  sessions: null,
  activeUsers: null,
  latency: null,
  quality: null
};

export const useDashboardData = (): UseDashboardDataResult => {
  const [timeWindow, setTimeWindow] = useState<DashboardTimeWindow>("24h");
  const [overview, setOverview] = useState<DashboardOverview | null>(null);
  const [performance, setPerformance] = useState<DashboardPerformance | null>(null);
  const [trends, setTrends] = useState<DashboardTrendBundle>(EMPTY_TRENDS);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);

  const loadData = useCallback(async (window: DashboardTimeWindow) => {
    setLoading(true);
    setError(null);
    const granularity = window === "24h" ? "hour" : "day";

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
