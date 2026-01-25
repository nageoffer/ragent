import { api } from "./api";

export interface KnowledgeBase {
  id: string;
  name: string;
  embeddingModel: string;
  collectionName: string;
  documentCount?: number;
  createTime?: string;
  updateTime?: string;
}

export interface KnowledgeDocument {
  id: string;
  kbId: string;
  docName: string;
  fileUrl: string;
  fileType: string;
  status: string;
  chunkCount: number;
  createTime?: string;
}

export interface KnowledgeChunk {
  id: string;
  docId: string;
  content: string;
  embedding?: number[];
  createTime?: string;
}

interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

// 知识库管理
export const getKnowledgeBases = async (): Promise<KnowledgeBase[]> => {
  const page = await api.get<PageResult<KnowledgeBase>, PageResult<KnowledgeBase>>("/knowledge-base");
  return page?.records || [];
};

export const getKnowledgeBase = async (id: string): Promise<KnowledgeBase> => {
  return api.get<KnowledgeBase, KnowledgeBase>(`/knowledge-base/${id}`);
};

export const createKnowledgeBase = async (data: Partial<KnowledgeBase>): Promise<string> => {
  return api.post<string, string>("/knowledge-base", data);
};

export const updateKnowledgeBase = async (id: string, data: Partial<KnowledgeBase>): Promise<void> => {
  await api.put(`/knowledge-base/${id}`, data);
};

export const deleteKnowledgeBase = async (id: string): Promise<void> => {
  await api.delete(`/knowledge-base/${id}`);
};

// 文档管理
export const getDocuments = async (kbId: string): Promise<KnowledgeDocument[]> => {
  const page = await api.get<PageResult<KnowledgeDocument>, PageResult<KnowledgeDocument>>(
    `/knowledge-base/${kbId}/docs`
  );
  return page?.records || [];
};

export const uploadDocument = async (kbId: string, file: File): Promise<KnowledgeDocument> => {
  const formData = new FormData();
  formData.append("file", file);
  return api.post<KnowledgeDocument, KnowledgeDocument>(`/knowledge-base/${kbId}/docs/upload`, formData, {
    headers: {
      "Content-Type": "multipart/form-data"
    }
  });
};

export const deleteDocument = async (docId: string): Promise<void> => {
  await api.delete(`/knowledge-base/docs/${docId}`);
};

// 文档块管理
export const getChunks = async (docId: string): Promise<KnowledgeChunk[]> => {
  const page = await api.get<PageResult<KnowledgeChunk>, PageResult<KnowledgeChunk>>(
    `/knowledge-base/docs/${docId}/chunks`
  );
  return page?.records || [];
};
