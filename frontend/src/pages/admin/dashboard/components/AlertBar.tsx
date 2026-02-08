import { AlertTriangle, CheckCircle2, Info } from "lucide-react";

import { cn } from "@/lib/utils";

import { type HealthStatusView } from "../hooks/useHealthStatus";

interface AlertBarProps {
  health: HealthStatusView;
}

const STATUS_STYLE: Record<
  HealthStatusView["status"],
  {
    box: string;
    bar: string;
    text: string;
    icon: typeof CheckCircle2;
  }
> = {
  healthy: {
    box: "border-emerald-200 bg-emerald-50/80",
    bar: "bg-emerald-500",
    text: "text-emerald-800",
    icon: CheckCircle2
  },
  attention: {
    box: "border-amber-200 bg-amber-50/80",
    bar: "bg-amber-500",
    text: "text-amber-800",
    icon: Info
  },
  critical: {
    box: "border-red-200 bg-red-50/80",
    bar: "bg-red-500",
    text: "text-red-800",
    icon: AlertTriangle
  }
};

export const AlertBar = ({ health }: AlertBarProps) => {
  const current = STATUS_STYLE[health.status];
  const Icon = current.icon;

  return (
    <div className={cn("relative overflow-hidden rounded-lg border px-5 py-3", current.box)}>
      <div className={cn("absolute inset-y-0 left-0 w-1", current.bar)} />
      <div className={cn("flex items-center gap-2", current.text)}>
        <Icon className="h-4 w-4 shrink-0" />
        <span className="text-sm font-medium">{health.title}</span>
        <span className="text-sm opacity-80">{health.description}</span>
      </div>
    </div>
  );
};
