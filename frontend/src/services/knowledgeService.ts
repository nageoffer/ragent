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
  ingestionSpec?: string | null;
  pipelineId?: string | number | null;
  status?: string | null;
  createdBy?: string | null;
  updatedBy?: string | null;
  createTime?: string | null;
  updateTime?: string | null;
  chunksEdited?: boolean | null;
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

export interface KnowledgeDocumentSearchItem {
  id: string;
  kbId: string | number;
  docName: string;
  kbName?: string | null;
}

export interface KnowledgeDocumentChunkLog {
  id: string;
  docId: string;
  status: string;
  processMode?: string | null;
  parseProfile?: string | null;
  pipelineId?: string | null;
  pipelineName?: string | null;
  extractDuration?: number | null;
  chunkDuration?: number | null;
  embedDuration?: number | null;
  persistDuration?: number | null;
  otherDuration?: number | null;
  totalDuration?: number | null;
  chunkCount?: number | null;
  errorMessage?: string | null;
  startTime?: string | null;
  endTime?: string | null;
  createTime?: string | null;
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
  current?: number;
  size?: number;
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
  ingestionSpec?: string | null;
  pipelineId?: string | null;
}

export interface KnowledgeChunkPageParams {
  current?: number;
  size?: number;
  enabled?: number;
}

// 知识库管理
export interface ParseProfileOption {
  value: string;
  label: string;
  hint?: string | null;
}

export interface BudgetFieldSchema {
  key: string;
  label: string;
  defaultValue: number;
  min: number;
  max: number;
  recommendedMin: number;
  recommendedMax: number;
  hint?: string | null;
  detail?: string | null;
}

/**
 * 摄取配置表单 schema：后端下发字段定义与取值范围，前端据此动态渲染，
 * 后端加一个参数不需要改前端
 */
export interface IngestionSpecSchema {
  /**
   * 档位选项的字段名：与下面的选项名一起由后端下发。这几段文案要互相说得通
   * （"表格结构"配"规整表格 / 复杂表格"），拆到两端各存一半必然改一处漏一处
   */
  parseProfileLabel: string;
  parseProfiles: ParseProfileOption[];
  /**
   * 档位真正有区别的文件扩展名（后端从解析器注册表推导）：不在清单里的格式，
   * 两档命中同一个解析器，选项必须藏起来。前端不得自己维护一份格式清单
   */
  parseProfileExtensions: string[];
  budgetFields: BudgetFieldSchema[];
  /**
   * 整篇不分块在线路上的取值：只在提交与回读时出现，不进表单状态
   */
  wholeDocumentSentinel: number;
}

export const getIngestionSpecSchema = async (): Promise<IngestionSpecSchema> => {
  return api.get<IngestionSpecSchema, IngestionSpecSchema>("/knowledge-base/docs/ingestion-spec-schema");
};

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
      current: params.current ?? 1,
      size: params.size ?? 10,
      status: params.status || undefined,
      keyword: params.keyword || undefined
    }
  });
};

export const searchKnowledgeDocuments = async (
  keyword: string,
  limit = 8
): Promise<KnowledgeDocumentSearchItem[]> => {
  return api.get<KnowledgeDocumentSearchItem[], KnowledgeDocumentSearchItem[]>("/knowledge-base/docs/search", {
    params: {
      keyword,
      limit
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
  if (payload.ingestionSpec) {
    formData.append("ingestionSpec", payload.ingestionSpec);
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

export const updateDocument = async (docId: string, data: {
  docName?: string;
  processMode?: string;
  ingestionSpec?: string;
  pipelineId?: string;
  sourceLocation?: string;
  scheduleEnabled?: number;
  scheduleCron?: string;
}): Promise<void> => {
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

export const toggleChunk = async (docId: string, chunkId: string, enabled: boolean): Promise<void> => {
  await api.patch(`/knowledge-base/docs/${docId}/chunks/${chunkId}/enable`, null, {
    params: { value: enabled }
  });
};

export const batchToggleChunks = async (
  docId: string,
  enabled: boolean,
  chunkIds: Array<string | number>
): Promise<void> => {
  await api.patch(
    `/knowledge-base/docs/${docId}/chunks/batch-enable`,
    { chunkIds },
    { params: { value: enabled } }
  );
};

export const previewDocument = async (docId: string): Promise<string> => {
  return api.get<unknown, string>(`/knowledge-base/docs/${docId}/preview`);
};

// 拉取文档源文件二进制(带鉴权)，用于 xlsx 在线预览 / csv 解码
export const fetchDocumentFile = async (docId: string): Promise<ArrayBuffer> => {
  return api.get<unknown, ArrayBuffer>(`/knowledge-base/docs/${docId}/file`, {
    responseType: "arraybuffer"
  });
};

// 文档分块日志管理
export const getChunkLogsPage = async (
  docId: string,
  current = 1,
  size = 10
): Promise<PageResult<KnowledgeDocumentChunkLog>> => {
  return api.get<PageResult<KnowledgeDocumentChunkLog>, PageResult<KnowledgeDocumentChunkLog>>(
    `/knowledge-base/docs/${docId}/chunk-logs`,
    {
      params: {
        current,
        size
      }
    }
  );
};
