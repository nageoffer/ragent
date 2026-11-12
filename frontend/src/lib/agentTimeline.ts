import type {
  AgentBlock,
  AgentBlockUI,
  AgentConfirmStatus,
  AgentTextBlockSeal,
  AgentToolProgress,
  AgentTurn
} from "@/types/agent";

/**
 * Agent 时间线的纯投影层：SSE 帧与落库块进来 轨迹行出去
 * 状态、批次、序号、执行耗时全部照抄服务端 —— 这里一个都不推断 也不碰 Date.now()
 */

// 轨迹通道 与块 kind 同名 batch 是前端按 batchId 合成的批头行
export type TraceChannel =
  | "user"
  | "reasoning"
  | "tool"
  | "answer"
  | "hint"
  | "confirm"
  | "error"
  | "batch";

export interface TraceRow {
  key: string;
  channel: TraceChannel;
  ts: string;
  text?: string;
  block?: AgentBlockUI;
  // 块归属的助手消息 一轮多答时展开态与确认动作都要认准这条
  messageId?: string;
  // 确认卡各项的执行结局 与 block.calls 同序 认不到为 undefined
  outcomes?: (AgentBlockUI | undefined)[];
  streaming?: boolean;
  // 这条 awaiting 不在确认卡里 只是同批有人要授权才跟着停 不是它自己要用户点头
  batchWaiting?: boolean;
  // 批内工具数 只有批头行带
  batchSize?: number;
  // 批内已记录的执行区间有没有重叠 只有批头行带 读不出来就不表态
  parallel?: boolean;
  // 服务端耗时 工具行是这次调用自己的 批头行是各工具体的时间包络 两者不是一个量
  durationMs?: number;
}

const pad2 = (value: number) => String(value).padStart(2, "0");

function hms(date: Date): string {
  return Number.isNaN(date.getTime())
    ? ""
    : `${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`;
}

/** 本地时刻 HH:mm:ss 认不出的值给空串 由调用方决定要不要显示 */
export function toHms(value?: string | null): string {
  return value ? hms(new Date(value)) : "";
}

// 块时刻两种历史形态都要认：新数据是 yyyy-MM-ddTHH:mm:ss 老数据只有 HH:mm:ss
export function toBlockHms(value?: string | null): string {
  if (!value) return "";
  return /^\d{2}:\d{2}:\d{2}$/.test(value) ? value : toHms(value);
}

/** 服务端 epoch 毫秒转本地时刻 用来标批次与工具的执行起点 */
export function epochHms(value?: number): string {
  return value != null && Number.isFinite(value) ? hms(new Date(value)) : "";
}

/**
 * 耗时刻度：1s 内按毫秒说 10s 内留一位小数 1m 起转 m/s 复合
 * 起止都是毫秒级采样 差值为 0 只说明落在同一毫秒里 写成 0ms 会被读成没花时间
 * 秒级刻度不许下探到毫秒：3ms 经 toFixed(1) 就成了 0.0s 那是把非零耗时抹成零
 */
export function formatDuration(ms?: number): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return "";
  if (ms < 1) return "<1ms";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 10_000) return `${(ms / 1000).toFixed(1)}s`;
  const secs = Math.round(ms / 1000);
  if (secs < 60) return `${secs}s`;
  return `${Math.floor(secs / 60)}m${String(secs % 60).padStart(2, "0")}s`;
}

// 还开着的工具块才可能收到后续帧 终态块不再接管新事件
function isOpenTool(block: AgentBlockUI): boolean {
  return block.kind === "tool" && (block.status === "pending" || block.status === "running");
}

/**
 * 这一帧该落在哪个块上：有 toolCallId 就按它配对 同名并行才不会串块
 * 只有端点不回 id 的旧数据才按名字回落 —— pending 帧是一次新调用的announcement 谁也不认领
 * 其余帧先开先闭 running 优先认还停在 pending 的那个 终态优先认已经在跑的那个
 * 降级后果：同名并行且都没有 id 时 两条结果可能张冠李戴 但调用次数与状态推进不会错
 */
