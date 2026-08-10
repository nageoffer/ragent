import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ChatInput } from "@/components/chat/ChatInput";

const { sendMessageMock, setDeepThinkingEnabledMock } = vi.hoisted(() => ({
  sendMessageMock: vi.fn(),
  setDeepThinkingEnabledMock: vi.fn()
}));

vi.mock("@/stores/chatStore", () => ({
  useChatStore: () => ({
    sendMessage: sendMessageMock,
    isStreaming: false,
    cancelGeneration: vi.fn(),
    deepThinkingEnabled: false,
    setDeepThinkingEnabled: setDeepThinkingEnabledMock,
    inputFocusKey: 0
  })
}));

beforeEach(() => {
  vi.clearAllMocks();
});

describe("ChatInput", () => {
  it("输入内容后点击发送触发提交回调并清空输入框", async () => {
    const user = userEvent.setup();
    render(<ChatInput />);

    const textarea = screen.getByLabelText("聊天输入框");
    await user.type(textarea, "你好，RAG 平台");

    await user.click(screen.getByRole("button", { name: "发送消息" }));

    expect(sendMessageMock).toHaveBeenCalledTimes(1);
    expect(sendMessageMock).toHaveBeenCalledWith("你好，RAG 平台");
    expect(textarea).toHaveValue("");
  });

  it("Enter 键提交", async () => {
    const user = userEvent.setup();
    render(<ChatInput />);

    await user.type(screen.getByLabelText("聊天输入框"), "问题一{enter}");

    expect(sendMessageMock).toHaveBeenCalledTimes(1);
    expect(sendMessageMock).toHaveBeenCalledWith("问题一");
  });

  it("空输入不触发提交", async () => {
    const user = userEvent.setup();
    render(<ChatInput />);

    await user.click(screen.getByRole("button", { name: "发送消息" }));

    expect(sendMessageMock).not.toHaveBeenCalled();
  });

  it("Shift + Enter 换行而非提交", async () => {
    const user = userEvent.setup();
    render(<ChatInput />);

    const textarea = screen.getByLabelText("聊天输入框");
    await user.type(textarea, "第一行");
    await user.keyboard("{Shift>}{Enter}{/Shift}");

    expect(sendMessageMock).not.toHaveBeenCalled();
    expect(textarea).toHaveValue("第一行\n");
  });
});
