import * as React from "react";
import { Brain, Bot, Send, Square } from "lucide-react";

import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";

export function WelcomeScreen() {
  const [value, setValue] = React.useState("");
  const [isFocused, setIsFocused] = React.useState(false);
  const isComposingRef = React.useRef(false);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const { sendMessage, isStreaming, cancelGeneration, deepThinkingEnabled, setDeepThinkingEnabled } =
    useChatStore();

  const focusInput = React.useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.focus({ preventScroll: true });
  }, []);

  const adjustHeight = React.useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    const next = Math.min(el.scrollHeight, 160);
    el.style.height = `${next}px`;
  }, []);

  React.useEffect(() => {
    adjustHeight();
  }, [value, adjustHeight]);

  const handleSubmit = async () => {
    if (isStreaming) {
      cancelGeneration();
      focusInput();
      return;
    }
    if (!value.trim()) return;
    const next = value;
    setValue("");
    focusInput();
    await sendMessage(next);
    focusInput();
  };

  const hasContent = value.trim().length > 0;

  return (
    <div className="chat-welcome">
      <div className="chat-welcome-inner">
        <div className="chat-welcome-header">
          <span className="chat-welcome-logo">
            <Bot className="chat-welcome-logo-icon" />
          </span>
          <span>忙什么呢？臭宝！</span>
        </div>
        <div className={cn("chat-welcome-input", isFocused && "is-focused")}>
          <textarea
            ref={textareaRef}
            value={value}
            onChange={(event) => setValue(event.target.value)}
            placeholder="给 AI 助手发送消息"
            className="chat-welcome-textarea"
            rows={1}
            onFocus={() => setIsFocused(true)}
            onBlur={() => setIsFocused(false)}
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
                handleSubmit();
              }
            }}
            aria-label="发送消息"
          />
          <div className="chat-welcome-toolbar">
            <div className="chat-welcome-tags">
              <button
                type="button"
                onClick={() => setDeepThinkingEnabled(!deepThinkingEnabled)}
                aria-pressed={deepThinkingEnabled}
                disabled={isStreaming}
                className={cn("chat-welcome-tag", deepThinkingEnabled && "is-active")}
              >
                <Brain className="h-4 w-4" />
                深度思考
              </button>
            </div>
            <button
              type="button"
              onClick={handleSubmit}
              disabled={!hasContent && !isStreaming}
              className={cn("chat-welcome-send", isStreaming && "is-cancel")}
              aria-label={isStreaming ? "停止生成" : "发送"}
            >
              {isStreaming ? <Square className="h-4 w-4" /> : <Send className="h-4 w-4" />}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
