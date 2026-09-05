import { useMemo, useState } from "react";
import { ArrowDown, ArrowUp, Search } from "lucide-react";

import { VIZ_COLORS } from "@/components/admin/vizTokens";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import type { AgentDashboardPerformance } from "@/services/dashboardService";

import {
  DataNote,
  DetailDialog,
  DialogSection,
  Meter,
  Stat,
  compact,
  number,
  percent,
  percentStat,
  ratio,
  successRateClass
} from "./DashboardPrimitives";

/**
 * 三个详情弹框。它们承接首页各卡「查看详情」，只呈现 /admin/dashboard/performance
 * 已经下发的字段——接口给不出逐条明细的地方一律写明缺什么，不拿聚合数拼一张像明细的表。
 */

type ToolRow = AgentDashboardPerformance["tools"]["topTools"][number];
type ToolSortKey = "count" | "failed" | "successRate" | "lastCallAt";

/**
 * 最近调用只读到分钟。这一列回答的是「这个工具最近还在被用吗」，
 * 秒级精度既没有判断价值，还会把这一列撑到要横向滚动
 */
const clockTime = (timestamp: number | null) =>
  timestamp === null
    ? null
    : new Date(timestamp).toLocaleString("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false
      });

/** 一行「标签 + 计数 + 占比条」。状态构成与回复形态都是同一个总量的去向，共用这一种写法。 */
function BreakdownRow({
  label,
  value,
  share,
  color = VIZ_COLORS.accent
}: {
  label: string;
  value: number;
  share: number | null;
  color?: string;
}) {
  return (
    <div className="flex items-center gap-3 py-1.5">
      <span className="flex w-24 shrink-0 items-center gap-1.5 text-xs text-[#667085]">
        <span
          className="h-1.5 w-1.5 shrink-0 rounded-full"
          style={{ backgroundColor: color }}
          aria-hidden="true"
        />
        <span className="truncate">{label}</span>
      </span>
      <span className="min-w-0 flex-1">
        <Meter value={share} color={color} />
      </span>
      <span className="w-12 shrink-0 text-right text-xs font-medium tabular-nums text-[#101828]">
        {number(value)}
      </span>
      <span className="w-12 shrink-0 text-right text-xs tabular-nums text-[#98A2B3]">
        {percent(share)}
      </span>
    </div>
  );
}

/** 可排序表头。箭头只画在当前排序列上，其余列靠 hover 变深提示可点。 */
function SortableHead({
  label,
  sortKey,
  active,
  desc,
  onSort,
  className
}: {
  label: string;
  sortKey: ToolSortKey;
  active: boolean;
  desc: boolean;
  onSort: (key: ToolSortKey) => void;
  className?: string;
}) {
  const Arrow = desc ? ArrowDown : ArrowUp;
  return (
    <th scope="col" className={cn("font-medium", className)}>
      <button
        type="button"
        onClick={() => onSort(sortKey)}
        aria-sort={active ? (desc ? "descending" : "ascending") : "none"}
        className={cn(
          "inline-flex w-full items-center justify-end gap-0.5 transition-colors hover:text-[#667085]",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#4F6EF7]",
          active && "text-[#4F6EF7] hover:text-[#405FE8]"
        )}
      >
        {label}
        <Arrow className={cn("h-3 w-3", !active && "opacity-0")} aria-hidden="true" />
      </button>
    </th>
  );
}

