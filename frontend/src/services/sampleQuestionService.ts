import { api } from "@/services/api";

export interface SampleQuestion {
  id: string;
  title?: string | null;
  description?: string | null;
  question: string;
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

export interface SampleQuestionPayload {
  title?: string | null;
  description?: string | null;
  question?: string | null;
}

/** 随机取若干条示例问题，两套前端的欢迎页共用 */
export async function listSampleQuestions(limit = 3): Promise<SampleQuestion[]> {
  return api.get<SampleQuestion[], SampleQuestion[]>("/sample-questions/random", {
    params: { limit }
  });
}

export async function getSampleQuestionsPage(
  current = 1,
  size = 10,
  keyword?: string
): Promise<PageResult<SampleQuestion>> {
  return api.get<PageResult<SampleQuestion>, PageResult<SampleQuestion>>("/sample-questions", {
    params: { current, size, keyword: keyword || undefined }
  });
}

export async function createSampleQuestion(payload: SampleQuestionPayload): Promise<string> {
  return api.post<string, string>("/sample-questions", payload);
}

export async function updateSampleQuestion(id: string, payload: SampleQuestionPayload): Promise<void> {
  await api.put(`/sample-questions/${id}`, payload);
}

export async function deleteSampleQuestion(id: string): Promise<void> {
  await api.delete(`/sample-questions/${id}`);
}
