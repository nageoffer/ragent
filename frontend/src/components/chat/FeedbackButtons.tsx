import * as React from "react";
import { ChevronDown, Copy, ThumbsDown, ThumbsUp } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { ThumbDownFilledIcon, ThumbUpFilledIcon } from "@/components/chat/ThumbIcons";
import { VoicePlayButton } from "@/components/chat/VoicePlayButton";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger
} from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";
import { markdownToPlainText } from "@/lib/markdownToText";
import { useChatStore } from "@/stores/chatStore";
import type { FeedbackValue } from "@/types";

interface FeedbackButtonsProps {
  messageId: string;
  feedback: FeedbackValue;
  content: string;
  playing?: boolean;
  onTogglePlay?: () => void;
  className?: string;
  alwaysVisible?: boolean;
}

const HOLD_AFTER_INTERACTION_MS = 3000;
// 悬停离开后延迟关闭复制菜单 留出移动到菜单的余量
const COPY_MENU_CLOSE_DELAY_MS = 130;

export function FeedbackButtons({
  messageId,
  feedback,
  content,
  playing,
  onTogglePlay,
  className,
  alwaysVisible
}: FeedbackButtonsProps) {
  const submitFeedback = useChatStore((state) => state.submitFeedback);
  const [held, setHeld] = React.useState(false);
  const [copyOpen, setCopyOpen] = React.useState(false);
  const hideTimerRef = React.useRef<number | null>(null);
  const copyTimerRef = React.useRef<number | null>(null);

  const cancelHideTimer = React.useCallback(() => {
    if (hideTimerRef.current) {
      window.clearTimeout(hideTimerRef.current);
      hideTimerRef.current = null;
    }
  }, []);

  const cancelCopyTimer = React.useCallback(() => {
    if (copyTimerRef.current) {
      window.clearTimeout(copyTimerRef.current);
      copyTimerRef.current = null;
    }
  }, []);

  React.useEffect(
    () => () => {
      cancelHideTimer();
      cancelCopyTimer();
    },
    [cancelHideTimer, cancelCopyTimer]
  );

  const markInteracted = React.useCallback(() => {
    cancelHideTimer();
    setHeld(true);
  }, [cancelHideTimer]);

  const handleMouseEnter = React.useCallback(() => {
    if (held) cancelHideTimer();
  }, [held, cancelHideTimer]);

  const handleMouseLeave = React.useCallback(() => {
    if (!held) return;
    cancelHideTimer();
    hideTimerRef.current = window.setTimeout(() => {
      hideTimerRef.current = null;
      setHeld(false);
    }, HOLD_AFTER_INTERACTION_MS);
  }, [held, cancelHideTimer]);

  // 悬停打开复制菜单 移入触发区或菜单即保持打开
  const openCopyMenu = React.useCallback(() => {
    cancelCopyTimer();
    setCopyOpen(true);
  }, [cancelCopyTimer]);

  const scheduleCloseCopyMenu = React.useCallback(() => {
    cancelCopyTimer();
    copyTimerRef.current = window.setTimeout(() => {
      copyTimerRef.current = null;
      setCopyOpen(false);
    }, COPY_MENU_CLOSE_DELAY_MS);
  }, [cancelCopyTimer]);

  const handleFeedback = (value: FeedbackValue) => {
    markInteracted();
    const next = feedback === value ? null : value;
    submitFeedback(messageId, next).catch(() => null);
  };

  const handleCopy = async (mode: "text" | "markdown") => {
    markInteracted();
    cancelCopyTimer();
    setCopyOpen(false);
    const value = mode === "markdown" ? content : markdownToPlainText(content);
    try {
      await navigator.clipboard.writeText(value);
      toast.success(mode === "markdown" ? "已复制 Markdown" : "复制成功");
    } catch {
      toast.error("复制失败");
    }
  };

  return (
    <div
      onMouseEnter={handleMouseEnter}
      onMouseLeave={handleMouseLeave}
      className={cn(
        "flex items-center gap-1",
        alwaysVisible || held
          ? "opacity-100"
          : "pointer-events-none opacity-0 group-hover:pointer-events-auto group-hover:opacity-100",
        className
      )}
    >
      {/* 复制：图标 + 下拉箭头一体 悬停整块高亮 仅悬停箭头才向上展开菜单 */}
      <div className="group/copy flex items-center rounded-md transition-colors hover:bg-[#F5F5F5]">
        <button
          type="button"
          onClick={() => handleCopy("text")}
          aria-label="复制内容"
          className="flex h-7 items-center rounded-l-md pl-2 pr-0.5 text-[#999999] outline-none transition-colors group-hover/copy:text-[#666666]"
        >
          <Copy className="h-4 w-4" />
        </button>
        <DropdownMenu open={copyOpen} onOpenChange={setCopyOpen} modal={false}>
          <DropdownMenuTrigger
            aria-label="复制选项"
            onMouseEnter={openCopyMenu}
            onMouseLeave={scheduleCloseCopyMenu}
            className="flex h-7 items-center rounded-r-md pl-0 pr-1.5 text-[#999999] outline-none transition-colors group-hover/copy:text-[#666666]"
          >
            <ChevronDown
              className={cn("h-3 w-3 transition-transform duration-200", copyOpen && "rotate-180")}
            />
          </DropdownMenuTrigger>
          <DropdownMenuContent
            side="bottom"
            align="start"
            sideOffset={6}
            onMouseEnter={openCopyMenu}
            onMouseLeave={scheduleCloseCopyMenu}
            onCloseAutoFocus={(event) => event.preventDefault()}
            className="min-w-[9rem]"
          >
            <DropdownMenuItem
              onSelect={() => handleCopy("markdown")}
              className="focus:bg-[#F5F5F5] focus:text-[#1A1A1A]"
            >
              复制为 Markdown
            </DropdownMenuItem>
            <DropdownMenuItem
              onSelect={() => handleCopy("text")}
              className="focus:bg-[#F5F5F5] focus:text-[#1A1A1A]"
            >
              复制
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
      {onTogglePlay ? <VoicePlayButton playing={Boolean(playing)} onToggle={onTogglePlay} /> : null}
      <Button
        variant="ghost"
        size="icon"
        onClick={() => handleFeedback("like")}
        aria-label="点赞"
        className={cn(
          "h-7 w-7 rounded-md hover:bg-[#F5F5F5]",
          feedback === "like" ? "text-[#1A1A1A]" : "text-[#999999] hover:text-[#666666]"
        )}
      >
        {feedback === "like" ? (
          <ThumbUpFilledIcon className="h-4 w-4" />
        ) : (
          <ThumbsUp className="h-4 w-4" />
        )}
      </Button>
      <Button
        variant="ghost"
        size="icon"
        onClick={() => handleFeedback("dislike")}
        aria-label="点踩"
        className={cn(
          "h-7 w-7 rounded-md hover:bg-[#F5F5F5]",
          feedback === "dislike" ? "text-[#1A1A1A]" : "text-[#999999] hover:text-[#666666]"
        )}
      >
        {feedback === "dislike" ? (
          <ThumbDownFilledIcon className="h-4 w-4" />
        ) : (
          <ThumbsDown className="h-4 w-4" />
        )}
      </Button>
    </div>
  );
}
