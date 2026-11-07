/**
 * 两个引擎分支共用的取数与读数件：页头、KPI 卡、流量卡。
 * 这里只收"同一份接口数据、同一种读法"的东西，排布一律不进——
 * Agent 与 Workflow 的栅格是两套，共用一条排布规则就会互相牵动
 */
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode
} from "react";
import { MessageSquare, RefreshCw, TrendingDown, TrendingUp, Users } from "lucide-react";

import { CardHead, DashCard, Hint } from "@/components/admin/DashboardCard";
import { ChartLegend, SimpleLineChart, type TrendSeries } from "@/components/admin/SimpleLineChart";
import { VIZ_SURFACE } from "@/components/admin/vizTokens";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import {
  getDashboardOverview,
  getDashboardPerformance,
  getDashboardTrends,
  type DashboardOverview,
  type DashboardPerformance,
  type DashboardTrends
} from "@/services/dashboardService";
import type { EngineType } from "@/stores/engineStore";


// ==========================================================================
// Types
// ==========================================================================

export type DashboardTimeWindow = "24h" | "7d" | "30d";

export type DashboardTrendBundle = {
  sessions: DashboardTrends | null;
  messages: DashboardTrends | null;
  activeUsers: DashboardTrends | null;
  latency: DashboardTrends | null;
  quality: DashboardTrends | null;
  tools: DashboardTrends | null;
  replies: DashboardTrends | null;
};

/** 流量卡的三个标签页共用一套渲染，差异只在取哪条趋势、配哪个 KPI。 */
type TrafficMetric = "messages" | "sessions" | "activeUsers";

type KPIChange = {
  value: number;
  trend: "up" | "down" | "flat";
};

// ==========================================================================
// Constants
// ==========================================================================

const WINDOW_OPTIONS: Array<{ value: DashboardTimeWindow; label: string }> = [
  { value: "24h", label: "24h" },
  { value: "7d", label: "7d" },
  { value: "30d", label: "30d" }
];

export const WINDOW_LABEL_MAP: Record<DashboardTimeWindow, string> = {
  "24h": "滚动 24h",
  "7d": "近 7 天",
  "30d": "近 30 天"
};

const EMPTY_TRENDS: DashboardTrendBundle = {
  sessions: null,
  messages: null,
  activeUsers: null,
  latency: null,
  quality: null,
  tools: null,
  replies: null
};

const TRAFFIC_TABS: Array<{ value: TrafficMetric; label: string }> = [
  { value: "messages", label: "消息" },
  { value: "sessions", label: "新建会话" },
  { value: "activeUsers", label: "活跃用户" }
];

// ==========================================================================
// Utils
// ==========================================================================

const formatLastUpdated = (timestamp: number | null) => {
  if (!timestamp) return "-";
  return new Date(timestamp).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  });
};

export const formatPercent = (value?: number | null) => {
  if (value === null || value === undefined) return "-";
  return `${value.toFixed(1)}%`;
};

export const formatNumber = (value?: number | null) => {
  if (value === null || value === undefined) return "-";
  return value.toLocaleString("zh-CN");
};

// ==========================================================================
// Hooks
// ==========================================================================

