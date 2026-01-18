import { Copy, ThumbsDown, ThumbsUp } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";
import type { FeedbackValue } from "@/types";

interface FeedbackButtonsProps {
  messageId: string;
  feedback: FeedbackValue;
  content: string;
  className?: string;
}

export function FeedbackButtons({ messageId, feedback, content, className }: FeedbackButtonsProps) {
  const submitFeedback = useChatStore((state) => state.submitFeedback);

  const handleFeedback = (value: FeedbackValue) => {
    const next = feedback === value ? null : value;
    submitFeedback(messageId, next).catch(() => null);
  };

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(content);
      toast.success("复制成功");
    } catch {
      toast.error("复制失败");
    }
  };

  return (
    <div
      className={cn(
        "mt-3 flex items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100",
        className
      )}
    >
      <Button
        variant="ghost"
        size="icon"
        onClick={() => handleFeedback("like")}
        aria-label="点赞"
        className={cn(
          "h-8 w-8 text-gray-400 hover:text-emerald-600 hover:bg-gray-100",
          feedback === "like" && "text-emerald-600"
        )}
      >
        <ThumbsUp className="h-4 w-4" />
      </Button>
      <Button
        variant="ghost"
        size="icon"
        onClick={() => handleFeedback("dislike")}
        aria-label="点踩"
        className={cn(
          "h-8 w-8 text-gray-400 hover:text-rose-600 hover:bg-gray-100",
          feedback === "dislike" && "text-rose-600"
        )}
      >
        <ThumbsDown className="h-4 w-4" />
      </Button>
      <Button
        variant="ghost"
        size="icon"
        onClick={handleCopy}
        aria-label="复制内容"
        className="h-8 w-8 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
      >
        <Copy className="h-4 w-4" />
      </Button>
    </div>
  );
}
