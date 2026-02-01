import { Brain, Loader2 } from "lucide-react";

interface ThinkingIndicatorProps {
  content?: string;
  duration?: number;
}

export function ThinkingIndicator({ content, duration }: ThinkingIndicatorProps) {
  return (
    <div className="rounded-lg border border-[#BFDBFE] bg-[#DBEAFE] p-4">
      <div className="flex items-center gap-2 text-[#2563EB]">
        <Loader2 className="h-4 w-4 animate-spin" />
        <span className="text-sm font-medium">正在深度思考...</span>
        {duration ? (
          <span className="text-xs text-[#2563EB] bg-[#BFDBFE] px-2 py-0.5 rounded-full">
            {duration}秒
          </span>
        ) : null}
      </div>
      <div className="mt-3 flex items-start gap-2 text-sm text-[#1E40AF]">
        <Brain className="mt-0.5 h-4 w-4 shrink-0 text-[#2563EB]" />
        <p className="whitespace-pre-wrap leading-relaxed">
          {content || ""}
          <span className="ml-1 inline-block h-4 w-1.5 animate-pulse bg-[#3B82F6] align-middle" />
        </p>
      </div>
    </div>
  );
}
