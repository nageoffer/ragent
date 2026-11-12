import { api } from "@/services/api";

export interface AgentSkill {
  id: string;
  /** 技能标识，模型加载正文时报的名字 */
  skillCode: string;
  name: string;
  description: string;
  /** 技能正文 Markdown，列表接口不返回 */
  content?: string | null;
  toolIds: string[];
  sortOrder: number;
  enabled: boolean;
  createTime?: string | null;
  updateTime?: string | null;
}

/** 技能可勾为解锁项的工具，来自意图树里的 MCP 节点 */
export interface AgentSkillToolOption {
  toolId: string;
  name: string;
  description?: string | null;
  /** 已被哪个技能收为解锁项，未被收走时为空 */
  referencedBySkillName?: string | null;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

export interface AgentSkillPayload {
  skillCode?: string | null;
  name?: string | null;
  description?: string | null;
  content?: string | null;
  toolIds?: string[] | null;
  sortOrder?: number | null;
  enabled?: boolean | null;
}

export async function getAgentSkillsPage(
  current = 1,
  size = 10,
  keyword?: string
): Promise<PageResult<AgentSkill>> {
  return api.get<PageResult<AgentSkill>, PageResult<AgentSkill>>("/agent-skills", {
    params: { current, size, keyword: keyword || undefined }
  });
}

export async function getAgentSkill(id: string): Promise<AgentSkill> {
  return api.get<AgentSkill, AgentSkill>(`/agent-skills/${id}`);
}

export async function createAgentSkill(payload: AgentSkillPayload): Promise<string> {
  return api.post<string, string>("/agent-skills", payload);
}

export async function updateAgentSkill(id: string, payload: AgentSkillPayload): Promise<void> {
  await api.put(`/agent-skills/${id}`, payload);
}

export async function deleteAgentSkill(id: string): Promise<void> {
  await api.delete(`/agent-skills/${id}`);
}

export async function toggleAgentSkillEnabled(id: string, enabled: boolean): Promise<void> {
  await api.post(`/agent-skills/${id}/enabled`, null, { params: { enabled } });
}

export async function getAgentSkillToolOptions(): Promise<AgentSkillToolOption[]> {
  return api.get<AgentSkillToolOption[], AgentSkillToolOption[]>("/agent-skills/tool-options");
}
