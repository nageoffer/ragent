import * as React from "react";
import { File, FileText, Globe, Image as ImageIcon, Presentation, Sheet } from "lucide-react";

import { fileExt, isExternal } from "@/lib/source";
import { cn } from "@/lib/utils";
import type { SourceRef } from "@/types";

const IMAGE_EXTS = ["png", "jpg", "jpeg", "svg", "gif", "webp", "bmp"];

// favicon 取来源站点根目录的 /favicon.ico（浏览器标签页上的站点图标）失败回退地球
function faviconUrl(url?: string | null): string | null {
  if (!url) return null;
  try {
    return `${new URL(url).origin}/favicon.ico`;
  } catch {
    return null;
  }
}

// 本地文件按扩展名选类型图标与配色
function fileGlyph(ext: string): { Icon: typeof File; color: string } {
  if (ext === "pdf") return { Icon: FileText, color: "text-[#E5484D]" };
  if (ext === "xlsx" || ext === "xls" || ext === "csv") return { Icon: Sheet, color: "text-[#12A150]" };
  if (ext === "doc" || ext === "docx") return { Icon: FileText, color: "text-[#2563EB]" };
  if (ext === "ppt" || ext === "pptx") return { Icon: Presentation, color: "text-[#EA7B2C]" };
  if (ext === "md" || ext === "markdown") return { Icon: FileText, color: "text-[#2563EB]" };
  if (ext === "txt") return { Icon: FileText, color: "text-[#666666]" };
  if (IMAGE_EXTS.includes(ext)) return { Icon: ImageIcon, color: "text-[#8B5CF6]" };
  return { Icon: File, color: "text-[#9AA0A6]" };
}

interface SourceIconProps {
  source: SourceRef;
  /** 控制字形尺寸 如 "h-3.5 w-3.5" */
  className?: string;
}

/**
 * 来源字形：有链接的来源出真实站点 favicon（失败回退地球），本地文件出文件类型图标
 */
export function SourceIcon({ source, className }: SourceIconProps) {
  const [failed, setFailed] = React.useState(false);
  const external = isExternal(source);
  const favicon = external ? faviconUrl(source.url) : null;

  if (external && favicon && !failed) {
    return (
      <img
        src={favicon}
        alt=""
        referrerPolicy="no-referrer"
        onError={() => setFailed(true)}
        className={cn("rounded-[3px] object-contain", className)}
      />
    );
  }
  if (external) {
    return <Globe className={cn("text-[#9AA0A6]", className)} />;
  }
  const { Icon, color } = fileGlyph(fileExt(source));
  return <Icon className={cn(color, className)} />;
}
