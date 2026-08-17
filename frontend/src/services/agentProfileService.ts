import { api } from "@/services/api";

/** 执行架构档位，取自后端 ragent.engine.type，前端只读 */
export type OrchestrationMode = "WORKFLOW" | "AGENT";

/** 槽位分栏，与后端 AgentPromptSlot.Group 一致 */
export type SlotGroup = "WORKFLOW" | "AGENT" | "COMMON";

export interface AgentProfile {
  id: string;
  name: string;
  description?: string | null;
  /** 头像预设标识，见 AgentAvatar 预设表 */
  avatar?: string | null;
  builtin: boolean;
  active: boolean;
  /** 自身已填写、且当前架构会读取的槽位数，其余槽位回落内置 */
  effectiveSlots: number;
  /** 已填写但当前架构读不到的槽位数，切换 ragent.engine.type 后才生效 */
  inactiveSlots: number;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface AgentProfileList {
  mode: OrchestrationMode;
  /** 当前架构下生效的槽位总数，卡片覆盖率的分母 */
  effectiveSlotTotal: number;
  agents: AgentProfile[];
}

export interface AgentPromptSlot {
  slotKey: string;
  displayName: string;
  group: SlotGroup;
  groupName: string;
  /** 该槽位在当前架构下是否生效 */
  effective: boolean;
  /** 未生效原因，生效时为 null */
  inactiveReason?: string | null;
  requiredPlaceholders: string[];
  /** 本智能体自身配置的内容，空串表示未配置并回落内置 */
  content: string;
}

export interface AgentPromptConfig {
  agentId: string;
  agentName: string;
  builtin: boolean;
  /** 内置智能体的名称，文案要指名道姓说清空后沿用谁 */
  defaultAgentName?: string | null;
  mode: OrchestrationMode;
  slots: AgentPromptSlot[];
}

export interface AgentProfilePayload {
  name?: string | null;
  description?: string | null;
  avatar?: string | null;
}

export async function getAgentProfiles(): Promise<AgentProfileList> {
  return api.get<AgentProfileList, AgentProfileList>("/agents");
}

export async function createAgentProfile(payload: AgentProfilePayload): Promise<string> {
  return api.post<string, string>("/agents", payload);
}

export async function updateAgentProfile(id: string, payload: AgentProfilePayload): Promise<void> {
  await api.put(`/agents/${id}`, payload);
}

export async function deleteAgentProfile(id: string): Promise<void> {
  await api.delete(`/agents/${id}`);
}

export async function activateAgentProfile(id: string): Promise<void> {
  await api.post(`/agents/${id}/activate`);
}

export async function getAgentPrompts(id: string): Promise<AgentPromptConfig> {
  return api.get<AgentPromptConfig, AgentPromptConfig>(`/agents/${id}/prompts`);
}

export async function saveAgentPrompt(id: string, slotKey: string, content: string): Promise<void> {
  await api.put(`/agents/${id}/prompts/${slotKey}`, { content });
}

/** 内置智能体的槽位内容，供「从默认复制」 */
export async function getDefaultPrompt(slotKey: string): Promise<string> {
  return api.get<string, string>(`/agents/prompt-slots/${slotKey}/default`);
}
