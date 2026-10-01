import axios from "axios";
import { create } from "zustand";
import { toast } from "sonner";
import { format } from "date-fns";

import type {
  AgentBlockUpdate,
  AgentBlockUI,
  AgentCompletionPayload,
  AgentConfirmPayload,
  AgentHintPayload,
  AgentMessage,
  AgentMessageDelta,
  AgentMetaPayload,
  AgentRawFrame,
  AgentSession
} from "@/types/agent";
import {
  batchDeleteAgentSessions,
  deleteAgentSession,
  listAgentMessages,
  listAgentSessions,
  renameAgentSession,
  stopAgentTask
} from "@/services/agentService";
import { buildQuery } from "@/utils/helpers";
import { createAgentStreamResponse } from "@/hooks/useAgentStream";
import {
  applyConfirmStatus,
  applyTextBlockSeal,
  applyToolFrame,
  replayBlock,
  settleToolBlocks,
  toHms
} from "@/lib/agentTimeline";
import { storage } from "@/utils/storage";

interface AgentChatState {
  sessions: AgentSession[];
  currentSessionId: string | null;
  messages: AgentMessage[];
  isLoading: boolean;
  sessionsLoaded: boolean;
  inputFocusKey: number;
  // 欢迎页示例问题点击后预填输入框 key 保证同文重复点击也能触发
  draft: { text: string; key: number } | null;
  isStreaming: boolean;
  isCreatingNew: boolean;
  streamTaskId: string | null;
  streamAbort: (() => void) | null;
  streamingMessageId: string | null;
  // 当前接收增量的文本块 工具事件与换段都会将其封口
  streamOpenBlockId: number | null;
  cancelRequested: boolean;
  // 原始帧抽屉：本次连接收到的全部 SSE 帧 换会话即清
  frames: AgentRawFrame[];
  loadSessions: () => Promise<void>;
  // force 用于回查：绕开「已在本会话且有消息就不拉」的早退，拿服务端的说法覆盖本地
  loadMessages: (sessionId: string, force?: boolean) => Promise<void>;
  renameSession: (sessionId: string, title: string) => Promise<void>;
  deleteSession: (sessionId: string) => Promise<void>;
  batchDeleteSessions: (sessionIds: string[]) => Promise<void>;
  startNewChat: () => void;
  updateSessionTitle: (sessionId: string, title: string) => void;
  setDraft: (text: string) => void;
  toggleBlockOpen: (messageId: string, blockId: number) => void;
  sendMessage: (question: string) => Promise<void>;
  confirmPendingTool: (messageId: string, blockId: number, approved: boolean) => Promise<void>;
  cancelGeneration: () => void;
}

// 挂起中的会话只有确认与取消两条出路 新提问会被后端挡下 前端先自查免得白跑一趟
export function awaitingConfirm(messages: AgentMessage[]): boolean {
  const last = messages[messages.length - 1];
  return last?.role === "assistant" && last.messageStatus === "AWAITING_CONFIRM";
}

let blockSeq = 0;
const nextBlockId = () => ++blockSeq;

let frameSeq = 0;
// 原始帧上限：超长会话防内存膨胀 抽屉是调试面 截尾可接受
const MAX_FRAMES = 2000;

function nowHms() {
  return format(new Date(), "HH:mm:ss");
}

function upsertSession(sessions: AgentSession[], next: AgentSession) {
  const index = sessions.findIndex((session) => session.id === next.id);
  const updated = [...sessions];
  if (index >= 0) {
    updated[index] = { ...sessions[index], ...next };
  } else {
    updated.unshift(next);
  }
  return updated.sort((a, b) => {
    const timeA = a.lastTime ? new Date(a.lastTime).getTime() : 0;
    const timeB = b.lastTime ? new Date(b.lastTime).getTime() : 0;
    return timeB - timeA;
  });
}

// 封口敞开的文本块 思考块闭合即自动折叠
function sealOpenBlock(blocks: AgentBlockUI[], openBlockId: number | null) {
  if (openBlockId == null) return blocks;
  return blocks.map((block) =>
    block.id === openBlockId && block.kind === "reasoning" ? { ...block, open: false } : block
  );
}

// 收尾：工具块落定 + 思考块折叠
function settleBlocks(blocks: AgentBlockUI[] | undefined, toolStatus: "interrupted" | "awaiting") {
  return settleToolBlocks(blocks, toolStatus)?.map((block) =>
    block.kind === "reasoning" && block.open ? { ...block, open: false } : block
  );
}

