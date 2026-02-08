import { ArrowDown, ArrowUp, Minus } from "lucide-react";

import { cn } from "@/lib/utils";

interface ChangeIndicatorProps {
  value?: number | null;
  isPositiveGood?: boolean;
}

export const ChangeIndicator = ({
  value,
  isPositiveGood = true
}: ChangeIndicatorProps) => {
  if (value === null || value === undefined) {
    return (
      <div className="flex items-center gap-1.5 text-xs text-slate-400">
        <Minus className="h-3.5 w-3.5" />
        <span>-</span>
        <span>vs 上周期</span>
      </div>
    );
  }

  if (value === 0) {
    return (
      <div className="flex items-center gap-1.5 text-xs">
        <span className="flex h-4 w-4 items-center justify-center rounded-full bg-slate-100 text-slate-500">
          <Minus className="h-3 w-3" />
        </span>
        <span className="text-sm text-slate-500">持平</span>
        <span className="text-slate-300">vs 上周期</span>
      </div>
    );
  }

  const positive = value > 0;
  const isGood = isPositiveGood ? positive : !positive;
  const tone = isGood ? "text-emerald-600" : "text-red-600";
  const bg = isGood ? "bg-emerald-100" : "bg-red-100";

  return (
    <div className="flex items-center gap-1.5 text-xs">
      <span className={cn("flex h-4 w-4 items-center justify-center rounded-full", bg)}>
        {positive ? (
          <ArrowUp className={cn("h-3 w-3", tone)} />
        ) : (
          <ArrowDown className={cn("h-3 w-3", tone)} />
        )}
      </span>
      <span className={cn("text-sm font-medium", tone)}>
        {positive ? "+" : ""}
        {value.toFixed(1)}%
      </span>
      <span className="text-slate-400">vs 上周期</span>
    </div>
  );
};