export const useDashboardData = () => {
  // 默认落在 7d：24h 的样本量常常小到一次异常就把成功率打穿，日粒度的七个点才看得出趋势
  const [timeWindow, setTimeWindow] = useState<DashboardTimeWindow>("7d");
  const [overview, setOverview] = useState<DashboardOverview | null>(null);
  const [performance, setPerformance] = useState<DashboardPerformance | null>(null);
  const [trends, setTrends] = useState<DashboardTrendBundle>(EMPTY_TRENDS);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);
  const [engine, setEngine] = useState<EngineType | null>(null);
  const requestIdRef = useRef(0);

  const loadData = useCallback(async (windowValue: DashboardTimeWindow) => {
    const requestId = ++requestIdRef.current;
    setLoading(true);
    setError(null);
    // 保留上一次的渲染结果：切换时间窗时整页塌成骨架会让布局跳动，加载态由整体降透明度承载。
    const granularity = windowValue === "24h" ? "hour" : "day";

    try {
      const [overviewData, performanceData] = await Promise.all([
        getDashboardOverview(windowValue),
        getDashboardPerformance(windowValue)
      ]);
      if (requestIdRef.current !== requestId) return;
      if (!(["agent", "workflow"] as string[]).includes(overviewData.engine) || performanceData.engine !== overviewData.engine) {
        throw new Error("统计接口的引擎标识不一致，请确认前后端版本一致");
      }
      setEngine(overviewData.engine);
      setOverview(overviewData);
      setPerformance(performanceData);
      setLastUpdated(overviewData.updatedAt);

      try {
        const isAgent = overviewData.engine === "agent";
        // 两个引擎都支持 sessions/messages/activeusers，流量卡的三个标签页在两边都能画
        const [sessions, messages, activeUsers, firstEngineTrend, secondEngineTrend] =
          await Promise.all([
            getDashboardTrends("sessions", windowValue, granularity),
            getDashboardTrends("messages", windowValue, granularity),
            getDashboardTrends("activeusers", windowValue, granularity),
            getDashboardTrends(isAgent ? "tools" : "avgLatency", windowValue, granularity),
            getDashboardTrends(isAgent ? "replies" : "quality", windowValue, granularity)
          ]);
        if (requestIdRef.current !== requestId) return;
        setTrends({ sessions, messages, activeUsers,
          latency: isAgent ? null : firstEngineTrend, quality: isAgent ? null : secondEngineTrend,
          tools: isAgent ? firstEngineTrend : null, replies: isAgent ? secondEngineTrend : null });
      } catch (trendErr) {
        if (requestIdRef.current !== requestId) return;
        console.error(trendErr);
        setTrends(EMPTY_TRENDS);
        setError("趋势数据加载失败");
      }
    } catch (err) {
      if (requestIdRef.current !== requestId) return;
      console.error(err);
      setError(err instanceof Error ? err.message : "数据加载失败");
    } finally {
      if (requestIdRef.current === requestId) {
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    void loadData(timeWindow);
    return () => { requestIdRef.current += 1; };
  }, [loadData, timeWindow]);

  const refresh = useCallback(async () => {
    await loadData(timeWindow);
  }, [loadData, timeWindow]);

  return {
    engine,
    timeWindow,
    setTimeWindow,
    loading,
    error,
    lastUpdated,
    overview,
    performance,
    trends,
    refresh
  };
};

// ==========================================================================
// Base Components
// ==========================================================================

export const LoadingBlock = ({ className }: { className?: string }) => (
    <div className={cn("motion-safe:animate-pulse rounded-lg bg-[#F1F3F7]", className)} />
);

/**
 * 分段器：选中态用主色浅底而不是深底反白。
 * 深底那一格在浅色页面里是全页最重的一块，而它只是个筛选器，不该比它筛出来的数字更响
 */
const SEGMENT_TRACK = "inline-flex rounded-lg bg-[#F2F4F7] p-0.5";
const segmentItem = (active: boolean) =>
    cn(
        "rounded-[7px] px-2.5 py-1 text-xs font-medium transition-colors",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#4F6EF7]",
        active ? "bg-[#EEF2FF] text-[#4F6EF7]" : "text-[#667085] hover:text-[#101828]"
    );

// ==========================================================================
// Header
// ==========================================================================

export const DashboardHeader = ({
                           engine,
                           timeWindow,
                           lastUpdated,
                           loading,
                           onRefresh,
                           onTimeWindowChange
                         }: {
  engine: EngineType | null;
  timeWindow: DashboardTimeWindow;
  lastUpdated: number | null;
  loading?: boolean;
  onRefresh: () => void;
  onTimeWindowChange: (window: DashboardTimeWindow) => void;
}) => (
    <header className="flex flex-wrap items-start justify-between gap-x-3 gap-y-2">
      {/*
        标题、引擎标签共占一行，不设副标题：「了解 X 使用情况、执行轨迹与需要关注的问题」这类句子
        没有一个字是读者看完能拿去做事的，它只是把标题换个说法再讲一遍，而它连着行距要吃掉 24px——
        这一页要在不下拉的前提下放下四层，这 24px 是全页最先该让出来的。
        面包屑同理不恢复：它只是把左侧导航已选中的那一项又念一遍
      */}
      <div className="min-w-0">
        <div className="flex min-w-0 flex-wrap items-center gap-x-2.5 gap-y-1">
          <h1 className="text-[22px] font-[650] leading-7 tracking-[-0.01em] text-[#101828]">
            {engine === "workflow" ? "Workflow 运行概览" : engine === "agent" ? "Agent 运行概览" : "运行概览"}
          </h1>
          {/* 标签只说"这些数字是谁的"，不带状态语义，所以走主色浅底而不是任何一档状态色 */}
          <span className="rounded-md bg-[#EEF2FF] px-2 py-0.5 text-xs font-medium leading-5 text-[#405FE8]">
            {engine ? (engine === "agent" ? "Agent · 自主执行" : "Workflow · RAG 编排") : loading ? "正在读取引擎" : "引擎信息不可用"}
          </span>
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <div className={SEGMENT_TRACK} role="group" aria-label="统计时间范围">
          {WINDOW_OPTIONS.map((opt) => (
              <button
                  key={opt.value}
                  onClick={() => onTimeWindowChange(opt.value)}
                  disabled={loading}
                  aria-pressed={timeWindow === opt.value}
                  className={segmentItem(timeWindow === opt.value)}
              >
                {opt.label}
              </button>
          ))}
        </div>

        {/*
          归组与刷新两段口径挂在"数据更新于"旁边而不是各卡上：它们限定的是这一整页数字的时间坐标，
          挂到某一张卡上等于宣称只有那张卡按这个规则归组
        */}
        <span className="flex items-center gap-1.5 text-xs text-[#98A2B3]">
          数据更新于 {formatLastUpdated(lastUpdated)}
          <Hint>
            按回复创建时间归组，状态随后续处理更新。缓存最长 30 秒。暂无精确执行耗时指标。
          </Hint>
        </span>

        <Button
            variant="outline"
            size="icon"
            onClick={onRefresh}
            disabled={loading}
            aria-label="刷新统计"
            className="h-8 w-8 rounded-lg border-[#EAECF0] bg-white text-[#667085] hover:border-[#D9DDE7] hover:text-[#101828]"
        >
          <RefreshCw className={cn("h-4 w-4", loading && "motion-safe:animate-spin")} />
        </Button>
      </div>
    </header>
);

// ==========================================================================
// KPI Cards
// ==========================================================================

export type KPICardProps = {
  /** null 表示这一格算不出来（缺分母等），渲染成灰破折号而不是 0 */
  value: string | null;
  label: string;
  change?: KPIChange;
  /** 该指标"向上是不是好事"。涨跌配色 = 变化方向 × 这个值，不能由 delta 自身的符号推导。 */
  upIsGood: boolean;
  icon: ReactNode;
  /** 末行右侧的从属读数，补一个环比说不出来的绝对量；没有对比期时它独占末行 */
  sub?: string;
  /**
   * 图标片默认全蓝，只有真正带"成功"语义的那一格才允许转绿。
   * 一格一色时四个色相互相争，读者会以为蓝色那格和琥珀色那格分属两类东西
   */
  chipTone?: "accent" | "good";
};

const KPICardItem = ({ value, label, change, upIsGood, icon, sub, chipTone = "accent" }: KPICardProps) => {
  const showChange = change !== undefined && change.trend !== "flat";
  const isUp = change?.trend === "up";
  /*
   * 涨跌用的绿/红比状态色深一档：状态色（#12B76A / #F04438）是给条与点定的，
   * 对白底只有 2.6 / 3.8，落到 12px 文字上过不了 4.5 的门。色相一致、明度压深，语义不变
   */
  const changeColor = isUp === upIsGood ? "text-emerald-700" : "text-red-600";

  /*
   * 图标独占左列，标题、数字、涨跌共用右列的同一条左边界。
   * 图标压在标题行里、数字却退回卡片左边缘时，一张卡内会出现两条起始线，
   * 四格并排更明显——这是"图标行下面直接放数字"读起来不协调的根。
   *
   * 高度下限 108、内边距 16、数字行高 32：这一层在页面上只出现一次，
   * 省下的 12px 是整页四层里最便宜的一刀——三行内容（标题 / 数字 / 环比）一行没少，字号也没动
   */
  return (
      <DashCard className="min-h-[108px] p-4">
        <div className="flex gap-3">
          <span
              className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-[10px]"
              style={{
                backgroundColor: chipTone === "good" ? VIZ_SURFACE.goodSoft : VIZ_SURFACE.accentSoft,
                color: chipTone === "good" ? "#12B76A" : "#4F6EF7"
              }}
          >
            {icon}
          </span>
          <div className="min-w-0 flex-1">
            <p className="truncate text-[13px] leading-5 text-[#667085]">{label}</p>
            {/*
              从属读数贴着数字收尾而不是挤进末行：它补的是这个数字的绝对量，挨着数字才读得成一句。
              末行于是只剩环比，四格都是"标题 / 数字 / 环比"三行。
              数字位数多到与它并排放不下时整行换行（四格同步长高），不截半个词
            */}
            <div className="mt-1 flex flex-wrap items-baseline justify-between gap-x-2">
              <p className="text-[30px] font-semibold leading-8 tracking-[-0.02em] text-[#101828]">
                {value === null ? <span className="text-[#B0B8C4]">—</span> : value}
              </p>
              {sub && <span className="min-w-0 truncate text-xs text-[#98A2B3]">{sub}</span>}
            </div>
            {/* 没有对比期时这行空着不塌：四格的底边由它对齐 */}
            <div className="mt-1 flex min-h-4 items-center text-xs">
              {showChange && (
                  <span className="flex shrink-0 items-center gap-1">
                    {isUp ? (
                        <TrendingUp className={cn("h-3.5 w-3.5", changeColor)} />
                    ) : (
                        <TrendingDown className={cn("h-3.5 w-3.5", changeColor)} />
                    )}
                    <span className={cn("font-medium tabular-nums", changeColor)}>
                      {change.value > 0 ? "+" : ""}
                      {change.value.toFixed(1)}%
                    </span>
                    <span className="text-[#98A2B3]">较上周期</span>
                  </span>
              )}
            </div>
          </div>
        </div>
      </DashCard>
  );
};

export const toChange = (deltaPct?: number | null): KPIChange | undefined => {
  if (deltaPct === null || deltaPct === undefined) return undefined;
  if (deltaPct > 0) return { value: deltaPct, trend: "up" };
  if (deltaPct < 0) return { value: deltaPct, trend: "down" };
  return { value: 0, trend: "flat" };
};

/**
 * KPI 带只负责"几格并排"。哪四格由分支自己列：前两格两个引擎读的是同一批数，
 * 后两格分别是各自的主体与成功率，塞进同一个组件里用 null 区分等于把两套口径缝在一起
 */
export const KpiBand = ({ items, className }: { items: KPICardProps[]; className?: string }) => (
    <div className={cn("grid gap-4", className)}>
      {items.map((item) => (
          <KPICardItem key={item.label} {...item} />
      ))}
    </div>
);

/**
 * 活跃用户与活跃会话：两个引擎读的是同一张表的同一列，口径也是同一句话，
 * 所以由这里给出，分支只在后面接自己那两格。
 * 消息数与新建会话不占格——它们是流量卡的标签页，在那里连带趋势一起读
 */
export const baseKpiItems = (overview: DashboardOverview | null): KPICardProps[] => {
  const kpis = overview?.kpis;
  return [
    {
      value: formatNumber(kpis?.activeUsers.value),
      label: "活跃用户",
      change: toChange(kpis?.activeUsers.deltaPct),
      upIsGood: true,
      icon: <Users className="h-[18px] w-[18px]" />,
      sub: `累计 ${formatNumber(kpis?.totalUsers.value)} 人`
    },
    {
      value: formatNumber(kpis?.activeSessions.value),
      label: "活跃会话",
      change: toChange(kpis?.activeSessions.deltaPct),
      upIsGood: true,
      icon: <MessageSquare className="h-[18px] w-[18px]" />,
      sub: `新建 ${formatNumber(kpis?.sessions24h.value)} 个`
    }
  ];
};

// ==========================================================================
// Traffic Overview Section
// ==========================================================================

/**
 * 序列 → 图表色调。「上周期」在全页只认一个 tone，具体画成底槽还是折线由图表按横轴坐标制自己定：
 * 按桶计数的流量卡给底槽，逐时刻取值的响应时间给折线。这里只管别让它跟主序列同色。
 * 空序列直接丢掉：它画不出东西，却会在图例里留一个说不出所以然的条目。
 * currentKind 只作用在当前周期上：参照物无论主序列画成什么，都还是那件压低了的背景
 */
export const mapSeries = (
    trend: DashboardTrends | null,
    tone: TrendSeries["tone"],
    currentKind: TrendSeries["kind"] = "line"
): TrendSeries[] =>
    (trend?.series ?? [])
        .filter((item) => item.data.length > 0)
        .map((item) =>
            item.name === "上周期"
                ? { name: item.name, data: item.data, tone: "reference" as const }
                : { name: item.name, data: item.data, tone, kind: currentKind }
        );

/**
 * 消息流量趋势：全页唯一的主图，三个标签页换的是同一张图的度量。
 * Agent 分支下发「当前周期 + 上周期」两条序列，Workflow 只有一条，两种形状都要能画。
 */
export const TrafficCard = ({
                       trends,
                       overview,
                       timeWindow,
                       loading,
                       plotFill,
                       className
                     }: {
  trends: DashboardTrendBundle;
  overview: DashboardOverview | null;
  timeWindow: DashboardTimeWindow;
  loading?: boolean;
  /** 图区吃掉卡内剩余高，只有 Agent 分支给：那边这张卡就是分栏的高度本身 */
  plotFill?: boolean;
  className?: string;
}) => {
  const [metric, setMetric] = useState<TrafficMetric>("messages");
  const kpis = overview?.kpis;

  const current = {
    messages: { trend: trends.messages, kpi: kpis?.messages24h, unit: "条", caption: "当前消息总数" },
    sessions: { trend: trends.sessions, kpi: kpis?.sessions24h, unit: "个", caption: "当前新建会话数" },
    activeUsers: { trend: trends.activeUsers, kpi: kpis?.activeUsers, unit: "人", caption: "当前活跃用户数" }
  }[metric];

  /*
   * 当前周期画柱、上周期仍是线：这三个度量（消息数/新建会话数/活跃用户数）本来就是按桶计数的，
   * 一根柱就是那一天（或那一小时）发生的量，高度可以直接互相比长短；折线在两点之间画出的那段斜坡
   * 则暗示中间有过渡值，而这里并没有。参照物留在线上，两者形状不同，叠在一起也不会看成同一族
   */
  const series = useMemo(() => mapSeries(current.trend, "primary", "bar"), [current.trend]);

  const change = toChange(current.kpi?.deltaPct);

  /*
   * 三种状态（加载中 / 无数据 / 有图）共用这一个渲染，盒子高与是否跟着容器长由调用处给：
   * 两个分支对图高的要求不同，但"哪三种状态、各画什么"是同一件事，抄两份就会各自漂
   */
  const renderPlot = (boxClass: string, fill: boolean) =>
      loading ? (
          <LoadingBlock className={boxClass} />
      ) : series.length === 0 || current.kpi?.value === 0 ? (
          <div className={cn("flex items-center justify-center text-sm text-[#98A2B3]", boxClass)}>
            暂无流量数据
          </div>
      ) : (
          <SimpleLineChart
              series={series}
              xAxisMode={timeWindow === "24h" ? "hour" : "date"}
              height={140}
              fillHeight={fill}
              theme="light"
              yAxisTickCount={4}
              showLegend={false}
          />
      );

  return (
      /*
       * 下限 268 只是空态兜底，与右邻的健康卡取同一个数；这张卡实际多高由内容决定（288），
       * 第二层的高度就是它。下限不许写到比内容还高——
       * 那时压内容是压不动布局的
       */
      <DashCard className={cn("flex min-h-[268px] flex-col", className)}>
        <CardHead
            title="消息流量趋势"
            hint="消息包含用户与助手双方记录。浅色底槽为上一周期对比，仅供参照。"
            action={
              <div className={SEGMENT_TRACK} role="group" aria-label="流量度量">
                {TRAFFIC_TABS.map((tab) => (
                    <button
                        key={tab.value}
                        onClick={() => setMetric(tab.value)}
                        aria-pressed={metric === tab.value}
                        className={segmentItem(metric === tab.value)}
                    >
                      {tab.label}
                    </button>
                ))}
              </div>
            }
        />

        {/* 标题、读数、图三段之间只留 6~8px：这一列是主图，间距一放大就把图挤出首屏 */}
        <div className="mb-2 mt-1.5 flex flex-wrap items-end justify-between gap-x-4 gap-y-1.5">
          <div className="min-w-0">
            {/*
              度量名压到数字上方单独一行：大数字自己不说自己是什么，而它跟在数字后面时，
              要和单位、环比排成一串，读者得横着扫过三段小字才知道这个数是什么
            */}
            <p className="truncate text-xs text-[#667085]">{current.caption}</p>
            <div className="mt-0.5 flex items-end gap-2">
              <span className="text-[30px] font-semibold leading-9 tracking-[-0.02em] text-[#101828]">
                {formatNumber(current.kpi?.value)}
              </span>
              <span className="pb-1.5 text-xs text-[#98A2B3]">{current.unit}</span>
              {change && change.trend !== "flat" && (
                  /* 环比给药丸底：它是这行里唯一带方向的读数，纯文字放在 30px 数字旁边会被压没 */
                  <span
                      className={cn(
                          "mb-1 inline-flex shrink-0 items-center gap-0.5 rounded-full px-1.5 py-0.5 text-xs font-medium tabular-nums",
                          change.trend === "up"
                              ? "bg-emerald-50 text-emerald-700"
                              : "bg-red-50 text-red-600"
                      )}
                  >
                    {change.trend === "up" ? (
                        <TrendingUp className="h-3 w-3" />
                    ) : (
                        <TrendingDown className="h-3 w-3" />
                    )}
                    {change.value > 0 ? "+" : ""}
                    {change.value.toFixed(1)}%
                  </span>
              )}
            </div>
          </div>
          {/*
            图例收进这一行的右端：读数行本来就有空位，而图内图例要在图上方再吃掉一行高。
            窗口与粒度不再重复——页头的分段器已经写了窗口，横轴自己就带着粒度
          */}
          <ChartLegend series={series} className="pb-1.5" />
        </div>

        {/*
          140 是"整页不下拉"给这张图的配额：上下留白吃掉 40，绘图区还剩 100，
          默认窗口 7 天只有 8 根柱，一格 80 以上，柱高差读得出来。24h 的 24 个桶在这个高度
          会比 208 时矮——那是为一屏付的价。不开面积：柱本身就是填充，两层叠着会分不清哪块色是读数。
          三种状态取同一个盒子高，卡片高度不随数据有无跳动
        */}
        {plotFill ? (
            /*
              Agent 分支：这张卡就是第二层分栏的高度本身，屏幕高过一屏时这个盒子吃掉余量
              （上限在 globals.css 的 dashboard-split--trend），140 从写死的图高退化成盒子的下限
            */
            <div className="dashboard-traffic-plot">{renderPlot("h-full", true)}</div>
        ) : (
            /*
              Workflow 分支：那一列的余量由列末的洞察卡吃，图不参与分配，
              所以这边图高照旧写死 140——主图跟着屏幕长会把下面两张趋势图挤出首屏
            */
            renderPlot("h-[140px]", false)
        )}
      </DashCard>
  );
};
