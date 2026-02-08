import type { ComponentType } from "react";
import { AlertCircle, Clock, FileQuestion, Timer } from "lucide-react";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import { type DashboardPerformance } from "@/services/dashboardService";

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

const STATUS_BG_CLASS: Record<MetricStatusView[keyof MetricStatusView], string> = {
  good: "bg-emerald-50 border-emerald-200",
  warning: "bg-amber-50 border-amber-200",
  bad: "bg-red-50 border-red-200"
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

const Ring = ({ value }: { value: number }) => {
  const radius = 42;
  const circumference = 2 * Math.PI * radius;
  const clamped = Math.max(0, Math.min(value, 100));
  const progress = (clamped / 100) * circumference;

  return (
    <div className="relative h-20 w-20">
      <svg className="h-20 w-20 -rotate-90" viewBox="0 0 100 100">
        <circle cx="50" cy="50" r={radius} fill="none" stroke="#E2E8F0" strokeWidth={8} />
        <circle
          cx="50"
          cy="50"
          r={radius}
          fill="none"
          stroke="#10B981"
          strokeWidth={8}
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={circumference - progress}
        />
      </svg>
      <div className="absolute inset-0 flex items-center justify-center">
        <span className="text-sm font-bold text-emerald-600">{formatPercent(value)}</span>
      </div>
    </div>
  );
};

const MetricLine = ({
  icon: Icon,
  label,
  value,
  status
}: {
  icon: ComponentType<{ className?: string }>;
  label: string;
  value: string;
  status: MetricStatusView[keyof MetricStatusView];
}) => {
  return (
    <div className={cn("flex items-center justify-between rounded-lg border px-3 py-2.5", STATUS_BG_CLASS[status])}>
      <div className="flex items-center gap-2">
        <Icon className="h-4 w-4 text-slate-500" />
        <span className="text-sm text-slate-700">{label}</span>
      </div>
      <span className={cn("text-sm font-bold", STATUS_TEXT_CLASS[status])}>{value}</span>
    </div>
  );
};

export const AIPerformanceRadar = ({ performance, metricStatus }: AIPerformanceRadarProps) => {
  return (
    <Card className="rounded-xl border border-slate-200 bg-white shadow-sm">
      <CardHeader className="pb-3">
        <CardTitle className="text-base font-semibold text-slate-900">AI 性能雷达</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex items-center gap-4 rounded-lg bg-slate-50 p-4">
          <Ring value={performance?.successRate ?? 0} />
          <div className="space-y-1">
            <p className="text-sm font-semibold text-slate-900">成功率</p>
            <p className="text-xs text-slate-500">当前窗口关键可用性指标</p>
          </div>
        </div>

        <div className="space-y-2">
          <MetricLine
            icon={Timer}
            label="平均响应"
            value={formatDuration(performance?.avgLatencyMs)}
            status={metricStatus.latency}
          />
          <MetricLine
            icon={Clock}
            label="P95 响应"
            value={formatDuration(performance?.p95LatencyMs)}
            status={metricStatus.latency}
          />
          <MetricLine
            icon={AlertCircle}
            label="错误率"
            value={formatPercent(performance?.errorRate)}
            status={metricStatus.error}
          />
          <MetricLine
            icon={FileQuestion}
            label="无知识率"
            value={formatPercent(performance?.noDocRate)}
            status={metricStatus.noDoc}
          />
        </div>
      </CardContent>
    </Card>
  );
};
