import { api } from "@/services/api";

export interface IntentNodeTree {
  id: number;
  intentCode: string;
  name: string;
  level: number;
  parentCode?: string | null;
  description?: string | null;
  examples?: string | null;
  collectionName?: string | null;
  collectionNames?: string[] | null;
  mcpToolId?: string | null;
  requireConfirm?: number | null;
  topK?: number | null;
  kind?: number | null;
  sortOrder?: number | null;
  enabled?: number | null;
  promptSnippet?: string | null;
  promptTemplate?: string | null;
  children?: IntentNodeTree[];
}

export interface IntentNodeCreatePayload {
  kbId?: string;
  collectionNames?: string[];
  intentCode: string;
  name: string;
  level: number;
  parentCode?: string | null;
  description?: string | null;
  examples?: string[];
  mcpToolId?: string | null;
  requireConfirm?: number | null;
  topK?: number | null;
  kind?: number | null;
  sortOrder?: number | null;
  enabled?: number | null;
  promptSnippet?: string | null;
  promptTemplate?: string | null;
}

export interface IntentNodeUpdatePayload {
  name?: string;
  level?: number;
  parentCode?: string | null;
  description?: string | null;
  examples?: string[];
  collectionName?: string | null;
  collectionNames?: string[] | null;
  mcpToolId?: string | null;
  requireConfirm?: number | null;
  topK?: number | null;
  kind?: number | null;
  sortOrder?: number | null;
  enabled?: number | null;
  promptSnippet?: string | null;
  promptTemplate?: string | null;
}

export async function getIntentTree() {
  return api.get<IntentNodeTree[], IntentNodeTree[]>("/intent-tree/trees");
}

export async function createIntentNode(payload: IntentNodeCreatePayload) {
  return api.post<string, string>("/intent-tree", payload);
}

export async function updateIntentNode(id: number | string, payload: IntentNodeUpdatePayload) {
  await api.put(`/intent-tree/${id}`, payload);
}

export async function deleteIntentNode(id: number | string) {
  await api.delete(`/intent-tree/${id}`);
}

export async function batchEnableIntentNodes(ids: number[]) {
  await api.post("/intent-tree/batch/enable", { ids });
}

export async function batchDisableIntentNodes(ids: number[]) {
  await api.post("/intent-tree/batch/disable", { ids });
}

export async function batchDeleteIntentNodes(ids: number[]) {
  await api.post("/intent-tree/batch/delete", { ids });
}
