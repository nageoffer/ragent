// @ts-nocheck
/* eslint-disable */

import * as React from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Check, Copy } from "lucide-react";
import { Prism as SyntaxHighlighter } from "react-syntax-highlighter";
import { oneDark, oneLight } from "react-syntax-highlighter/dist/esm/styles/prism";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { useThemeStore } from "@/stores/themeStore";

interface MarkdownRendererProps {
  content: string;
}

export function MarkdownRenderer({ content }: MarkdownRendererProps) {
  const theme = useThemeStore((state) => state.theme);

  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      components={{
        code({ inline, className, children, ...props }) {
          const match = /language-(\w+)/.exec(className || "");
          const language = match?.[1] || "text";
          const value = String(children).replace(/\n$/, "");

          if (inline) {
            return (
              <code className={cn("rounded bg-[#F5F5F5] px-1.5 py-0.5 text-xs text-[#333333]", className)} {...props}>
                {children}
              </code>
            );
          }

          return (
            <div className="mt-3 overflow-hidden rounded-lg border border-[#E5E5E5] bg-[#FAFAFA]">
              <div className="flex items-center justify-between border-b border-[#E5E5E5] bg-[#F5F5F5] px-3 py-2 text-xs text-[#666666]">
                <span className="font-mono uppercase tracking-wider">{language}</span>
                <CopyButton value={value} />
              </div>
              <SyntaxHighlighter
                language={language}
                style={theme === "dark" ? oneDark : oneLight}
                PreTag="div"
                customStyle={{ margin: 0, padding: "1rem", background: "transparent" }}
              >
                {value}
              </SyntaxHighlighter>
            </div>
          );
        },
        a({ children, ...props }) {
          return (
            <a
              className="text-[#3B82F6] underline-offset-4 hover:underline"
              target="_blank"
              rel="noreferrer"
              {...props}
            >
              {children}
            </a>
          );
        }
      }}
      className="prose prose-gray max-w-none dark:prose-invert prose-headings:text-[#1A1A1A] prose-p:text-[#333333] prose-p:leading-relaxed prose-li:text-[#333333] prose-strong:text-[#1A1A1A]"
    >
      {content}
    </ReactMarkdown>
  );
}

function CopyButton({ value }: { value: string }) {
  const [copied, setCopied] = React.useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      setCopied(false);
    }
  };

  return (
    <Button variant="ghost" size="icon" onClick={handleCopy} aria-label="复制代码">
      {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
    </Button>
  );
}
