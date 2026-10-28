import { api } from "@/services/api";
import type { AgentBlock, AgentEngineMeta, AgentPersistedMessageStatus } from "@/types/agent";

export interface AgentConversationVO {
  conversationId: string;
  title: string;
  lastTime?: string;
  turns?: number;
}

export interface AgentMessageVO {
  id: number | string;
  role: string;
  content: string;
  thinkingContent?: string | null;
  // 旧数据为 null 由前端按持久化字段合成
  blocks?: AgentBlock[] | null;
  messageStatus?: AgentPersistedMessageStatus | null;
  // 服务端耗时 assistant 才有
  durationMs?: number | null;
  createTime?: string;
}

export async function listAgentSessions() {
  return api.get<AgentConversationVO[], AgentConversationVO[]>("/agent/v1/conversations");
}

export async function listAgentMessages(conversationId: string) {
  return api.get<AgentMessageVO[], AgentMessageVO[]>(
    `/agent/v1/conversations/${conversationId}/messages`
  );
}

export async function renameAgentSession(conversationId: string, title: string) {
  return api.put<void>(`/agent/v1/conversations/${conversationId}/title`, { title });
}

export async function deleteAgentSession(conversationId: string) {
  return api.delete<void>(`/agent/v1/conversations/${conversationId}`);
}

export async function batchDeleteAgentSessions(conversationIds: string[]) {
  return api.post<void>("/agent/v1/conversations/batch-delete", { ids: conversationIds });
}

export async function getAgentMeta() {
  return api.get<AgentEngineMeta, AgentEngineMeta>("/agent/v1/meta");
}

export async function stopAgentTask(taskId: string) {
  return api.post<void>(`/agent/v1/stop?taskId=${encodeURIComponent(taskId)}`);
}

export interface CartOperation {
  operationId: string;
  conversationId: string;
  roundId: string;
  skuCode: string;
  productName?: string | null;
  quantity: number;
  status: "PROCESSING" | "VERIFYING" | "PENDING" | "SUCCEEDED" | "REJECTED" | "CANCELLED";
  writeAttempts: number;
  stoppedAt?: string | null;
  disconnectedAt?: string | null;
  message?: string | null;
  quantityAfter?: number | null;
  executedAt?: string | null;
}

export function listCartOperations(conversationId: string) {
  return api.get<CartOperation[], CartOperation[]>(`/agent/v1/conversations/${conversationId}/cart-operations`);
}

export interface CartOperationActionResult {
  operation: CartOperation;
  verificationStarted: boolean;
  deferred: boolean;
  retryAt?: string | null;
  notice?: string | null;
}

export function actOnCartOperation(operationId: string, action: "verify" | "continue" | "stop", signal?: AbortSignal) {
  return api.post<CartOperationActionResult, CartOperationActionResult>(`/agent/v1/cart-operations/${operationId}/${action}`, undefined, { signal });
}
