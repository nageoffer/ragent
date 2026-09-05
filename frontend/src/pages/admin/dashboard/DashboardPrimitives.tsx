import { useRef, type ReactNode } from "react";

import { CardHead, DashCard } from "@/components/admin/DashboardCard";
import { VIZ_COLORS } from "@/components/admin/vizTokens";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { cn } from "@/lib/utils";

/**
 * 概览页与三个详情弹框共用的读数原子。抽出来是因为弹框要和它开出来的那张卡
 * 用同一套写法：同一个数在两处长得不一样，读者会以为它们是两个数。
 */

export const number = (value: number) => value.toLocaleString("zh-CN");
export const percent = (value: number | null) => (value === null ? "—" : `${value.toFixed(1)}%`);
export const ratio = (part: number, whole: number) => (whole > 0 ? (part / whole) * 100 : null);
/**
 * 统计格里的百分比要把「没有值」交回 Stat 处理：percent() 的破折号是一个字符串，
 * Stat 会按真值渲染成粗黑，与同一行里真正缺值的浅灰破折号撞成两种写法。
 */
export const percentStat = (value: number | null) => (value === null ? null : percent(value));
/** 字符数折成 K/M：这几处读的是量级对比，精确到个位没有意义还会挤掉条形。 */
export const compact = (value: number) => {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (value >= 1000) return `${(value / 1000).toFixed(1)}K`;
  return number(value);
};

/**
 * 成功率的文字色按档走，不是一律绿：整列都绿时读者会跳过这一列，
 * 而它恰恰是唯一能把「调用很多却一直失败」的那一行挑出来的地方。
 * 三档都比 VIZ_COLORS 深一档——状态色是给条与点定的，12px 文字上过不了 4.5:1
 */
export const successRateClass = (value: number | null) => {
  if (value === null) return "text-[#B0B8C4]";
  if (value >= 95) return "text-emerald-700";
  if (value >= 80) return "text-amber-700";
  return "text-red-600";
};

/** 卡片外壳走公共的 DashCard/CardHead，两个引擎分支共用同一套卡片语言。 */
export function Card({
  title,
  hint,
  action,
  children,
  className
}: {
  title: string;
  hint?: ReactNode;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <DashCard className={className}>
      <CardHead
        // 标题到内容 12px：标题是这张卡的名字，不是一段引言，隔太开会让首行读数像另起一段
        className="mb-3"
        title={title}
        hint={hint}
        action={action}
      />
      {children}
    </DashCard>
  );
}

/**
 * 空态占位。flex-1 只在卡片被拉成 flex 列时生效：卡被邻卡撑高时由它接住多出来的高度，
 * 否则那段白会落在卡底，读起来像"少画了点什么"而不是"这里本来就没有数据"。
 */
