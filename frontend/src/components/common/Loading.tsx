import { Loader2 } from "lucide-react";

import { cn } from "@/lib/utils";

interface LoadingProps {
  label?: string;
  className?: string;
}

export function Loading({ label = "加载中...", className }: LoadingProps) {
  return (
    <div className={cn("flex items-center gap-2 text-muted-foreground", className)}>
      <Loader2 className="h-4 w-4 animate-spin" />
      <span className="text-sm">{label}</span>
    </div>
  );
}
