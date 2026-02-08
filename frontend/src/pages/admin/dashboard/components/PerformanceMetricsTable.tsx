import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from "@/components/ui/table";
import { type DashboardPerformance } from "@/services/dashboardService";

interface PerformanceMetricsTableProps {
  performance: DashboardPerformance | null;
  loading?: boolean;
}

interface MetricRow {
  label: string;
  value: string;
  status: "good" | "warning" | "critical";
}

const STATUS_DOT: Record<MetricRow["status"], string> = {
  good: "bg-emerald-500",
  warning: "bg-amber-500",
  critical: "bg-red-500"
};

const STATUS_TEXT: Record<MetricRow["status"], string> = {
  good: "text-emerald-600",
  warning: "text-amber-600",
  critical: "text-red-600"
};

const STATUS_LABEL: Record<MetricRow["status"], string> = {
  good: "正常",
  warning: "警告",
  critical: "异常"
};

const buildRows = (p: DashboardPerformance): MetricRow[] => {
  const latencyStatus = p.avgLatencyMs < 2000 ? "good" : p.avgLatencyMs < 5000 ? "warning" : "critical";
  const p95Status = p.p95LatencyMs < 3000 ? "good" : p.p95LatencyMs < 8000 ? "warning" : "critical";
  const successStatus = p.successRate >= 95 ? "good" : p.successRate >= 85 ? "warning" : "critical";
  const errorStatus = p.errorRate <= 5 ? "good" : p.errorRate <= 15 ? "warning" : "critical";
  const noDocStatus = p.noDocRate <= 10 ? "good" : p.noDocRate <= 25 ? "warning" : "critical";

  const fmtMs = (ms: number) => (ms < 1000 ? `${Math.round(ms)}ms` : `${(ms / 1000).toFixed(2)}s`);

  return [
    { label: "平均响应时间", value: fmtMs(p.avgLatencyMs), status: latencyStatus },
    { label: "P95 响应时间", value: fmtMs(p.p95LatencyMs), status: p95Status },
    { label: "成功率", value: `${p.successRate.toFixed(1)}%`, status: successStatus },
    { label: "错误率", value: `${p.errorRate.toFixed(1)}%`, status: errorStatus },
    { label: "无知识率", value: `${p.noDocRate.toFixed(1)}%`, status: noDocStatus }
  ];
};

export const PerformanceMetricsTable = ({ performance, loading }: PerformanceMetricsTableProps) => {
  if (loading) {
    return (
      <Card className="rounded-xl border border-slate-200 bg-white shadow-sm">
        <CardHeader className="pb-2">
          <div className="h-5 w-28 animate-pulse rounded bg-slate-100" />
        </CardHeader>
        <CardContent>
          <div className="space-y-3">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="h-8 animate-pulse rounded bg-slate-100" />
            ))}
          </div>
        </CardContent>
      </Card>
    );
  }

  const rows = performance ? buildRows(performance) : [];

  return (
    <Card className="rounded-xl border border-slate-200 bg-white shadow-sm transition-shadow duration-200 hover:shadow-md">
      <CardHeader className="pb-2">
        <CardTitle className="text-base font-semibold text-slate-900">性能指标</CardTitle>
        <p className="text-sm text-slate-500">核心性能指标一览</p>
      </CardHeader>
      <CardContent>
        {rows.length === 0 ? (
          <div className="flex h-32 items-center justify-center text-sm text-slate-400">暂无数据</div>
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="text-xs font-medium text-slate-500">指标</TableHead>
                <TableHead className="text-right text-xs font-medium text-slate-500">数值</TableHead>
                <TableHead className="text-right text-xs font-medium text-slate-500">状态</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((row) => (
                <TableRow key={row.label} className="border-slate-100">
                  <TableCell className="py-2.5 text-sm text-slate-700">{row.label}</TableCell>
                  <TableCell className="py-2.5 text-right text-sm font-medium text-slate-900">
                    {row.value}
                  </TableCell>
                  <TableCell className="py-2.5 text-right">
                    <span className="inline-flex items-center gap-1.5">
                      <span className={`h-2 w-2 rounded-full ${STATUS_DOT[row.status]}`} />
                      <span className={`text-xs font-medium ${STATUS_TEXT[row.status]}`}>
                        {STATUS_LABEL[row.status]}
                      </span>
                    </span>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
};
