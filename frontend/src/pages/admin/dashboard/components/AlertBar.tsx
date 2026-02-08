import { AlertTriangle, CheckCircle2, Info } from "lucide-react";

import { cn } from "@/lib/utils";
import { type DashboardPerformance } from "@/services/dashboardService";

import { type HealthStatusView } from "../hooks/useHealthStatus";

interface AlertBarProps {
  health: HealthStatusView;
  performance: DashboardPerformance | null;
}

const formatPercent = (value?: number | null) => {
  if (value === null || value === undefined) return "-";
  return `${value.toFixed(1)}%`;
};

const formatDuration = (value?: number | null) => {
  if (value === null || value === undefined) return "-";
  if (value < 1000) return `${Math.round(value)}ms`;
  return `${(value / 1000).toFixed(2)}s`;
};

const STATUS_STYLE: Record<
  HealthStatusView["status"],
  {
    box: string;
    text: string;
    icon: typeof CheckCircle2;
  }
> = {
  healthy: {
    box: "border-emerald-200 bg-emerald-50",
    text: "text-emerald-800",
    icon: CheckCircle2
  },
  attention: {
    box: "border-amber-200 bg-amber-50",
    text: "text-amber-800",
    icon: Info
  },
  critical: {
    box: "border-red-200 bg-red-50",
    text: "text-red-800",
    icon: AlertTriangle
  }
};

export const AlertBar = ({ health, performance }: AlertBarProps) => {
  const current = STATUS_STYLE[health.status];
  const Icon = current.icon;

  return (
    <div className={cn("rounded-lg border px-4 py-3", current.box)}>
      <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <div className={cn("flex items-center gap-2", current.text)}>
          <Icon className="h-4 w-4" />
          <span className="text-sm font-medium">{health.title}</span>
          <span className="text-xs opacity-80">{health.description}</span>
        </div>

        <div className="grid grid-cols-2 gap-2 text-sm lg:flex lg:items-center lg:gap-6">
          <div className="text-slate-700">
            平均响应：<span className="font-semibold text-slate-900">{formatDuration(performance?.avgLatencyMs)}</span>
          </div>
          <div className="text-slate-700">
            成功率：<span className="font-semibold text-emerald-600">{formatPercent(performance?.successRate)}</span>
          </div>
          <div className="text-slate-700">
            错误率：<span className="font-semibold text-red-600">{formatPercent(performance?.errorRate)}</span>
          </div>
          <div className="text-slate-700">
            无知识率：<span className="font-semibold text-amber-600">{formatPercent(performance?.noDocRate)}</span>
          </div>
        </div>
      </div>
    </div>
  );
};
