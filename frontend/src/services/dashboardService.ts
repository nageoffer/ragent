import { api } from "@/services/api";
import type { EngineType } from "@/stores/engineStore";

export type DashboardKpi = {
  value: number;
  delta?: number;
  deltaPct?: number | null;
};

export type DashboardOverview = {
  engine: EngineType;
  window: string;
  compareWindow: string;
  updatedAt: number;
  kpis: {
    totalUsers: DashboardKpi;
    activeUsers: DashboardKpi;
    totalSessions: DashboardKpi;
    sessions24h: DashboardKpi;
    totalMessages: DashboardKpi;
    messages24h: DashboardKpi;
    activeSessions: DashboardKpi;
  };
};

export type WorkflowDashboardPerformance = {
  engine: "workflow";
  window: string;
  avgLatencyMs: number;
  p95LatencyMs: number;
  successRate: number;
  errorRate: number;
  noDocRate: number;
  slowRate: number;
  sampleCount: number;
};

export type AgentDashboardPerformance = {
  engine: "agent";
  window: string;
  updatedAt: number;
  replies: {
    total: number;
    // previousTotal 只数条数不看轨迹，deltaPct 与概览四个 KPI 走同一个环比口径。
    previousTotal: number;
    deltaPct: number | null;
    normal: number;
    interrupted: number;
    awaitingConfirm: number;
    unknown: number;
    withBlocks: number;
    // 下面三档的分母是 withBlocks（有轨迹的回复），相加等于 withBlocks 而不是 total。
    directReplies: number;
    singleToolReplies: number;
    multiToolReplies: number;
  };
  tools: {
    total: number;
    done: number;
    failed: number;
    interrupted: number;
    other: number;
    callsPerRecordedReply: number | null;
    knowledgeSearchCalls: number;
    // successRate 的分母是该工具全部调用，done + failed 未必等于 count（还有中断与未知状态）。
    topTools: Array<{
      name: string;
      displayName: string;
      count: number;
      done: number;
      failed: number;
      successRate: number | null;
      // 最近一次调用所在回复的创建时间，不是调用本身的时刻，缺轨迹时为 null。
      lastCallAt: number | null;
    }>;
  };
  confirmations: {
    total: number;
    approved: number;
    denied: number;
    pending: number;
    expired: number;
    other: number;
    approvalRate: number | null;
    // 一张卡整体裁决、卡内每个调用共用同一个结果，所以 calls 之和不小于 total。
    topTools: Array<{
      name: string;
      displayName: string;
      calls: number;
      approved: number;
      denied: number;
    }>;
  };
  memory: {
    compactions: number;
    // 只有这批事件记了字符数，求「平均每次节省」时分母只能用它。
    compactionsWithChars: number;
    contextReductionPct: number | null;
    // 这两个是上下文字符数而非 token，量纲不同，展示措辞不可混用。
    contextCharsBefore: number;
    contextCharsAfter: number;
    activeMemories: number;
    addedMemories: number;
    invalidatedMemories: number;
  };
};

export type DashboardPerformance = WorkflowDashboardPerformance | AgentDashboardPerformance;

export type DashboardTrendPoint = {
  ts: number;
  value: number;
};

export type DashboardTrendSeries = {
  name: string;
  data: DashboardTrendPoint[];
};

export type DashboardTrends = {
  metric: string;
  window: string;
  granularity: string;
  series: DashboardTrendSeries[];
};

export async function getDashboardOverview(window: string = "24h"): Promise<DashboardOverview> {
  return api.get<DashboardOverview, DashboardOverview>("/admin/dashboard/overview", {
    params: { window }
  });
}

export async function getDashboardPerformance(
  window: string = "24h"
): Promise<DashboardPerformance> {
  return api.get<DashboardPerformance, DashboardPerformance>("/admin/dashboard/performance", {
    params: { window }
  });
}

export async function getDashboardTrends(
  metric: string,
  window: string = "7d",
  granularity: string = "day"
): Promise<DashboardTrends> {
  return api.get<DashboardTrends, DashboardTrends>("/admin/dashboard/trends", {
    params: { metric, window, granularity }
  });
}
