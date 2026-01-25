import { api } from "@/services/api";

export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

export interface IngestionPipelineNode {
  id: number;
  nodeId: string;
  nodeType: string;
  settings?: Record<string, unknown> | null;
  condition?: Record<string, unknown> | null;
  nextNodeId?: string | null;
}

export interface IngestionPipeline {
  id: string;
  name: string;
  description?: string | null;
  nodes?: IngestionPipelineNode[];
  createTime?: string;
  updateTime?: string;
}

export interface IngestionPipelinePayload {
  name: string;
  description?: string | null;
  nodes?: Array<{
    nodeId: string;
    nodeType: string;
    settings?: Record<string, unknown> | null;
    condition?: Record<string, unknown> | null;
    nextNodeId?: string | null;
  }>;
}

export interface IngestionTask {
  id: string;
  pipelineId: string;
  sourceType?: string | null;
  sourceLocation?: string | null;
  sourceFileName?: string | null;
  status?: string | null;
  chunkCount?: number | null;
  errorMessage?: string | null;
  logs?: Array<{
    nodeId: string;
    nodeType: string;
    message?: string | null;
    durationMs?: number | null;
    success?: boolean | null;
    error?: string | null;
  }>;
  metadata?: Record<string, unknown> | null;
  startedAt?: string | null;
  completedAt?: string | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface IngestionTaskNode {
  id: string;
  taskId: string;
  pipelineId: string;
  nodeId: string;
  nodeType: string;
  nodeOrder?: number | null;
  status?: string | null;
  durationMs?: number | null;
  message?: string | null;
  errorMessage?: string | null;
  output?: Record<string, unknown> | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface IngestionResult {
  taskId: string;
  pipelineId: string;
  status?: string | null;
  chunkCount?: number | null;
  message?: string | null;
}

export interface IngestionTaskCreatePayload {
  pipelineId: string;
  source: {
    type: string;
    location: string;
    fileName?: string | null;
    credentials?: Record<string, string>;
  };
  metadata?: Record<string, unknown>;
  vectorSpaceId?: Record<string, unknown>;
}

export async function getIngestionPipelines(pageNo = 1, pageSize = 10, keyword?: string) {
  return api.get<PageResult<IngestionPipeline>, PageResult<IngestionPipeline>>(
    "/ingestion/pipelines",
    {
      params: { pageNo, pageSize, keyword: keyword || undefined }
    }
  );
}

export async function getIngestionPipeline(id: string) {
  return api.get<IngestionPipeline, IngestionPipeline>(`/ingestion/pipelines/${id}`);
}

export async function createIngestionPipeline(payload: IngestionPipelinePayload) {
  return api.post<IngestionPipeline, IngestionPipeline>("/ingestion/pipelines", payload);
}

export async function updateIngestionPipeline(id: string, payload: IngestionPipelinePayload) {
  return api.put<IngestionPipeline, IngestionPipeline>(`/ingestion/pipelines/${id}`, payload);
}

export async function deleteIngestionPipeline(id: string) {
  await api.delete(`/ingestion/pipelines/${id}`);
}

export async function getIngestionTasks(pageNo = 1, pageSize = 10, status?: string) {
  return api.get<PageResult<IngestionTask>, PageResult<IngestionTask>>("/ingestion/tasks", {
    params: { pageNo, pageSize, status: status || undefined }
  });
}

export async function getIngestionTask(id: string) {
  return api.get<IngestionTask, IngestionTask>(`/ingestion/tasks/${id}`);
}

export async function getIngestionTaskNodes(id: string) {
  return api.get<IngestionTaskNode[], IngestionTaskNode[]>(`/ingestion/tasks/${id}/nodes`);
}

export async function createIngestionTask(payload: IngestionTaskCreatePayload) {
  return api.post<IngestionResult, IngestionResult>("/ingestion/tasks", payload);
}

export async function uploadIngestionTask(pipelineId: string, file: File) {
  const formData = new FormData();
  formData.append("file", file);
  return api.post<IngestionResult, IngestionResult>("/ingestion/tasks/upload", formData, {
    params: { pipelineId },
    headers: { "Content-Type": "multipart/form-data" }
  });
}
