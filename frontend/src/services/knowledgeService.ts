import { api } from "./api";

export interface KnowledgeBase {
  id: string;
  name: string;
  embeddingModel: string;
  collectionName: string;
  createdBy?: string | null;
  documentCount?: number;
  createTime?: string;
  updateTime?: string;
}

export interface KnowledgeDocument {
  id: string;
  kbId: string;
  docName: string;
  sourceType?: string | null;
  sourceLocation?: string | null;
  scheduleEnabled?: number | null;
  scheduleCron?: string | null;
  enabled?: boolean | null;
  chunkCount?: number | null;
  fileUrl?: string | null;
  fileType?: string | null;
  fileSize?: number | null;
  processMode?: string | null;
  chunkStrategy?: string | null;
  chunkConfig?: string | null;
  chunkSize?: number | null;
  overlapSize?: number | null;
  targetChars?: number | null;
  maxChars?: number | null;
  minChars?: number | null;
  overlapChars?: number | null;
  pipelineId?: string | number | null;
  status?: string | null;
  createdBy?: string | null;
  updatedBy?: string | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface KnowledgeChunk {
  id: string;
  kbId?: string;
  docId: string;
  chunkIndex?: number | null;
  content?: string | null;
  contentHash?: string | null;
  charCount?: number | null;
  tokenCount?: number | null;
  enabled?: number | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

export interface KnowledgeBaseUpdatePayload {
  name?: string;
  embeddingModel?: string;
}

export interface KnowledgeDocumentPageParams {
  pageNo?: number;
  pageSize?: number;
  status?: string;
  keyword?: string;
}

export interface KnowledgeDocumentUploadPayload {
  sourceType: "file" | "url";
  file?: File | null;
  sourceLocation?: string | null;
  scheduleEnabled?: boolean;
  scheduleCron?: string | null;
  processMode?: "chunk" | "pipeline";
  chunkStrategy?: "fixed_size" | "structure_aware";
  chunkSize?: number | null;
  overlapSize?: number | null;
  targetChars?: number | null;
  maxChars?: number | null;
  minChars?: number | null;
  overlapChars?: number | null;
  pipelineId?: string | null;
}

export interface KnowledgeChunkPageParams {
  current?: number;
  size?: number;
  enabled?: number;
}

// 知识库管理
export const getKnowledgeBases = async (current = 1, size = 200, name?: string): Promise<KnowledgeBase[]> => {
  const page = await api.get<PageResult<KnowledgeBase>, PageResult<KnowledgeBase>>("/knowledge-base", {
    params: { current, size, name: name || undefined }
  });
  return page?.records || [];
};

export const getKnowledgeBasesPage = async (
  current = 1,
  size = 10,
  name?: string
): Promise<PageResult<KnowledgeBase>> => {
  return api.get<PageResult<KnowledgeBase>, PageResult<KnowledgeBase>>("/knowledge-base", {
    params: { current, size, name: name || undefined }
  });
};

export const getKnowledgeBase = async (id: string): Promise<KnowledgeBase> => {
  return api.get<KnowledgeBase, KnowledgeBase>(`/knowledge-base/${id}`);
};

export const createKnowledgeBase = async (data: Partial<KnowledgeBase>): Promise<string> => {
  return api.post<string, string>("/knowledge-base", data);
};

export const updateKnowledgeBase = async (id: string, data: KnowledgeBaseUpdatePayload): Promise<void> => {
  await api.put(`/knowledge-base/${id}`, data);
};

export const renameKnowledgeBase = async (id: string, name: string): Promise<void> => {
  await api.put(`/knowledge-base/${id}`, { name });
};

export const deleteKnowledgeBase = async (id: string): Promise<void> => {
  await api.delete(`/knowledge-base/${id}`);
};

// 文档管理
export const getDocumentsPage = async (
  kbId: string,
  params: KnowledgeDocumentPageParams = {}
): Promise<PageResult<KnowledgeDocument>> => {
  return api.get<PageResult<KnowledgeDocument>, PageResult<KnowledgeDocument>>(`/knowledge-base/${kbId}/docs`, {
    params: {
      pageNo: params.pageNo ?? 1,
      pageSize: params.pageSize ?? 10,
      status: params.status || undefined,
      keyword: params.keyword || undefined
    }
  });
};

export const getDocuments = async (
  kbId: string,
  params: KnowledgeDocumentPageParams = {}
): Promise<KnowledgeDocument[]> => {
  const page = await getDocumentsPage(kbId, params);
  return page.records || [];
};

export const uploadDocument = async (
  kbId: string,
  payload: KnowledgeDocumentUploadPayload
): Promise<KnowledgeDocument> => {
  const formData = new FormData();
  formData.append("sourceType", payload.sourceType);
  if (payload.file) {
    formData.append("file", payload.file);
  }
  if (payload.sourceLocation) {
    formData.append("sourceLocation", payload.sourceLocation);
  }
  if (payload.scheduleEnabled !== undefined) {
    formData.append("scheduleEnabled", String(payload.scheduleEnabled));
  }
  if (payload.scheduleCron) {
    formData.append("scheduleCron", payload.scheduleCron);
  }
  if (payload.processMode) {
    formData.append("processMode", payload.processMode);
  }
  if (payload.chunkStrategy) {
    formData.append("chunkStrategy", payload.chunkStrategy);
  }
  if (payload.chunkSize !== undefined && payload.chunkSize !== null) {
    formData.append("chunkSize", String(payload.chunkSize));
  }
  if (payload.overlapSize !== undefined && payload.overlapSize !== null) {
    formData.append("overlapSize", String(payload.overlapSize));
  }
  if (payload.targetChars !== undefined && payload.targetChars !== null) {
    formData.append("targetChars", String(payload.targetChars));
  }
  if (payload.maxChars !== undefined && payload.maxChars !== null) {
    formData.append("maxChars", String(payload.maxChars));
  }
  if (payload.minChars !== undefined && payload.minChars !== null) {
    formData.append("minChars", String(payload.minChars));
  }
  if (payload.overlapChars !== undefined && payload.overlapChars !== null) {
    formData.append("overlapChars", String(payload.overlapChars));
  }
  if (payload.pipelineId) {
    formData.append("pipelineId", payload.pipelineId);
  }
  return api.post<KnowledgeDocument, KnowledgeDocument>(`/knowledge-base/${kbId}/docs/upload`, formData, {
    headers: {
      "Content-Type": "multipart/form-data"
    }
  });
};

export const getDocument = async (docId: string): Promise<KnowledgeDocument> => {
  return api.get<KnowledgeDocument, KnowledgeDocument>(`/knowledge-base/docs/${docId}`);
};

export const updateDocument = async (docId: string, data: { docName?: string }): Promise<void> => {
  await api.put(`/knowledge-base/docs/${docId}`, data);
};

export const startDocumentChunk = async (docId: string): Promise<void> => {
  await api.post(`/knowledge-base/docs/${docId}/chunk`);
};

export const enableDocument = async (docId: string, enabled: boolean): Promise<void> => {
  await api.patch(`/knowledge-base/docs/${docId}/enable`, null, {
    params: { value: enabled }
  });
};

export const deleteDocument = async (docId: string): Promise<void> => {
  await api.delete(`/knowledge-base/docs/${docId}`);
};

// 文档块管理
export const getChunksPage = async (
  docId: string,
  params: KnowledgeChunkPageParams = {}
): Promise<PageResult<KnowledgeChunk>> => {
  return api.get<PageResult<KnowledgeChunk>, PageResult<KnowledgeChunk>>(
    `/knowledge-base/docs/${docId}/chunks`,
    {
      params: {
        current: params.current ?? 1,
        size: params.size ?? 10,
        enabled: params.enabled ?? undefined
      }
    }
  );
};

export const getChunks = async (
  docId: string,
  params: KnowledgeChunkPageParams = {}
): Promise<KnowledgeChunk[]> => {
  const page = await getChunksPage(docId, params);
  return page.records || [];
};

export const createChunk = async (
  docId: string,
  payload: { content: string; index?: number | null; chunkId?: string }
): Promise<KnowledgeChunk> => {
  return api.post<KnowledgeChunk, KnowledgeChunk>(`/knowledge-base/docs/${docId}/chunks`, payload);
};

export const updateChunk = async (
  docId: string,
  chunkId: string,
  payload: { content: string }
): Promise<void> => {
  await api.put(`/knowledge-base/docs/${docId}/chunks/${chunkId}`, payload);
};

export const deleteChunk = async (docId: string, chunkId: string): Promise<void> => {
  await api.delete(`/knowledge-base/docs/${docId}/chunks/${chunkId}`);
};

export const enableChunk = async (docId: string, chunkId: string): Promise<void> => {
  await api.post(`/knowledge-base/docs/${docId}/chunks/${chunkId}/enable`);
};

export const disableChunk = async (docId: string, chunkId: string): Promise<void> => {
  await api.post(`/knowledge-base/docs/${docId}/chunks/${chunkId}/disable`);
};

export const batchEnableChunks = async (docId: string, chunkIds?: Array<string | number>): Promise<void> => {
  await api.post(`/knowledge-base/docs/${docId}/chunks/batch-enable`, {
    chunkIds: chunkIds && chunkIds.length ? chunkIds : undefined
  });
};

export const batchDisableChunks = async (docId: string, chunkIds?: Array<string | number>): Promise<void> => {
  await api.post(`/knowledge-base/docs/${docId}/chunks/batch-disable`, {
    chunkIds: chunkIds && chunkIds.length ? chunkIds : undefined
  });
};

export const rebuildChunks = async (docId: string): Promise<void> => {
  await api.post(`/knowledge-base/docs/${docId}/chunks/rebuild`);
};
