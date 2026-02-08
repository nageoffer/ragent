import { useEffect } from "react";
import { toast } from "sonner";

import { AIPerformanceRadar } from "./components/AIPerformanceRadar";
import { AlertBar } from "./components/AlertBar";
import { BackgroundDecoration } from "./components/BackgroundDecoration";
import { DashboardHeader } from "./components/DashboardHeader";
import { InsightList } from "./components/InsightList";
import { KPIGrid } from "./components/KPIGrid";
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

        <div className="mx-auto max-w-[1600px] px-6 py-4">
          <AlertBar health={health} performance={performance} />
        </div>

        <main className="mx-auto max-w-[1600px] px-6 pb-6">
          <div className="grid gap-6 xl:grid-cols-12">
            <div className="space-y-6 xl:col-span-8">
              <KPIGrid overview={overview} trends={trends} />
              <TrendChartsGrid trends={trends} timeWindow={timeWindow} loading={loading} />
            </div>

            <aside className="space-y-6 xl:col-span-4 xl:sticky xl:top-24 xl:self-start">
              <AIPerformanceRadar performance={performance} metricStatus={metricStatus} />
              <InsightList
                performance={performance}
                timeWindowLabel={WINDOW_LABEL_MAP[timeWindow]}
                timestamp={lastUpdated}
              />
            </aside>
          </div>
        </main>
      </div>
    </div>
  );
}
