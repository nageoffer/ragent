import { Activity, MessageSquare, Users, Zap } from "lucide-react";

import { type DashboardOverview, type DashboardTrends } from "@/services/dashboardService";

import { KPICard } from "./KPICard";

interface KPIGridProps {
  overview: DashboardOverview | null;
  trends: {
    sessions: DashboardTrends | null;
    activeUsers: DashboardTrends | null;
  };
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
  if (deltaPct <= -20) return "critical";
  if (deltaPct < 0) return "warning";
  return "normal";
};

const readSparkline = (trend: DashboardTrends | null) => {
  if (!trend?.series?.length) return [];
  return trend.series[0].data.slice(-12).map((item) => item.value);
};

export const KPIGrid = ({ overview, trends }: KPIGridProps) => {
  const kpis = overview?.kpis;
  const sessionsSparkline = readSparkline(trends.sessions);
  const activeSparkline = readSparkline(trends.activeUsers);

  const items = [
    {
      label: "总用户数",
      value: formatNumber(kpis?.totalUsers.value),
      change: toChange(kpis?.totalUsers.deltaPct),
      sparklineData: activeSparkline,
      status: toStatus(kpis?.totalUsers.deltaPct),
      icon: <Users className="h-4 w-4" />
    },
    {
      label: "活跃用户",
      value: formatNumber(kpis?.activeUsers.value),
      change: toChange(kpis?.activeUsers.deltaPct),
      sparklineData: activeSparkline,
      status: toStatus(kpis?.activeUsers.deltaPct),
      icon: <Activity className="h-4 w-4" />
    },
    {
      label: "总会话数",
      value: formatNumber(kpis?.totalSessions.value),
      change: toChange(kpis?.totalSessions.deltaPct),
      sparklineData: sessionsSparkline,
      status: toStatus(kpis?.totalSessions.deltaPct),
      icon: <MessageSquare className="h-4 w-4" />
    },
    {
      label: "会话数(窗口)",
      value: formatNumber(kpis?.sessions24h.value),
      change: toChange(kpis?.sessions24h.deltaPct),
      sparklineData: sessionsSparkline,
      status: toStatus(kpis?.sessions24h.deltaPct),
      icon: <Zap className="h-4 w-4" />
    },
    {
      label: "总消息数",
      value: formatNumber(kpis?.totalMessages.value),
      change: toChange(kpis?.totalMessages.deltaPct),
      sparklineData: sessionsSparkline,
      status: toStatus(kpis?.totalMessages.deltaPct),
      icon: <MessageSquare className="h-4 w-4" />
    },
    {
      label: "消息数(窗口)",
      value: formatNumber(kpis?.messages24h.value),
      change: toChange(kpis?.messages24h.deltaPct),
      sparklineData: sessionsSparkline,
      status: toStatus(kpis?.messages24h.deltaPct),
      icon: <Zap className="h-4 w-4" />
    }
  ];

  return (
    <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
      {items.map((item) => (
        <KPICard key={item.label} {...item} />
      ))}
    </div>
  );
};
