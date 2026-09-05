import { useState } from "react";
import { Info } from "lucide-react";

import { DetailButton } from "@/components/admin/DashboardCard";
import { VIZ_COLORS } from "@/components/admin/vizTokens";
import { cn } from "@/lib/utils";
import type { AgentDashboardPerformance } from "@/services/dashboardService";

import {
  ConfirmDetailDialog,
  HealthDetailDialog,
  MemoryDetailDialog,
  ToolDetailDialog
} from "./AgentDetailDialogs";
import {
  Card,
  Empty,
  LegendDot,
  Meter,
  Ring,
  Stat,
  compact,
  number,
  percent,
  percentStat,
  ratio,
  successRateClass
} from "./DashboardPrimitives";

/** 首页只列调用最多的这几个工具，其余进残余说明，卡片高度不随工具数量增长。 */
const TOOL_ROW_LIMIT = 4;

/** 四张卡都要把首页的时间范围带进各自的弹框，两处必须是同一批数。 */
type CardProps = {
  data: AgentDashboardPerformance;
  windowLabel: string;
  className?: string;
};

/**
 * 回复状态的段序 正常 → 待确认 → 中断 → 其他 既是语义序也是配色需要：
 * 相邻两段必须能分开，同时颜色本身要读得出「越往后越该看一眼」。
 */
const replyStates = (replies: AgentDashboardPerformance["replies"]) => [
  { label: "正常回复", value: replies.normal, color: VIZ_COLORS.good },
  { label: "待确认", value: replies.awaitingConfirm, color: VIZ_COLORS.warning },
  { label: "中断", value: replies.interrupted, color: VIZ_COLORS.critical },
  ...(replies.unknown > 0
    ? [{ label: "其他状态", value: replies.unknown, color: VIZ_COLORS.neutral }]
    : [])
];

