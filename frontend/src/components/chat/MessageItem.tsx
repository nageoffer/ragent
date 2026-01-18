import * as React from "react";
import { Brain, ChevronDown, Sparkles, User } from "lucide-react";

import { FeedbackButtons } from "@/components/chat/FeedbackButtons";
import { MarkdownRenderer } from "@/components/chat/MarkdownRenderer";
import { ThinkingIndicator } from "@/components/chat/ThinkingIndicator";
import { cn } from "@/lib/utils";
import type { Message } from "@/types";

interface MessageItemProps {
  message: Message;
}

export const MessageItem = React.memo(function MessageItem({ message }: MessageItemProps) {
  const isUser = message.role === "user";
  const showFeedback =
    message.role === "assistant" &&
    message.status !== "streaming" &&
    message.id &&
    !message.id.startsWith("assistant-");
  const [thinkingExpanded, setThinkingExpanded] = React.useState(false);
  const hasThinking = Boolean(message.thinking && message.thinking.trim().length > 0);

  if (isUser) {
    return (
      <div className="flex justify-end">
        <div className="flex max-w-[80%] items-start gap-3">
          <div className="rounded-2xl rounded-tr-md bg-gradient-to-r from-indigo-500 to-purple-500 px-5 py-3.5 shadow-md shadow-indigo-500/20">
            <p className="whitespace-pre-wrap text-[15px] leading-relaxed text-white">
              {message.content}
            </p>
          </div>
          <div className="flex h-9 w-9 items-center justify-center rounded-full bg-gradient-to-br from-indigo-500 to-purple-500 text-white shadow-md">
            <User className="h-4 w-4" />
          </div>
        </div>
      </div>
    );
  }

  const thinkingDuration = message.thinkingDuration ? `${message.thinkingDuration}秒` : "";
  const isThinking = Boolean(message.isThinking);

  return (
    <div className="group flex gap-4">
      <div className="relative mt-1 h-9 w-9 shrink-0">
        <div
          className={cn(
            "relative flex h-9 w-9 items-center justify-center rounded-full text-white",
            isThinking
              ? "bg-gradient-to-br from-amber-400 to-orange-500"
              : "bg-gradient-to-br from-indigo-500 to-purple-500"
          )}
        >
          {isThinking ? <Brain className="h-4 w-4 animate-pulse" /> : <Sparkles className="h-4 w-4" />}
        </div>
      </div>
      <div className="min-w-0 flex-1 space-y-4">
        {isThinking ? (
          <ThinkingIndicator content={message.thinking} duration={message.thinkingDuration} />
        ) : null}
        {!isThinking && hasThinking ? (
          <div className="overflow-hidden rounded-xl border border-amber-200 bg-amber-50">
            <button
              type="button"
              onClick={() => setThinkingExpanded((prev) => !prev)}
              className="flex w-full items-center gap-2 px-4 py-3 text-left transition hover:bg-amber-100/50"
            >
              <div className="flex flex-1 items-center gap-2">
                <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-amber-100">
                  <Brain className="h-4 w-4 text-amber-600" />
                </div>
                <span className="text-sm font-medium text-amber-700">深度思考</span>
                {thinkingDuration ? (
                  <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs text-amber-500">
                    {thinkingDuration}
                  </span>
                ) : null}
              </div>
              <ChevronDown
                className={cn(
                  "h-4 w-4 text-amber-500 transition-transform",
                  thinkingExpanded && "rotate-180"
                )}
              />
            </button>
            {thinkingExpanded ? (
              <div className="border-t border-amber-200/60 px-4 pb-4">
                <div className="mt-3 whitespace-pre-wrap text-sm leading-relaxed text-amber-800/80">
                  {message.thinking}
                </div>
              </div>
            ) : null}
          </div>
        ) : null}
        <MarkdownRenderer content={message.content || " "} />
        {message.status === "streaming" && !isThinking ? (
          <span className="dot-flash ml-1 text-gray-400" />
        ) : null}
        {message.status === "error" ? (
          <p className="text-xs text-rose-500">生成已中断。</p>
        ) : null}
        {showFeedback ? (
          <FeedbackButtons
            messageId={message.id}
            feedback={message.feedback ?? null}
            content={message.content}
          />
        ) : null}
      </div>
    </div>
  );
});
