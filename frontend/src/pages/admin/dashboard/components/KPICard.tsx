import type { ReactNode } from "react";
import { ArrowDown, ArrowUp, Minus } from "lucide-react";

import { cn } from "@/lib/utils";

import { Sparkline } from "./Sparkline";

interface KPIChange {
  value: number;
  trend: "up" | "down" | "flat";
  isPositive: boolean;
}

export interface KPICardProps {
  label: string;
  value: string | number;
  change?: KPIChange;
  sparklineData?: number[];
  status?: "normal" | "warning" | "critical";
  icon?: ReactNode;
}

const STATUS_BORDER: Record<NonNullable<KPICardProps["status"]>, string> = {
  normal: "border-l-slate-200",
  warning: "border-l-amber-400",
  critical: "border-l-red-400"
};

const SPARKLINE_COLOR: Record<NonNullable<KPICardProps["status"]>, string> = {
  normal: "#3B82F6",
  warning: "#F59E0B",
  critical: "#EF4444"
};

const getTrendTone = (change?: KPIChange) => {
  if (!change || change.value === 0) return "text-slate-400";
  const rising = change.value > 0;
  const falling = change.value < 0;
  const isGood = (rising && change.isPositive) || (falling && !change.isPositive);
  return isGood ? "text-emerald-600" : "text-red-600";
};

export const KPICard = ({
  label,
  value,
  change,
  sparklineData = [],
  status = "normal",
  icon
}: KPICardProps) => {
  const trendTone = getTrendTone(change);
  const TrendIcon = change?.trend === "up" ? ArrowUp : change?.trend === "down" ? ArrowDown : Minus;

  return (
    <div
      className={cn(
        "rounded-xl border border-slate-200 border-l-4 bg-white p-5 transition-all duration-200 hover:border-slate-300 hover:shadow-md",
        STATUS_BORDER[status]
      )}
    >
      <div className="mb-3 flex items-center justify-between">
        <p className="text-sm font-medium text-slate-600">{label}</p>
        {icon ? <div className="text-slate-400">{icon}</div> : null}
      </div>

      <div className="text-4xl font-bold tracking-tight text-slate-900">{value}</div>

      <div className="mb-4 mt-2 flex items-center gap-1.5 text-xs">
        {change?.value ? <TrendIcon className={cn("h-3.5 w-3.5", trendTone)} /> : <Minus className="h-3.5 w-3.5 text-slate-400" />}
        <span className={cn("font-medium", trendTone)}>
          {change ? `${Math.abs(change.value).toFixed(1)}%` : "0.0%"}
        </span>
        <span className="text-slate-400">vs last period</span>
      </div>

      <div className="h-12">
        <Sparkline data={sparklineData} color={SPARKLINE_COLOR[status]} fillOpacity={0.15} strokeWidth={2} />
      </div>
    </div>
  );
};
