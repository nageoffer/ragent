import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { AlertTriangle, ArrowLeft, RefreshCw } from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import { getRagTraceDetail, type RagTraceDetail } from "@/services/ragTraceService";
import { getErrorMessage } from "@/utils/error";
import {
  clamp,
  formatDateTime,
  formatDuration,
  normalizeStatus,
  resolveNodeDuration,
  statusBadgeVariant,
  statusLabel,
  toTimestamp,
  type TimelineNode
} from "@/pages/admin/traces/traceUtils";

const decodeRunId = (value?: string): string => {
  if (!value) return "";
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
};

export function RagTraceDetailPage() {
  const params = useParams<{ runId: string }>();
  const runId = decodeRunId(params.runId);
  const detailRequestRef = useRef(0);
  const [detail, setDetail] = useState<RagTraceDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const loadDetail = async (nextRunId: string) => {
    if (!nextRunId) return;
    const requestId = ++detailRequestRef.current;
    setDetailLoading(true);
    try {
      const result = await getRagTraceDetail(nextRunId);
      if (detailRequestRef.current !== requestId) return;
      setDetail(result);
    } catch (error) {
      if (detailRequestRef.current !== requestId) return;
      toast.error(getErrorMessage(error, "加载链路详情失败"));
      console.error(error);
      setDetail(null);
    } finally {
      if (detailRequestRef.current !== requestId) return;
      setDetailLoading(false);
    }
  };

  useEffect(() => {
    if (!runId) {
      detailRequestRef.current += 1;
      setDetail(null);
      setDetailLoading(false);
      return;
    }
    loadDetail(runId);
  }, [runId]);

  const selectedRun = detail?.run || null;

  const timeline = useMemo(() => {
    const nodes = detail?.nodes || [];
    if (!nodes.length) {
      return {
        totalWindowMs: 0,
        nodes: [] as TimelineNode[]
      };
    }

    const normalized = nodes.map((node) => {
      const startTs = toTimestamp(node.startTime);
      const endTs = toTimestamp(node.endTime);
      const resolvedDurationMs = resolveNodeDuration(node);
      const depthValue = Math.max(0, Number(node.depth ?? 0));
      const resolvedStartTs = startTs ?? 0;
      const resolvedEndTs =
        endTs ?? (resolvedStartTs > 0 ? resolvedStartTs + resolvedDurationMs : 0);
      return {
        ...node,
        depthValue,
        resolvedDurationMs,
        startTs: resolvedStartTs,
        endTs: resolvedEndTs
      };
    });

    const withTime = normalized.filter((item) => item.startTs > 0);
    const baseStart = withTime.length
      ? withTime.reduce((min, item) => Math.min(min, item.startTs), withTime[0].startTs)
      : Date.now();
    const maxEnd = withTime.length
      ? withTime.reduce(
          (max, item) => Math.max(max, item.endTs || item.startTs),
          withTime[0].endTs || withTime[0].startTs
        )
      : baseStart;
    const runDuration = Number(selectedRun?.durationMs ?? 0);
    const windowDuration = Math.max(runDuration > 0 ? runDuration : maxEnd - baseStart, 1);

    const rows = normalized
      .sort((a, b) => a.startTs - b.startTs || a.depthValue - b.depthValue)
      .map((node) => {
        const offsetMs = node.startTs > 0 ? Math.max(0, node.startTs - baseStart) : 0;
        const leftPercent = clamp((offsetMs / windowDuration) * 100, 0, 99.2);
        const widthPercent = clamp(
          (Math.max(node.resolvedDurationMs, 1) / windowDuration) * 100,
          0.8,
          100 - leftPercent
        );
        return {
          ...node,
          offsetMs,
          leftPercent,
          widthPercent
        };
      });

    return {
      totalWindowMs: windowDuration,
      nodes: rows
    };
  }, [detail?.nodes, selectedRun?.durationMs]);

  const nodeSummary = useMemo(() => {
    const nodes = detail?.nodes || [];
    const total = nodes.length;
    const failed = nodes.filter((node) => normalizeStatus(node.status) === "failed").length;
    const running = nodes.filter((node) => normalizeStatus(node.status) === "running").length;
    const success = nodes.filter((node) => normalizeStatus(node.status) === "success").length;
    return { total, failed, running, success };
  }, [detail?.nodes]);

  return (
    <div className="admin-page trace-page space-y-6">
      <Card className="trace-hero-card border-0">
        <CardContent className="trace-hero-content">
          <div className="trace-hero-head">
            <div>
              <p className="trace-hero-tag">RAG Observability</p>
              <h1 className="trace-hero-title">链路详情</h1>
              <p className="trace-hero-subtitle">
                Run ID: <span className="trace-mono inline-block max-w-full align-bottom">{runId || "-"}</span>
              </p>
            </div>
            <div className="trace-hero-chip-wrap">
              <Button asChild className="trace-filter-btn" variant="outline">
                <Link to="/admin/traces">
                  <ArrowLeft className="mr-2 h-4 w-4" />
                  返回运行列表
                </Link>
              </Button>
              <Button
                className="trace-filter-btn"
                variant="outline"
                disabled={!runId || detailLoading}
                onClick={() => loadDetail(runId)}
              >
                <RefreshCw className="mr-2 h-4 w-4" />
                刷新详情
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>

      <Card className="trace-detail-card overflow-hidden">
        <CardHeader className="border-b border-slate-100">
          <CardTitle>运行链路</CardTitle>
          <CardDescription>{runId ? `Run: ${runId}` : "未指定 Run ID"}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4 px-5 py-4">
          {detailLoading ? (
            <div className="py-10 text-center text-muted-foreground">加载链路详情中...</div>
          ) : !runId ? (
            <div className="py-10 text-center text-muted-foreground">缺少 Run ID，无法加载详情</div>
          ) : !selectedRun ? (
            <div className="py-10 text-center text-muted-foreground">暂无可展示的链路详情</div>
          ) : (
            <>
              <div className="trace-run-meta">
                <div className="trace-run-meta-item">
                  <p className="trace-meta-label">Trace</p>
                  <p className="trace-meta-value">{selectedRun.traceName || "-"}</p>
                </div>
                <div className="trace-run-meta-item">
                  <p className="trace-meta-label">状态</p>
                  <Badge variant={statusBadgeVariant(selectedRun.status)}>
                    {statusLabel(selectedRun.status)}
                  </Badge>
                </div>
                <div className="trace-run-meta-item">
                  <p className="trace-meta-label">总耗时</p>
                  <p className="trace-meta-value">{formatDuration(selectedRun.durationMs ?? undefined)}</p>
                </div>
                <div className="trace-run-meta-item">
                  <p className="trace-meta-label">开始时间</p>
                  <p className="trace-meta-value">{formatDateTime(selectedRun.startTime ?? undefined)}</p>
                </div>
              </div>

              {selectedRun.errorMessage ? (
                <div className="trace-error-box">
                  <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-red-500" />
                  <p className="text-xs leading-5 text-red-600">{selectedRun.errorMessage}</p>
                </div>
              ) : null}

              <div className="trace-node-summary">
                <Badge variant="outline">节点 {nodeSummary.total}</Badge>
                <Badge variant="default">成功 {nodeSummary.success}</Badge>
                <Badge variant="destructive">失败 {nodeSummary.failed}</Badge>
                <Badge variant="secondary">运行中 {nodeSummary.running}</Badge>
              </div>

              <div className="trace-waterfall-wrap">
                <div className="trace-waterfall-head">
                  <span>节点调用瀑布图</span>
                  <span>总窗口 {formatDuration(timeline.totalWindowMs)}</span>
                </div>
                <div className="trace-waterfall-body">
                  {timeline.nodes.length === 0 ? (
                    <div className="py-8 text-center text-sm text-slate-400">当前 Run 暂无节点记录</div>
                  ) : (
                    timeline.nodes.map((node) => {
                      const nodeDisplayName = node.nodeName || node.methodName || node.nodeId;
                      return (
                        <div key={node.nodeId} className="trace-waterfall-row">
                          <div
                            className="trace-waterfall-node"
                            style={{ paddingLeft: `${Math.min(node.depthValue, 6) * 8 + 4}px` }}
                            title={nodeDisplayName}
                          >
                            <span
                              className={cn(
                                "trace-node-dot",
                                `is-${normalizeStatus(node.status) || "default"}`
                              )}
                            />
                            <span className="trace-waterfall-node-name" title={nodeDisplayName}>
                              {nodeDisplayName}
                            </span>
                          </div>
                          <div className="trace-waterfall-type-col">
                            <span className="trace-waterfall-node-type" title={node.nodeType || "-"}>
                              {node.nodeType || "-"}
                            </span>
                          </div>
                          <div className="trace-waterfall-chart">
                            <div className="trace-waterfall-track" />
                            <div
                              className={cn(
                                "trace-waterfall-bar",
                                `is-${normalizeStatus(node.status) || "default"}`
                              )}
                              style={{
                                left: `${node.leftPercent}%`,
                                width: `${node.widthPercent}%`
                              }}
                              title={`${nodeDisplayName} ${formatDuration(node.resolvedDurationMs)}`}
                            />
                          </div>
                          <div className="trace-waterfall-duration">
                            <p>{formatDuration(node.resolvedDurationMs)}</p>
                            <p className="text-[11px] text-slate-400">+{formatDuration(node.offsetMs)}</p>
                          </div>
                        </div>
                      );
                    })
                  )}
                </div>
              </div>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
