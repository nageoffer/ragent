import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { Activity, Clock3, Layers, TrendingUp } from "lucide-react";
import { toast } from "sonner";

import { getRagTraceRuns, type PageResult, type RagTraceRun } from "@/services/ragTraceService";
import { getErrorMessage } from "@/utils/error";
import { FilterBar } from "@/pages/admin/traces/components/FilterBar";
import { PageHeader } from "@/pages/admin/traces/components/PageHeader";
import { RunsTable } from "@/pages/admin/traces/components/RunsTable";
import { StatCard, type StatCardTone } from "@/pages/admin/traces/components/StatCard";
import {
  DEFAULT_FILTERS,
  PAGE_SIZE,
  normalizeStatus,
  percentile,
  type TraceFilters,
} from "@/pages/admin/traces/traceUtils";

type DurationMetric = {
  value: string;
  unit: string;
};

const formatDurationMetric = (durationMs: number): DurationMetric => {
  const duration = Number.isFinite(durationMs) && durationMs > 0 ? durationMs : 0;
  if (duration < 1000) {
    return { value: `${Math.round(duration)}`, unit: "ms" };
  }
  if (duration < 60_000) {
    return { value: (duration / 1000).toFixed(2), unit: "s" };
  }
  return { value: (duration / 1000).toFixed(1), unit: "s" };
};

export function RagTracePage() {
  const navigate = useNavigate();
  const runsRequestRef = useRef(0);
  const [filters, setFilters] = useState<TraceFilters>(DEFAULT_FILTERS);
  const [query, setQuery] = useState<TraceFilters>(DEFAULT_FILTERS);
  const [pageNo, setPageNo] = useState(1);
  const [pageData, setPageData] = useState<PageResult<RagTraceRun> | null>(null);
  const [loading, setLoading] = useState(false);

  const runs = pageData?.records || [];

  const loadRuns = async (current = pageNo, nextQuery = query) => {
    const requestId = ++runsRequestRef.current;
    setLoading(true);
    try {
      const result = await getRagTraceRuns({
        current,
        size: PAGE_SIZE,
        runId: nextQuery.runId.trim() || undefined,
        conversationId: nextQuery.conversationId.trim() || undefined,
        taskId: nextQuery.taskId.trim() || undefined,
        status: nextQuery.status || undefined
      });
      if (runsRequestRef.current !== requestId) return;
      setPageData(result);
    } catch (error) {
      if (runsRequestRef.current !== requestId) return;
      toast.error(getErrorMessage(error, "加载链路运行列表失败"));
      console.error(error);
    } finally {
      if (runsRequestRef.current !== requestId) return;
      setLoading(false);
    }
  };

  useEffect(() => {
    loadRuns();
  }, [pageNo, query]);

  const handleSearch = () => {
    setPageNo(1);
    setQuery({
      runId: filters.runId.trim(),
      conversationId: filters.conversationId.trim(),
      taskId: filters.taskId.trim(),
      status: filters.status
    });
  };

  const handleReset = () => {
    setFilters(DEFAULT_FILTERS);
    setQuery(DEFAULT_FILTERS);
    setPageNo(1);
  };

  const handleRefresh = () => {
    loadRuns(pageNo, query);
  };

  const traceStats = useMemo(() => {
    const durations = runs
      .map((item) => Number(item.durationMs ?? 0))
      .filter((value) => Number.isFinite(value) && value > 0);
    const successCount = runs.filter((item) => normalizeStatus(item.status) === "success").length;
    const failedCount = runs.filter((item) => normalizeStatus(item.status) === "failed").length;
    const runningCount = runs.filter((item) => normalizeStatus(item.status) === "running").length;
    const avgDuration = durations.length
      ? Math.round(durations.reduce((sum, value) => sum + value, 0) / durations.length)
      : 0;
    const p95Duration = Math.round(percentile(durations, 0.95));
    const successRate = runs.length ? Math.round((successCount / runs.length) * 1000) / 10 : 0;
    return {
      totalRuns: pageData?.total ?? runs.length,
      successCount,
      failedCount,
      runningCount,
      avgDuration,
      p95Duration,
      successRate
    };
  }, [runs, pageData?.total]);

  const current = pageData?.current || pageNo;
  const pages = pageData?.pages || 1;
  const total = pageData?.total || 0;
  const avgDurationMetric = formatDurationMetric(traceStats.avgDuration);
  const p95DurationMetric = formatDurationMetric(traceStats.p95Duration);
  const statCards: {
    key: string;
    title: string;
    value: string;
    unit?: string;
    icon: ReactNode;
    tone: StatCardTone;
  }[] = [
    {
      key: "status",
      title: "成功 / 失败 / 运行中",
      value: `${traceStats.successCount} / ${traceStats.failedCount} / ${traceStats.runningCount}`,
      icon: <Activity className="h-4 w-4" />,
      tone: "emerald"
    },
    {
      key: "successRate",
      title: "成功率",
      value: `${traceStats.successRate}%`,
      icon: <TrendingUp className="h-4 w-4" />,
      tone: "cyan"
    },
    {
      key: "avg",
      title: "平均耗时",
      value: avgDurationMetric.value,
      unit: avgDurationMetric.unit,
      icon: <Clock3 className="h-4 w-4" />,
      tone: "indigo"
    },
    {
      key: "p95",
      title: "P95 耗时",
      value: p95DurationMetric.value,
      unit: p95DurationMetric.unit,
      icon: <Layers className="h-4 w-4" />,
      tone: "amber"
    }
  ];

  return (
    <div className="admin-page trace-page trace-list-page">
      <div className="trace-list-shell">
        <PageHeader
          tag="RAG Observability"
          title="链路追踪"
          description="独立列表页聚焦运行检索，点击任意运行记录进入详情页分析慢节点与失败节点"
          kpis={[]}
        />

        <section className="trace-list-stat-grid">
          {statCards.map((stat) => (
            <StatCard
              key={stat.key}
              title={stat.title}
              value={stat.value}
              unit={stat.unit}
              icon={stat.icon}
              tone={stat.tone}
            />
          ))}
        </section>

        <FilterBar
          filters={filters}
          onFiltersChange={(next) => setFilters((prev) => ({ ...prev, ...next }))}
          onSearch={handleSearch}
          onReset={handleReset}
          onRefresh={handleRefresh}
        />

        <RunsTable
          runs={runs}
          loading={loading}
          current={current}
          pages={pages}
          total={total}
          onOpenRun={(runId) => navigate(`/admin/traces/${encodeURIComponent(runId)}`)}
          onPrevPage={() => setPageNo((prev) => Math.max(1, prev - 1))}
          onNextPage={() => setPageNo((prev) => prev + 1)}
        />
      </div>
    </div>
  );
}
