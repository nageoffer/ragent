import { AlertCircle, Info, Lightbulb } from "lucide-react";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from "@/components/ui/table";
import { cn } from "@/lib/utils";
import { type DashboardPerformance } from "@/services/dashboardService";

interface TopInsightsTableProps {
  performance: DashboardPerformance | null;
  timeWindowLabel: string;
  loading?: boolean;
}

interface InsightRow {
  type: "anomaly" | "trend" | "recommendation";
  title: string;
  detail: string;
}

const TYPE_ICON = {
  anomaly: AlertCircle,
  trend: Info,
  recommendation: Lightbulb
} as const;

const TYPE_STYLE = {
  anomaly: "text-red-500",
  trend: "text-blue-500",
  recommendation: "text-amber-500"
} as const;

const TYPE_LABEL = {
  anomaly: "异常",
  trend: "趋势",
  recommendation: "建议"
} as const;

const buildInsights = (p: DashboardPerformance, windowLabel: string): InsightRow[] => {
  const rows: InsightRow[] = [];

  if (p.errorRate > 5) {
    rows.push({
      type: "anomaly",
      title: "错误率偏高",
      detail: `${windowLabel}内错误率 ${p.errorRate.toFixed(1)}%，超过 5% 阈值`
    });
  }

  if (p.avgLatencyMs > 2000) {
    rows.push({
      type: "anomaly",
      title: "响应时间偏高",
      detail: `平均响应 ${(p.avgLatencyMs / 1000).toFixed(2)}s，建议排查慢查询`
    });
  }

  if (p.noDocRate > 10) {
    rows.push({
      type: "recommendation",
      title: "知识库覆盖不足",
      detail: `无知识率 ${p.noDocRate.toFixed(1)}%，建议补充知识文档`
    });
  }

  if (p.successRate >= 95 && p.avgLatencyMs < 2000) {
    rows.push({
      type: "trend",
      title: "系统运行良好",
      detail: `成功率 ${p.successRate.toFixed(1)}%，响应时间正常`
    });
  }

  if (rows.length === 0) {
    rows.push({
      type: "trend",
      title: "暂无特别洞察",
      detail: `${windowLabel}内各项指标在正常范围`
    });
  }

  return rows;
};

export const TopInsightsTable = ({ performance, timeWindowLabel, loading }: TopInsightsTableProps) => {
  if (loading) {
    return (
      <Card className="rounded-xl border border-slate-200 bg-white shadow-sm">
        <CardHeader className="pb-2">
          <div className="h-5 w-28 animate-pulse rounded bg-slate-100" />
        </CardHeader>
        <CardContent>
          <div className="space-y-3">
            {Array.from({ length: 3 }).map((_, i) => (
              <div key={i} className="h-8 animate-pulse rounded bg-slate-100" />
            ))}
          </div>
        </CardContent>
      </Card>
    );
  }

  const rows = performance ? buildInsights(performance, timeWindowLabel) : [];

  return (
    <Card className="rounded-xl border border-slate-200 bg-white shadow-sm transition-shadow duration-200 hover:shadow-md">
      <CardHeader className="pb-2">
        <CardTitle className="text-base font-semibold text-slate-900">关键洞察</CardTitle>
        <p className="text-sm text-slate-500">基于当前指标的智能分析</p>
      </CardHeader>
      <CardContent>
        {rows.length === 0 ? (
          <div className="flex h-32 items-center justify-center text-sm text-slate-400">暂无数据</div>
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="w-16 text-xs font-medium text-slate-500">类型</TableHead>
                <TableHead className="text-xs font-medium text-slate-500">标题</TableHead>
                <TableHead className="text-xs font-medium text-slate-500">详情</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((row, i) => {
                const Icon = TYPE_ICON[row.type];
                return (
                  <TableRow key={i} className="border-slate-100">
                    <TableCell className="py-2.5">
                      <span className="inline-flex items-center gap-1">
                        <Icon className={cn("h-3.5 w-3.5", TYPE_STYLE[row.type])} />
                        <span className="text-xs text-slate-500">{TYPE_LABEL[row.type]}</span>
                      </span>
                    </TableCell>
                    <TableCell className="py-2.5 text-sm font-medium text-slate-900">
                      {row.title}
                    </TableCell>
                    <TableCell className="py-2.5 text-xs text-slate-500">
                      {row.detail}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
};
