import { useEffect, useRef, useState } from "react";
import { renderAsync } from "docx-preview";

import { fetchDocumentFile } from "@/services/knowledgeService";

interface DocxPreviewProps {
  docId: string;
}

/**
 * 去掉纸张外框后页与页之间没有了分界，补一条带页码的分隔线
 * 页面自带 overflow: hidden，分隔线只能作为兄弟节点插在 wrapper 里，不能用页面的伪元素
 */
function insertPageDividers(container: HTMLElement) {
  const pages = container.querySelectorAll<HTMLElement>("section.docx");
  pages.forEach((page, index) => {
    if (index === 0) return;
    const divider = document.createElement("div");
    divider.className = "doc-page-divider";
    divider.textContent = `第 ${index + 1} 页`;
    page.parentElement?.insertBefore(divider, page);
  });
}

/**
 * DOCX 在线预览：拉取鉴权后的源文件，在浏览器内解析 Office Open XML 并渲染为 HTML。
 */
export function DocxPreview({ docId }: DocxPreviewProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [status, setStatus] = useState<"loading" | "done" | "error">("loading");

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    let cancelled = false;
    container.replaceChildren();
    setStatus("loading");

    (async () => {
      try {
        const buffer = await fetchDocumentFile(docId);
        if (cancelled) return;
        await renderAsync(buffer, container, container, {
          className: "docx",
          inWrapper: true,
          breakPages: true,
          // 不套用 A4 的固定宽高：正文按对话框宽度排版，短文档也不会撑出整页空白
          ignoreWidth: true,
          ignoreHeight: true,
          renderHeaders: true,
          renderFooters: true,
          renderFootnotes: true,
          renderEndnotes: true,
          useBase64URL: true
        });
        if (cancelled) return;
        insertPageDividers(container);
        setStatus("done");
      } catch {
        if (!cancelled) setStatus("error");
      }
    })();

    return () => {
      cancelled = true;
      container.replaceChildren();
    };
  }, [docId]);

  return (
    <div className="docx-host relative flex-1 overflow-auto px-8 py-6">
      {status === "loading" ? (
        <div className="absolute inset-0 z-10 flex items-center justify-center text-sm text-muted-foreground">
          正在加载 Word 文档…
        </div>
      ) : null}
      {status === "error" ? (
        <div className="absolute inset-0 z-10 flex items-center justify-center text-sm text-muted-foreground">
          Word 文档预览失败
        </div>
      ) : null}
      <div ref={containerRef} />
    </div>
  );
}