export function HealthDetailDialog({
  open,
  onOpenChange,
  data,
  windowLabel
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  data: AgentDashboardPerformance;
  windowLabel: string;
}) {
  const { replies } = data;
  const missing = replies.total - replies.withBlocks;

  const states = [
    { label: "正常回复", value: replies.normal, color: VIZ_COLORS.good },
    { label: "待确认", value: replies.awaitingConfirm, color: VIZ_COLORS.warning },
    { label: "中断", value: replies.interrupted, color: VIZ_COLORS.critical },
    { label: "其他状态", value: replies.unknown, color: VIZ_COLORS.neutral }
  ];

  return (
    <DetailDialog
      open={open}
      onOpenChange={onOpenChange}
      title="Agent 运行健康详情"
      windowLabel={windowLabel}
    >
      <div className="space-y-6">
        <DialogSection title="汇总">
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Stat label="助手回复" value={number(replies.total)} unit="条" />
            <Stat label="正常回复占比" value={percentStat(ratio(replies.normal, replies.total))} />
            <Stat label="轨迹覆盖率" value={percentStat(ratio(replies.withBlocks, replies.total))} />
            <Stat label="待确认" value={number(replies.awaitingConfirm)} unit="条" />
          </div>
        </DialogSection>

        <DialogSection title="回复状态构成">
          <div className="divide-y divide-[#F4F5F8]">
            {states.map((state) => (
              <BreakdownRow
                key={state.label}
                label={state.label}
                value={state.value}
                share={ratio(state.value, replies.total)}
                color={state.color}
              />
            ))}
          </div>
        </DialogSection>

        {/* 覆盖率是后面几张卡的共同分母，把分子分母摊开写，省得每张卡各解释一遍 */}
        <DialogSection title="轨迹覆盖口径">
          <p className="text-xs leading-5 text-[#667085]">
            有轨迹的回复 <span className="font-medium tabular-nums text-[#101828]">{number(replies.withBlocks)}</span> ÷
            全部回复 <span className="tabular-nums">{number(replies.total)}</span> ={" "}
            <span className="font-semibold tabular-nums text-[#101828]">
              {percent(ratio(replies.withBlocks, replies.total))}
            </span>
            。余下 <span className="tabular-nums">{number(missing)}</span> 条缺少执行轨迹，不代表未调用工具，工具与确认统计均未覆盖。
          </p>
        </DialogSection>

        <DataNote>
          状态以统计时刻的最新值为准，回复在后续处理后可能变更状态但仍归属原窗口。「中断」包含主动停止与执行异常，暂无法区分。状态反映回复的完结情况，不等同于任务是否完成。
        </DataNote>
      </div>
    </DetailDialog>
  );
}

