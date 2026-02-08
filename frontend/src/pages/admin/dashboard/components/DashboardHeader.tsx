import { Clock3, RefreshCw } from "lucide-react";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

import { type DashboardTimeWindow } from "../hooks/useDashboardData";

interface DashboardHeaderProps {
  title: string;
  subtitle: string;
  timeWindow: DashboardTimeWindow;
  lastUpdated: number | null;
  loading?: boolean;
  onRefresh: () => void;
  onTimeWindowChange: (window: DashboardTimeWindow) => void;
}

const WINDOW_OPTIONS: Array<{ value: DashboardTimeWindow; label: string }> = [
  { value: "24h", label: "24h" },
  { value: "7d", label: "7d" },
  { value: "30d", label: "30d" }
];

const WINDOW_LABEL: Record<DashboardTimeWindow, string> = {
  "24h": "滚动 24h",
  "7d": "近 7 天",
  "30d": "近 30 天"
};

const formatLastUpdated = (timestamp: number | null) => {
  if (!timestamp) return "-";
  return new Date(timestamp).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  });
};

export const DashboardHeader = ({
  title,
  subtitle,
  timeWindow,
  lastUpdated,
  loading,
  onRefresh,
  onTimeWindowChange
}: DashboardHeaderProps) => {
  return (
    <header className="sticky top-0 z-40 border-b border-slate-200 bg-white">
      <div className="mx-auto flex max-w-[1600px] flex-col gap-4 px-6 py-4 md:flex-row md:items-center md:justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">{title}</h1>
          <p className="mt-0.5 text-sm text-slate-500">{subtitle}</p>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <div className="inline-flex items-center rounded-lg bg-slate-100 p-1">
            {WINDOW_OPTIONS.map((option) => (
              <button
                key={option.value}
                className={cn(
                  "rounded-md px-4 py-1.5 text-sm font-medium transition-all",
                  timeWindow === option.value
                    ? "bg-white text-slate-900 shadow-sm"
                    : "text-slate-600 hover:text-slate-900"
                )}
                disabled={loading}
                onClick={() => onTimeWindowChange(option.value)}
              >
                {option.label}
              </button>
            ))}
          </div>

          <div className="rounded-lg border-l border-slate-200 pl-3 text-xs text-slate-500">
            <div>当前窗口：{WINDOW_LABEL[timeWindow]}</div>
            <div className="mt-0.5 flex items-center gap-1.5">
              <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
              <Clock3 className="h-3 w-3" />
              <span>最近更新：{formatLastUpdated(lastUpdated)}</span>
            </div>
          </div>

          <Button
            variant="outline"
            size="icon"
            onClick={onRefresh}
            disabled={loading}
            className="rounded-lg border-slate-200 text-slate-600 hover:bg-slate-50 hover:text-slate-900"
          >
            <RefreshCw className={cn("h-4 w-4", loading && "animate-spin")} />
          </Button>
        </div>
      </div>
    </header>
  );
};
