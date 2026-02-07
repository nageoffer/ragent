import type { RagTraceNode } from "@/services/ragTraceService";

export const PAGE_SIZE = 10;

export type BadgeVariant = "default" | "secondary" | "destructive" | "outline";

export type TraceStatus = "" | "success" | "failed" | "running";

export type TraceFilters = {
  traceId: string;
  conversationId: string;
  taskId: string;
  status: TraceStatus;
};

export type TimelineNode = RagTraceNode & {
  depthValue: number;
  resolvedDurationMs: number;
  offsetMs: number;
  leftPercent: number;
  widthPercent: number;
};

export const DEFAULT_FILTERS: TraceFilters = {
  traceId: "",
  conversationId: "",
  taskId: "",
  status: ""
};

export const STATUS_OPTIONS: { value: TraceStatus; label: string }[] = [
  { value: "", label: "全部状态" },
  { value: "running", label: "运行中" },
  { value: "success", label: "成功" },
  { value: "failed", label: "失败" }
];

export const normalizeStatus = (status?: string | null): string => (status || "").trim().toLowerCase();

export const statusLabel = (status?: string | null): string => {
  const normalized = normalizeStatus(status);
  if (!normalized) return "UNKNOWN";
  if (normalized === "success") return "SUCCESS";
  if (normalized === "failed") return "FAILED";
  if (normalized === "running") return "RUNNING";
  if (normalized === "timeout") return "TIMEOUT";
  return normalized.toUpperCase();
};

export const statusBadgeVariant = (status?: string | null): BadgeVariant => {
  const normalized = normalizeStatus(status);
  if (normalized === "failed" || normalized === "timeout") return "destructive";
  if (normalized === "running") return "secondary";
  if (normalized === "success") return "default";
  return "outline";
};

export const toTimestamp = (value?: string | number | null): number | null => {
  if (value === null || value === undefined || value === "") return null;
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  const parsedByDate = new Date(value).getTime();
  if (!Number.isNaN(parsedByDate)) return parsedByDate;
  const asNumber = Number(value);
  if (!Number.isFinite(asNumber)) return null;
  return asNumber;
};

export const formatDateTime = (value?: string | number | null): string => {
  const timestamp = toTimestamp(value);
  if (timestamp === null) return "-";
  return new Date(timestamp).toLocaleString("zh-CN");
};

export const formatDuration = (value?: number | null): string => {
  if (value === null || value === undefined || Number.isNaN(value)) return "-";
  if (value < 1000) return `${Math.round(value)}ms`;
  if (value < 60_000) return `${(value / 1000).toFixed(2)}s`;
  const minute = Math.floor(value / 60_000);
  const second = ((value % 60_000) / 1000).toFixed(1);
  return `${minute}m ${second}s`;
};

export const percentile = (values: number[], ratio: number): number => {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.max(0, Math.min(sorted.length - 1, Math.ceil(sorted.length * ratio) - 1));
  return sorted[index];
};

export const clamp = (value: number, min: number, max: number): number => {
  return Math.min(max, Math.max(min, value));
};

export const resolveNodeDuration = (node: RagTraceNode): number => {
  const durationMs = Number(node.durationMs ?? 0);
  if (Number.isFinite(durationMs) && durationMs > 0) return durationMs;
  const start = toTimestamp(node.startTime);
  const end = toTimestamp(node.endTime);
  if (start !== null && end !== null && end >= start) return end - start;
  return 0;
};