/** Agent 运行健康：三列状态数字 + 覆盖率 + 状态分布条，右上开详情。 */
export function AgentRunHealth({ data, windowLabel, className }: CardProps) {
  const [open, setOpen] = useState(false);
  const { replies } = data;

  if (replies.total === 0) {
    return (
      <Card className={cn("flex min-h-[268px] flex-col", className)} title="Agent 运行健康">
        <Empty>暂无助手回复记录，产生回复后这里显示状态构成。</Empty>
      </Card>
    );
  }

  const states = replyStates(replies);
  const coverage = ratio(replies.withBlocks, replies.total);
  /*
   * 顶部固定三列：把「其他状态」也摆上来会让这排随数据在三列与四列之间跳，
   * 它的量本来就小，交给下面那条分布条的图例带出计数就够了
   */
  const primary = states.slice(0, 3);
  const extras = states.slice(3);

  return (
    <>
      <Card
        /*
         * 与左侧流量卡同高：卡内容拉成 flex 列，多出来的高度由下面那层摊掉，两卡下沿才对得上。
         * 下限跟着流量图从 300 收到 268——它比这张卡自己的内容还高时，第二层就由这个下限说了算，
         * 左边把图压矮也白压
         */
        className={cn("flex min-h-[268px] flex-col", className)}
        title="Agent 运行健康"
        action={<DetailButton onClick={() => setOpen(true)} />}
        hint={
          <>
            统计窗口内助手回复的完结状态，不等同于任务成功率。中断包含主动停止与执行异常。未覆盖的{" "}
            {number(replies.total - replies.withBlocks)} 条缺少执行轨迹，未纳入统计。
          </>
        }
      >
        {/*
          这张卡被左侧那张 208px 图的流量卡拉高，多出来的白总得有去处。
          全让给末行会在覆盖率之下留一个近百像素的洞，读成卡没画完；
          拆成「状态数 / 覆盖率 / 分布 / 限定语」四段各摊一点，才是四层而不是一处空白
        */}
        <div className="flex flex-1 flex-col justify-between gap-4">
          {/* 状态横排成格：竖排三行时每行只用掉右端一个数字，剩下的宽度全是空的，卡还高出一倍 */}
          <div className="grid grid-cols-3 gap-x-3">
            {primary.map((state) => (
              <div key={state.label} className="min-w-0">
                <p className="flex items-center gap-1.5 text-xs leading-4 text-[#667085]">
                  <span
                    className="h-1.5 w-1.5 shrink-0 rounded-full"
                    style={{ backgroundColor: state.color }}
                    aria-hidden="true"
                  />
                  <span className="truncate">{state.label}</span>
                </p>
                {/*
                  计数与占比上下两行，不并排：并排时「100」和「90.9%」中间只有一个空格，
                  两个数会先读成一个数（一百九十点九），换行后计数是主、占比是注，主次立刻分开
                */}
                <p className="mt-2 text-[22px] font-semibold leading-none tracking-tight text-[#101828]">
                  {number(state.value)}
                </p>
                <p className="mt-1.5 text-xs leading-4 tabular-nums text-[#98A2B3]">
                  {percent(ratio(state.value, replies.total))}
                </p>
              </div>
            ))}
          </div>

          {/* 标签与读数一行、条另起一行：三段挤成一行时条只剩半截宽，读不出它填了多少 */}
          <div>
            <div className="flex items-baseline justify-between gap-3">
              <span className="text-xs leading-4 text-[#667085]">轨迹覆盖率</span>
              <span className="text-sm font-semibold tabular-nums text-[#101828]">{percent(coverage)}</span>
            </div>
            <div className="mt-2">
              <Meter value={coverage} />
            </div>
          </div>

          <div>
            <p className="mb-2 text-xs leading-4 text-[#667085]">回复状态分布</p>
            {/* 段间 2px 用表面色留缝，相邻两段靠缝隙分开，不给标记描边；色点交给下面的图例 */}
            <div
              className="flex h-2 gap-0.5 overflow-hidden rounded-full bg-[#F1F3F7]"
              aria-hidden="true"
            >
              {states
                .filter((state) => state.value > 0)
                .map((state) => (
                  <div
                    key={state.label}
                    style={{ flex: `${state.value} 0 0`, minWidth: 3, backgroundColor: state.color }}
                  />
                ))}
            </div>
            {/*
              只给没上过场的段补图例：前三段的色点和计数就在上面那排里，
              整排图例会把同样三个数原样再念一遍，读者会以为是另一组统计
            */}
            {extras.length > 0 && (
              <div className="mt-2.5 flex flex-wrap gap-x-4 gap-y-1.5">
                {extras.map((state) => (
                  <LegendDot
                    key={state.label}
                    color={state.color}
                    label={state.label}
                    value={number(state.value)}
                  />
                ))}
              </div>
            )}
          </div>

          {/*
            这行是对上面三个数字的限定，不是告警：黄底红框会把它读成「出事了」，
            而它要说的恰恰是「这三个数正常也不代表任务成功」。底色连同上下 16px 内边距一起去掉——
            一句限定语要用一个色块把自己框起来才站得住的话，那说明它没被写清楚；
            图标和灰字已经把它和上面的读数分开了，省下的 16px 直接进第二层的高度
          */}
          <p className="flex items-start gap-1.5 text-xs leading-[18px] text-[#667085]">
            <Info className="mt-px h-3.5 w-3.5 shrink-0 text-[#98A2B3]" aria-hidden="true" />
            正常回复不代表任务最终成功
          </p>
        </div>
      </Card>

      <HealthDetailDialog
        open={open}
        onOpenChange={setOpen}
        data={data}
        windowLabel={windowLabel}
      />
    </>
  );
}

