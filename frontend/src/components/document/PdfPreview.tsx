import { Fragment, useEffect, useRef, useState } from "react";
import { getDocument, GlobalWorkerOptions } from "pdfjs-dist";
import type { PDFDocumentProxy, RenderTask } from "pdfjs-dist";
// worker 走 Vite 产物，不依赖运行时从 CDN 取
import workerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";

import { fetchDocumentFile } from "@/services/knowledgeService";

GlobalWorkerOptions.workerSrc = workerUrl;

// 视口外提前一屏开始渲染，滚动时不至于看到空白
const PRERENDER_MARGIN = "600px";

// 高分屏按 2 倍图封顶，再高只是徒增显存
const MAX_PIXEL_RATIO = 2;

interface PdfPreviewProps {
  docId: string;
}

interface PdfPageProps {
  pdf: PDFDocumentProxy;
  pageNumber: number;
  width: number;
  aspect: number;
}

/**
 * 单页：滚动到附近才真正绘制，避免长文档一次性吃掉几百张 canvas 的显存
 */
function PdfPage({ pdf, pageNumber, width, aspect }: PdfPageProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || visible) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) setVisible(true);
      },
      { rootMargin: PRERENDER_MARGIN }
    );
    observer.observe(canvas);
    return () => observer.disconnect();
  }, [visible]);

  useEffect(() => {
    if (!visible || width <= 0) return;
    let cancelled = false;
    let task: RenderTask | null = null;

    (async () => {
      const page = await pdf.getPage(pageNumber);
      const canvas = canvasRef.current;
      if (cancelled || !canvas) return;
      const context = canvas.getContext("2d");
      if (!context) return;

      const ratio = Math.min(window.devicePixelRatio || 1, MAX_PIXEL_RATIO);
      const scale = width / page.getViewport({ scale: 1 }).width;
      const viewport = page.getViewport({ scale: scale * ratio });
      canvas.width = Math.floor(viewport.width);
      canvas.height = Math.floor(viewport.height);
      // 页面尺寸可能与首页不一致，绘制后按真实高度纠正占位值
      canvas.style.height = `${Math.floor(viewport.height / ratio)}px`;

      task = page.render({ canvasContext: context, viewport });
      // 宽度变化触发重绘时旧任务会被 cancel，抛出的是取消而非失败
      await task.promise.catch(() => undefined);
    })();

    return () => {
      cancelled = true;
      task?.cancel();
    };
  }, [pdf, pageNumber, visible, width]);

  return <canvas ref={canvasRef} className="w-full bg-white" style={{ height: Math.round(width * aspect) }} />;
}

/**
 * PDF 在线预览：拉取鉴权后的源文件，用 pdf.js 自行绘制
 * 不走 iframe 是因为浏览器原生阅读器自带背景、页面外框与悬浮工具栏，样式不可控且各家不一致
 */
export function PdfPreview({ docId }: PdfPreviewProps) {
  const listRef = useRef<HTMLDivElement>(null);
  const [pdf, setPdf] = useState<PDFDocumentProxy | null>(null);
  const [aspect, setAspect] = useState(0);
  const [width, setWidth] = useState(0);
  const [status, setStatus] = useState<"loading" | "done" | "error">("loading");

  useEffect(() => {
    let cancelled = false;
    let loaded: PDFDocumentProxy | null = null;
    setPdf(null);
    setStatus("loading");

    (async () => {
      try {
        const buffer = await fetchDocumentFile(docId);
        if (cancelled) return;
        loaded = await getDocument({ data: buffer }).promise;
        // 首页尺寸用作全部页面的占位高宽比，省掉逐页取元数据
        const first = await loaded.getPage(1);
        const viewport = first.getViewport({ scale: 1 });
        if (cancelled) return;
        setAspect(viewport.height / viewport.width);
        setPdf(loaded);
        setStatus("done");
      } catch {
        if (!cancelled) setStatus("error");
      }
    })();

    return () => {
      cancelled = true;
      loaded?.destroy();
    };
  }, [docId]);

  useEffect(() => {
    const list = listRef.current;
    if (!list) return;
    const observer = new ResizeObserver(() => setWidth(list.clientWidth));
    observer.observe(list);
    setWidth(list.clientWidth);
    return () => observer.disconnect();
  }, []);

  return (
    // 页面自带页边距，容器再留白会把正文挤成窄条，这里让整页铺满对话框
    <div className="relative flex-1 overflow-auto">
      {status !== "done" ? (
        <div className="absolute inset-0 z-10 flex items-center justify-center text-sm text-muted-foreground">
          {status === "loading" ? "正在加载 PDF…" : "PDF 预览失败"}
        </div>
      ) : null}
      <div ref={listRef} className="flex flex-col">
        {pdf && aspect > 0 && width > 0
          ? Array.from({ length: pdf.numPages }, (_, index) => (
              <Fragment key={index}>
                {index > 0 ? <div className="doc-page-divider px-8">第 {index + 1} 页</div> : null}
                <PdfPage pdf={pdf} pageNumber={index + 1} width={width} aspect={aspect} />
              </Fragment>
            ))
          : null}
      </div>
    </div>
  );
}
