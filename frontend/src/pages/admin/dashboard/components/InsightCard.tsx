import { AlertCircle, Info, Lightbulb } from "lucide-react";

import { cn } from "@/lib/utils";

export interface InsightCardData {
  type: "anomaly" | "trend" | "recommendation";
  severity: "info" | "warning" | "critical";
  title: string;
  metric: string;
  change: string;
  context: string;
  action?: string;
  timestamp: string;
}

const TYPE_LABEL: Record<InsightCardData["type"], string> = {
  anomaly: "异常",
  trend: "趋势",
  recommendation: "建议"
};

const TYPE_ICON: Record<InsightCardData["type"], typeof Info> = {
  anomaly: AlertCircle,
  trend: Info,
  recommendation: Lightbulb
};

const TYPE_BADGE_STYLE: Record<InsightCardData["type"], string> = {
  anomaly: "border-red-200 bg-red-50 text-red-600",
  trend: "border-blue-200 bg-blue-50 text-blue-600",
  recommendation: "border-amber-200 bg-amber-50 text-amber-600"
};

const SEVERITY_STYLE: Record<InsightCardData["severity"], string> = {
  info: "border-slate-200 bg-slate-50 text-slate-800",
  warning: "border-amber-200 bg-amber-50 text-amber-800",
  critical: "border-red-200 bg-red-50 text-red-800"
};

export const InsightCard = ({ item }: { item: InsightCardData }) => {
  const Icon = TYPE_ICON[item.type];
  return (
    <div className={cn("rounded-lg border p-3.5", SEVERITY_STYLE[item.severity])}>
      <div className="mb-2 flex items-center justify-between">
        <div
          className={cn(
            "inline-flex items-center gap-1.5 rounded-md border px-2 py-0.5 text-xs font-medium",
            TYPE_BADGE_STYLE[item.type]
          )}
        >
          <Icon className="h-3.5 w-3.5" />
          <span>{TYPE_LABEL[item.type]}</span>
        </div>
        <span className="text-[11px] text-slate-500">{item.timestamp}</span>
      </div>

      <p className="text-sm font-semibold text-slate-900">{item.title}</p>
      <p className="mt-1 text-xs text-slate-600">
        {item.metric}: {item.change}
      </p>
      <p className="mt-1 text-xs text-slate-500">归因：{item.context}</p>
      {item.action ? <p className="mt-1 text-xs font-medium text-slate-700">建议：{item.action}</p> : null}
    </div>
  );
};
