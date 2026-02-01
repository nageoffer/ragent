import * as React from "react";
import { BookOpen, Bot, Brain, Check, Lightbulb, Send, Square } from "lucide-react";

import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";

export function WelcomeScreen() {
  const [value, setValue] = React.useState("");
  const [isFocused, setIsFocused] = React.useState(false);
  const isComposingRef = React.useRef(false);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const { sendMessage, isStreaming, cancelGeneration, deepThinkingEnabled, setDeepThinkingEnabled } =
    useChatStore();

  const focusInput = React.useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.focus({ preventScroll: true });
  }, []);

  const adjustHeight = React.useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    const next = Math.min(el.scrollHeight, 160);
    el.style.height = `${next}px`;
  }, []);

  React.useEffect(() => {
    adjustHeight();
  }, [value, adjustHeight]);

  const promptPresets = [
    {
      title: "内容总结",
      description: "提炼 3-5 条关键信息与行动点",
      prompt: "请帮我总结以下内容，并列出3-5条要点：",
      icon: BookOpen
    },
    {
      title: "任务拆解",
      description: "把目标拆成可执行步骤与优先级",
      prompt: "请把下面需求拆解为步骤，并给出优先级和里程碑：",
      icon: Check
    },
    {
      title: "灵感扩展",
      description: "给出多个方案并比较优缺点",
      prompt: "围绕以下主题给出5-8个方案，并注明优缺点：",
      icon: Lightbulb
    }
  ];

  const applyPreset = React.useCallback(
    (prompt: string) => {
      if (isStreaming) return;
      setValue(prompt);
      focusInput();
    },
    [isStreaming, focusInput]
  );

  const handleSubmit = async () => {
    if (isStreaming) {
      cancelGeneration();
      focusInput();
      return;
    }
    if (!value.trim()) return;
    const next = value;
    setValue("");
    focusInput();
    await sendMessage(next);
    focusInput();
  };

  const hasContent = value.trim().length > 0;

  return (
    <div className="relative flex min-h-full items-center justify-center overflow-hidden px-4 py-16 sm:px-6">
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 bg-gradient-to-br from-[#F8FAFC] via-white to-[#EFF6FF]"
      />
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 bg-grid-pattern opacity-40 [background-size:40px_40px]"
      />
      <div
        aria-hidden="true"
        className="pointer-events-none absolute -top-32 right-[-40px] h-72 w-72 rounded-full bg-gradient-radial from-[#BFDBFE]/60 via-transparent to-transparent blur-3xl animate-float"
      />
      <div
        aria-hidden="true"
        className="pointer-events-none absolute -bottom-36 left-[-80px] h-80 w-80 rounded-full bg-gradient-radial from-[#FDE68A]/40 via-transparent to-transparent blur-3xl animate-float"
      />

      <div className="relative w-full max-w-[860px]">
        <div
          className="text-center opacity-0 animate-fade-up"
          style={{ animationFillMode: "both" }}
        >
          <span className="inline-flex items-center gap-2 rounded-full border border-white/70 bg-white/70 px-3 py-1 text-xs font-medium text-[#2563EB] shadow-sm">
            <Bot className="h-3.5 w-3.5" />
            RAG 智能问答
          </span>
          <h1 className="mt-4 font-display text-4xl leading-tight tracking-tight text-[#111827] sm:text-5xl md:text-6xl">
            把问题变成
            <span className="text-gradient">清晰答案</span>
          </h1>
          <p className="mt-4 text-base text-[#4B5563] sm:text-lg">
            结构化提问、知识检索与深度思考，一次对话给出可执行方案
          </p>
        </div>

        <div
          className="mt-10 opacity-0 animate-fade-up"
          style={{ animationDelay: "80ms", animationFillMode: "both" }}
        >
          <div
            className={cn(
              "relative flex flex-col rounded-3xl border border-white/70 bg-white/80 px-5 pt-4 pb-3 shadow-soft backdrop-blur-xl transition-all duration-200",
              isFocused
                ? "border-[#BFDBFE] shadow-glow"
                : "hover:border-[#D4D4D4]"
            )}
          >
            <div className="relative">
              <textarea
                ref={textareaRef}
                value={value}
                onChange={(event) => setValue(event.target.value)}
                placeholder={deepThinkingEnabled ? "输入需要深度分析的问题..." : "输入你的问题..."}
                className="max-h-40 min-h-[52px] w-full resize-none border-0 bg-transparent px-2 pt-2 pb-2 text-[15px] text-[#1F2937] placeholder:text-[#9CA3AF] focus:outline-none sm:text-base"
                rows={1}
                onFocus={() => setIsFocused(true)}
                onBlur={() => setIsFocused(false)}
                onCompositionStart={() => {
                  isComposingRef.current = true;
                }}
                onCompositionEnd={() => {
                  isComposingRef.current = false;
                }}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && !event.shiftKey) {
                    const nativeEvent = event.nativeEvent as KeyboardEvent;
                    if (nativeEvent.isComposing || isComposingRef.current || nativeEvent.keyCode === 229) {
                      return;
                    }
                    event.preventDefault();
                    handleSubmit();
                  }
                }}
                aria-label="发送消息"
              />
              <div className="pointer-events-none absolute bottom-0 left-0 right-0 h-[10px] bg-gradient-to-b from-white/0 via-white/40 to-white/90" />
            </div>
            <div className="mt-3 flex flex-wrap items-center gap-3">
              <button
                type="button"
                onClick={() => setDeepThinkingEnabled(!deepThinkingEnabled)}
                disabled={isStreaming}
                aria-pressed={deepThinkingEnabled}
                className={cn(
                  "rounded-full border px-3 py-1.5 text-xs font-medium transition-all",
                  deepThinkingEnabled
                    ? "border-[#BFDBFE] bg-[#DBEAFE] text-[#2563EB]"
                    : "border-transparent bg-[#F5F5F5] text-[#6B7280] hover:bg-[#EEEEEE]",
                  isStreaming && "cursor-not-allowed opacity-60"
                )}
              >
                <span className="inline-flex items-center gap-2">
                  <Brain className={cn("h-3.5 w-3.5", deepThinkingEnabled && "text-[#3B82F6]")} />
                  深度思考
                  {deepThinkingEnabled ? (
                    <span className="h-2 w-2 rounded-full bg-[#3B82F6] animate-pulse" />
                  ) : null}
                </span>
              </button>
              <button
                type="button"
                onClick={handleSubmit}
                disabled={!hasContent && !isStreaming}
                aria-label={isStreaming ? "停止生成" : "发送消息"}
                className={cn(
                  "ml-auto inline-flex items-center justify-center rounded-full p-2.5 transition-all duration-200",
                  isStreaming
                    ? "bg-[#FEE2E2] text-[#EF4444] hover:bg-[#FECACA]"
                    : hasContent
                      ? "bg-[#3B82F6] text-white hover:bg-[#2563EB]"
                      : "cursor-not-allowed bg-[#F5F5F5] text-[#CCCCCC]"
                )}
              >
                {isStreaming ? <Square className="h-4 w-4" /> : <Send className="h-4 w-4" />}
              </button>
            </div>
          </div>
          {deepThinkingEnabled ? (
            <p className="mt-3 text-xs text-[#2563EB]">
              <span className="inline-flex items-center gap-1.5">
                <Lightbulb className="h-3.5 w-3.5" />
                深度思考模式已开启，AI将进行更深入的分析推理
              </span>
            </p>
          ) : null}
          <p className="mt-3 text-center text-xs text-[#94A3B8]">
            <kbd className="rounded bg-white/80 px-1.5 py-0.5 text-[#6B7280] shadow-sm">
              Enter
            </kbd>{" "}
            发送
            <span className="px-1.5">·</span>
            <kbd className="rounded bg-white/80 px-1.5 py-0.5 text-[#6B7280] shadow-sm">
              Shift + Enter
            </kbd>{" "}
            换行
            {isStreaming ? <span className="ml-2 animate-pulse-soft">生成中...</span> : null}
          </p>
        </div>

        <div
          className="mt-10 opacity-0 animate-fade-up"
          style={{ animationDelay: "160ms", animationFillMode: "both" }}
        >
          <div className="flex items-center justify-center gap-2 text-xs uppercase tracking-[0.24em] text-[#94A3B8]">
            <span className="h-px w-8 bg-[#E5E7EB]" />
            试试这些开场
            <span className="h-px w-8 bg-[#E5E7EB]" />
          </div>
          <div className="mt-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {promptPresets.map((preset) => {
              const Icon = preset.icon;
              return (
                <button
                  key={preset.title}
                  type="button"
                  onClick={() => applyPreset(preset.prompt)}
                  disabled={isStreaming}
                  className={cn(
                    "group rounded-2xl border border-white/70 bg-white/70 p-4 text-left shadow-sm transition-all duration-200 hover:-translate-y-0.5 hover:border-[#BFDBFE] hover:shadow-md",
                    isStreaming && "cursor-not-allowed opacity-60"
                  )}
                >
                  <div className="flex items-center gap-3">
                    <span className="flex h-10 w-10 items-center justify-center rounded-full bg-[#EFF6FF] text-[#2563EB]">
                      <Icon className="h-4 w-4" />
                    </span>
                    <div>
                      <p className="text-sm font-semibold text-[#1F2937]">{preset.title}</p>
                      <p className="text-xs text-[#6B7280]">{preset.description}</p>
                    </div>
                  </div>
                  <p className="mt-3 text-xs text-[#94A3B8]">点击填充示例提问</p>
                </button>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}
