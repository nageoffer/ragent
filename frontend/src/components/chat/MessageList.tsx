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
  const scrollerRef = React.useRef<HTMLElement | null>(null);
  const lastSessionRef = React.useRef<string | null>(null);
  const pendingScrollRef = React.useRef(true);
  const settleTimerRef = React.useRef<number | null>(null);
  const heightScrollRafRef = React.useRef<number | null>(null);
  const prevStreamingRef = React.useRef(false);
  const initialTopMostItemIndex = React.useMemo(
    () => ({ index: "LAST" as const, align: "end" as const }),
    []
  );
  const lastMessage = messages[messages.length - 1];
  const streamingContentKey = `${lastMessage?.id ?? ""}:${lastMessage?.content?.length ?? 0}:${lastMessage?.thinking?.length ?? 0}:${lastMessage?.status ?? ""}`;

  const scrollToBottom = React.useCallback(() => {
    virtuosoRef.current?.scrollToIndex({ index: "LAST", align: "end", behavior: "auto" });
    const scroller = scrollerRef.current;
    if (scroller) {
      scroller.scrollTop = scroller.scrollHeight;
    }
  }, []);

  React.useEffect(() => {
    const nextKey = sessionKey ?? "empty";
    if (lastSessionRef.current !== nextKey) {
      lastSessionRef.current = nextKey;
      pendingScrollRef.current = true;
      if (settleTimerRef.current) {
        window.clearTimeout(settleTimerRef.current);
        settleTimerRef.current = null;
      }
    }
  }, [sessionKey]);

  React.useEffect(() => {
    const wasStreaming = prevStreamingRef.current;
    prevStreamingRef.current = isStreaming;
    if (!wasStreaming && isStreaming) {
      scrollToBottom();
      const timer = window.setTimeout(scrollToBottom, 80);
      const lateTimer = window.setTimeout(scrollToBottom, 260);
      return () => {
        window.clearTimeout(timer);
        window.clearTimeout(lateTimer);
      };
    }
    return;
  }, [isStreaming, scrollToBottom]);

  React.useLayoutEffect(() => {
    if (!isStreaming || messages.length === 0) {
      return;
    }
    const rafId = window.requestAnimationFrame(() => {
      scrollToBottom();
    });
    return () => window.cancelAnimationFrame(rafId);
  }, [isStreaming, messages.length, streamingContentKey, scrollToBottom]);

  React.useLayoutEffect(() => {
    if (!pendingScrollRef.current || isStreaming || isLoading || messages.length === 0) {
      return;
    }
    let attempts = 0;
    let rafId = 0;
    let active = true;
    const run = () => {
      scrollToBottom();
      attempts += 1;
      if (attempts < 3) {
        rafId = window.requestAnimationFrame(run);
      }
    };
    run();
    const timer = window.setTimeout(scrollToBottom, 240);
    const lateTimer = window.setTimeout(scrollToBottom, 900);
    const handleLoad = () => {
      if (active) {
        scrollToBottom();
      }
    };
    if (document.readyState === "complete") {
      handleLoad();
    } else {
      window.addEventListener("load", handleLoad, { once: true });
    }
    if (document.fonts?.ready) {
      document.fonts.ready.then(() => {
      if (active) {
        scrollToBottom();
      }
    });
  }
    if (settleTimerRef.current) {
      window.clearTimeout(settleTimerRef.current);
    }
    settleTimerRef.current = window.setTimeout(() => {
      pendingScrollRef.current = false;
      settleTimerRef.current = null;
    }, 1500);
    return () => {
      active = false;
      window.cancelAnimationFrame(rafId);
      window.clearTimeout(timer);
      window.clearTimeout(lateTimer);
      if (settleTimerRef.current) {
        window.clearTimeout(settleTimerRef.current);
        settleTimerRef.current = null;
      }
      window.removeEventListener("load", handleLoad);
    };
  }, [messages.length, isStreaming, isLoading, sessionKey]);

  React.useEffect(() => {
    return () => {
      if (heightScrollRafRef.current) {
        window.cancelAnimationFrame(heightScrollRafRef.current);
        heightScrollRafRef.current = null;
      }
      if (settleTimerRef.current) {
        window.clearTimeout(settleTimerRef.current);
        settleTimerRef.current = null;
      }
    };
  }, []);

  const handleTotalListHeightChanged = React.useCallback(() => {
    if (!pendingScrollRef.current || isStreaming || isLoading) {
      return;
    }
    if (heightScrollRafRef.current) {
      return;
    }
    heightScrollRafRef.current = window.requestAnimationFrame(() => {
      heightScrollRafRef.current = null;
      scrollToBottom();
    });
  }, [isStreaming, isLoading, scrollToBottom]);

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
    if (isLoading) {
      return <div className="h-full" />;
    }
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
      key={sessionKey ?? "empty"}
      ref={virtuosoRef}
      data={messages}
      initialTopMostItemIndex={initialTopMostItemIndex}
      followOutput={(atBottom) => {
        if (isStreaming) return "smooth";
        return atBottom ? "auto" : false;
      }}
      scrollerRef={(node) => {
        scrollerRef.current = node as HTMLElement | null;
      }}
      totalListHeightChanged={handleTotalListHeightChanged}
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
