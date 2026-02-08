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
    <header className="sticky top-0 z-40 border-b border-slate-200/80 bg-white/90 backdrop-blur-sm supports-[backdrop-filter]:bg-white/75">
      <div className="mx-auto flex max-w-[1600px] flex-col gap-4 px-6 py-4 md:flex-row md:items-center md:justify-between">
        <div>
          <h1 className="text-xl font-semibold tracking-tight text-slate-900">{title}</h1>
          <p className="mt-0.5 text-sm text-slate-500">{subtitle}</p>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <div className="inline-flex items-center rounded-lg bg-slate-100 p-0.5">
            {WINDOW_OPTIONS.map((option) => (
              <button
                key={option.value}
                className={cn(
                  "rounded-md px-3.5 py-1.5 text-sm font-medium transition-all",
                  timeWindow === option.value
                    ? "bg-white text-slate-900 shadow-sm"
                    : "text-slate-500 hover:text-slate-900"
                )}
                disabled={loading}
                onClick={() => onTimeWindowChange(option.value)}
              >
                {option.label}
              </button>
            ))}
          </div>

          <div className="flex items-center gap-1.5 text-xs text-slate-400">
            <Clock3 className="h-3 w-3" />
            <span>{formatLastUpdated(lastUpdated)}</span>
          </div>

          <Button
            variant="outline"
            size="icon"
            onClick={onRefresh}
            disabled={loading}
            className="h-8 w-8 rounded-lg border-slate-200 text-slate-500 hover:bg-slate-50 hover:text-slate-900"
          >
            <RefreshCw className={cn("h-3.5 w-3.5", loading && "animate-spin")} />
          </Button>
        </div>
      </div>
    </header>
  );
};
