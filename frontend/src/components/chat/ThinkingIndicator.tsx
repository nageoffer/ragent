import { Brain, Loader2 } from "lucide-react";

interface ThinkingIndicatorProps {
  content?: string;
  duration?: number;
}

export function ThinkingIndicator({ content, duration }: ThinkingIndicatorProps) {
  return (
    <div className="rounded-xl border border-amber-200 bg-amber-50 p-4">
      <div className="flex items-center gap-2 text-amber-700">
        <Loader2 className="h-4 w-4 animate-spin" />
        <span className="text-sm font-medium">正在深度思考...</span>
        {duration ? (
          <span className="text-xs text-amber-500 bg-amber-100 px-2 py-0.5 rounded-full">
            {duration}秒
          </span>
        ) : null}
      </div>
      <div className="mt-3 flex items-start gap-2 text-sm text-amber-800/70">
        <Brain className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" />
        <p className="whitespace-pre-wrap leading-relaxed">
          {content || ""}
          <span className="ml-1 inline-block h-4 w-1.5 animate-pulse bg-amber-500 align-middle" />
        </p>
      </div>
    </div>
  );
}