function matchToolIndex(blocks: AgentBlockUI[], payload: AgentToolProgress): number {
  if (payload.toolCallId) {
    for (let i = blocks.length - 1; i >= 0; i -= 1) {
      const block = blocks[i];
      if (block.kind === "tool" && block.toolCallId === payload.toolCallId) return i;
    }
    return -1;
  }
  if (payload.status === "pending") return -1;
  const expect = payload.status === "running" ? "pending" : "running";
  let fallback = -1;
  for (let i = 0; i < blocks.length; i += 1) {
    const block = blocks[i];
    if (block.toolCallId || block.name !== payload.name || !isOpenTool(block)) continue;
    if (block.status === expect) return i;
    if (fallback < 0) fallback = i;
  }
  return fallback;
}

/**
 * SSE block 工具载荷投影成时间线块
 * 认不到已有块就补一行：确认续跑的工具是上一条消息开的头 这一轮只收得到执行段 不补就看不见它跑过
 * 缺字段一律退回块上的旧值 —— 载荷按 NON_NULL 走 pending 帧尚无执行批次与起止时间
 */
export function applyToolFrame(
  blocks: AgentBlockUI[],
  payload: AgentToolProgress,
  ctx: { allocId: () => number; fallbackAt: string }
): AgentBlockUI[] {
  const next = [...blocks];
  const index = matchToolIndex(next, payload);
  const prev = index >= 0 ? next[index] : undefined;
  const merged: AgentBlockUI = {
    ...prev,
    id: prev?.id ?? ctx.allocId(),
    kind: "tool",
    // 服务端给了时刻就以它为准：旧接口缺少时刻时才使用到达时刻占位
    at: toBlockHms(payload.at) || prev?.at || ctx.fallbackAt,
    name: payload.name,
    displayName: payload.displayName || payload.name,
    toolCallId: payload.toolCallId ?? prev?.toolCallId,
    status: payload.status,
    // 结果只随终态帧来 pending / running 不许把已有结果清成空
    result: payload.result ?? prev?.result,
    batchId: payload.batchId ?? prev?.batchId,
    callIndex: payload.callIndex ?? prev?.callIndex,
    startedAt: payload.startedAt ?? prev?.startedAt,
    endedAt: payload.endedAt ?? prev?.endedAt,
    durationMs: payload.durationMs ?? prev?.durationMs,
    durationSource: payload.durationSource ?? prev?.durationSource
  };
  if (index >= 0) {
    next[index] = merged;
  } else {
    next.push(merged);
  }
  return next;
}

/**
 * SSE block 帧投影：认领最早那个同类且还没收口的文本块
 * 服务端按封口先后发 依次认领就与它那边一一对应 不必在帧里另带一个块标识
 * 认不到就丢弃 —— 无主的起止挂到别的块上 就是把一段文字的耗时说成另一段的
 */
export function applyTextBlockSeal(
  blocks: AgentBlockUI[],
  payload: AgentTextBlockSeal
): AgentBlockUI[] {
  const index = blocks.findIndex(
    (block) => block.kind === payload.kind && block.endedAt == null
  );
  if (index < 0) return blocks;
  const next = [...blocks];
  next[index] = {
    ...next[index],
    // 时刻也换成服务端那份：本地时钟盖的章与库里差一秒 刷新后当场跳一下
    at: toBlockHms(payload.at) || next[index].at,
    startedAt: payload.startedAt ?? next[index].startedAt,
    endedAt: payload.endedAt ?? undefined,
    durationMs: payload.durationMs ?? undefined
  };
  return next;
}

/**
 * 收尾时还开着的工具块按后端 settledBlocks 同一条规则落定
 * pending 与 running 一视同仁：模型开了口没跑成 与 跑到一半断了 对用户都是没有结果
 */