export function ToolDetailDialog({
  open,
  onOpenChange,
  data,
  windowLabel
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  data: AgentDashboardPerformance;
  windowLabel: string;
}) {
  const { tools, replies } = data;
  const [query, setQuery] = useState("");
  const [sortKey, setSortKey] = useState<ToolSortKey>("count");
  const [desc, setDesc] = useState(true);

  const rows = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    const matched = keyword
      ? tools.topTools.filter(
          (tool) =>
            tool.displayName.toLowerCase().includes(keyword) ||
            tool.name.toLowerCase().includes(keyword)
        )
      : [...tools.topTools];
    // 成功率与最近调用为 null 的行恒排到末尾：它是"算不出来"，两个方向都不该让它顶到第一行
    const rank = (tool: ToolRow) => tool[sortKey];
    return matched.sort((a, b) => {
      const left = rank(a);
      const right = rank(b);
      if (left === null) return 1;
      if (right === null) return -1;
      if (left === right) return a.name.localeCompare(b.name);
      return desc ? right - left : left - right;
    });
  }, [tools.topTools, query, sortKey, desc]);

  // 接口按调用量只下发排名靠前的工具，这里的残余是「总数减去下发的这几个」，与首页那条说明同源
  const listedCalls = tools.topTools.reduce((sum, tool) => sum + tool.count, 0);
  const otherCalls = Math.max(0, tools.total - listedCalls);

  const onSort = (key: ToolSortKey) => {
    if (key === sortKey) {
      setDesc((prev) => !prev);
      return;
    }
    setSortKey(key);
    setDesc(true);
  };

  return (
    <DetailDialog
      open={open}
      onOpenChange={onOpenChange}
      title="工具调用详情"
      windowLabel={windowLabel}
    >
      <div className="space-y-6">
        <DialogSection title="汇总">
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Stat label="工具调用总数" value={replies.withBlocks > 0 ? number(tools.total) : null} unit="次" />
            <Stat label="工具调用成功率" value={percentStat(ratio(tools.done, tools.total))} />
            <Stat label="每条回复平均调用" value={tools.callsPerRecordedReply?.toFixed(2) ?? null} unit="次" />
            <Stat
              label="知识检索"
              value={replies.withBlocks > 0 ? number(tools.knowledgeSearchCalls) : null}
              unit="次"
            />
          </div>
        </DialogSection>

        {tools.total > 0 && (
          <DialogSection title="调用状态构成">
            <div className="divide-y divide-[#F4F5F8]">
              <BreakdownRow
                label="完成"
                value={tools.done}
                share={ratio(tools.done, tools.total)}
                color={VIZ_COLORS.good}
              />
              <BreakdownRow
                label="失败"
                value={tools.failed}
                share={ratio(tools.failed, tools.total)}
                color={VIZ_COLORS.critical}
              />
              <BreakdownRow
                label="中断"
                value={tools.interrupted}
                share={ratio(tools.interrupted, tools.total)}
                color={VIZ_COLORS.warning}
              />
              <BreakdownRow
                label="其他状态"
                value={tools.other}
                share={ratio(tools.other, tools.total)}
                color={VIZ_COLORS.neutral}
              />
            </div>
          </DialogSection>
        )}

        {replies.withBlocks > 0 && (
          <DialogSection title="回复形态">
            {/* 分母是有轨迹的回复而不是全部回复，三档相加正好等于它，所以能并成一组读 */}
            <div className="divide-y divide-[#F4F5F8]">
              <BreakdownRow
                label="直接回答"
                value={replies.directReplies}
                share={ratio(replies.directReplies, replies.withBlocks)}
                color={VIZ_COLORS.reference}
              />
              <BreakdownRow
                label="单工具回复"
                value={replies.singleToolReplies}
                share={ratio(replies.singleToolReplies, replies.withBlocks)}
                color={VIZ_COLORS.accentAlt}
              />
              <BreakdownRow
                label="多工具回复"
                value={replies.multiToolReplies}
                share={ratio(replies.multiToolReplies, replies.withBlocks)}
                color={VIZ_COLORS.accent}
              />
            </div>
          </DialogSection>
        )}

        <DialogSection
          title="工具明细"
          action={
            <div className="relative w-48">
              <Search
                className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-[#B0B8C4]"
                aria-hidden="true"
              />
              <Input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="搜索工具名称"
                aria-label="搜索工具名称"
                className="h-8 rounded-lg border-[#EAECF0] bg-white pl-8 text-xs"
              />
            </div>
          }
        >
          {tools.topTools.length === 0 ? (
            <p className="rounded-xl border border-dashed border-[#EAECF0] px-4 py-6 text-center text-sm text-[#667085]">
              当前统计范围内没有工具调用轨迹。
            </p>
          ) : (
            /* 表体单独滚动，表头钉住：工具数变多时弹框自身高度不动 */
            <div className="max-h-[264px] overflow-y-auto">
              <table className="w-full min-w-[600px] border-collapse text-xs">
                <thead className="sticky top-0 z-10 bg-white">
                  <tr className="h-8 text-left align-middle text-[#98A2B3] [&>th]:border-b [&>th]:border-[#EEF0F3] [&>th]:font-medium">
                    <th scope="col" className="w-40 pr-3">
                      工具名称
                    </th>
                    <SortableHead
                      label="调用次数"
                      sortKey="count"
                      active={sortKey === "count"}
                      desc={desc}
                      onSort={onSort}
                      className="w-20 pr-3 text-right"
                    />
                    {/*
                      与首页那张表同一种排法：百分数在左、条形接着往右伸，表头右边缘对齐百分数的 44px 盒子。
                      两处是同一份数据，排法不该一处一个样
                    */}
                    <th scope="col" className="w-[150px] pr-3 font-medium">
                      <span className="block w-11 text-right">占比</span>
                    </th>
                    <SortableHead
                      label="失败数"
                      sortKey="failed"
                      active={sortKey === "failed"}
                      desc={desc}
                      onSort={onSort}
                      className="w-16 pr-3 text-right"
                    />
                    <SortableHead
                      label="成功率"
                      sortKey="successRate"
                      active={sortKey === "successRate"}
                      desc={desc}
                      onSort={onSort}
                      className="w-16 pr-3 text-right"
                    />
                    <SortableHead
                      label="最近调用"
                      sortKey="lastCallAt"
                      active={sortKey === "lastCallAt"}
                      desc={desc}
                      onSort={onSort}
                      className="w-[86px] text-right"
                    />
                  </tr>
                </thead>
                <tbody className="divide-y divide-[#F4F5F8]">
                  {rows.map((tool) => (
                    <tr key={tool.name} className="h-10 align-middle">
                      <td className="max-w-40 truncate pr-3 font-medium text-[#101828]" title={tool.name}>
                        {tool.displayName}
                      </td>
                      <td className="pr-3 text-right tabular-nums text-[#101828]">
                        {number(tool.count)}
                      </td>
                      <td className="pr-3">
                        <div className="flex items-center gap-2.5">
                          <span className="w-11 shrink-0 text-right tabular-nums text-[#667085]">
                            {percent(ratio(tool.count, tools.total))}
                          </span>
                          <Meter className="min-w-[24px] flex-1" value={ratio(tool.count, tools.total)} />
                        </div>
                      </td>
                      <td
                        className="pr-3 text-right tabular-nums"
                        style={{ color: tool.failed > 0 ? VIZ_COLORS.critical : VIZ_COLORS.neutral }}
                      >
                        {number(tool.failed)}
                      </td>
                      <td
                        className={cn(
                          "pr-3 text-right font-medium tabular-nums",
                          successRateClass(tool.successRate)
                        )}
                      >
                        {percent(tool.successRate)}
                      </td>
                      <td className="text-right tabular-nums text-[#667085]">
                        {clockTime(tool.lastCallAt) ?? <span className="text-[#B0B8C4]">—</span>}
                      </td>
                    </tr>
                  ))}
                  {rows.length === 0 && (
                    <tr>
                      <td colSpan={6} className="h-16 text-center text-[#98A2B3]">
                        没有匹配「{query}」的工具
                      </td>
                    </tr>
                  )}
                </tbody>
                {/* 残余桶固定在表尾，不参与搜索与排序：它不是一个工具，是这张表之外的量 */}
                {otherCalls > 0 && (
                  <tfoot>
                    <tr className="h-10 align-middle border-t border-[#EEF0F3] text-[#667085]">
                      <td className="pr-3 font-medium">其他工具</td>
                      <td className="pr-3 text-right tabular-nums">{number(otherCalls)}</td>
                      <td className="pr-3">
                        <div className="flex items-center gap-2.5">
                          <span className="w-11 shrink-0 text-right tabular-nums">
                            {percent(ratio(otherCalls, tools.total))}
                          </span>
                          <Meter
                            className="min-w-[24px] flex-1"
                            value={ratio(otherCalls, tools.total)}
                            color={VIZ_COLORS.neutral}
                          />
                        </div>
                      </td>
                      <td className="pr-3 text-right tabular-nums text-[#98A2B3]">—</td>
                      <td className="pr-3 text-right tabular-nums text-[#98A2B3]">—</td>
                      <td className="text-right tabular-nums text-[#98A2B3]">—</td>
                    </tr>
                  </tfoot>
                )}
              </table>
            </div>
          )}
        </DialogSection>

        {/* 中文句子不能跨源码行折——JSX 会把换行并成一个空格，读者会看到句号后多一格 */}
        <DataNote>
          排名之外的工具仅有总量，表尾留破折号而非 0。成功率分母为该工具全部调用，含中断等状态，失败数并非补数。「最近调用」取自所在回复的创建时间，暂无单次调用时间戳与耗时。
        </DataNote>
      </div>
    </DetailDialog>
  );
}

