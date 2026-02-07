import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ChevronRight, Eye } from "lucide-react";
import type { RagTraceRun } from "@/services/ragTraceService";
import {
  formatDateTime,
  formatDuration,
  statusBadgeVariant,
  statusLabel
} from "@/pages/admin/traces/traceUtils";

interface RunsTableProps {
  runs: RagTraceRun[];
  loading: boolean;
  current: number;
  pages: number;
  total: number;
  onOpenRun: (runId: string) => void;
  onPrevPage: () => void;
  onNextPage: () => void;
}

export function RunsTable({
  runs,
  loading,
  current,
  pages,
  total,
  onOpenRun,
  onPrevPage,
  onNextPage
}: RunsTableProps) {
  return (
    <Card className="trace-list-table-card">
      <div className="trace-list-table-header">
        <h2 className="trace-list-table-title">运行列表</h2>
        <p className="trace-list-table-description">按时间倒序查看运行记录，通过操作按钮进入独立详情页</p>
      </div>
      <CardContent className="trace-list-table-content">
        {loading ? (
          <div className="trace-list-table-empty">加载中...</div>
        ) : runs.length === 0 ? (
          <div className="trace-list-table-empty">暂无链路数据</div>
        ) : (
          <div className="trace-list-table-wrap">
            <Table className="trace-list-table">
              <TableHeader>
                <TableRow>
                  <TableHead className="trace-col-trace">Trace</TableHead>
                  <TableHead className="trace-col-run-id">Trace ID</TableHead>
                  <TableHead className="trace-col-meta">会话ID / TaskID</TableHead>
                  <TableHead className="trace-col-duration">耗时</TableHead>
                  <TableHead className="trace-col-status">状态</TableHead>
                  <TableHead className="trace-col-user">用户名</TableHead>
                  <TableHead className="trace-col-action">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {runs.map((run) => (
                  <TableRow key={run.runId} className="trace-list-table-row">
                    <TableCell className="trace-col-trace">
                      <div className="trace-list-run-trace">
                        <p className="trace-list-run-name line-clamp-1" title={run.traceName || "-"}>
                          {run.traceName || "-"}
                        </p>
                        <p className="trace-list-run-time">{formatDateTime(run.startTime ?? undefined)}</p>
                      </div>
                    </TableCell>
                    <TableCell className="trace-col-run-id">
                      <span className="trace-list-run-id" title={run.runId}>
                        {run.runId}
                      </span>
                    </TableCell>
                    <TableCell className="trace-col-meta">
                      <p className="trace-list-run-meta-line" title={run.conversationId || "-"}>
                        {run.conversationId || "-"}
                      </p>
                      <p className="trace-list-run-meta-line is-secondary" title={run.taskId || "-"}>
                        {run.taskId || "-"}
                      </p>
                    </TableCell>
                    <TableCell className="trace-col-duration trace-list-duration-cell">
                      {formatDuration(run.durationMs ?? undefined)}
                    </TableCell>
                    <TableCell className="trace-col-status trace-list-status-cell">
                      <Badge className="trace-list-status-badge" variant={statusBadgeVariant(run.status)}>
                        {statusLabel(run.status)}
                      </Badge>
                    </TableCell>
                    <TableCell className="trace-col-user">
                      <span
                        className="trace-list-user-name"
                        title={run.userName || run.username || run.userId || "-"}
                      >
                        {run.userName || run.username || run.userId || "-"}
                      </span>
                    </TableCell>
                    <TableCell className="trace-col-action trace-list-action-cell">
                      <Button
                        size="sm"
                        variant="outline"
                        className="trace-list-action-btn"
                        onClick={() => onOpenRun(run.runId)}
                      >
                        <Eye className="h-3.5 w-3.5" />
                        查看
                        <ChevronRight className="h-3.5 w-3.5" />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
        <div className="trace-list-table-footer">
          <span className="trace-list-table-meta">
            第 {current} / {pages} 页，共 {total.toLocaleString("zh-CN")} 条
          </span>
          <div className="trace-list-pagination">
            <Button
              className="trace-list-pagination-btn"
              variant="outline"
              disabled={current <= 1 || loading}
              onClick={onPrevPage}
            >
              上一页
            </Button>
            <Button
              className="trace-list-pagination-btn"
              variant="outline"
              disabled={current >= pages || loading}
              onClick={onNextPage}
            >
              下一页
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
