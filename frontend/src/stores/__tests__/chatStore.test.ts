import { beforeEach, describe, expect, it, vi } from "vitest";

import { useChatStore } from "@/stores/chatStore";
import { createStreamResponse } from "@/hooks/useStreamResponse";
import { stopTask } from "@/services/chatService";
import { storage } from "@/utils/storage";
import { toast } from "sonner";

vi.mock("@/services/sessionService", () => ({
  listSessions: vi.fn(),
  listMessages: vi.fn(),
  deleteSession: vi.fn(),
  renameSession: vi.fn()
}));

vi.mock("@/services/chatService", () => ({
  stopTask: vi.fn(),
  submitFeedback: vi.fn()
}));

vi.mock("@/hooks/useStreamResponse", () => ({
  createStreamResponse: vi.fn()
}));

vi.mock("@/utils/storage", () => ({
  storage: { getToken: vi.fn() }
}));

vi.mock("sonner", () => ({
  toast: { error: vi.fn(), success: vi.fn() }
}));

const createStreamResponseMock = vi.mocked(createStreamResponse);
const stopTaskMock = vi.mocked(stopTask);
const storageMock = vi.mocked(storage);

let startMock: ReturnType<typeof vi.fn>;
let capturedHandlers: Parameters<typeof createStreamResponse>[1] | undefined;

function mockStream() {
  startMock = vi.fn();
  capturedHandlers = undefined;
  createStreamResponseMock.mockImplementation((_options, handlers) => {
    capturedHandlers = handlers;
    return { start: startMock, cancel: vi.fn() };
  });
}

function resetStore() {
  useChatStore.setState({
    sessions: [],
    currentSessionId: null,
    messages: [],
    isLoading: false,
    sessionsLoaded: false,
    inputFocusKey: 0,
    isStreaming: false,
    isCreatingNew: false,
    deepThinkingEnabled: false,
    thinkingStartAt: null,
    streamTaskId: null,
    streamAbort: null,
    streamingMessageId: null,
    cancelRequested: false
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  mockStream();
  resetStore();
  storageMock.getToken.mockReturnValue(null);
  stopTaskMock.mockResolvedValue(undefined);
});

describe("chatStore.sendMessage", () => {
  it("追加 user + assistant 消息并置 isStreaming", async () => {
    let resolveStart: () => void = () => {};
    startMock.mockReturnValue(
      new Promise<void>((resolve) => {
        resolveStart = resolve;
      })
    );

    const pending = useChatStore.getState().sendMessage("你好");

    const state = useChatStore.getState();
    expect(state.messages).toHaveLength(2);
    expect(state.messages[0]).toMatchObject({
      role: "user",
      content: "你好",
      status: "done"
    });
    expect(state.messages[1]).toMatchObject({
      role: "assistant",
      content: "",
      status: "streaming"
    });
    expect(state.isStreaming).toBe(true);
    expect(state.streamingMessageId).toBe(state.messages[1].id);
    expect(createStreamResponseMock).toHaveBeenCalledTimes(1);
    expect(createStreamResponseMock.mock.calls[0][0].url).toContain("/rag/v3/chat");

    resolveStart();
    await pending;

    const after = useChatStore.getState();
    expect(after.isStreaming).toBe(false);
    expect(after.streamingMessageId).toBeNull();
  });

  it("流失败后消息置 error 且清理流状态", async () => {
    startMock.mockRejectedValue(new Error("网络错误"));

    await useChatStore.getState().sendMessage("你好");

    const state = useChatStore.getState();
    expect(state.messages[1].status).toBe("error");
    expect(state.isStreaming).toBe(false);
    expect(state.streamingMessageId).toBeNull();
    expect(toast.error).toHaveBeenCalledWith("网络错误");
  });

  it("onError 后消息 status=error 且 isStreaming=false", async () => {
    startMock.mockResolvedValue(undefined);

    const pending = useChatStore.getState().sendMessage("你好");
    capturedHandlers?.onError?.(new Error("生成中断"));

    await pending;

    const state = useChatStore.getState();
    expect(state.messages[1].status).toBe("error");
    expect(state.isStreaming).toBe(false);
  });

  it("onDone 清理流状态", async () => {
    let resolveStart: () => void = () => {};
    startMock.mockReturnValue(
      new Promise<void>((resolve) => {
        resolveStart = resolve;
      })
    );

    const pending = useChatStore.getState().sendMessage("你好");
    capturedHandlers?.onDone?.();

    const afterDone = useChatStore.getState();
    expect(afterDone.isStreaming).toBe(false);
    expect(afterDone.streamingMessageId).toBeNull();
    expect(afterDone.cancelRequested).toBe(false);

    resolveStart();
    await pending;
  });
});

describe("chatStore.cancelGeneration", () => {
  it("置 cancelRequested 并通知后端停止任务", () => {
    useChatStore.setState({
      isStreaming: true,
      streamTaskId: "task-1",
      cancelRequested: false
    });

    useChatStore.getState().cancelGeneration();

    const state = useChatStore.getState();
    expect(state.cancelRequested).toBe(true);
    expect(stopTaskMock).toHaveBeenCalledWith("task-1");
  });

  it("非流式状态下不产生副作用", () => {
    useChatStore.getState().cancelGeneration();
    expect(stopTaskMock).not.toHaveBeenCalled();
  });
});
