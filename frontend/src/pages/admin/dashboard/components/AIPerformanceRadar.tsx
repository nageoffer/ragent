import type { ComponentType } from "react";
import { AlertCircle, Clock, FileQuestion, Timer } from "lucide-react";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import { type DashboardPerformance } from "@/services/dashboardService";

import { DASHBOARD_THRESHOLDS } from "../constants/thresholds";
import { type MetricStatusView } from "../hooks/useHealthStatus";

interface AIPerformanceRadarProps {
  performance: DashboardPerformance | null;
  metricStatus: MetricStatusView;
}

const STATUS_TEXT_CLASS: Record<MetricStatusView[keyof MetricStatusView], string> = {
  good: "text-emerald-600",
  warning: "text-amber-600",
  bad: "text-red-600"
};

const STATUS_BAR_COLOR: Record<MetricStatusView[keyof MetricStatusView], string> = {
  good: "#10B981",
  warning: "#F59E0B",
  bad: "#EF4444"
};

const STATUS_BAR_BG: Record<MetricStatusView[keyof MetricStatusView], string> = {
  good: "#D1FAE5",
  warning: "#FEF3C7",
  bad: "#FEE2E2"
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

const getLatencyStatus = (
  value?: number | null
): MetricStatusView[keyof MetricStatusView] => {
  if (value === null || value === undefined) return "warning";
  if (value <= DASHBOARD_THRESHOLDS.latency.good) return "good";
  if (value <= DASHBOARD_THRESHOLDS.latency.warning) return "warning";
  return "bad";
};

const Ring = ({ value }: { value: number }) => {
  const radius = 52;
  const circumference = 2 * Math.PI * radius;
  const clamped = Math.max(0, Math.min(value, 100));
  const progress = (clamped / 100) * circumference;
  const ringColor = clamped >= 95 ? "#10B981" : clamped >= 85 ? "#F59E0B" : "#EF4444";

  return (
    <div className="relative h-28 w-28">
      <svg className="h-28 w-28 -rotate-90" viewBox="0 0 120 120">
        <circle cx="60" cy="60" r={radius} fill="none" stroke="#F1F5F9" strokeWidth={9} />
        <circle
          cx="60"
          cy="60"
          r={radius}
          fill="none"
          stroke={ringColor}
          strokeWidth={9}
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={circumference - progress}
          className="transition-all duration-700 ease-out"
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <span className="text-xl font-bold text-slate-900">{formatPercent(value)}</span>
        <span className="text-[10px] text-slate-400">成功率</span>
      </div>
    </div>
  );
};

const getBarPercent = (
  label: string,
  rawValue: number | undefined | null
): number => {
  if (rawValue === null || rawValue === undefined) return 0;
  if (label === "平均响应" || label === "P95 响应") {
    // Map 0-10000ms to 0-100%
    return Math.min((rawValue / 10000) * 100, 100);
  }
  // Percent-based metrics
  return Math.min(rawValue, 100);
};

const MetricLine = ({
  icon: Icon,
  label,
  value,
  rawValue,
  status
}: {
  icon: ComponentType<{ className?: string }>;
  label: string;
  value: string;
  rawValue?: number | null;
  status: MetricStatusView[keyof MetricStatusView];
}) => {
  const barPercent = getBarPercent(label, rawValue);

  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Icon className="h-3.5 w-3.5 text-slate-400" />
          <span className="text-sm text-slate-600">{label}</span>
        </div>
        <span className={cn("text-sm font-bold tabular-nums", STATUS_TEXT_CLASS[status])}>{value}</span>
      </div>
      <div className="h-1.5 w-full overflow-hidden rounded-full" style={{ backgroundColor: STATUS_BAR_BG[status] }}>
        <div
          className="h-full rounded-full transition-all duration-500 ease-out"
          style={{
            width: `${barPercent}%`,
            backgroundColor: STATUS_BAR_COLOR[status]
          }}
        />
      </div>
    </div>
  );
};

export const AIPerformanceRadar = ({ performance, metricStatus }: AIPerformanceRadarProps) => {
  const avgLatencyStatus = getLatencyStatus(performance?.avgLatencyMs);
  const p95LatencyStatus = getLatencyStatus(performance?.p95LatencyMs);

  return (
    <Card className="rounded-xl border border-slate-200 bg-white shadow-sm">
      <CardHeader className="pb-3">
        <CardTitle className="text-base font-semibold text-slate-900">AI 性能雷达</CardTitle>
      </CardHeader>
      <CardContent className="space-y-5">
        <div className="flex items-center justify-center rounded-lg bg-slate-50/80 py-5">
          <Ring value={performance?.successRate ?? 0} />
        </div>

        <div className="space-y-3">
          <MetricLine
            icon={Timer}
            label="平均响应"
            value={formatDuration(performance?.avgLatencyMs)}
            rawValue={performance?.avgLatencyMs}
            status={avgLatencyStatus}
          />
          <MetricLine
            icon={Clock}
            label="P95 响应"
            value={formatDuration(performance?.p95LatencyMs)}
            rawValue={performance?.p95LatencyMs}
            status={p95LatencyStatus}
          />
          <MetricLine
            icon={AlertCircle}
            label="错误率"
            value={formatPercent(performance?.errorRate)}
            rawValue={performance?.errorRate}
            status={metricStatus.error}
          />
          <MetricLine
            icon={FileQuestion}
            label="无知识率"
            value={formatPercent(performance?.noDocRate)}
            rawValue={performance?.noDocRate}
            status={metricStatus.noDoc}
          />
        </div>
      </CardContent>
    </Card>
  );
};
