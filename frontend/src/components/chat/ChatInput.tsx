import * as React from "react";
import { Brain, Lightbulb, Send, Square } from "lucide-react";

import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";

export function ChatInput() {
  const [value, setValue] = React.useState("");
  const [isFocused, setIsFocused] = React.useState(false);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const { sendMessage, isStreaming, cancelGeneration, deepThinkingEnabled, setDeepThinkingEnabled } =
    useChatStore();

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
      return;
    }
    if (!value.trim()) return;
    const next = value;
    setValue("");
    await sendMessage(next);
  };

  const hasContent = value.trim().length > 0;

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={() => setDeepThinkingEnabled(!deepThinkingEnabled)}
          disabled={isStreaming}
          aria-pressed={deepThinkingEnabled}
          className={cn(
            "relative flex items-center gap-2 rounded-xl border px-4 py-2 text-sm font-medium transition-all",
            deepThinkingEnabled
              ? "border-amber-200 bg-amber-50 text-amber-600 shadow-sm"
              : "border-transparent bg-gray-50 text-gray-500 hover:bg-gray-100",
            isStreaming && "cursor-not-allowed opacity-60"
          )}
        >
          <Brain className={cn("h-4 w-4", deepThinkingEnabled && "text-amber-500")} />
          <span>深度思考</span>
          {deepThinkingEnabled ? (
            <span className="h-2 w-2 rounded-full bg-amber-500 animate-pulse" />
          ) : null}
        </button>
      </div>
      <div
        className={cn(
          "relative flex items-end gap-3 rounded-2xl border-2 bg-white p-4 transition-all duration-300",
          isFocused
            ? "border-indigo-500 shadow-lg shadow-indigo-500/10"
            : "border-gray-200 hover:border-gray-300"
        )}
      >
        <Textarea
          ref={textareaRef}
          value={value}
          onChange={(event) => setValue(event.target.value)}
          placeholder={deepThinkingEnabled ? "输入需要深度分析的问题..." : "输入你的问题..."}
          className="max-h-40 min-h-[44px] flex-1 resize-none border-0 bg-transparent px-2 py-2 text-[15px] text-gray-700 shadow-none placeholder:text-gray-400 focus-visible:ring-0"
          rows={1}
          disabled={isStreaming}
          onFocus={() => setIsFocused(true)}
          onBlur={() => setIsFocused(false)}
          onKeyDown={(event) => {
            if (event.key === "Enter" && !event.shiftKey) {
              event.preventDefault();
              handleSubmit();
            }
          }}
          aria-label="聊天输入框"
        />
        <button
          type="button"
          onClick={handleSubmit}
          disabled={!hasContent && !isStreaming}
          aria-label={isStreaming ? "停止生成" : "发送消息"}
          className={cn(
            "rounded-xl p-3 transition-all duration-300",
            isStreaming
              ? "bg-rose-50 text-rose-500 hover:bg-rose-100"
              : hasContent
                ? "bg-gradient-to-r from-indigo-500 to-purple-500 text-white shadow-md shadow-indigo-500/25 hover:shadow-lg hover:scale-105"
                : "cursor-not-allowed bg-gray-100 text-gray-400"
          )}
        >
          {isStreaming ? <Square className="h-5 w-5" /> : <Send className="h-5 w-5" />}
        </button>
      </div>
      {deepThinkingEnabled ? (
        <p className="text-xs text-amber-600">
          <span className="inline-flex items-center gap-1.5">
            <Lightbulb className="h-3.5 w-3.5" />
            深度思考模式已开启，AI将进行更深入的分析推理
          </span>
        </p>
      ) : null}
      <p className="text-center text-xs text-gray-400">
        <kbd className="rounded bg-gray-100 px-1.5 py-0.5 text-gray-500">Enter</kbd> 发送
        <span className="px-1.5">·</span>
        <kbd className="rounded bg-gray-100 px-1.5 py-0.5 text-gray-500">
          Shift + Enter
        </kbd>{" "}
        换行
        {isStreaming ? <span className="ml-2 animate-pulse-soft">生成中...</span> : null}
      </p>
    </div>
  );
}
