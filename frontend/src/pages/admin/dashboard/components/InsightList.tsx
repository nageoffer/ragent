import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { type DashboardPerformance } from "@/services/dashboardService";

import { InsightCard, type InsightCardData } from "./InsightCard";

interface InsightListProps {
  performance: DashboardPerformance | null;
  timeWindowLabel: string;
  timestamp: number | null;
}

const formatTime = (timestamp: number | null) => {
  if (!timestamp) return "-";
  return new Date(timestamp).toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  });
};

const buildInsights = (
  performance: DashboardPerformance | null,
  timeWindowLabel: string,
  timestamp: number | null
): InsightCardData[] => {
  const t = formatTime(timestamp);

  if (!performance) {
    return [
      {
        type: "trend",
        severity: "info",
        title: "等待数据回传",
        metric: "Dashboard",
        change: timeWindowLabel,
        context: "当前窗口尚未返回完整性能数据",
        timestamp: t
      }
    ];
  }

  const items: InsightCardData[] = [];

  if (performance.errorRate > 5 || performance.successRate < 95) {
    items.push({
      type: "anomaly",
      severity: "critical",
      title: "链路稳定性触发告警",
      metric: "成功率/错误率",
      change: `${performance.successRate.toFixed(1)}% / ${performance.errorRate.toFixed(1)}%`,
      context: "成功率低于 95% 或错误率高于 5%",
      action: "优先查看失败请求分布与超时节点",
      timestamp: t
    });
  } else {
    items.push({
      type: "trend",
      severity: "info",
      title: "系统可用性稳定",
      metric: "成功率",
      change: `${performance.successRate.toFixed(1)}%`,
      context: "当前窗口整体可用性处于健康区间",
      timestamp: t
    });
  }

  if (performance.noDocRate > 20) {
    items.push({
      type: "recommendation",
      severity: "warning",
      title: "召回质量需优化",
      metric: "无知识率",
      change: `${performance.noDocRate.toFixed(1)}%`,
      context: "无知识率超过 20%，用户命中体验存在风险",
      action: "优化索引覆盖率与检索重排策略",
      timestamp: t
    });
  }

  if (performance.avgLatencyMs > 3000) {
    items.push({
      type: "recommendation",
      severity: "warning",
      title: "响应性能需要关注",
      metric: "平均响应时间",
      change: `${(performance.avgLatencyMs / 1000).toFixed(2)}s`,
      context: "平均延迟高于 3s，影响交互体验",
      action: "排查慢节点与模型并发配置",
      timestamp: t
    });
  }

  if (items.length < 3) {
    items.push({
      type: "recommendation",
      severity: "info",
      title: "继续保持当前策略",
      metric: "运营状态",
      change: timeWindowLabel,
      context: "当前窗口内未发现显著异常趋势",
      timestamp: t
    });
  }

  return items.slice(0, 3);
};

export const InsightList = ({ performance, timeWindowLabel, timestamp }: InsightListProps) => {
  const items = buildInsights(performance, timeWindowLabel, timestamp);

  return (
    <Card className="rounded-xl border border-slate-200 bg-white shadow-sm">
      <CardHeader className="pb-3">
        <CardTitle className="text-base font-semibold text-slate-900">运营洞察</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        {items.map((item, index) => (
          <InsightCard key={`${item.title}-${index}`} item={item} />
        ))}
      </CardContent>
    </Card>
  );
};
