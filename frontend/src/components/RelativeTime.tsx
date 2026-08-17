import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { formatRelativeTime, formatTooltipTime } from "@/utils/time";
import { cn } from "@/lib/utils";

interface RelativeTimeProps {
  value?: string | null;
  updatedBy?: string | null;
  /** 覆盖默认字号，供嵌进胶囊等更小的容器 */
  className?: string;
}

export function RelativeTime({ value, updatedBy, className }: RelativeTimeProps) {
  if (!value) return <span className="text-muted-foreground/35">-</span>;

  const display = updatedBy
    ? `${updatedBy} · ${formatRelativeTime(value)}`
    : formatRelativeTime(value);

  return (
    <TooltipProvider delayDuration={300}>
      <Tooltip>
        <TooltipTrigger asChild>
          <span className={cn("cursor-default truncate text-sm tabular-nums", className)}>
            {display}
          </span>
        </TooltipTrigger>
        <TooltipContent>
          <p>{formatTooltipTime(value)}</p>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}
