import { api } from "@/services/api";

export interface RagTraceRun {
  runId: string;
  traceName?: string | null;
  entryMethod?: string | null;
  conversationId?: string | null;
  taskId?: string | null;
  userName?: string | null;
  username?: string | null;
  userId?: string | null;
  status?: string | null;
  errorMessage?: string | null;
  durationMs?: number | null;
  startTime?: string | null;
  endTime?: string | null;
}

export interface RagTraceNode {
  runId: string;
  nodeId: string;
  parentNodeId?: string | null;
  depth?: number | null;
  nodeType?: string | null;
  nodeName?: string | null;
  className?: string | null;
  methodName?: string | null;
  status?: string | null;
  errorMessage?: string | null;
  durationMs?: number | null;
  startTime?: string | null;
  endTime?: string | null;
}

export interface RagTraceDetail {
  run: RagTraceRun;
  nodes: RagTraceNode[];
}

export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

export interface RagTraceRunQuery {
  current?: number;
  size?: number;
  runId?: string;
  conversationId?: string;
  taskId?: string;
  status?: string;
}

export async function getRagTraceRuns(
  query: RagTraceRunQuery = {}
): Promise<PageResult<RagTraceRun>> {
  return api.get<PageResult<RagTraceRun>, PageResult<RagTraceRun>>("/rag/traces/runs", {
    params: {
      current: query.current ?? 1,
      size: query.size ?? 10,
      runId: query.runId || undefined,
      conversationId: query.conversationId || undefined,
      taskId: query.taskId || undefined,
      status: query.status || undefined
    }
  });
}

export async function getRagTraceDetail(runId: string): Promise<RagTraceDetail> {
  return api.get<RagTraceDetail, RagTraceDetail>(`/rag/traces/runs/${runId}`);
}

export async function getRagTraceNodes(runId: string): Promise<RagTraceNode[]> {
  return api.get<RagTraceNode[], RagTraceNode[]>(`/rag/traces/runs/${runId}/nodes`);
}
