import { lazy, Suspense, useEffect, useState } from "react";
import { Download } from "lucide-react";

import { MarkdownRenderer } from "@/components/chat/MarkdownRenderer";
import { csvToMarkdown } from "@/lib/csvToMarkdown";
import { fetchDocumentFile, previewDocument } from "@/services/knowledgeService";

// xlsx 预览依赖较重(exceljs + x-data-spreadsheet)，懒加载避免拖累主包
const SpreadsheetPreview = lazy(() =>
  import("@/components/admin/SpreadsheetPreview").then((m) => ({ default: m.SpreadsheetPreview }))
);

// docx-preview 只在预览 Word 时加载，避免增大知识库列表首包
const DocxPreview = lazy(() =>
  import("@/components/document/DocxPreview").then((m) => ({ default: m.DocxPreview }))
);

// pdf.js 同理，含 worker 体积不小
const PdfPreview = lazy(() =>
  import("@/components/document/PdfPreview").then((m) => ({ default: m.PdfPreview }))
);

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");

const IMAGE_EXTS = ["png", "jpg", "jpeg", "svg", "gif", "webp", "bmp"];

export const isSpreadsheetType = (ext?: string | null) => {
  const e = (ext || "").toLowerCase();
  return e === "xlsx" || e === "xls";
};

export const isImageType = (ext?: string | null) => IMAGE_EXTS.includes((ext || "").toLowerCase());

export const isDocxType = (ext?: string | null) => (ext || "").toLowerCase() === "docx";

export const isPreviewableType = (ext?: string | null) => {
  const type = (ext || "").toLowerCase();
  return ["pdf", "csv", "markdown", "txt"].includes(type)
    || isDocxType(type)
    || isSpreadsheetType(type)
    || isImageType(type);
};

// 剥离 markdown 头部的 front-matter，单独展示
export const parseFrontMatter = (content: string): { head: string | null; body: string } => {
  if (content.startsWith("---\n")) {
    const end = content.indexOf("\n---\n", 4);
    if (end > 0) {
      return { head: content.substring(4, end), body: content.substring(end + 5) };
    }
  }
  return { head: null, body: content };
};

interface DocumentPreviewProps {
  docId: string;
  fileType?: string | null;
  docName?: string | null;
}

function Centered({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex flex-1 items-center justify-center py-16 text-sm text-muted-foreground">{children}</div>
  );
}

function DownloadFallback({ docId, docName, fileType }: DocumentPreviewProps) {
  const handleDownload = async () => {
    const buffer = await fetchDocumentFile(docId);
    const url = URL.createObjectURL(new Blob([buffer]));
    const anchor = document.createElement("a");
    anchor.href = url;
    const name = docName || `document-${docId}`;
    const hasExt = /\.[^./\\]+$/.test(name);
    anchor.download = !hasExt && fileType ? `${name}.${fileType.toLowerCase()}` : name;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
  };
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-3 py-16 text-sm text-muted-foreground">
      <span>该格式暂不支持在线预览</span>
      <button
        type="button"
        onClick={handleDownload}
        className="inline-flex items-center gap-1.5 rounded-full border border-[#EAEAEA] bg-[#F7F7F8] px-3 py-1.5 text-[#666666] transition-colors hover:border-[#DCDCDC] hover:bg-[#F0F0F1] hover:text-[#1A1A1A]"
      >
        <Download className="h-3.5 w-3.5" />
        下载原文件
      </button>
    </div>
  );
}

/**
 * 文档预览：按文件类型直出原文件
 * pdf→canvas、docx→HTML、xlsx/xls→表格预览、图片→img、csv→表格化 markdown、markdown→正文、txt→纯文本；其余类型给下载入口
 */
export function DocumentPreview({ docId, fileType, docName }: DocumentPreviewProps) {
  const type = (fileType || "").toLowerCase();
  const isPdf = type === "pdf";
  const isDocx = isDocxType(type);
  const isSheet = isSpreadsheetType(type);
  const isImage = isImageType(type);
  const isCsv = type === "csv";
  const isMarkdown = type === "markdown";
  const isPlainText = type === "txt";
  const needsText = isCsv || isMarkdown || isPlainText;

  const [content, setContent] = useState("");
  const [status, setStatus] = useState<"loading" | "done" | "error">("loading");

  useEffect(() => {
    if (!needsText) {
      return;
    }
    let cancelled = false;
    setStatus("loading");
    (async () => {
      try {
        if (isCsv || isPlainText) {
          const buffer = await fetchDocumentFile(docId);
          if (cancelled) return;
          const text = new TextDecoder("utf-8").decode(buffer);
          setContent(isCsv ? csvToMarkdown(text) : text);
        } else {
          const text = await previewDocument(docId);
          if (cancelled) return;
          setContent(text);
        }
        if (!cancelled) setStatus("done");
      } catch {
        if (!cancelled) setStatus("error");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [docId, isCsv, isPlainText, needsText]);

  const fileUrl = `${API_BASE_URL}/knowledge-base/docs/${docId}/file`;

  if (isPdf) {
    return (
      <Suspense fallback={<Centered>加载中…</Centered>}>
        <PdfPreview docId={docId} />
      </Suspense>
    );
  }
  if (isDocx) {
    return (
      <Suspense fallback={<Centered>加载中…</Centered>}>
        <DocxPreview docId={docId} />
      </Suspense>
    );
  }
  if (isSheet) {
    return (
      <Suspense fallback={<Centered>加载中…</Centered>}>
        <SpreadsheetPreview docId={docId} />
      </Suspense>
    );
  }
  if (isImage) {
    return (
      <div className="flex flex-1 items-center justify-center overflow-auto bg-[#F7F7F8] p-4">
        <img className="max-h-full max-w-full object-contain" src={fileUrl} alt={docName || ""} />
      </div>
    );
  }
  if (!needsText) {
    return <DownloadFallback docId={docId} docName={docName} fileType={fileType} />;
  }
  if (status === "loading") {
    return <Centered>正在加载文档内容…</Centered>;
  }
  if (status === "error") {
    return <DownloadFallback docId={docId} docName={docName} fileType={fileType} />;
  }
  if (isPlainText) {
    return (
      <div className="flex-1 overflow-auto px-6 py-4">
        <pre className="whitespace-pre-wrap break-words font-mono text-sm leading-6 text-foreground">{content}</pre>
      </div>
    );
  }
  const { head, body } = parseFrontMatter(content);
  return (
    <div className="flex-1 overflow-y-auto">
      {head ? (
        <pre className="mx-6 mt-4 overflow-auto rounded-lg border bg-slate-50 px-4 py-3 font-mono text-xs leading-relaxed text-slate-600">
          {head}
        </pre>
      ) : null}
      <div className="px-6 py-4">
        <MarkdownRenderer content={body} />
      </div>
    </div>
  );
}