export function settleToolBlocks(
  blocks: AgentBlockUI[] | undefined,
  status: "interrupted" | "awaiting"
): AgentBlockUI[] | undefined {
  if (!blocks) return blocks;
  return blocks.map((block) => (isOpenTool(block) ? { ...block, status } : block));
}

/** 确认决定落到原消息：只有拒绝才同步卡片明确关联的 awaiting 工具，不推断执行结果。 */
export function applyConfirmStatus(
  blocks: AgentBlockUI[],
  blockId: number,
  status: AgentConfirmStatus
): AgentBlockUI[] {
  const card = blocks.find((block) => block.id === blockId && block.kind === "confirm");
  if (!card) return blocks;
  const deniedCallIds = new Set(
    status === "denied"
      ? (card.calls ?? []).map((call) => call.toolCallId).filter((id) => id?.trim())
      : []
  );
  return blocks.map((block) => {
    if (block === card) return { ...block, status };
    if (
      block.kind === "tool" &&
      block.status === "awaiting" &&
      block.toolCallId &&
      deniedCallIds.has(block.toolCallId)
    ) {
      return { ...block, status: "denied" };
    }
    return block;
  });
}

/**
 * 落库块投影成时间线块：与 SSE 帧写的是同一组字段 刷新前后才是同一条记录
 * 老数据这些字段是空的 空就是不显示耗时 不拿到达时间补猜
 */
export function replayBlock(block: AgentBlock, id: number): AgentBlockUI {
  return {
    id,
    kind: block.kind,
    at: toBlockHms(block.at),
    text: block.text ?? undefined,
    name: block.name ?? undefined,
    displayName: block.displayName ?? undefined,
    status: block.status ?? (block.kind === "tool" ? "done" : undefined),
    result: block.result ?? undefined,
    toolCallId: block.toolCallId ?? undefined,
    calls: block.calls ?? undefined,
    batchId: block.batchId ?? undefined,
    callIndex: block.callIndex ?? undefined,
    startedAt: block.startedAt ?? undefined,
    endedAt: block.endedAt ?? undefined,
    durationMs: block.durationMs ?? undefined,
    durationSource: block.durationSource ?? undefined,
    open: false
  };
}

/**
 * 确认卡按 toolCallId 认领本轮的工具块 逐项结局由卡片自己显示
 * 一轮可跨多条助手消息：待确认那条只有开了头的块 结果块在续跑那条 后者自然覆盖前者
 */
export function claimByConfirm(turn: AgentTurn): Map<string, AgentBlockUI | undefined> {
  const blocks = turn.assistants.flatMap((assistant) => assistant.blocks ?? []);
  const claimed = new Map<string, AgentBlockUI | undefined>();
  for (const block of blocks) {
    if (block.kind !== "confirm") continue;
    for (const call of block.calls ?? []) claimed.set(call.toolCallId, undefined);
  }
  for (const block of blocks) {
    if (block.kind === "tool" && block.toolCallId && claimed.has(block.toolCallId)) {
      claimed.set(block.toolCallId, block);
    }
  }
  return claimed;
}

/**
 * 一轮里同一个 toolCallId 只该出现一次：挂起那条与续跑那条是一次调用的两个阶段
 * 留后不留前 —— 执行发生在用户点头之后 位置也该落在确认卡之后
 */
function supersededBlockIds(turn: AgentTurn): Set<number> {
  const latest = new Map<string, number>();
  const superseded = new Set<number>();
  for (const assistant of turn.assistants) {
    for (const block of assistant.blocks ?? []) {
      if (block.kind !== "tool" || !block.toolCallId) continue;
      const prev = latest.get(block.toolCallId);
      if (prev != null) superseded.add(prev);
      latest.set(block.toolCallId, block.id);
    }
  }
  return superseded;
}

// 同批才归一组：没有批次的块各算各的 按名字或时刻猜批次就是替后端编事实
function sameBatch(a: AgentBlockUI, b: AgentBlockUI): boolean {
  return a.batchId != null && a.batchId === b.batchId;
}