export function ConfirmDetailDialog({
  open,
  onOpenChange,
  data,
  windowLabel
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  data: AgentDashboardPerformance;
  windowLabel: string;
}) {
  const { confirmations: confirm } = data;
  const decided = confirm.approved + confirm.denied;

  const states = [
    { label: "已批准", value: confirm.approved, color: VIZ_COLORS.good },
    { label: "已拒绝", value: confirm.denied, color: VIZ_COLORS.critical },
    { label: "待确认", value: confirm.pending, color: VIZ_COLORS.warning },
    { label: "已过期", value: confirm.expired, color: VIZ_COLORS.neutral },
    { label: "其他状态", value: confirm.other, color: VIZ_COLORS.neutral }
  ];

  return (
    <DetailDialog
      open={open}
      onOpenChange={onOpenChange}
      title="人工确认详情"
      windowLabel={windowLabel}
    >
      <div className="space-y-6">
        <DialogSection title="汇总">
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Stat label="确认卡" value={number(confirm.total)} unit="张" />
            <Stat label="批准率" value={percentStat(confirm.approvalRate)} />
            <Stat label="待确认" value={number(confirm.pending)} unit="张" />
            <Stat label="已过期" value={number(confirm.expired)} unit="张" />
          </div>
        </DialogSection>

        {confirm.total > 0 ? (
          <>
            <DialogSection title="状态构成">
              <div className="divide-y divide-[#F4F5F8]">
                {states.map((state) => (
                  <BreakdownRow
                    key={state.label}
                    label={state.label}
                    value={state.value}
                    share={ratio(state.value, confirm.total)}
                    color={state.color}
                  />
                ))}
              </div>
            </DialogSection>

            {/* 批准率的分子分母直接写出来：它与上面那张表用的不是同一个分母，不写清就会被当成占总数的比例 */}
            <DialogSection title="批准率口径">
              <p className="text-xs leading-5 text-[#667085]">
                已批准 <span className="font-medium tabular-nums text-[#101828]">{number(confirm.approved)}</span> ÷
                （已批准 <span className="tabular-nums">{number(confirm.approved)}</span> + 已拒绝{" "}
                <span className="tabular-nums">{number(confirm.denied)}</span> ={" "}
                <span className="tabular-nums">{number(decided)}</span>） ={" "}
                <span className="font-semibold tabular-nums text-[#101828]">
                  {percent(confirm.approvalRate)}
                </span>
                。待确认与已过期这 {number(confirm.pending + confirm.expired)} 张还没有结论，不进分母。
              </p>
            </DialogSection>

            {confirm.topTools.length > 0 && (
              /* 换了计数单位：上面几段数的是卡，这一段数的是卡里的调用，所以它不与上表同分母 */
              <DialogSection title="涉及的工具（按调用计）">
                <div className="max-h-[220px] overflow-y-auto">
                  <table className="w-full border-collapse text-xs">
                    <thead className="sticky top-0 z-10 bg-white">
                      <tr className="h-8 text-left align-middle text-[#98A2B3] [&>th]:border-b [&>th]:border-[#EEF0F3] [&>th]:font-medium">
                        <th scope="col" className="pr-3">
                          工具名称
                        </th>
                        <th scope="col" className="w-20 pr-3 text-right">
                          调用次数
                        </th>
                        <th scope="col" className="w-16 pr-3 text-right">
                          已批准
                        </th>
                        <th scope="col" className="w-16 pr-3 text-right">
                          已拒绝
                        </th>
                        <th scope="col" className="w-16 text-right">
                          未裁决
                        </th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-[#F4F5F8]">
                      {confirm.topTools.map((tool) => {
                        const undecided = Math.max(0, tool.calls - tool.approved - tool.denied);
                        return (
                          <tr key={tool.name} className="h-10 align-middle">
                            <td className="truncate pr-3 font-medium text-[#101828]" title={tool.name}>
                              {tool.displayName}
                            </td>
                            <td className="pr-3 text-right tabular-nums text-[#101828]">
                              {number(tool.calls)}
                            </td>
                            <td
                              className="pr-3 text-right tabular-nums"
                              style={{
                                color: tool.approved > 0 ? VIZ_COLORS.good : VIZ_COLORS.neutral
                              }}
                            >
                              {number(tool.approved)}
                            </td>
                            <td
                              className="pr-3 text-right tabular-nums"
                              style={{
                                color: tool.denied > 0 ? VIZ_COLORS.critical : VIZ_COLORS.neutral
                              }}
                            >
                              {number(tool.denied)}
                            </td>
                            <td className="text-right tabular-nums text-[#98A2B3]">
                              {number(undecided)}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </DialogSection>
            )}
          </>
        ) : (
          <p className="rounded-xl border border-dashed border-[#EAECF0] px-4 py-6 text-center text-sm text-[#667085]">
            当前统计范围内没有确认卡片。
          </p>
        )}

        <DataNote>
          当前仅提供聚合统计，暂无逐张卡片的筛选列表。工具表按「调用」计数，一张卡可含多个调用且整卡裁决，因此各工具调用总和可能大于卡片数。确认卡按所属回复的创建时间归窗口，状态以统计时刻为准。
        </DataNote>
      </div>
    </DetailDialog>
  );
}

export function MemoryDetailDialog({
  open,
  onOpenChange,
  data,
  windowLabel
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  data: AgentDashboardPerformance;
  windowLabel: string;
}) {
  const { memory } = data;
  const before = memory.contextCharsBefore;
  const after = memory.contextCharsAfter;
  const saved = Math.max(0, before - after);
  const net = memory.addedMemories - memory.invalidatedMemories;
  // 字符数只来自记了字符的那批压缩事件，求「平均每次」时分母只能用它，不能用压缩总次数
  const sampled = memory.compactionsWithChars;
  const unsampled = Math.max(0, memory.compactions - sampled);

  return (
    <DetailDialog
      open={open}
      onOpenChange={onOpenChange}
      title="记忆与上下文详情"
      windowLabel={windowLabel}
    >
      <div className="space-y-6">
        <DialogSection title="上下文压缩">
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Stat label="压缩次数" value={number(memory.compactions)} unit="次" />
            <Stat label="缩减比例" value={percentStat(memory.contextReductionPct)} />
            {/* 这两格是窗口内的合计而不是某一次的量，标签里写明「合计」，省得被读成单次压缩的前后 */}
            <Stat label="压缩前合计" value={compact(before)} unit="字符" />
            <Stat label="压缩后合计" value={compact(after)} unit="字符" />
          </div>
        </DialogSection>

        {before > 0 ? (
          <DialogSection title="压缩效果">
            {/* 「压缩前」定为满槽，「压缩后」按同一分母收缩：两条条长之比就是缩减比例 */}
            <div className="space-y-2.5">
              {[
                { label: "压缩前", value: before, width: 100, color: VIZ_COLORS.reference },
                { label: "压缩后", value: after, width: (after / before) * 100, color: VIZ_COLORS.accent }
              ].map((bar) => (
                <div key={bar.label} className="flex items-center gap-3">
                  <span className="w-12 shrink-0 text-xs text-[#667085]">{bar.label}</span>
                  <span className="min-w-0 flex-1">
                    <Meter value={bar.width} color={bar.color} className="h-2" />
                  </span>
                  <span className="w-24 shrink-0 text-right text-xs tabular-nums text-[#101828]">
                    {number(bar.value)}
                    <span className="ml-1 font-normal text-[#98A2B3]">字符</span>
                  </span>
                </div>
              ))}
            </div>
            <p className="mt-3 text-xs text-[#667085]">
              共省下 <span className="font-semibold tabular-nums text-[#101828]">{number(saved)}</span> 个字符，相当于压缩前的{" "}
              <span className="font-semibold tabular-nums text-[#101828]">{percent(memory.contextReductionPct)}</span>。
            </p>
            {sampled > 0 && (
              /* 分母写在句子里：这两个平均值除的是「记了字符的那 N 次」，不是上面那格压缩总次数 */
              <p className="mt-1.5 text-xs text-[#667085]">
                平均每次 <span className="font-semibold tabular-nums text-[#101828]">
                  {number(Math.round(before / sampled))}
                </span> → <span className="font-semibold tabular-nums text-[#101828]">
                  {number(Math.round(after / sampled))}
                </span> 字符，取自 <span className="tabular-nums">{number(sampled)}</span> 次记录了压缩前后字符数的压缩
                {unsampled > 0 && (
                  <>
                    ，另有 <span className="tabular-nums">{number(unsampled)}</span> 次没有记录，不进这两个平均值
                  </>
                )}
                。
              </p>
            )}
          </DialogSection>
        ) : (
          <DialogSection title="压缩效果">
            <p className="text-xs leading-5 text-[#667085]">
              {memory.compactions === 0
                ? "本窗口未触发上下文压缩，因此没有压缩前后的字符数可比。"
                : `本窗口 ${number(memory.compactions)} 次压缩都没有记录压缩前后字符数，因此算不出缩减比例与平均值。`}
            </p>
          </DialogSection>
        )}

        <DialogSection title="长期记忆变化">
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Stat label="有效条数" value={number(memory.activeMemories)} unit="条" />
            <Stat label="窗口内新增" value={number(memory.addedMemories)} unit="条" />
            <Stat label="窗口内失效" value={number(memory.invalidatedMemories)} unit="条" />
            <Stat label="净增长" value={`${net > 0 ? "+" : ""}${number(net)}`} unit="条" />
          </div>
          {memory.addedMemories + memory.invalidatedMemories > 0 && (
            <div className="mt-4 divide-y divide-[#F4F5F8]">
              <BreakdownRow
                label="新增"
                value={memory.addedMemories}
                share={ratio(memory.addedMemories, memory.addedMemories + memory.invalidatedMemories)}
                color={VIZ_COLORS.accent}
              />
              <BreakdownRow
                label="失效"
                value={memory.invalidatedMemories}
                share={ratio(
                  memory.invalidatedMemories,
                  memory.addedMemories + memory.invalidatedMemories
                )}
                color={VIZ_COLORS.neutral}
              />
            </div>
          )}
        </DialogSection>

        <DataNote>
          压缩统计的是字符数而非 Token，缩减比例不等于 Token 节省率。字符量仅来自记录了前后值的{" "}
          {number(sampled)} 次压缩，「平均每次」也以此为分母。有效条数为截至统计时刻的存量，不受窗口限制。暂无逐次压缩与逐条记忆的变更明细。
        </DataNote>
      </div>
    </DetailDialog>
  );
}
