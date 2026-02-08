import { useMemo } from "react";

import { type DashboardPerformance } from "@/services/dashboardService";

import { getHealthStatus, getMetricStatus, type HealthStatus } from "../constants/thresholds";

export interface HealthStatusView {
  status: HealthStatus;
  title: string;
  description: string;
  badgeVariant: "default" | "secondary" | "destructive" | "outline";
}

export interface MetricStatusView {
  success: "good" | "warning" | "bad";
  latency: "good" | "warning" | "bad";
  error: "good" | "warning" | "bad";
  noDoc: "good" | "warning" | "bad";
}

export const useHealthStatus = (performance: DashboardPerformance | null) => {
  const health = useMemo<HealthStatusView>(() => {
    const status = getHealthStatus(performance);
    if (status === "critical") {
      return {
        status,
        title: "系统风险偏高",
        description: "错误率或成功率触发告警阈值",
        badgeVariant: "destructive"
      };
    }
    if (status === "attention") {
      return {
        status,
        title: "系统需要关注",
        description: "召回质量或性能波动接近阈值",
        badgeVariant: "secondary"
      };
    }
    return {
      status,
      title: "系统运行健康",
      description: "核心质量指标保持稳定",
      badgeVariant: "default"
    };
  }, [performance]);

  const metricStatus = useMemo<MetricStatusView>(() => {
    return {
      success: getMetricStatus("successRate", performance?.successRate),
      latency: getMetricStatus("latency", performance?.avgLatencyMs),
      error: getMetricStatus("errorRate", performance?.errorRate),
      noDoc: getMetricStatus("noDocRate", performance?.noDocRate)
    };
  }, [performance]);

  return { health, metricStatus };
};
