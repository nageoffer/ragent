import * as React from "react";
import { Volume2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

interface VoicePlayButtonProps {
  playing: boolean;
  onToggle: () => void;
}

/**
 * 消息语音播放按钮 播放中高亮 点击停止
 */
export function VoicePlayButton({ playing, onToggle }: VoicePlayButtonProps) {
  return (
    <Button
      variant="ghost"
      size="icon"
      type="button"
      onClick={onToggle}
      aria-label={playing ? "停止播放" : "播放语音"}
      className={cn(
        "h-7 w-7 rounded-md hover:bg-[#F5F5F5]",
        playing ? "text-[#1A1A1A]" : "text-[#999999] hover:text-[#666666]"
      )}
    >
      <Volume2 className="h-4 w-4" />
    </Button>
  );
}
