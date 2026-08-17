import type { SourceRef } from "@/types";

export function normalizeType(sourceType?: string) {
  return (sourceType || "").toLowerCase();
}

// 有外部链接的来源（url/飞书等）走 favicon 与新窗口跳转 本地文件走 docId 预览
export function isExternal(source: SourceRef) {
  return Boolean(source.url);
}

// 从 fileType 取扩展名 缺失时回退按 docName 扩展名兜底（兼容无 fileType 的历史数据）
export function fileExt(source: SourceRef): string {
  if (source.fileType) return source.fileType.toLowerCase();
  const match = (source.docName || "").match(/\.([a-z0-9]+)$/i);
  return match ? match[1].toLowerCase() : "";
}

// 来源基础文案：飞书文档 / 网页域名 / 本地文件
export function sourceLabel(source: SourceRef) {
  const type = normalizeType(source.sourceType);
  if (isExternal(source)) {
    if (type === "feishu") return "飞书文档";
    try {
      return new URL(source.url as string).hostname;
    } catch {
      return "网页";
    }
  }
  return "本地文件";
}

// 站点行文案：外链去掉 www. 只留域名 本地文件补扩展名（本地文件 · xlsx）
export function sourceSite(source: SourceRef) {
  const base = sourceLabel(source).replace(/^www\./i, "");
  if (isExternal(source)) return base;
  const ext = fileExt(source);
  return ext ? `${base} · ${ext}` : base;
}

// 打开来源：外链新窗口跳原站 本地文件走 docId 预览页
export function openSource(source: SourceRef) {
  if (isExternal(source) && source.url) {
    window.open(source.url, "_blank", "noopener,noreferrer");
    return;
  }
  window.open(`/preview/doc/${source.docId}`, "_blank", "noopener,noreferrer");
}

// 网页标题里常见的章节标记 如 ^第8章^
const CHAPTER_MARK = /\^([^^]{1,40})\^/g;
// 网页标题尾部的更新时间 如 最新更新:2018-04-04 16:02:03
const UPDATED_TAIL =
  /\s*(?:最新更新|更新时间|发布时间)[:：]?\s*(\d{4}[-/.]\d{1,2}[-/.]\d{1,2})(?:[ T]\d{1,2}:\d{2}(?::\d{2})?)?\s*$/;
// 摘要开头被切进来的日期前缀 如 2025/07/15-
const LEADING_TIMESTAMP =
  /^\s*\d{4}[-/.]\d{1,2}[-/.]\d{1,2}(?:[ T]\d{1,2}:\d{2}(?::\d{2})?)?\s*[-–—:：|·]?\s*/;
// 摘要因截断而残留的开头标点
const LEADING_PUNCT = /^[\s。，、；：！？…—·)）\]】>》"”'’,.;:!?]+/;
// 标题清洗后可能残留的尾部分隔符
const TRAILING_SEPARATOR = /[\s\-–—·|,、/]+$/;

export interface SourceDisplayName {
  /** 主标题：剥掉章节标记与更新时间后的干净标题 */
  title: string;
  /** 副标题：从标题里抽出的章节、日期等次要信息 以 · 连接 空串表示不展示 */
  detail: string;
}

/**
 * 拆分来源标题
 * <p>
 * 纯展示层处理：网页抓取的 title 常把章节号、更新时间一起塞进来，占满两行 clamp 把真正的
 * 书名/文章名挤没。这里把次要信息抽到副标题，原始值仍由调用方保留在 title 属性里
 */
export function displayName(source: SourceRef): SourceDisplayName {
  const raw = (source.docName || "").replace(/　/g, " ");
  if (!raw.trim()) return { title: "未命名文档", detail: "" };

  const details: string[] = [];
  let title = raw;

  const chapters = [...title.matchAll(CHAPTER_MARK)].map((match) => match[1].trim());
  if (chapters.length > 0) {
    details.push(...chapters.filter(Boolean));
    title = title.replace(CHAPTER_MARK, " ");
  }

  const updated = UPDATED_TAIL.exec(title);
  if (updated) {
    details.push(updated[1]);
    title = title.slice(0, updated.index);
  }

  title = title.replace(/\s+/g, " ").trim().replace(TRAILING_SEPARATOR, "").trim();
  if (!title) {
    // 整个标题只有章节标记与更新时间时 把首个次要信息提上来当标题 避免留空
    title = details.shift() || raw.trim();
  }
  return { title: title || "未命名文档", detail: details.join(" · ") };
}

/**
 * 清洗摘要：去掉切片开头带进来的日期前缀与残缺标点 并把换行折成空格
 */
export function displayExcerpt(excerpt?: string) {
  if (!excerpt) return "";
  const cleaned = excerpt
    .replace(/\s+/g, " ")
    .replace(LEADING_TIMESTAMP, "")
    .replace(LEADING_PUNCT, "")
    .trim();
  return cleaned || excerpt.trim();
}
