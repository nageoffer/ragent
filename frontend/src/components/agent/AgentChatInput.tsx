import * as React from "react";

import { ArrowUp, Loader2, Square } from "lucide-react";

import { awaitingConfirm, useAgentChatStore } from "@/stores/agentChatStore";

// 无深度思考开关：Agent 自主规划是否思考
export function AgentChatInput() {
  const [value, setValue] = React.useState("");
  const isComposingRef = React.useRef(false);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const {
    sendMessage,
    isStreaming,
    cancelRequested,
    cancelGeneration,
    inputFocusKey,
    draft,
    messages
  } = useAgentChatStore();
  // 挂起在写操作确认上时输入条让位：这会儿只有卡片上那两枚钮是有效的下一步
  const awaiting = awaitingConfirm(messages);

  const focusInput = React.useCallback(() => {
    textareaRef.current?.focus({ preventScroll: true });
  }, []);

  const adjustHeight = React.useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  }, []);

  React.useEffect(() => {
    adjustHeight();
  }, [value, adjustHeight]);

  React.useEffect(() => {
    if (!inputFocusKey) return;
    focusInput();
  }, [inputFocusKey, focusInput]);

  // 空态示例问题：预填并聚焦 不直接发送
  React.useEffect(() => {
    if (!draft) return;
    setValue(draft.text);
    focusInput();
  }, [draft, focusInput]);

  const submit = async () => {
    if (!value.trim() || isStreaming || awaiting) return;
    const next = value;
    setValue("");
    focusInput();
    await sendMessage(next);
    focusInput();
  };

  return (
    <div className="agent-composer">
      {/* 框内一行：文字弹性 钮贴底不跟着长 排布见 globals.css */}
      {/* 键位提示不常驻：Enter 发送试一次就会 不值框里一行 想找的人在钮上悬停能看到 */}
      <div className="agent-composer-box">
        <textarea
          ref={textareaRef}
          className="agent-composer-input"
          value={value}
          rows={1}
          placeholder={awaiting ? "请先在上方确认或取消该操作" : "给 RagentAI 发送消息"}
          onChange={(event) => setValue(event.target.value)}
          onCompositionStart={() => {
            isComposingRef.current = true;
          }}
          onCompositionEnd={() => {
            isComposingRef.current = false;
          }}
          onKeyDown={(event) => {
            if (event.key === "Enter" && !event.shiftKey) {
              const nativeEvent = event.nativeEvent as KeyboardEvent;
              if (nativeEvent.isComposing || isComposingRef.current || nativeEvent.keyCode === 229) {
                return;
              }
              event.preventDefault();
              void submit();
            }
          }}
          aria-label="消息"
        />
        {/* 两态共用一枚圆钮：图标换 位置与尺寸不动 换态时框内不跳
            钮上只有图形 标签只剩无障碍名 title 顺带带出键位提示（只在悬停时占位） */}
        {isStreaming ? (
          <button
            type="button"
            className="agent-composer-btn"
            data-stop="true"
            data-stopping={cancelRequested ? "true" : undefined}
            aria-label={cancelRequested ? "正在停止" : "停止生成"}
            aria-busy={cancelRequested}
            title={cancelRequested ? "正在停止…" : "停止生成"}
            disabled={cancelRequested}
            onClick={() => {
              cancelGeneration();
              focusInput();
            }}
          >
            {cancelRequested ? (
              <Loader2 className="h-4 w-4 motion-safe:animate-spin" />
            ) : (
              <Square className="h-3 w-3" fill="currentColor" strokeWidth={0} />
            )}
          </button>
        ) : (
          <button
            type="button"
            className="agent-composer-btn"
            aria-label="发送"
            title="发送（Enter）"
            onClick={() => void submit()}
            disabled={value.trim() === "" || awaiting}
          >
            <ArrowUp className="h-4 w-4" strokeWidth={2.5} />
          </button>
        )}
      </div>
      {/* 免责一行在框外：框里不摆第二行是因为没有真控件 这句有真职责 */}
      <p className="agent-composer-note" aria-live="polite">
        {cancelRequested ? "正在停止并保存已生成内容…" : "内容由 AI 生成，请仔细甄别"}
      </p>
    </div>
  );
}