// 组内按模型给的调用序号排 缺序号的排在后面且保持原有先后
function byCallIndex(a: AgentBlockUI, b: AgentBlockUI): number {
  if (a.callIndex == null && b.callIndex == null) return 0;
  if (a.callIndex == null) return 1;
  if (b.callIndex == null) return -1;
  return a.callIndex - b.callIndex;
}

/**
 * 批头那一行的工具体时间包络：起点取最早开工 终点取最晚收工
 * 并行工具的区间本就重叠 逐条相加会把重叠段算两遍 包络只能量墙上时间的首尾
 * 有工具开了工却没有终点 说明这批还没收口 此时只报起点不报耗时 不拿现有的最晚终点冒充整批
 * 这不是后端那个 tool_batch —— 那一段从 acting 入口起算 含权限判定与后处理 比包络长几十毫秒 两个数不该互相对账
 */
function batchWindow(
  group: AgentBlockUI[],
  perTool: boolean
): { startedAt?: number; durationMs?: number } {
  if (!perTool) {
    // 老数据整批共享同一份耗时 谁身上有就是这一批的
    const head = group.find((item) => item.durationMs != null) ?? group[0];
    return { startedAt: head.startedAt, durationMs: head.durationMs };
  }
  const started = group.filter((item) => item.startedAt != null);
  if (started.length === 0) return {};
  const startedAt = Math.min(...started.map((item) => item.startedAt as number));
  if (started.some((item) => item.endedAt == null)) return { startedAt };
  return {
    startedAt,
    durationMs: Math.max(...started.map((item) => item.endedAt as number)) - startedAt
  };
}

/**
 * 批内已记录的执行区间有没有重叠：这是从起止读出来的事实 不是替后端猜的调度方式
 * 任一条没收口就不表态 断在半路的区间未知 说它并行或串行都是编
 * 老口径整批共用一份起止 读出来必然重叠 那不是并行的证据 调用方不该拿老数据问这个
 */
function overlapped(group: AgentBlockUI[]): boolean | undefined {
  const spans = group.filter((item) => item.startedAt != null);
  if (spans.length < 2 || spans.some((item) => item.endedAt == null)) return undefined;
  const ordered = [...spans].sort((a, b) => (a.startedAt as number) - (b.startedAt as number));
  let latestEnd = ordered[0].endedAt as number;
  for (let i = 1; i < ordered.length; i += 1) {
    // 前一条收工那一毫秒后一条才开工 一毫秒都不重叠 就不算并行
    if ((ordered[i].startedAt as number) < latestEnd) return true;
    latestEnd = Math.max(latestEnd, ordered[i].endedAt as number);
  }
  return false;
}

/**
 * 一轮的轨迹行：用户行 + 助手时间线块 依消息态补 等待/错误 合成行
 * 多工具批前面加一条批头行 —— 批头量工具体包络 各工具行量自己那次执行 两个数各有各的口径
 * 一次调用一行 不按同名同结果折叠：折叠只留得下第一条的身份与耗时 后几条的 toolCallId 就此对不上账
 * 行右侧的时刻同一根轴：工具行说的是这次执行 有起点就取执行起点 与批头一致；
 * 没进过工具体的（待执行 / 待确认 / 被拒）只有块建起的那一刻可讲 才退回 at
 * 块上的 at 是模型声明这次调用的时刻 拿它当执行时刻 子行会早于批头两秒 那正是同轴混两种语义的样子
 */