export function Empty({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-24 flex-1 items-center justify-center rounded-xl border border-dashed border-[#EAECF0] px-4 py-6 text-center text-sm leading-relaxed text-[#667085]">
      {children}
    </div>
  );
}

/** 卡内统计格。无值时只留一个破折号，不带单位——「— 次」读起来像缺了个数。 */
export function Stat({
  label,
  value,
  unit,
  className
}: {
  label: string;
  value: string | null;
  unit?: string;
  className?: string;
}) {
  return (
    <div className={cn("min-w-0", className)}>
      <p className="truncate text-xs leading-4 text-[#667085]">{label}</p>
      <p className="mt-1.5 text-xl font-semibold leading-none tracking-tight text-[#101828]">
        {value === null ? (
          <span className="text-[#B0B8C4]">—</span>
        ) : (
          <>
            {value}
            {unit && <span className="ml-1 text-xs font-normal text-[#98A2B3]">{unit}</span>}
          </>
        )}
      </p>
    </div>
  );
}

/** 单条比例条。track 用中性浅灰而不是主色浅底，满槽代表 100%。 */
export function Meter({
  value,
  color = VIZ_COLORS.accent,
  className
}: {
  value: number | null;
  color?: string;
  className?: string;
}) {
  return (
    <div
      className={cn("h-1.5 w-full overflow-hidden rounded-full bg-[#F1F3F7]", className)}
      aria-hidden="true"
    >
      <div
        className="h-full rounded-full transition-[width] duration-500"
        style={{
          width: `${Math.max(0, Math.min(100, value ?? 0))}%`,
          minWidth: value && value > 0 ? 3 : 0,
          backgroundColor: color
        }}
      />
    </div>
  );
}

/**
 * 单值圆环。尺寸压到 64：它只承载一个百分比，做大了会成为整卡最重的一块，
 * 而卡里真正的主角是左侧那四格状态计数；它又是所在那一行的最高元素，行高就是它的直径，
 * 每缩 8px 都直接从第三层的高度里减掉。环内文字比环本身更该被读到，所以描边只给 7px。
 */
export function Ring({
  value,
  label,
  size = 64
}: {
  value: number | null;
  label: string;
  size?: number;
}) {
  // 描边 6 而不是 7：环缩到 64 之后，环内净宽是直径减两道描边，多一档描边就少两像素给读数
  const stroke = 6;
  const radius = (size - stroke) / 2;
  const circumference = 2 * Math.PI * radius;
  const filled = Math.max(0, Math.min(100, value ?? 0));

  return (
    <div className="relative shrink-0" style={{ width: size, height: size }}>
      <svg width={size} height={size} className="-rotate-90" aria-hidden="true">
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke="#EEF2FF"
          strokeWidth={stroke}
        />
        {value !== null && (
          <circle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            fill="none"
            stroke={VIZ_COLORS.accent}
            strokeWidth={stroke}
            strokeLinecap="round"
            strokeDasharray={circumference}
            strokeDashoffset={circumference - (filled / 100) * circumference}
            className="transition-[stroke-dashoffset] duration-700 ease-out"
          />
        )}
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center leading-none">
        <span className="text-[14px] font-semibold tabular-nums text-[#101828]">
          {/*
            字号 14 而不是 15：环内净宽 64−2×6＝52，15px 下「82.9%」要 48.4，两侧各只剩 0.8px，
            读数看上去是贴在环上的。14px 下是 45.5，两侧各留 3.2px 才像一个居中的数。
            满值仍要特判：14px 下「100.0%」是 54.5，六个字符照样出界。
            100 与 100.0 是同一个数，去掉的只是一个恒为零的小数位，口径没变
          */}
          {value === null ? (
            <span className="text-[#B0B8C4]">—</span>
          ) : (
            `${value >= 99.95 ? "100" : value.toFixed(1)}%`
          )}
        </span>
        <span className="mt-1 text-[10px] text-[#98A2B3]">{label}</span>
      </div>
    </div>
  );
}

/** 带色点的状态图例项，堆叠条与状态格共用同一套点色，两处对得上才算同一份编码。 */
export function LegendDot({
  color,
  label,
  value
}: {
  color: string;
  label: string;
  value?: string;
}) {
  return (
    <span className="inline-flex min-w-0 items-center gap-1.5 text-xs text-[#667085]">
      <span
        className="h-1.5 w-1.5 shrink-0 rounded-full"
        style={{ backgroundColor: color }}
        aria-hidden="true"
      />
      <span className="truncate">{label}</span>
      {value && <span className="tabular-nums text-[#101828]">{value}</span>}
    </span>
  );
}

/**
 * 详情弹框外壳。首页各卡只放摘要，全量明细一律走这里而不是往页面下面继续接：
 * 弹框是同一页里开的一层，读者看完关掉就回到原处，不必再滚回去找刚才那张卡。
 */
export function DetailDialog({
  open,
  onOpenChange,
  title,
  windowLabel,
  children
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  /** 与首页时间选择器同源，写进副标题让读者确认两处是同一批数 */
  windowLabel: string;
  children: ReactNode;
}) {
  const contentRef = useRef<HTMLDivElement>(null);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        ref={contentRef}
        /*
         * 打开时把焦点放在弹框本身，不交给里面第一个可聚焦的控件。
         * 这几个弹框第一件东西是搜索框，默认聚焦会让它一开就顶着光标和高亮边框，
         * 读者是来看明细的，却先被指去输入——而搜索是看完之后才会用的动作。
         * 焦点仍留在弹框内：Esc 关闭、Tab 循环、读屏播报标题都还照旧，只是不预选任何一个控件
         */
        onOpenAutoFocus={(event) => {
          event.preventDefault();
          contentRef.current?.focus();
        }}
        className="max-w-[720px] gap-0 rounded-[14px] border-[#EAECF0] bg-white p-0 sm:rounded-[14px]"
      >
        <DialogHeader className="space-y-1 border-b border-[#EEF0F3] px-6 py-4">
          <DialogTitle className="text-base font-semibold leading-6 text-[#101828]">
            {title}
          </DialogTitle>
          <DialogDescription className="text-xs text-[#98A2B3]">
            统计范围 {windowLabel}，与概览首页一致
          </DialogDescription>
        </DialogHeader>
        {/* 明细在弹框内部滚动：弹框自身不长高，页面也不会被它带着一起滚 */}
        <div className="max-h-[calc(85vh-88px)] overflow-y-auto px-6 py-5">{children}</div>
      </DialogContent>
    </Dialog>
  );
}

/** 弹框内的分段标题。它分的是同一份数据的几个侧面，所以只给字重不给描边。 */
export function DialogSection({
  title,
  action,
  children,
  className
}: {
  title: string;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={cn("min-w-0", className)}>
      <div className="mb-3 flex flex-wrap items-center justify-between gap-x-3 gap-y-1.5">
        <h4 className="text-[13px] font-semibold leading-5 text-[#101828]">{title}</h4>
        {action}
      </div>
      {children}
    </section>
  );
}

/**
 * 口径与缺口说明。接口没下发的东西宁可在这里写明「没有」，也不编一个看起来像真的表格：
 * 弹框比首页更容易被当成"完整版"，它缺什么必须自己说出来。
 */
export function DataNote({ children }: { children: ReactNode }) {
  return (
    <p className="border-t border-[#EEF0F3] pt-4 text-xs leading-[18px] text-[#667085]">
      {children}
    </p>
  );
}
