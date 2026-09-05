import type { ReactNode } from "react";
import { ArrowRight, Info } from "lucide-react";

import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

/**
 * 概览页唯一的卡片外壳。描边负责收边界，投影只压到 1px——
 * 页底 #F7F8FA 与卡面只差一档明度，卡片一"浮"起来，八张卡就成了八块浮块。
 */
export function DashCard({ children, className }: { children: ReactNode; className?: string }) {
  return (
      <section
          className={cn(
              "min-w-0 rounded-[14px] border border-[#EAECF0] bg-white p-5",
              "shadow-[0_1px_2px_rgba(16,24,40,0.025)] transition-colors hover:border-[#D9DDE7]",
              className
          )}
      >
        {children}
      </section>
  );
}

/**
 * 标题旁的口径指针。口径不进正文：一屏并存五段小字谁也不会读，
 * 而删掉它们等于让读者按字面意思猜数字的定义。正文一字不改地收进这里。
 */
export function Hint({ children }: { children: ReactNode }) {
  return (
      <TooltipProvider delayDuration={0}>
        <Tooltip>
          <TooltipTrigger asChild>
            <button
                type="button"
                aria-label="口径说明"
                className="inline-flex shrink-0 text-[#B0B8C4] transition-colors hover:text-[#667085] focus-visible:rounded focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#4F6EF7]"
            >
              <Info className="h-3.5 w-3.5" aria-hidden="true" />
            </button>
          </TooltipTrigger>
          <TooltipContent side="top" className="max-w-[300px]">
            <p className="text-xs font-normal leading-relaxed">{children}</p>
          </TooltipContent>
        </Tooltip>
      </TooltipProvider>
  );
}

/**
 * 卡头右上的详情入口。它开的是本卡的弹框而不是跳页，所以是按钮不是链接；
 * 一张卡只开一道门——表格底下再放一个同去处的入口，读者会以为那是另一个地方。
 */
export function DetailButton({ onClick, label = "查看详情" }: { onClick: () => void; label?: string }) {
  return (
      <button
          type="button"
          onClick={onClick}
          className={cn(
              "inline-flex shrink-0 items-center gap-1 rounded text-xs font-medium",
              "text-[#4F6EF7] transition-colors hover:text-[#405FE8]",
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#4F6EF7]"
          )}
      >
        {label}
        <ArrowRight className="h-3.5 w-3.5" aria-hidden="true" />
      </button>
  );
}

/** 卡头：标题 + 口径 ⓘ 在左，右侧留给分段器或链接。 */
export function CardHead({
                           title,
                           hint,
                           action,
                           className
                         }: {
  title: string;
  hint?: ReactNode;
  action?: ReactNode;
  className?: string;
}) {
  return (
      <div className={cn("flex flex-wrap items-center justify-between gap-x-3 gap-y-1.5", className)}>
        <h3 className="flex items-center gap-1.5 text-base font-semibold leading-6 text-[#101828]">
          {title}
          {hint && <Hint>{hint}</Hint>}
        </h3>
        {action}
      </div>
  );
}
