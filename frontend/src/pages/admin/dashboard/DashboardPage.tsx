import { useEffect } from "react";
import { toast } from "sonner";

import { ActiveUsersAreaChart } from "./components/ActiveUsersAreaChart";
import { AIPerformanceRadar } from "./components/AIPerformanceRadar";
import { AlertBar } from "./components/AlertBar";
import { BackgroundDecoration } from "./components/BackgroundDecoration";
import { DashboardHeader } from "./components/DashboardHeader";
import { InsightList } from "./components/InsightList";
import { KPIGrid } from "./components/KPIGrid";
import { PerformanceMetricsTable } from "./components/PerformanceMetricsTable";
import { SessionBarChart } from "./components/SessionBarChart";
import { SuccessRateDonut } from "./components/SuccessRateDonut";
import { TopInsightsTable } from "./components/TopInsightsTable";
import { TrendChartsGrid } from "./components/TrendChartsGrid";
import { useDashboardData } from "./hooks/useDashboardData";
import { useHealthStatus } from "./hooks/useHealthStatus";

const WINDOW_LABEL_MAP = {
  "24h": "滚动 24h",
  "7d": "近 7 天",
  "30d": "近 30 天"
} as const;

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
    if (!error) return;
    toast.error(error);
  }, [error]);

  return (
    <div className="relative min-h-screen bg-slate-50 text-slate-900">
      <BackgroundDecoration />

      <div className="relative z-10">
        <DashboardHeader
          title="运营 Dashboard"
          subtitle="企业级科技运营看板，统一窗口语义驱动 KPI / 性能 / 趋势"
          timeWindow={timeWindow}
          lastUpdated={lastUpdated}
          loading={loading}
          onRefresh={() => {
            void refresh();
          }}
          onTimeWindowChange={setTimeWindow}
        />

        <main className="mx-auto max-w-[1600px] space-y-5 px-6 py-5">
          <AlertBar health={health} />

          <KPIGrid overview={overview} performance={performance} />

          {/* Trend charts + Radar / Insights sidebar */}
          <div className="grid gap-5 xl:grid-cols-12">
            <div className="xl:col-span-8">
              <TrendChartsGrid trends={trends} timeWindow={timeWindow} loading={loading} />
            </div>

            <aside className="space-y-5 xl:col-span-4">
              <AIPerformanceRadar performance={performance} metricStatus={metricStatus} />
              <InsightList
                performance={performance}
                timeWindowLabel={WINDOW_LABEL_MAP[timeWindow]}
                timestamp={lastUpdated}
              />
            </aside>
          </div>

          {/* Recharts visualizations */}
          <div className="grid gap-5 md:grid-cols-3">
            <SuccessRateDonut performance={performance} loading={loading} />
            <SessionBarChart trend={trends.sessions} loading={loading} />
            <ActiveUsersAreaChart trend={trends.activeUsers} loading={loading} />
          </div>

          {/* Performance table + Insights table */}
          <div className="grid gap-5 md:grid-cols-2">
            <PerformanceMetricsTable performance={performance} loading={loading} />
            <TopInsightsTable
              performance={performance}
              timeWindowLabel={WINDOW_LABEL_MAP[timeWindow]}
              loading={loading}
            />
          </div>
        </main>
      </div>
    </div>
  );
}
