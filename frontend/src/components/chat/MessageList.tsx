import * as React from "react";
import { Virtuoso, type VirtuosoHandle } from "react-virtuoso";

import { MessageItem } from "@/components/chat/MessageItem";
import { cn } from "@/lib/utils";
import type { Message } from "@/types";

interface MessageListProps {
  messages: Message[];
  isLoading: boolean;
  isStreaming: boolean;
  sessionKey?: string | null;
}

export function MessageList({ messages, isLoading, isStreaming, sessionKey }: MessageListProps) {
  const virtuosoRef = React.useRef<VirtuosoHandle | null>(null);
  const lastSessionRef = React.useRef<string | null>(null);
  const pendingScrollRef = React.useRef(true);

  React.useEffect(() => {
    const nextKey = sessionKey ?? "empty";
    if (lastSessionRef.current !== nextKey) {
      lastSessionRef.current = nextKey;
      pendingScrollRef.current = true;
    }
  }, [sessionKey]);

  React.useEffect(() => {
    if (!pendingScrollRef.current || isStreaming || isLoading || messages.length === 0) {
      return;
    }
    const scrollToBottom = () => {
      virtuosoRef.current?.scrollToIndex({
        index: messages.length - 1,
        align: "end",
        behavior: "auto"
      });
    };
    scrollToBottom();
    const timer = window.setTimeout(scrollToBottom, 120);
    pendingScrollRef.current = false;
    return () => window.clearTimeout(timer);
  }, [messages.length, isStreaming, isLoading, sessionKey]);

  const List = React.useMemo(() => {
    const Comp = React.forwardRef<HTMLDivElement, React.HTMLAttributes<HTMLDivElement>>(
      ({ className, ...props }, ref) => (
        <div
          ref={ref}
          className={cn("mx-auto max-w-4xl space-y-8 px-6 pt-10 pb-8 md:px-8", className)}
          {...props}
        />
      )
    );
    Comp.displayName = "MessageList";
    return Comp;
  }, []);

  if (messages.length === 0) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="relative max-w-md rounded-3xl border border-gray-200 bg-white p-8 text-center shadow-sm">
          <div className="absolute -inset-0.5 rounded-3xl bg-gradient-to-r from-indigo-50 to-purple-50 blur-lg" />
          <div className="relative">
            <p className="text-xl font-semibold text-gray-900">开始新的对话</p>
            <p className="mt-3 text-sm text-gray-500">提出问题即可开始检索增强回答。</p>
          </div>
        </div>
      </div>
    );
  }

  return (
    <Virtuoso
      ref={virtuosoRef}
      data={messages}
      followOutput={(atBottom) => (isStreaming && atBottom ? "smooth" : false)}
      className="h-full"
      components={{ List }}
      itemContent={(index, message) => (
        <div className={index === messages.length - 1 ? "animate-fade-up" : ""}>
          <MessageItem message={message} isLast={index === messages.length - 1} />
        </div>
      )}
    />
  );
}
