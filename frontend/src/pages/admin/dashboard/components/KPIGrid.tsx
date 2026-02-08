import { Activity, CheckCircle2, MessageSquare, Zap } from "lucide-react";

import { type DashboardOverview, type DashboardPerformance } from "@/services/dashboardService";

import { KPICard } from "./KPICard";

interface KPIGridProps {
  overview: DashboardOverview | null;
  performance: DashboardPerformance | null;
}

const formatNumber = (value?: number | null) => {
  if (value === null || value === undefined) return "-";
  return value.toLocaleString("zh-CN");
};

const toChange = (deltaPct?: number | null) => {
  if (deltaPct === null || deltaPct === undefined) {
    return { value: 0, trend: "flat" as const, isPositive: true };
  }
  if (deltaPct > 0) {
    return { value: deltaPct, trend: "up" as const, isPositive: true };
  }
  if (deltaPct < 0) {
    return { value: deltaPct, trend: "down" as const, isPositive: false };
  }
  return { value: 0, trend: "flat" as const, isPositive: true };
};

const toStatus = (deltaPct?: number | null): "normal" | "warning" | "critical" => {
  if (deltaPct === null || deltaPct === undefined) return "normal";
  if (deltaPct <= -50) return "critical";
  if (deltaPct <= -20) return "warning";
  return "normal";
};

export const KPIGrid = ({ overview, performance }: KPIGridProps) => {
  const kpis = overview?.kpis;

  const items = [
    {
      label: "活跃用户",
      value: formatNumber(kpis?.activeUsers.value),
      change: toChange(kpis?.activeUsers.deltaPct),
      status: toStatus(kpis?.activeUsers.deltaPct),
      icon: <Activity className="h-4.5 w-4.5" />,
      accentColor: "#3B82F6",
      accentBg: "#EFF6FF"
    },
    {
      label: "会话数(窗口)",
      value: formatNumber(kpis?.sessions24h.value),
      change: toChange(kpis?.sessions24h.deltaPct),
      status: toStatus(kpis?.sessions24h.deltaPct),
      icon: <MessageSquare className="h-4.5 w-4.5" />,
      accentColor: "#8B5CF6",
      accentBg: "#F5F3FF"
    },
    {
      label: "消息数(窗口)",
      value: formatNumber(kpis?.messages24h.value),
      change: toChange(kpis?.messages24h.deltaPct),
      status: toStatus(kpis?.messages24h.deltaPct),
      icon: <Zap className="h-4.5 w-4.5" />,
      accentColor: "#F59E0B",
      accentBg: "#FFFBEB"
    },
    {
      label: "成功率",
      value: performance ? `${performance.successRate.toFixed(1)}%` : "-",
      change: undefined,
      status:
        performance && performance.successRate < 95
          ? "critical" as const
          : performance && performance.successRate < 99
            ? "warning" as const
            : "normal" as const,
      icon: <CheckCircle2 className="h-4.5 w-4.5" />,
      accentColor: "#10B981",
      accentBg: "#ECFDF5"
    }
  ];

  return (
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      {items.map((item) => (
        <KPICard key={item.label} {...item} />
      ))}
    </div>
  );
};
