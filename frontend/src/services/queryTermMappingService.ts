import { api } from "@/services/api";

export interface QueryTermMapping {
  id: string;
  sourceTerm: string;
  targetTerm: string;
  matchType: number;
  priority: number;
  enabled: boolean;
  remark?: string | null;
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

export interface QueryTermMappingPayload {
  sourceTerm?: string | null;
  targetTerm?: string | null;
  matchType?: number | null;
  priority?: number | null;
  enabled?: boolean | null;
  remark?: string | null;
}

export async function getQueryTermMappingsPage(
  current = 1,
  size = 10,
  keyword?: string
): Promise<PageResult<QueryTermMapping>> {
  return api.get<PageResult<QueryTermMapping>, PageResult<QueryTermMapping>>("/mappings", {
    params: { current, size, keyword: keyword || undefined }
  });
}

export async function createQueryTermMapping(payload: QueryTermMappingPayload): Promise<string> {
  return api.post<string, string>("/mappings", payload);
}

export async function updateQueryTermMapping(id: string, payload: QueryTermMappingPayload): Promise<void> {
  await api.put(`/mappings/${id}`, payload);
}

export async function deleteQueryTermMapping(id: string): Promise<void> {
  await api.delete(`/mappings/${id}`);
}