/** 工具调用分析：一排六个读数给总体，表格给前六个工具，完整明细走弹框。 */
export function AgentToolAnalysis({ data, windowLabel, className }: CardProps) {
  const [open, setOpen] = useState(false);
  const { tools, replies } = data;
  const shown = tools.topTools.slice(0, TOOL_ROW_LIMIT);
  const shownCalls = shown.reduce((sum, tool) => sum + tool.count, 0);
  const otherCalls = Math.max(0, tools.total - shownCalls);
  const missing = replies.total - replies.withBlocks;
  /*
   * 这两个是比例不是计数，跟前四个读数不同量纲。并成一列各配一条细条，
   * 既凑齐这一排六个指标，又不让两个百分比顶着 20px 字号和「684 次」抢首读
   */
  const shapeRatios = [
    {
      label: "直接回答占比",
      value: ratio(replies.directReplies, replies.withBlocks),
      color: VIZ_COLORS.reference
    },
    {
      label: "多工具回复占比",
      value: ratio(replies.multiToolReplies, replies.withBlocks),
      color: VIZ_COLORS.accent
    }
  ];

  return (
    <>
      <Card
        className={cn("flex flex-col", className)}
        title="工具调用分析"
        action={<DetailButton onClick={() => setOpen(true)} />}
        hint={
          <>
            {missing > 0
              ? `有 ${number(missing)} 条回复缺少轨迹，未纳入统计。`
              : "仅统计有轨迹的回复。"}
            首页展示前 {TOOL_ROW_LIMIT} 个工具，其余计入总数。成功率分母为全部调用，含中断等状态。占比分母为有轨迹的回复（{number(replies.withBlocks)} 条）。
          </>
        }
      >
        {/*
          六个指标不各自成卡：它们是同一件事的六个读数，给每个套一层底色会把一排读成六块，
          分隔靠发丝竖线——竖线不占面积，也不会像卡片那样暗示「里面还有层级」
        */}
        <div
          className={cn(
            /*
              窄于门槛时按「量纲」折行，不按格子数折：四个计数一行、两个百分比一行。
              原来折成三列两行时，第二行是「工具调用成功率 + 双层占比块 + 一个空格」——
              一个单读数挨着一个双层块，两者基线对不上，右边还空着三分之一，
              读起来像上面那行没排完掉下来的。四加二两行各自成一件事，行内高度也齐
            */
            "mb-4 grid grid-cols-2 gap-x-4 gap-y-3.5 sm:grid-cols-4",
            /*
              并成一行的门槛按「六个标签都写得全」定，不按断点表定：这张卡在 1280 宽的控制台里
              只有 600 出头，五等分后连「每条回复平均调用」都放不下，截成「每条回复平均…」等于
              把指标名换了。门槛实测出来是 1520（再窄一档那个标签的列只剩 94px，标签本身要 96px），
              取 1600 是给字体差异留一档余量——左列比例改动会挪这个数，改完必须重量一遍。
              末列装的是两行「标签 + 百分比」，比另外四列多一个读数，所以单独给 1.4 份宽
            */
            "min-[1600px]:grid-cols-[repeat(4,minmax(0,1fr))_minmax(0,1.4fr)]",
            "min-[1600px]:gap-x-0 min-[1600px]:gap-y-0 min-[1600px]:[&>*]:pr-3",
            "min-[1600px]:[&>*+*]:border-l min-[1600px]:[&>*+*]:border-[#EEF0F3] min-[1600px]:[&>*+*]:pl-4"
          )}
        >
          <Stat
            label="工具调用总数"
            value={replies.withBlocks > 0 ? number(tools.total) : null}
            unit="次"
          />
          <Stat label="每条回复平均调用" value={tools.callsPerRecordedReply?.toFixed(2) ?? null} unit="次" />
          <Stat
            label="知识检索"
            value={replies.withBlocks > 0 ? number(tools.knowledgeSearchCalls) : null}
            unit="次"
          />
          <Stat label="工具调用成功率" value={percentStat(ratio(tools.done, tools.total))} />
          {/*
            两个占比在窄屏并排、在宽屏（末列只有 1.4 份宽）叠成两行：并排时它们是同一行里的两个读数，
            叠起来时它们是末列里的两行，两种排法都不会跟左边四个计数混成一片
          */}
          <div className="col-span-2 grid min-w-0 grid-cols-2 gap-x-4 gap-y-2 sm:col-span-4 min-[1600px]:col-span-1 min-[1600px]:grid-cols-1">
            {shapeRatios.map((item) => (
              <div key={item.label} className="min-w-0">
                <div className="flex items-baseline justify-between gap-2">
                  <span className="truncate text-xs leading-4 text-[#667085]">{item.label}</span>
                  <span className="shrink-0 text-xs font-semibold tabular-nums text-[#101828]">
                    {percent(item.value)}
                  </span>
                </div>
                <Meter className="mt-1.5 h-1" value={item.value} color={item.color} />
              </div>
            ))}
          </div>
        </div>

        {tools.total === 0 ? (
          <Empty>
            {replies.withBlocks === 0
              ? "暂无可统计的工具轨迹。"
              : "有轨迹的回复中没有工具调用，直接回答也可能是正常行为。"}
          </Empty>
        ) : (
          <div className="overflow-x-auto">
            {/*
              数在左、条向右伸：五个读数挨着排在左半边，条形接着往右铺满剩下的宽度。
              反过来（条在左、数贴右边缘）时，「调用次数」与它右边的百分数中间隔着一整根条，
              两个本该连读的数被推到一行的两头，中间那段又没有标题——看起来就是"分布不均"。
              条形是占比的图形副本，跟百分数同一格、紧挨着放，读者的视线才不用跳
            */}
            <table className="w-full min-w-[520px] table-fixed border-collapse text-xs">
              <thead>
                {/*
                  表头只靠一条发丝线与首行分开：行高拉到 43px 后表头与数据已经分得清，
                  再加一条浅底色条就是在正文里又画一个容器
                */}
                <tr className="h-8 text-left align-middle text-[#98A2B3] [&>th]:border-b [&>th]:border-[#EEF0F3] [&>th]:font-medium">
                  {/* 25% 在最窄的 1280 控制台里是 134px，装得下十个字的工具名，再长才截 */}
                  <th scope="col" className="w-[25%] pr-3">
                    工具名称
                  </th>
                  {/*
                    14% 在 1280 下是 75px，扣掉 pr-3 还有 63px，比最宽的表头「调用次数」宽 15px。
                    这一列不再按"三个数字列等宽"分，而是按各自标题的实宽给——等宽会把这一列
                    推到行中间去，而它该紧跟在工具名后面被连读
                  */}
                  <th scope="col" className="w-[14%] pr-3 text-right">
                    调用次数
                  </th>
                  {/*
                    表头右边缘与百分数右边缘对齐（同一个 44px 盒子），不是对齐整格右边缘：
                    这一格右边还有一根条，标题跑到条的另一头去，就没人认得它管的是哪个数。
                    44px 是「100.0%」的宽度
                  */}
                  <th scope="col" className="w-[32%] pr-4">
                    <span className="block w-11 text-right">占比</span>
                  </th>
                  <th scope="col" className="w-[14%] pr-3 text-right">
                    失败数
                  </th>
                  <th scope="col" className="w-[15%] text-right">
                    成功率
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[#F4F5F8]">
                {shown.map((tool) => (
                  <tr key={tool.name} className="h-[43px] align-middle">
                    <td className="truncate pr-3 font-medium text-[#101828]" title={tool.name}>
                      {tool.displayName}
                    </td>
                    <td className="pr-3 text-right tabular-nums text-[#101828]">
                      {number(tool.count)}
                    </td>
                    <td className="pr-4">
                      {/* 数在左、条在右：百分数各行都右对齐在同一个 44px 盒子里，条形从盒子右侧起跑，起点也就各行一致 */}
                      <div className="flex items-center gap-2.5">
                        <span className="w-11 shrink-0 text-right tabular-nums text-[#667085]">
                          {percent(ratio(tool.count, tools.total))}
                        </span>
                        <Meter className="min-w-[24px] flex-1" value={ratio(tool.count, tools.total)} />
                      </div>
                    </td>
                    {/* 失败为 0 时转中性灰：全表零失败还整列红字，会把「没事」读成「到处是事」 */}
                    <td
                      className="pr-3 text-right tabular-nums"
                      style={{ color: tool.failed > 0 ? VIZ_COLORS.critical : VIZ_COLORS.neutral }}
                    >
                      {number(tool.failed)}
                    </td>
                    <td
                      className={cn(
                        "text-right font-medium tabular-nums",
                        successRateClass(tool.successRate)
                      )}
                    >
                      {percent(tool.successRate)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {/*
              残余不占第五行：四行是这张卡的高度上限，多一行就等于让表格随工具数往下长，
              首页也就重新变成要往下拉。完整二十行在弹框里，这里只回答「主要是哪几个工具」。
              这里也不再放一个「查看详情」——同一张卡开两道通往同一处的门，读者会以为是两个地方
            */}
            {otherCalls > 0 && (
              <p className="mt-2.5 text-xs leading-4 text-[#98A2B3]">
                另有 {number(otherCalls)} 次调用（占 {percent(ratio(otherCalls, tools.total))}）来自未进前{" "}
                {TOOL_ROW_LIMIT} 名的工具。
              </p>
            )}
          </div>
        )}
      </Card>

      <ToolDetailDialog open={open} onOpenChange={setOpen} data={data} windowLabel={windowLabel} />
    </>
  );
}

/** 人工确认：总量与批准率打头，四个状态按点色区分，完整口径走弹框。 */
export function AgentConfirmations({ data, windowLabel, className }: CardProps) {
  const [open, setOpen] = useState(false);
  const { confirmations: confirm, replies } = data;

  if (confirm.total === 0) {
    return (
      <Card className={cn("flex flex-col p-[18px]", className)} title="人工确认">
        <Empty>
          {replies.withBlocks === 0 ? "暂无可统计的确认轨迹。" : "当前统计范围内没有确认卡片。"}
        </Empty>
      </Card>
    );
  }

  /*
   * 只给状态点上色、数字与文字一律留深色：这四格是同一个总量的四个去向，
   * 真正要区分的是「哪一格」而不是「哪一格更重要」。数字也跟着染色的话，
   * 一眼扫过去会读成红的那格出了问题，而「已拒绝 7」本身可能正是该有的结果
   */
  const states = [
    { label: "已批准", value: confirm.approved, color: VIZ_COLORS.good },
    { label: "待确认", value: confirm.pending, color: VIZ_COLORS.warning },
    { label: "已拒绝", value: confirm.denied, color: VIZ_COLORS.critical },
    { label: "已过期", value: confirm.expired, color: VIZ_COLORS.neutral },
    ...(confirm.other > 0
      ? [{ label: "其他状态", value: confirm.other, color: VIZ_COLORS.neutral }]
      : [])
  ];

  return (
    <>
      <Card
        className={cn("flex flex-col p-[18px]", className)}
        title="人工确认"
        action={<DetailButton onClick={() => setOpen(true)} />}
        hint={
          <>
            按卡计数。批准率仅含已批准与已拒绝的卡片，待确认与已过期不计入分母。
            {confirm.pending > 0 &&
              `当前窗口有 ${number(confirm.pending)} 张待处理。`}
            {confirm.topTools.length > 0 &&
              "详情中按调用计数，与此处按卡计数口径不同。"}
          </>
        }
      >
        {/* 被左侧那张高卡拉高时，多出来的高度摊在两段之间，不堆在卡底 */}
        <div className="flex flex-1 flex-col justify-between gap-3">
          {/*
            环压到 64px 并且只承载批准率这一个数：做大它会成为整卡最重的一块，
            而这张卡真正的主角是下面那四格状态计数
          */}
          <div className="flex items-center gap-4">
            <Stat className="flex-1" label="确认卡" value={number(confirm.total)} unit="张" />
            <Ring value={confirm.approvalRate} label="批准率" />
          </div>

          <div className="grid grid-cols-2 gap-x-4 gap-y-0.5">
            {states.map((state) => (
              /*
                标签与数字贴着排，不用两端对齐：格子有近 200px 宽而「已批准」和「34」加起来不到 80px，
                推到两端会在中间留一段空白，读者得横跨这段空白才能把数字认回它的标签。
                标签宽度定死，四格的数字仍然各自成列对得齐
              */
              <div key={state.label} className="flex items-center gap-2 py-0.5">
                <span className="flex w-16 shrink-0 items-center gap-1.5 text-xs text-[#667085]">
                  <span
                    className="h-1.5 w-1.5 shrink-0 rounded-full"
                    style={{ backgroundColor: state.color }}
                    aria-hidden="true"
                  />
                  <span className="truncate">{state.label}</span>
                </span>
                <span className="text-sm font-semibold tabular-nums text-[#101828]">
                  {number(state.value)}
                </span>
              </div>
            ))}
          </div>

          {/*
            按工具拆分的那两行挪进了弹框：这张卡是右列的下半段，它和记忆卡的高度之和
            决定第三层有多高，而这两行回答的「主要是哪几件事在等人点头」属于明细而不是摘要。
            摘要是上面那两块——一共多少张、批了多少、四个去向各多少
          */}
        </div>
      </Card>

      <ConfirmDetailDialog open={open} onOpenChange={setOpen} data={data} windowLabel={windowLabel} />
    </>
  );
}

/** 记忆与上下文：三个读数 + 记忆增减 + 压缩前后对比条，完整口径走弹框。 */
export function AgentMemoryContext({ data, windowLabel, className }: CardProps) {
  const [open, setOpen] = useState(false);
  const { memory } = data;
  const before = memory.contextCharsBefore;
  const after = memory.contextCharsAfter;
  // 净增长是上面两个数的差，不是另测的量：写出来只是省掉读者自己减一次
  const net = memory.addedMemories - memory.invalidatedMemories;
  /*
   * 求平均只能用 compactionsWithChars 当分母：字符数那几列只有这批事件记了，
   * 拿 compactions 会把没记字符数的那几次也摊进去，把平均值摊小
   */
  const sampled = memory.compactionsWithChars;

  return (
    <>
      <Card
        className={cn("flex flex-col p-[18px]", className)}
        title="记忆与上下文"
        action={<DetailButton onClick={() => setOpen(true)} />}
        hint={
          <>
            缩减比例按字符数计算，不等于 Token 节省率。字符统计仅覆盖记录了前后值的{" "}
            {number(sampled)} 次压缩。长期记忆存量截至统计时刻，净增长 = 新增 − 失效。
          </>
        }
      >
        {/* 被邻卡拉高时多出来的高度摊在两段之间，不堆在卡底 */}
        <div className="flex flex-1 flex-col justify-between gap-3">
        <div>
          <div className="grid grid-cols-3 gap-x-3">
            <Stat label="上下文压缩" value={number(memory.compactions)} unit="次" />
            <Stat label="上下文缩减" value={percentStat(memory.contextReductionPct)} />
            <Stat label="有效长期记忆" value={number(memory.activeMemories)} unit="条" />
          </div>

          {/* 这行是上一格「有效长期记忆」的增减来源，贴着它走，与下面的压缩条分属两件事 */}
          <p className="mt-2 text-xs tabular-nums text-[#98A2B3]">
            新增 {number(memory.addedMemories)} · 失效 {number(memory.invalidatedMemories)} · 净增长{" "}
            {net > 0 ? "+" : ""}
            {number(net)}
          </p>
        </div>

        {before > 0 ? (
          // 「压缩前」定为满槽，「压缩后」按同一分母收缩：两条条长之比就是缩减比例
          <div>
            <div className="flex items-center gap-3">
              <div className="min-w-0 flex-1 space-y-2">
                {[
                  { label: "压缩前", value: before, width: 100, color: VIZ_COLORS.reference },
                  {
                    label: "压缩后",
                    value: after,
                    width: (after / before) * 100,
                    color: VIZ_COLORS.accent
                  }
                ].map((bar) => (
                  /* 标签、条、读数并成一行：分成上下两层时两条对比条被读数隔开，量级差反而不好比 */
                  <div key={bar.label} className="flex items-center gap-2.5">
                    <span className="w-10 shrink-0 text-xs text-[#667085]">{bar.label}</span>
                    <span className="min-w-0 flex-1">
                      <Meter value={bar.width} color={bar.color} />
                    </span>
                    {/*
                      定宽 80 且不许换行：读数最长是「999.9M 字符」＝71px，原来的 64 会让它折成两行，
                      两条条各多一行、整卡多 32px，而这张卡的高度直接进第三层。
                      宽出来的 16px 从中间那条比例条身上出，比例条只读长短，短一档不影响
                    */}
                    <span className="w-20 shrink-0 whitespace-nowrap text-right text-xs tabular-nums text-[#101828]">
                      {compact(bar.value)}
                      <span className="ml-1 font-normal text-[#98A2B3]">字符</span>
                    </span>
                  </div>
                ))}
              </div>
              {/* 缩减比例贴在两条条的右侧、跨两行居中：它说的是这两条之间的关系，不属于任何一行 */}
              {memory.contextReductionPct !== null && (
                <span className="shrink-0 text-sm font-semibold tabular-nums text-[#101828]">
                  ↓ {memory.contextReductionPct.toFixed(1)}%
                </span>
              )}
            </div>
            {/*
              折回单次的「平均每次 A → B」留在弹框里：它要跟着「分母是 N 次有字符记录的压缩」
              一起读才不会被当成上面那个「压缩 N 次」，两句话在首页占两行，在弹框里本来就有
            */}
          </div>
        ) : (
          /*
           * 没有字符数时整块消失会读成"这张卡到这就画完了"，而实际是两种完全不同的情况：
           * 一次都没压缩，和压过但没记字符数。两句话分开写，不合并成一句含糊的"暂无数据"
           */
          <p className="text-xs text-[#98A2B3]">
            {memory.compactions === 0
              ? "本窗口未触发上下文压缩，因此没有压缩前后字符数。"
              : `本窗口 ${number(memory.compactions)} 次压缩均未记录压缩前后字符数。`}
          </p>
        )}
        </div>
      </Card>

      <MemoryDetailDialog open={open} onOpenChange={setOpen} data={data} windowLabel={windowLabel} />
    </>
  );
}
