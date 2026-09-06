export type AgentRole = "user" | "assistant";

export type AgentMessageUiStatus = "streaming" | "done" | "cancelled" | "error";

// AWAITING_CONFIRM 是唯一的非终态 表示这条回答停在写操作确认卡片上
export type AgentPersistedMessageStatus = "NORMAL" | "INTERRUPTED" | "AWAITING_CONFIRM";

// hint 为流式过程中的运行提示 只存在于前端时间线 后端不落库
export type AgentBlockKind = "reasoning" | "answer" | "tool" | "hint" | "confirm" | "error";

// 块状态 前四个属于工具块 后五个属于确认卡片
export type AgentBlockStatus =
  | "done"
  | "failed"
  | "interrupted"
  | "awaiting"
  | "pending"
  | "submitting"
  | "approved"
  | "denied"
  | "expired";

// 结构化后的一项入参 name 用来比对差异 label 只管展示
export interface AgentConfirmField {
  name: string;
  label: string;
  value: string;
}

// 待用户裁决的一次工具调用 入参只用于展示 续跑时后端取自己那份原件
export interface AgentConfirmCall {
  toolCallId: string;
  name: string;
  displayName?: string | null;
  fields?: AgentConfirmField[] | null;
  arguments?: string | null;
}

export interface AgentSession {
  id: string;
  title: string;
  lastTime?: string;
  turns?: number;
}

// 后端回放的时间线块
export interface AgentBlock {
  kind: AgentBlockKind;
  at: string;
  text?: string | null;
  name?: string | null;
  displayName?: string | null;
  status?: AgentBlockStatus | null;
  result?: string | null;
  toolCallId?: string | null;
  calls?: AgentConfirmCall[] | null;
}

// 前端时间线块 id 为客户端自增 open 为折叠面板展开态
export interface AgentBlockUI {
  id: number;
  kind: AgentBlockKind;
  at: string;
  text?: string;
  name?: string;
  displayName?: string;
  status?: AgentBlockStatus | "running";
  result?: string;
  // 确认卡靠它认领本轮的工具块 老会话的块没有 认不到就退回旧形态
  toolCallId?: string;
  calls?: AgentConfirmCall[];
  open?: boolean;
  // 流式实测耗时 仅本次连接内可得 回放块无此二字段 行级不显示耗时
  startMs?: number;
  durationMs?: number;
}

export interface AgentMessage {
  id: string;
  role: AgentRole;
  content: string;
  thinking?: string;
  blocks?: AgentBlockUI[];
  status?: AgentMessageUiStatus;
  messageStatus?: AgentPersistedMessageStatus;
  createdAt?: string;
  // 轮次总耗时 流式收尾实测 回放由相邻 user/assistant createTime 差值补齐
  elapsedMs?: number;
}

export interface AgentMetaPayload {
  conversationId: string;
  taskId: string;
}

export interface AgentMessageDelta {
  type: string;
  delta: string;
}

export interface AgentToolProgress {
  toolCallId?: string | null;
  name: string;
  displayName: string;
  status: "start" | "end";
  result?: string | null;
  ok?: boolean | null;
}

export interface AgentHintPayload {
  code: string;
  text: string;
}

// 本轮停在写操作确认上 与 finish 互斥 messageId 是续跑凭据
export interface AgentConfirmPayload {
  messageId?: string | null;
  title?: string | null;
  calls: AgentConfirmCall[];
}

export interface AgentCompletionPayload {
  messageId?: string | null;
  title?: string | null;
  messageStatus?: AgentPersistedMessageStatus;
}

// 引擎探活身份 /agent/v1/meta
export interface AgentEngineMeta {
  framework: string;
  model: string;
  maxIters: number;
  capabilities: string[];
  toolProvider: string;
  mcpConfigured: boolean;
}

// 原始帧抽屉逐条记录
export interface AgentRawFrame {
  id: number;
  ts: string;
  name: string;
  data: unknown;
}