// 一次流跑完要归零的全部流态 少归零一个字段 下一次提问就会被当成上一次的续播
const STREAM_IDLE = {
  isStreaming: false,
  streamTaskId: null,
  streamAbort: null,
  streamingMessageId: null,
  streamOpenBlockId: null,
  cancelRequested: false
} as const;

// 起流前先摆好的空助手消息 流式增量随后逐块落进它的 blocks
function newAssistantMessage(assistantId: string): AgentMessage {
  return {
    id: assistantId,
    role: "assistant",
    content: "",
    blocks: [],
    status: "streaming",
    createdAt: new Date().toISOString()
  };
}

// 与 STREAM_IDLE 对称的起流置位 少置一个字段 新流就会带着上一次的残态跑
function streamStartPatch(assistantId: string) {
  return {
    isStreaming: true,
    streamingMessageId: assistantId,
    streamOpenBlockId: null,
    streamTaskId: null,
    cancelRequested: false
  } as const;
}

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");

export const useAgentChatStore = create<AgentChatState>((set, get) => {
  // 文本增量按块规则落位：敞开块同类则追加 否则封口旧块并新开
  const appendText = (kind: AgentMessageDelta["type"], delta: string) => {
    if (!delta) return;
    set((state) => {
      let nextOpenId = state.streamOpenBlockId;
      const messages = state.messages.map((message) => {
        if (message.id !== state.streamingMessageId) return message;
        if (message.status === "cancelled" || message.status === "error") return message;
        let blocks = [...(message.blocks ?? [])];
        const idx = nextOpenId != null ? blocks.findIndex((b) => b.id === nextOpenId) : -1;
        if (idx >= 0 && blocks[idx].kind === kind) {
          blocks[idx] = { ...blocks[idx], text: (blocks[idx].text ?? "") + delta };
        } else {
          blocks = sealOpenBlock(blocks, nextOpenId);
          const created: AgentBlockUI = {
            id: nextBlockId(),
            kind,
            // 占位时刻 服务端封口时校正
            at: nowHms(),
            text: delta,
            // 流式思考块自动展开实时滚字
            open: kind === "reasoning" ? true : undefined
          };
          blocks.push(created);
          nextOpenId = created.id;
        }
        return {
          ...message,
          blocks,
          content: kind === "answer" ? message.content + delta : message.content,
          thinking: kind === "reasoning" ? `${message.thinking ?? ""}${delta}` : message.thinking
        };
      });
      return { messages, streamOpenBlockId: nextOpenId };
    });
  };

  // 结算确认卡及其关联的拒绝调用；带上 messageStatus 时一并解除消息挂起态
  const setConfirmStatus = (
    messageId: string,
    blockId: number,
    status: "submitting" | "approved" | "denied",
    messageStatus?: "NORMAL"
  ) => {
    set((state) => ({
      messages: state.messages.map((message) =>
        message.id === messageId && message.blocks
          ? {
              ...message,
              ...(messageStatus ? { messageStatus } : {}),
              blocks: applyConfirmStatus(message.blocks, blockId, status)
            }
          : message
      )
    }));
  };

  // 停止失败时恢复按钮；流若已收尾则保持安静，避免迟到的网络错误覆盖成功状态
  const requestStop = (taskId: string) => {
    void stopAgentTask(taskId).catch((error: unknown) => {
      const state = get();
      if (!state.isStreaming || !state.cancelRequested || state.streamTaskId !== taskId) {
        return;
      }
      set({ cancelRequested: false });
      // HTTP / 网络错误已由全局拦截器提示；业务 code 异常是普通 Error，由这里补提示
      if (!axios.isAxiosError(error)) {
        toast.error("停止请求未确认，请重试");
      }
    });
  };

  /**
   * 首问与确认续跑共用，返回是否收到过 meta（后端是否受理）
   */
  const runStream = async (params: { url: string; body?: unknown; assistantId: string }) => {
    const { url, body, assistantId } = params;
    const token = storage.getToken();
    // meta 是后端受理这一轮的第一帧：收到它才算请求确实送达，没收到就不知道断在哪一侧
    let delivered = false;

    const handlers = {
      // 每一条 SSE 帧原样进抽屉 供深度核对
      onEvent: (event: string, payload: unknown) => {
        set((state) => ({
          frames: [
            ...(state.frames.length >= MAX_FRAMES ? state.frames.slice(1) : state.frames),
            { id: ++frameSeq, ts: format(new Date(), "HH:mm:ss"), name: event, data: payload }
          ]
        }));
      },
      onMeta: (payload: AgentMetaPayload) => {
        delivered = true;
        if (get().streamingMessageId !== assistantId) return;
        const nextId = payload.conversationId || get().currentSessionId;
        if (!nextId) return;
        const lastTime = new Date().toISOString();
        const existing = get().sessions.find((session) => session.id === nextId);
        set((state) => ({
          currentSessionId: nextId,
          isCreatingNew: false,
          streamTaskId: payload.taskId,
          sessions: upsertSession(state.sessions, {
            id: nextId,
            title: existing?.title || "新对话",
            lastTime
          })
        }));
        // meta 前用户已点停止：此刻才拿到 taskId 补发停止指令
        if (get().cancelRequested) {
          requestStop(payload.taskId);
        }
      },
      onMessage: (payload: AgentMessageDelta) => {
        if (!payload || typeof payload !== "object") return;
        // 中断提示单开 error 块，跟模型说的话不是一个身份，样式与刷新后回放都按块走
        if (payload.type !== "answer" && payload.type !== "error") return;
        if (get().streamingMessageId !== assistantId) return;
        appendText(payload.type, payload.delta);
      },
      onThinking: (payload: AgentMessageDelta) => {
        if (!payload || typeof payload !== "object") return;
        if (payload.type !== "reasoning") return;
        if (get().streamingMessageId !== assistantId) return;
        appendText(payload.type, payload.delta);
      },
      // 块更新：工具更新状态和结果，文本只补齐时间
      onBlock: (payload: AgentBlockUpdate) => {
        if (!payload || typeof payload !== "object" || !payload.kind) return;
        if (get().streamingMessageId !== assistantId) return;
        if (payload.kind === "tool") {
          if (!payload.name || !payload.status) return;
        } else if (payload.kind !== "answer" && payload.kind !== "reasoning" && payload.kind !== "error") {
          return;
        }
        set((state) => ({
          // 只有工具更新会结束当前文本块；文本计时可能晚于下一段文字到达
          streamOpenBlockId: payload.kind === "tool" ? null : state.streamOpenBlockId,
          messages: state.messages.map((message) => {
            if (message.id !== state.streamingMessageId) return message;
            if (message.status === "cancelled" || message.status === "error") return message;
            if (payload.kind !== "tool") {
              return { ...message, blocks: applyTextBlockSeal(message.blocks ?? [], payload) };
            }
            const sealed = sealOpenBlock([...(message.blocks ?? [])], state.streamOpenBlockId);
            return {
              ...message,
              blocks: applyToolFrame(sealed, payload, { allocId: nextBlockId, fallbackAt: nowHms() })
            };
          })
        }));
      },
      // 运行提示（如迭代熔断预告）单独成行 不落库 回放自然消失
      onHint: (payload: AgentHintPayload) => {
        if (!payload?.text) return;
        if (get().streamingMessageId !== assistantId) return;
        set((state) => ({
          streamOpenBlockId: null,
          messages: state.messages.map((message) => {
            if (message.id !== state.streamingMessageId) return message;
            if (message.status === "cancelled" || message.status === "error") return message;
            const blocks = sealOpenBlock([...(message.blocks ?? [])], state.streamOpenBlockId);
            blocks.push({ id: nextBlockId(), kind: "hint", at: nowHms(), text: payload.text });
            return { ...message, blocks };
          })
        }));
      },
      // 挂起在写操作确认上：这轮没有终答 卡片带着落库 id 等用户裁决
      onConfirm: (payload: AgentConfirmPayload) => {
        if (get().streamingMessageId !== assistantId) return;
        if (!payload?.calls?.length) return;
        const currentId = get().currentSessionId;
        if (currentId && payload.title) {
          get().updateSessionTitle(currentId, payload.title);
        }
        set((state) => ({
          streamOpenBlockId: null,
          messages: state.messages.map((message) => {
            if (message.id !== state.streamingMessageId) return message;
            // 停在卡片上的这个工具正是还没跑的那个：说完成是在用户点头前就宣布办完了
            // 说中断也不对，用户什么都还没做；它只是没执行，与后端挂起时的落库口径一致
            const blocks = settleBlocks(message.blocks, "awaiting") ?? [];
            blocks.push({
              id: nextBlockId(),
              kind: "confirm",
              at: nowHms(),
              status: "pending",
              calls: payload.calls
            });
            return {
              ...message,
              id: payload.messageId ? String(payload.messageId) : message.id,
              status: "done" as const,
              blocks,
              elapsedMs: payload.durationMs ?? undefined,
              messageStatus: "AWAITING_CONFIRM" as const
            };
          })
        }));
      },
      onFinish: (payload: AgentCompletionPayload) => {
        if (get().streamingMessageId !== assistantId) return;
        if (!payload) return;
        const currentId = get().currentSessionId;
        if (currentId) {
          const lastTime = new Date().toISOString();
          const existingTitle =
            get().sessions.find((session) => session.id === currentId)?.title || "新对话";
          const nextTitle = payload.title || existingTitle;
          // 本地即时更新轮数徽标 与服务端 user 消息计数同口径
          const turns = get().messages.filter((message) => message.role === "user").length;
          set((state) => ({
            sessions: upsertSession(state.sessions, {
              id: currentId,
              title: nextTitle,
              lastTime,
              turns
            })
          }));
        }
        set((state) => ({
          streamOpenBlockId: null,
          messages: state.messages.map((message) =>
            message.id === state.streamingMessageId
              ? {
                  ...message,
                  id: payload.messageId ? String(payload.messageId) : message.id,
                  status: "done",
                  // 未到终态的工具按中断落定 与后端 settledBlocks 同规则
                  blocks: settleBlocks(message.blocks, "interrupted"),
                  elapsedMs: payload.durationMs ?? undefined,
                  messageStatus: payload.messageStatus ?? "NORMAL"
                }
              : message
          )
        }));
      },
      onCancel: (payload: AgentCompletionPayload) => {
        if (get().streamingMessageId !== assistantId) return;
        if (payload?.title && get().currentSessionId) {
          get().updateSessionTitle(get().currentSessionId as string, payload.title);
        }
        set((state) => ({
          messages: state.messages.map((message) => {
            if (message.id !== state.streamingMessageId) return message;
            const suffix = message.content.includes("（已停止生成）") ? "" : "\n\n（已停止生成）";
            const nextId = payload?.messageId ? String(payload.messageId) : message.id;
            let blocks = settleBlocks(message.blocks, "interrupted") ?? [];
            if (suffix) {
              let appended = false;
              for (let i = blocks.length - 1; i >= 0; i -= 1) {
                if (blocks[i].kind === "answer") {
                  blocks = [...blocks];
                  blocks[i] = { ...blocks[i], text: (blocks[i].text ?? "") + suffix };
                  appended = true;
                  break;
                }
              }
              if (!appended) {
                blocks = [
                  ...blocks,
                  { id: nextBlockId(), kind: "answer", at: nowHms(), text: "（已停止生成）" }
                ];
              }
            }
            return {
              ...message,
              id: nextId,
              content: message.content + suffix,
              blocks,
              status: "cancelled" as const,
              elapsedMs: payload?.durationMs ?? undefined,
              messageStatus: payload?.messageStatus ?? "INTERRUPTED"
            };
          }),
          ...STREAM_IDLE
        }));
      },
      onDone: () => {
        if (get().streamingMessageId !== assistantId) return;
        set({ ...STREAM_IDLE });
      },
      onError: (error: Error) => {
        if (get().streamingMessageId !== assistantId) return;
        set((state) => ({
          ...STREAM_IDLE,
          // 只剩带外断连会走到这里 流内失败由服务端的 error 块与 finish 讲清楚了
          // 那时页面上没有任何失败痕迹 得由这行补出来
          // 这里读的是本次更新前的旧值 与上面清空 streamingMessageId 不冲突
          messages: state.messages.map((message) =>
            message.id === state.streamingMessageId
              ? {
                  ...message,
                  status: "error" as const,
                  blocks: settleBlocks(message.blocks, "interrupted")
                }
              : message
          )
        }));
        toast.error(error.message || "生成失败");
      }
    };

    const { start, cancel } = createAgentStreamResponse(
      {
        url,
        body,
        headers: token ? { Authorization: token } : undefined
      },
      handlers
    );

    set({ streamAbort: cancel });

    try {
      await start();
    } catch (error) {
      if ((error as Error).name !== "AbortError") {
        handlers.onError?.(error as Error);
      }
    } finally {
      if (get().streamingMessageId === assistantId) {
        set({ ...STREAM_IDLE });
      }
    }
    return delivered;
  };

  return {
    sessions: [],
    currentSessionId: null,
    messages: [],
    isLoading: false,
    sessionsLoaded: false,
    inputFocusKey: 0,
    draft: null,
    isStreaming: false,
    isCreatingNew: false,
    streamTaskId: null,
    streamAbort: null,
    streamingMessageId: null,
    streamOpenBlockId: null,
    cancelRequested: false,
    frames: [],
    loadSessions: async () => {
      set({ isLoading: true });
      try {
        const data = await listAgentSessions();
        const sessions = data
          .map((item) => ({
            id: item.conversationId,
            title: item.title || "新对话",
            lastTime: item.lastTime,
            turns: item.turns
          }))
          .sort((a, b) => {
            const timeA = a.lastTime ? new Date(a.lastTime).getTime() : 0;
            const timeB = b.lastTime ? new Date(b.lastTime).getTime() : 0;
            return timeB - timeA;
          });
        set({ sessions });
      } catch (error) {
        toast.error((error as Error).message || "加载会话失败");
      } finally {
        set({ isLoading: false, sessionsLoaded: true });
      }
    },
    loadMessages: async (sessionId, force) => {
      if (!sessionId) return;
      if (!force && get().currentSessionId === sessionId && get().messages.length > 0) return;
      if (get().isStreaming) {
        get().cancelGeneration();
      }
      set({
        isLoading: true,
        currentSessionId: sessionId,
        isCreatingNew: false,
        // 回查是接着上一次连接排障，帧留着；换会话才清
        frames: force ? get().frames : []
      });
      try {
        const data = await listAgentMessages(sessionId);
        if (get().currentSessionId !== sessionId) {
          return;
        }
        const mapped: AgentMessage[] = data.map((item) => {
          const isAssistant = item.role === "assistant";
          let blocks: AgentBlockUI[] | undefined;
          if (isAssistant) {
            if (Array.isArray(item.blocks) && item.blocks.length > 0) {
              // 与流式同一组字段
              blocks = item.blocks.map((block) => replayBlock(block, nextBlockId()));
            } else {
              // 旧数据无块结构 由持久化字段合成
              const at = toHms(item.createTime);
              blocks = [];
              if (item.thinkingContent) {
                blocks.push({
                  id: nextBlockId(),
                  kind: "reasoning",
                  at,
                  text: item.thinkingContent,
                  open: false
                });
              }
              if (item.content) {
                blocks.push({ id: nextBlockId(), kind: "answer", at, text: item.content });
              }
            }
          }
          return {
            id: String(item.id),
            role: isAssistant ? ("assistant" as const) : ("user" as const),
            content: item.content,
            thinking: item.thinkingContent || undefined,
            blocks,
            createdAt: item.createTime,
            // 服务端耗时 旧数据为空则不显示
            elapsedMs: item.durationMs ?? undefined,
            status: "done" as const,
            messageStatus: item.messageStatus ?? "NORMAL"
          };
        });
        set({ messages: mapped });
      } catch (error) {
        // 回查失败要让调用方接住：那边正等着服务端表态，吞掉就只能一直「提交中」
        if (force) {
          throw error;
        }
        toast.error((error as Error).message || "加载消息失败");
      } finally {
        if (get().currentSessionId !== sessionId) {
          set({ isLoading: false });
        } else {
          set({
            isLoading: false,
            isStreaming: false,
            streamTaskId: null,
            streamAbort: null,
            streamingMessageId: null,
            streamOpenBlockId: null,
            cancelRequested: false
          });
        }
      }
    },
    renameSession: async (sessionId, title) => {
      const trimmed = title.trim();
      if (!trimmed) return;
      try {
        await renameAgentSession(sessionId, trimmed);
        get().updateSessionTitle(sessionId, trimmed);
      } catch (error) {
        toast.error((error as Error).message || "重命名失败");
      }
    },
    deleteSession: async (sessionId) => {
      try {
        await deleteAgentSession(sessionId);
        set((state) => ({
          sessions: state.sessions.filter((session) => session.id !== sessionId),
          messages: state.currentSessionId === sessionId ? [] : state.messages,
          currentSessionId: state.currentSessionId === sessionId ? null : state.currentSessionId
        }));
        toast.success("删除成功");
      } catch (error) {
        toast.error((error as Error).message || "删除会话失败");
      }
    },
    batchDeleteSessions: async (sessionIds) => {
      if (sessionIds.length === 0) return;
      try {
        await batchDeleteAgentSessions(sessionIds);
        const removed = new Set(sessionIds);
        set((state) => ({
          sessions: state.sessions.filter((session) => !removed.has(session.id)),
          messages: state.currentSessionId && removed.has(state.currentSessionId) ? [] : state.messages,
          currentSessionId:
            state.currentSessionId && removed.has(state.currentSessionId) ? null : state.currentSessionId
        }));
        toast.success(`已删除 ${sessionIds.length} 条会话`);
      } catch (error) {
        toast.error((error as Error).message || "批量删除失败");
      }
    },
    startNewChat: () => {
      const state = get();
      if (state.messages.length === 0 && !state.currentSessionId) {
        set({ isCreatingNew: true, isLoading: false });
        return;
      }
      if (state.isStreaming) {
        get().cancelGeneration();
      }
      set({
        currentSessionId: null,
        messages: [],
        isStreaming: false,
        isLoading: false,
        isCreatingNew: true,
        streamTaskId: null,
        streamAbort: null,
        streamingMessageId: null,
        streamOpenBlockId: null,
        cancelRequested: false,
        frames: []
      });
    },
    updateSessionTitle: (sessionId, title) => {
      set((state) => ({
        sessions: state.sessions.map((session) =>
          session.id === sessionId ? { ...session, title } : session
        )
      }));
    },
    setDraft: (text) => {
      set({ draft: { text, key: Date.now() } });
    },
    toggleBlockOpen: (messageId, blockId) => {
      set((state) => ({
        messages: state.messages.map((message) =>
          message.id === messageId && message.blocks
            ? {
                ...message,
                blocks: message.blocks.map((block) =>
                  block.id === blockId ? { ...block, open: !block.open } : block
                )
              }
            : message
        )
      }));
    },
    sendMessage: async (question) => {
      const trimmed = question.trim();
      if (!trimmed) return;
      if (get().isStreaming) return;
      if (awaitingConfirm(get().messages)) {
        toast.error("上一步操作还在等你确认，请先确认或取消");
        return;
      }
      const inputFocusKey = Date.now();

      const userMessage: AgentMessage = {
        id: `user-${Date.now()}`,
        role: "user",
        content: trimmed,
        status: "done",
        createdAt: new Date().toISOString()
      };
      const assistantId = `assistant-${Date.now()}`;

      set((state) => ({
        messages: [...state.messages, userMessage, newAssistantMessage(assistantId)],
        inputFocusKey,
        ...streamStartPatch(assistantId)
      }));

      const conversationId = get().currentSessionId;
      const query = buildQuery({
        question: trimmed,
        conversationId: conversationId || undefined
      });
      const url = `${API_BASE_URL}/agent/v1/chat${query}`;

      await runStream({ url, assistantId });
    },
    confirmPendingTool: async (messageId, blockId, approved) => {
      const conversationId = get().currentSessionId;
      if (!conversationId || get().isStreaming) return;
      // 先只标「提交中」：这一刻我们只知道自己点了，还不知道后端收没收到
      // 直接落成已同意，断网时页面会替后端说一句它没说过的话，而工具到底跑没跑用户无从得知
      setConfirmStatus(messageId, blockId, "submitting");

      const assistantId = `assistant-${Date.now()}`;
      set((state) => ({
        messages: [...state.messages, newAssistantMessage(assistantId)],
        ...streamStartPatch(assistantId)
      }));

      const delivered = await runStream({
        url: `${API_BASE_URL}/agent/v1/chat/confirm`,
        body: { conversationId, messageId, approved },
        assistantId
      });

      if (delivered) {
        // 后端已受理，卡片这才落定；此后即使流中途断了，裁决在库里也是实的
        setConfirmStatus(messageId, blockId, approved ? "approved" : "denied", "NORMAL");
        return;
      }
      // 没收到 meta：可能压根没发出去，也可能发出去了只是回程断了，猜不得——回查一次以服务端为准
      try {
        await get().loadMessages(conversationId, true);
      } catch {
        toast.error("网络不通，这一步是否已提交无法确认，恢复后请刷新页面");
        return;
      }
      // 回查回来仍是待批，说明这一次点击后端没收到；按钮已随之复原，明说一句免得用户干等
      const refreshed = get().messages.find((message) => message.id === messageId);
      const confirmBlock = refreshed?.blocks?.find((block) => block.kind === "confirm");
      if (confirmBlock?.status === "pending") {
        toast.error("网络不稳，这一步没提交成功，请重新确认");
      }
    },
    cancelGeneration: () => {
      const { isStreaming, streamTaskId, cancelRequested } = get();
      if (!isStreaming || cancelRequested) return;
      // 不中断 fetch：后端落库部分内容后回发 cancel + done 完成收尾
      set({ cancelRequested: true });
      if (streamTaskId) {
        requestStop(streamTaskId);
      }
    }
  };
});