export function buildTimelineRows(turn: AgentTurn): TraceRow[] {
  const rows: TraceRow[] = [];
  const claimed = claimByConfirm(turn);
  const superseded = supersededBlockIds(turn);
  // 关联的未执行记录由确认卡展示，用户拒绝后已结算为 denied 的原工具块也不再单独成行。
  const hidden = (block: AgentBlockUI) =>
    superseded.has(block.id) ||
    (block.kind === "tool" &&
      (block.status === "awaiting" || block.status === "denied") &&
      Boolean(block.toolCallId) &&
      claimed.has(block.toolCallId as string));
  // 同批只要有一条要授权 整批就一起停下 卡里没有这条 它就只是随批等着 说它「待确认」是让用户去授权一件没人问他的事
  // 认不出 toolCallId 的老数据不表态：证不出它不在卡里 就不能改口
  const batchWaiting = (block: AgentBlockUI) =>
    block.kind === "tool" &&
    block.status === "awaiting" &&
    Boolean(block.toolCallId) &&
    !claimed.has(block.toolCallId as string);

  if (turn.user) {
    rows.push({
      key: `u-${turn.user.id}`,
      channel: "user",
      ts: toHms(turn.user.createdAt),
      text: turn.user.content
    });
  }

  for (const assistant of turn.assistants) {
    const blocks = assistant.blocks ?? [];
    const isStreaming = assistant.status === "streaming";
    // 最后一个块在流式中即活动轨迹 节点呼吸
    const activeId = isStreaming && blocks.length > 0 ? blocks[blocks.length - 1].id : null;
    const pushBlockRow = (block: AgentBlockUI, durationMs?: number) => {
      rows.push({
        key: `b-${block.id}`,
        channel: block.kind,
        // 时刻一律优先读服务端的执行/流式起点 只有没起点的块才退回 at
        ts: epochHms(block.startedAt) || block.at,
        text: block.text,
        block,
        messageId: assistant.id,
        outcomes:
          block.kind === "confirm"
            ? (block.calls ?? []).map((call) => claimed.get(call.toolCallId))
            : undefined,
        batchWaiting: batchWaiting(block) || undefined,
        streaming: block.id === activeId,
        // 文本块的耗时挂在自己身上 工具块那份由调用方按批次口径决定挂不挂
        durationMs: block.kind === "tool" ? durationMs : block.durationMs
      });
    };

    let i = 0;
    while (i < blocks.length) {
      const block = blocks[i];
      if (hidden(block)) {
        i += 1;
        continue;
      }
      if (block.kind !== "tool") {
        pushBlockRow(block);
        i += 1;
        continue;
      }
      // 收齐同批：中间被藏起来的块不算隔断 它们本来就不占行
      const group = [block];
      let j = i + 1;
      while (j < blocks.length) {
        const candidate = blocks[j];
        if (hidden(candidate)) {
          j += 1;
          continue;
        }
        if (candidate.kind !== "tool" || !sameBatch(candidate, block)) break;
        group.push(candidate);
        j += 1;
      }
      i = j;

      const ordered = [...group].sort(byCallIndex);
      // 口径认字段不认数据形状：这一批里只要有一条报了 tool 口径 整批就是逐条计时的新数据
      const perTool = ordered.some((item) => item.durationSource === "tool");
      if (ordered.length > 1) {
        const window = batchWindow(ordered, perTool);
        rows.push({
          key: `batch-${ordered[0].id}`,
          channel: "batch",
          ts: epochHms(window.startedAt),
          batchSize: ordered.length,
          // 行序按 callIndex 是模型声明的先后 并行那句得单独说 否则会被读成依次执行
          parallel: perTool ? overlapped(ordered) : undefined,
          durationMs: window.durationMs
        });
      }
      for (const item of ordered) {
        // 老数据那个数量的是整批 逐行各挂一遍会被读成每条都跑了这么久 新数据量的就是自己 照挂
        pushBlockRow(item, perTool || ordered.length === 1 ? item.durationMs : undefined);
      }
    }

    if (isStreaming && blocks.length === 0) {
      rows.push({
        key: `wait-${assistant.id}`,
        channel: "hint",
        ts: "",
        text: "等待响应…",
        streaming: true
      });
    }
    if (assistant.status === "error") {
      rows.push({
        key: `err-${assistant.id}`,
        channel: "error",
        ts: "",
        text: "生成失败，请稍后重试"
      });
    }
  }
  return rows;
}
