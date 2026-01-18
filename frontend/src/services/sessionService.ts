import { api } from "@/services/api";

export interface ConversationVO {
  conversationId: string;
  title: string;
  lastTime?: string;
}

export interface ConversationMessageVO {
  id: number | string;
  conversationId: string;
  role: string;
  content: string;
  vote: number | null;
  createTime?: string;
}

export async function listSessions() {
  return api.get<ConversationVO[]>("/conversations");
}

export async function deleteSession(conversationId: string) {
  return api.delete<void>(`/conversations/${conversationId}`);
}

export async function renameSession(conversationId: string, title: string) {
  return api.put<void>(`/conversations/${conversationId}`, { title });
}

export async function listMessages(conversationId: string) {
  return api.get<ConversationMessageVO[]>(`/conversations/${conversationId}/messages`);
}
