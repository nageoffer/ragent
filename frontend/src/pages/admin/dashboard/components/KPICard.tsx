import type { ReactNode } from "react";

import { cn } from "@/lib/utils";

interface KPIChange {
  value: number;
  trend: "up" | "down" | "flat";
  isPositive: boolean;
}

export interface KPICardProps {
  label: string;
  value: string | number;
  change?: KPIChange;
  status?: "normal" | "warning" | "critical";
  icon?: ReactNode;
  accentColor: string;
  accentBg: string;
}

const STATUS_CARD: Record<NonNullable<KPICardProps["status"]>, string> = {
  normal: "bg-white border-slate-200",
  warning: "bg-amber-50/40 border-amber-200",
  critical: "bg-red-50/40 border-red-200"
};

export const KPICard = ({
  label,
  value,
  change,
  status = "normal",
  icon,
  accentColor,
  accentBg
}: KPICardProps) => {
  const changeText =
    change && change.trend !== "flat"
      ? `${change.value > 0 ? "+" : ""}${change.value.toFixed(1)}%`
      : null;

  const changeColor =
    change?.trend === "up"
      ? change.isPositive
        ? "text-emerald-600"
        : "text-red-500"
      : change?.trend === "down"
        ? change.isPositive
          ? "text-red-500"
          : "text-emerald-600"
        : "text-slate-400";

  return (
    <div
      className={cn(
        "relative overflow-hidden rounded-xl border p-5 transition-all duration-200 hover:shadow-md",
        STATUS_CARD[status]
      )}
    >
      {/* Top accent bar */}
      <div className="absolute inset-x-0 top-0 h-1" style={{ backgroundColor: accentColor }} />

      <div className="mb-4 flex items-center justify-between">
        <p className="text-sm font-medium text-slate-500">{label}</p>
        <div
          className="flex h-9 w-9 items-center justify-center rounded-lg"
          style={{ backgroundColor: accentBg }}
        >
          <div style={{ color: accentColor }}>{icon}</div>
        </div>
      </div>

      <div className="text-[2.25rem] font-bold leading-none tracking-tight text-slate-900">
        {value}
      </div>

      <div className="mt-2 h-5">
        {changeText ? (
          <span className={cn("text-sm font-medium", changeColor)}>
            {changeText}
            <span className="ml-1 text-xs font-normal text-slate-400">vs 上周期</span>
          </span>
        ) : (
          <span className="text-sm text-slate-400">--</span>
        )}
      </div>
    </div>
  );
};
