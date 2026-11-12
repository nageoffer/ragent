export type AgentRole = "user" | "assistant";

export type AgentMessageUiStatus = "streaming" | "done" | "cancelled" | "error";

// AWAITING_CONFIRM 是唯一的非终态 表示这条回答停在写操作确认卡片上
export type AgentPersistedMessageStatus = "NORMAL" | "INTERRUPTED" | "AWAITING_CONFIRM";

// 文本增量与文本块共用内容类型
export type AgentTextKind = "answer" | "reasoning" | "error";

// hint 为流式过程中的运行提示 只存在于前端时间线 后端不落库
export type AgentBlockKind = AgentTextKind | "tool" | "hint" | "confirm";

/**
 * 工具块状态 与后端同名同值 前端照抄不推断
 */
export type AgentToolStatus =
  | "pending"
  | "running"
  | "awaiting"
  | "done"
  | "failed"
  | "denied"
  | "interrupted";

// 确认卡记录决定及失效状态；只有 submitting 是前端提交中的临时状态
export type AgentConfirmStatus = "pending" | "submitting" | "approved" | "denied" | "expired";

export type AgentBlockStatus = AgentToolStatus | AgentConfirmStatus;

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

/**
 * 耗时口径：tool 逐条 / batch 整批共享（老数据），缺省即 batch
 */
export type AgentDurationSource = "tool" | "batch";

/**
 * 后端回放的时间线块 batchId 起五个字段是服务端计时 老数据为空即不显示耗时
 */
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
  batchId?: string | null;
  callIndex?: number | null;
  startedAt?: number | null;
  endedAt?: number | null;
  durationMs?: number | null;
  durationSource?: AgentDurationSource | null;
}

// 前端时间线块 id 为客户端自增 open 为折叠面板展开态
export interface AgentBlockUI {
  id: number;
  kind: AgentBlockKind;
  at: string;
  text?: string;
  name?: string;
  displayName?: string;
  status?: AgentBlockStatus;
  result?: string;
  // 确认卡靠它认领本轮的工具块 老会话的块没有 认不到就退回旧形态
  toolCallId?: string;
  calls?: AgentConfirmCall[];
  open?: boolean;
  batchId?: string;
  // 组内序号（0 基）同名并行靠它区分
  callIndex?: number;
  // 服务端计时 前端只投影 缺了不显示
  startedAt?: number;
  endedAt?: number;
  durationMs?: number;
  // 耗时口径 工具块用 文本块留空
  durationSource?: AgentDurationSource;
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
  // 服务端耗时 直播与回放同源
  elapsedMs?: number;
}

/**
 * 一轮对话的视图模型
 * 一问可对多答：停在确认卡片上的那条与用户裁决后的续答都属同一轮 按先后拼进同一张卡
 */
export interface AgentTurn {
  id: string;
  index: number;
  user?: AgentMessage;
  assistants: AgentMessage[];
}

export interface AgentMetaPayload {
  conversationId: string;
  taskId: string;
}

export interface AgentMessageDelta {
  type: AgentTextKind;
  delta: string;
}

/**
 * SSE block 的工具载荷，通过同一 toolCallId 更新状态、结果与时间
 */
export interface AgentToolProgress {
  kind: "tool";
  toolCallId?: string | null;
  name: string;
  displayName: string;
  status: AgentToolStatus;
  result?: string | null;
  // 只在终态有值
  ok?: boolean | null;
  at?: string | null;
  batchId?: string | null;
  callIndex?: number | null;
  startedAt?: number | null;
  endedAt?: number | null;
  durationMs?: number | null;
  durationSource?: AgentDurationSource | null;
}

/**
 * SSE block 帧 文本封口后服务端下发起止 不带正文
 */
export interface AgentTextBlockSeal {
  kind: AgentTextKind;
  at?: string | null;
  startedAt?: number | null;
  endedAt?: number | null;
  durationMs?: number | null;
}

// 同一 block 事件的两种载荷，由 kind 区分
export type AgentBlockUpdate = AgentTextBlockSeal | AgentToolProgress;

export interface AgentHintPayload {
  code: string;
  text: string;
}

// 本轮停在写操作确认上 与 finish 互斥 messageId 是续跑凭据
export interface AgentConfirmPayload {
  messageId?: string | null;
  title?: string | null;
  calls: AgentConfirmCall[];
  // 挂起也是本段 run 的收口
  durationMs?: number | null;
}

export interface AgentCompletionPayload {
  messageId?: string | null;
  title?: string | null;
  messageStatus?: AgentPersistedMessageStatus;
  // 服务端耗时 与落库同源
  durationMs?: number | null;
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
