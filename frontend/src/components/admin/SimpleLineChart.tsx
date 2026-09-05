import {
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type MouseEvent as ReactMouseEvent
} from "react";

import { cn } from "@/lib/utils";

import { VIZ_COLORS } from "./vizTokens";

export type TrendPoint = {
  ts: number;
  value: number;
};

export type ChartTone =
  | "primary"
  | "secondary"
  | "success"
  | "warning"
  | "danger"
  | "neutral"
  | "reference";

export type TrendSeries = {
  name: string;
  data: TrendPoint[];
  /**
   * tone 同时决定这条序列在图上的分量：reference 一档是参照物，
   * 线更细、不铺面积、不标点、压在主序列下面。原先另有一个 lineStyle 开关也在表达同一件事，
   * 两个旋钮编码同一个意思就允许它们互相矛盾（一条 primary 的虚线是什么意思？），所以只留 tone
   */
  tone?: ChartTone;
  /**
   * 这条序列画成柱还是线。柱只给「一个桶就是一段独立时间里的量」的序列用——
   * 柱子的高度是可以互相比长短的，而折线的斜率暗示两点之间有连续变化。
   * 同一张图里只要有一条柱，横轴就从"点落在刻度上"改成"每个桶占一格"，线也跟着落到格心
   */
  kind?: "line" | "bar";
};

export type ChartYAxisType = "number" | "percent" | "duration";

export type ChartXAxisMode = "date" | "hour";

export type ChartThreshold = {
  value: number;
  label?: string;
  tone?: "warning" | "critical" | "info";
};

interface SimpleLineChartProps {
  series: TrendSeries[];
  height?: number;
  yAxisType?: ChartYAxisType;
  xAxisMode?: ChartXAxisMode;
  thresholds?: ChartThreshold[];
  theme?: "light" | "dark";
  yAxisTickCount?: number;
  /** 关掉图内图例，改由调用方用 ChartLegend 摆到自己的读数行里 */
  showLegend?: boolean;
  /**
   * 给主序列铺一层向下渐隐的面积。
   * 参照序列不受它管：折线形态下不铺（两层半透明叠着就分不出谁在上），
   * 底槽形态下的填充是这条序列的画法本身，不是这个开关加的装饰
   */
  showArea?: boolean;
}

/**
 * 参照序列（上周期）。它与当前周期的区别压在色、线宽与画法上，不用虚线：
 * 虚线是把一条连续的线切成一串短横，读者的眼睛会挨个去接那些断点，
 * 于是整条线看起来是"拼出来的"而不是"走出来的"
 */
const isReferenceSeries = (item: TrendSeries) => item.tone === "reference";

/** 参照物上沿 2px：比主序列的 2.5 细一档，主次不会反过来 */
const REFERENCE_STROKE_WIDTH = 2;

/**
 * 底槽填充。参照色对白底本来就只有 1.74:1，铺成整块再压到实色，网格线就全被吃掉了；
 * 0.45 是"看得出是一块地形、网格还透得过来"的那一档，读数仍由上沿那条线给
 */
const TROUGH_FILL_OPACITY = 0.45;

/** 非 0 的桶保底画出这么高的柱：1.5px 在 1x 屏上还剩得下一道看得见的边 */
const MIN_BAR_HEIGHT = 1.5;

/**
 * 参照物画成底槽还是折线，跟着横轴的坐标制走，不另开开关：
 * 底槽和阶梯都以"一个读数占满一格"为前提，而这个前提正是有柱时横轴的语义。
 * 点制坐标下（响应时间那类逐时刻的量）没有"一格"可言，参照物就还是一条线。
 * 图与图例共用这一个判据，否则两处会各自画成一种样子
 */
const hasBarSeries = (series: TrendSeries[]) => series.some((item) => item.kind === "bar");

/** reference 不进轮转：它是刻意压低的参照色，只能显式指定，不能被自动分配到某条主序列上。 */
const FALLBACK_TONES: ChartTone[] = ["primary", "success", "warning", "danger", "secondary", "neutral"];

const CHART_COLOR_VARS: CSSProperties = {
  ["--chart-primary" as string]: VIZ_COLORS.accent,
  ["--chart-secondary" as string]: VIZ_COLORS.accentAlt,
  ["--chart-success" as string]: VIZ_COLORS.good,
  ["--chart-warning" as string]: VIZ_COLORS.warning,
  ["--chart-danger" as string]: VIZ_COLORS.critical,
  ["--chart-neutral" as string]: VIZ_COLORS.neutral,
  ["--chart-reference" as string]: VIZ_COLORS.reference
};

const TONE_STROKE: Record<ChartTone, string> = {
  primary: "var(--chart-primary)",
  secondary: "var(--chart-secondary)",
  success: "var(--chart-success)",
  warning: "var(--chart-warning)",
  danger: "var(--chart-danger)",
  neutral: "var(--chart-neutral)",
  reference: "var(--chart-reference)"
};

const CHART_THEME = {
  light: {
    grid: "#EEF0F4",
    axis: "#E4E7EC",
    label: "#98A2B3",
    legend: "#667085",
    hoverLine: "#98A2B3",
    pointStroke: "#ffffff",
    tooltipBg: "rgba(255,255,255,0.97)",
    tooltipBorder: "#EAECF0",
    tooltipText: "#101828",
    tooltipSecondary: "#667085"
  },
  dark: {
    grid: "rgba(148,163,184,0.12)",
    axis: "rgba(148,163,184,0.35)",
    label: "#64748b",
    legend: "#94a3b8",
    hoverLine: "rgba(148,163,184,0.45)",
    pointStroke: "#0f172a",
    tooltipBg: "rgba(15,23,42,0.95)",
    tooltipBorder: "rgba(71,85,105,0.65)",
    tooltipText: "#e2e8f0",
    tooltipSecondary: "#94a3b8"
  }
} as const;

const DEFAULT_HEIGHT = 220;

/**
 * 图例单拎出来，是为了让调用方能把它摆进自己的读数行——那一行本来就有空位，
 * 而图内图例会在图上方再占一行高。样片照抄图上的画法（柱给柱、线给线、粗细也照搬），
 * 否则「当前周期」和「上周期」在图例上长得一模一样。
 * CHART_COLOR_VARS 得跟着一起挂：色值是 var(--chart-*)，离开图表容器就没人定义了。
 */
export function ChartLegend({
  series,
  className,
  style
}: {
  series: TrendSeries[];
  className?: string;
  style?: CSSProperties;
}) {
  if (series.length < 2) return null;
  return (
    <div
      className={cn("flex flex-wrap items-center gap-3 text-xs", className)}
      style={{ ...CHART_COLOR_VARS, color: CHART_THEME.light.legend, ...style }}
    >
      {series.map((item) => {
        const color = TONE_STROKE[item.tone || "primary"];
        const reference = isReferenceSeries(item);
        if (reference && hasBarSeries(series)) {
          return (
            <div key={item.name} className="inline-flex items-center gap-1.5">
              {/* 底槽的样片也是底槽：给它一条线的话，读者会去图上找一条并不存在的线 */}
              <svg width="18" height="10" viewBox="0 0 18 10" aria-hidden className="shrink-0">
                <rect x="0" y="3" width="18" height="7" fill={color} opacity={TROUGH_FILL_OPACITY} />
                <line x1="0" y1="4" x2="18" y2="4" stroke={color} strokeWidth={REFERENCE_STROKE_WIDTH} />
              </svg>
              <span>{item.name}</span>
            </div>
          );
        }
        if (item.kind === "bar") {
          return (
            <div key={item.name} className="inline-flex items-center gap-1.5">
              {/* 柱序列在图例里也得是柱：给它一条线的话，读者会去图上找一条并不存在的线 */}
              <svg width="18" height="10" viewBox="0 0 18 10" aria-hidden className="shrink-0">
                <rect x="1" y="3" width="4" height="7" rx="1" fill={color} />
                <rect x="7" y="0.5" width="4" height="9.5" rx="1" fill={color} />
                <rect x="13" y="4.5" width="4" height="5.5" rx="1" fill={color} />
              </svg>
              <span>{item.name}</span>
            </div>
          );
        }
        return (
          <div key={item.name} className="inline-flex items-center gap-1.5">
            {/* 图例照抄图上那条线的画法：线宽与标记一起给，参照线在图上不标点，图例里也就不给点 */}
            <svg width="18" height="10" viewBox="0 0 18 10" aria-hidden className="shrink-0">
              <line
                x1="0"
                y1="5"
                x2="18"
                y2="5"
                stroke={color}
                strokeWidth={reference ? REFERENCE_STROKE_WIDTH : 2.5}
                strokeLinecap="round"
              />
              {reference ? null : (
                <circle
                  cx="9"
                  cy="5"
                  r="3"
                  fill={color}
                  stroke={CHART_THEME.light.pointStroke}
                  strokeWidth={1.5}
                />
              )}
            </svg>
            <span>{item.name}</span>
          </div>
        );
      })}
    </div>
  );
}

/*
 * 千位只加分隔号不缩写：读者要拿 y 轴刻度去比对折线上的点，而 1.5K 把 1500 和 1549 印成同一个数。
 * 到百万才转 M——「1,250,000」比刻度栏还宽
 */
const formatCompactNumber = (value: number) => {
  if (Math.abs(value) >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (Math.abs(value) >= 1_000) return Math.round(value).toLocaleString("en-US");
  return `${Math.round(value * 10) / 10}`;
};

const formatDuration = (ms: number) => {
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  const minutes = Math.floor(ms / 60_000);
  const seconds = ((ms % 60_000) / 1000).toFixed(1);
  return `${minutes}m${seconds}s`;
};

const formatYAxisValue = (value: number, yAxisType: ChartYAxisType) => {
  if (yAxisType === "percent") {
    return `${(Math.round(value * 10) / 10).toFixed(1)}%`;
  }
  if (yAxisType === "duration") {
    return formatDuration(value);
  }
  return formatCompactNumber(value);
};

const formatXAxisValue = (ts: number, mode: ChartXAxisMode, includeDate = false) => {
  const date = new Date(ts);
  if (mode === "hour") {
    if (includeDate) {
      return date.toLocaleString("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false
      });
    }
    return date.toLocaleTimeString("zh-CN", {
      hour: "2-digit",
      minute: "2-digit",
      hour12: false
    });
  }
  return date.toLocaleDateString("zh-CN", {
    month: "2-digit",
    day: "2-digit"
  });
};

const formatTooltipTime = (ts: number, mode: ChartXAxisMode) => {
  const date = new Date(ts);
  if (mode === "hour") {
    return date.toLocaleString("zh-CN", {
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hour12: false
    });
  }
  return date.toLocaleDateString("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit"
  });
};

const getNiceStep = (roughStep: number) => {
  if (!Number.isFinite(roughStep) || roughStep <= 0) return 1;
  const magnitude = 10 ** Math.floor(Math.log10(roughStep));
  const normalized = roughStep / magnitude;
  if (normalized <= 1) return magnitude;
  if (normalized <= 2) return 2 * magnitude;
  if (normalized <= 5) return 5 * magnitude;
  return 10 * magnitude;
};

type PlotPoint = {
  x: number;
  y: number;
  ts: number;
  value: number;
};

/**
 * 平滑折线。切线取中心差分（Catmull-Rom），控制点夹两道，落成三次贝塞尔。
 *
 * 要紧的是「夹在哪一层」。原先是逐段夹（Fritsch-Carlson 那套单调性保形）：
 * 一个点只要比两侧都高或都低，它的切线就必须归零，曲线经过这个点时是水平的。
 * 而真实流量几乎每个点都是局部高低点，于是整条线成了「平一下、荡一下、再平一下」的重复贴片，
 * 每个节点顶着一小段平顶——这才是它看着僵硬的原因，换插值公式动不了它
 * （中心差分换调和平均实测只差 0.1px，因为归零发生在夹限那一步，不在求平均那一步）。
 *
 * 夹没有取消，只是从「一律不许越过本段」松成「最多越过本段落差的一半」：
 * 三次贝塞尔落在四个控制点的凸包内，两个端点本就是真实读数，控制点的活动范围就是曲线的活动范围。
 * 按本段自己的落差给余量有个要紧的副作用——平段的落差是 0，余量也就是 0，
 * 一段本来平着的数据不会因为隔壁有个尖峰就提前拱起来（Catmull-Rom 的老毛病），
 * 而有涨跌的那些段才拿得到余量，局部峰谷于是圆得过来。
 * 外面再套一层全序列的最高最低读数：无论怎么圆滑，曲线高不过这条线自己的最大值、
 * 也低不过最小值，柱图基线恒为 0 所以更不会拱成负数。越过局部峰的那一点点是圆滑的代价，
 * 该点的真实读数由画在上面的标记钉住
 */
const buildSmoothPath = (points: PlotPoint[]) => {
  if (points.length === 0) return "";
  if (points.length === 1) {
    const p = points[0];
    return `M${p.x.toFixed(2)} ${p.y.toFixed(2)}`;
  }

  const x = points.map((point) => point.x);
  const y = points.map((point) => point.y);
  const segmentCount = points.length - 1;
  const dx = new Array<number>(segmentCount);
  const delta = new Array<number>(segmentCount);
  for (let i = 0; i < segmentCount; i += 1) {
    const span = x[i + 1] - x[i];
    dx[i] = span;
    delta[i] = span <= 0 ? 0 : (y[i + 1] - y[i]) / span;
  }

  // 全序列的纵向边界，是控制点无论如何都出不去的那一层
  const yTop = Math.min(...y);
  const yBottom = Math.max(...y);
  /** 余量按本段自己的落差给：平段没有余量，涨跌大的段余量也大 */
  const clampY = (value: number, from: number, to: number) => {
    if (!Number.isFinite(value)) return from;
    const pad = Math.abs(to - from) / 2;
    const low = Math.max(yTop, Math.min(from, to) - pad);
    const high = Math.min(yBottom, Math.max(from, to) + pad);
    return Math.min(high, Math.max(low, value));
  };

  const slope = new Array<number>(points.length).fill(0);
  for (let i = 1; i < points.length - 1; i += 1) {
    const span = x[i + 1] - x[i - 1];
    slope[i] = span <= 0 ? 0 : (y[i + 1] - y[i - 1]) / span;
  }

  /*
   * 端点用三点外推（Fritsch-Butland）而不是直接取首段斜率：取首段斜率等于让曲线
   * 以直线出发，第一段就没有弧。外推值可能偏大，但控制点最后还要过 clampY 那一关
   */
  if (segmentCount >= 2) {
    const last = points.length - 1;
    slope[0] = (3 * delta[0] - slope[1]) / 2;
    slope[last] = (3 * delta[segmentCount - 1] - slope[last - 1]) / 2;
  } else {
    slope[0] = Number.isFinite(delta[0]) ? delta[0] : 0;
    slope[1] = slope[0];
  }

  let path = `M${x[0].toFixed(2)} ${y[0].toFixed(2)}`;
  for (let i = 0; i < segmentCount; i += 1) {
    const span = dx[i];
    if (!Number.isFinite(span) || span <= 0) {
      path += ` L${x[i + 1].toFixed(2)} ${y[i + 1].toFixed(2)}`;
      continue;
    }
    const c1x = x[i] + span / 3;
    const c1y = clampY(y[i] + (slope[i] * span) / 3, y[i], y[i + 1]);
    const c2x = x[i + 1] - span / 3;
    const c2y = clampY(y[i + 1] - (slope[i + 1] * span) / 3, y[i], y[i + 1]);
    path += ` C${c1x.toFixed(2)} ${c1y.toFixed(2)} ${c2x.toFixed(2)} ${c2y.toFixed(2)} ${x[i + 1].toFixed(2)} ${y[i + 1].toFixed(2)}`;
  }
  return path;
};

/**
 * 阶梯路径：每个读数占满自己那一格，格与格之间垂直跳变，格内是一段平的。
 *
 * 参照序列与当前周期读的是同一个按桶计数的量——一根柱就是那一天发生的量，
 * 两天之间并没有过渡值。平滑曲线却必须在两个读数之间画出一段斜坡，
 * 点稀且落差大时（0 → 497 这种阶跃）那段斜坡会鼓成一个谁都没测过的包，
 * 于是整条线读起来像一条波浪而不像数据。阶梯不插值，画出来的每一段都是真读数。
 *
 * 相邻两格的边界重合（右边缘 = 下一格左边缘），垂直段是接上去时自然出现的，不用另画。
 * 缺数据的区间由外面的 segments 切开，各段独立成路径，不会跨着缺口连成一条
 */
const buildStepPath = (points: PlotPoint[], halfBand: number) => {
  if (points.length === 0) return "";
  const commands: string[] = [];
  points.forEach((point, index) => {
    const left = (point.x - halfBand).toFixed(2);
    const right = (point.x + halfBand).toFixed(2);
    const y = point.y.toFixed(2);
    commands.push(`${index === 0 ? "M" : "L"}${left} ${y}`, `L${right} ${y}`);
  });
  return commands.join(" ");
};

/** 阶梯落到基线闭合成底槽。沿用上沿那条路径，另算一条会让填充边缘与上沿脱开一两像素 */
const buildStepAreaPath = (points: PlotPoint[], halfBand: number, baseline: number) => {
  if (points.length === 0) return "";
  const left = (points[0].x - halfBand).toFixed(2);
  const right = (points[points.length - 1].x + halfBand).toFixed(2);
  return `${buildStepPath(points, halfBand)} L${right} ${baseline.toFixed(2)} L${left} ${baseline.toFixed(2)} Z`;
};

/**
 * 只圆上面两角的柱。整根 rect 加 rx 时底边也会跟着圆，柱脚就与基线脱开一道弯，
 * 四根柱并排时那四道弯连成一排小括号，比柱子本身更抢眼。
 * 圆角还要同时被半宽和柱高夹住：矮柱（值接近 0）给 3px 圆角会把柱子画成一颗药丸
 */
const buildBarPath = (x: number, y: number, width: number, barHeight: number, corner: number) => {
  const r = Math.max(0, Math.min(corner, width / 2, barHeight));
  const bottom = y + barHeight;
  return [
    `M${x.toFixed(2)} ${bottom.toFixed(2)}`,
    `L${x.toFixed(2)} ${(y + r).toFixed(2)}`,
    `Q${x.toFixed(2)} ${y.toFixed(2)} ${(x + r).toFixed(2)} ${y.toFixed(2)}`,
    `L${(x + width - r).toFixed(2)} ${y.toFixed(2)}`,
    `Q${(x + width).toFixed(2)} ${y.toFixed(2)} ${(x + width).toFixed(2)} ${(y + r).toFixed(2)}`,
    `L${(x + width).toFixed(2)} ${bottom.toFixed(2)}`,
    "Z"
  ].join(" ");
};

const getThresholdToneColor = (tone?: ChartThreshold["tone"]) => {
  if (tone === "critical") return VIZ_COLORS.critical;
  if (tone === "warning") return VIZ_COLORS.warning;
  return VIZ_COLORS.accent;
};

const buildYAxisTicks = (
  minValue: number,
  maxValue: number,
  yAxisType: ChartYAxisType,
  yAxisTickCount: number
) => {
  if (yAxisType === "percent") {
    const step = 100 / Math.max(yAxisTickCount, 1);
    return Array.from({ length: yAxisTickCount + 1 }, (_, index) =>
      Math.round((100 - index * step) * 10) / 10
    );
  }

  const segmentCount = Math.max(yAxisTickCount, 2);
  const range = Math.max(maxValue - minValue, 1);
  const step = getNiceStep(range / segmentCount);
  const tickMin = Math.floor(minValue / step) * step;
  const tickMax = Math.ceil(maxValue / step) * step;
  const ticks: number[] = [];
  const maxTicks = segmentCount + 2;

  for (let cursor = tickMax; cursor >= tickMin - step / 2; cursor -= step) {
    ticks.push(Number(cursor.toFixed(6)));
    if (ticks.length > maxTicks) break;
  }

  if (ticks.length < 2) {
    return [tickMax, tickMin];
  }
  return ticks;
};

export function SimpleLineChart({
  series,
  height = DEFAULT_HEIGHT,
  yAxisType = "number",
  xAxisMode = "date",
  thresholds = [],
  theme = "light",
  yAxisTickCount = 4,
  showLegend = true,
  showArea = false
}: SimpleLineChartProps) {
  // 同一页可以并排放好几张图，渐变 id 必须逐实例唯一，否则后挂载的那张会把前一张的填充抢走
  const gradientPrefix = useId().replace(/:/g, "");
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [width, setWidth] = useState(0);
  const [hoverIndex, setHoverIndex] = useState<number | null>(null);
  const [hoverPosition, setHoverPosition] = useState<{ x: number; y: number } | null>(null);

  useEffect(() => {
    const element = containerRef.current;
    if (!element) return;

    const update = () => setWidth(element.clientWidth);
    update();

    if (typeof ResizeObserver === "undefined") {
      window.addEventListener("resize", update);
      return () => window.removeEventListener("resize", update);
    }

    const observer = new ResizeObserver(() => update());
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  const normalizedSeries = useMemo(() => {
    return series.map((item, index) => ({
      ...item,
      tone: item.tone || FALLBACK_TONES[index % FALLBACK_TONES.length]
    }));
  }, [series]);

  const xValues = useMemo(() => {
    const values = new Set<number>();
    normalizedSeries.forEach((item) => {
      item.data.forEach((point) => values.add(point.ts));
    });
    return Array.from(values).sort((a, b) => a - b);
  }, [normalizedSeries]);

  const hasData = xValues.length > 0;
  const hasBars = useMemo(() => hasBarSeries(normalizedSeries), [normalizedSeries]);

  const pointMaps = useMemo(() => {
    return normalizedSeries.map((item) => {
      const map = new Map<number, number>();
      item.data.forEach((point) => map.set(point.ts, point.value));
      return map;
    });
  }, [normalizedSeries]);

  const values = useMemo(() => {
    const lineValues = pointMaps.flatMap((map) => Array.from(map.values())).filter((value) => Number.isFinite(value));
    const thresholdValues = thresholds.map((item) => item.value).filter((value) => Number.isFinite(value));
    return [...lineValues, ...thresholdValues];
  }, [pointMaps, thresholds]);

  const { minValue, maxValue } = useMemo(() => {
    if (!values.length) {
      return { minValue: 0, maxValue: yAxisType === "percent" ? 100 : 1 };
    }
    let min = Math.min(...values);
    let max = Math.max(...values);
    if (yAxisType === "percent") {
      min = Math.min(0, min);
      max = Math.max(100, max);
    }
    if (yAxisType === "duration") {
      min = Math.min(0, min);
    }
    /*
     * 有柱就必须从 0 起：柱子是拿高度当读数的，轴底截在 100 时，120 与 140 会画成 1:2 的两根柱。
     * 折线没有这个问题——它读的是走势，所以只在这一种情况下放弃"贴着数据取轴"的省空间做法
     */
    if (hasBars) {
      min = Math.min(0, min);
    }
    if (min === max) {
      max = min + 1;
    }
    return { minValue: min, maxValue: max };
  }, [hasBars, values, yAxisType]);

  const yTicks = useMemo(() => {
    return buildYAxisTicks(minValue, maxValue, yAxisType, yAxisTickCount);
  }, [maxValue, minValue, yAxisType, yAxisTickCount]);

  const yAxisTop = yTicks[0] ?? maxValue;
  const yAxisBottom = yTicks[yTicks.length - 1] ?? minValue;

  const showDateOnHourAxis = useMemo(() => {
    if (xAxisMode !== "hour" || xValues.length <= 1) return false;
    const firstDate = new Date(xValues[0]).toDateString();
    const lastDate = new Date(xValues[xValues.length - 1]).toDateString();
    const uniqueClock = new Set(
      xValues.map((ts) => {
        const date = new Date(ts);
        return `${date.getHours()}-${date.getMinutes()}`;
      })
    ).size;
    return firstDate !== lastDate && uniqueClock <= 2;
  }, [xAxisMode, xValues]);
  const noDataColor = theme === "dark" ? "#64748b" : "#94a3b8";

  if (!hasData) {
    return (
      <div className="flex h-[180px] items-center justify-center text-sm" style={{ color: noDataColor }}>
        暂无数据
      </div>
    );
  }

  const outerWidth = Math.max(width, 320);
  // 左边距按最宽的 y 刻度（percent 轴的「100.0%」，12px 下约 40px）留，再多就是白吃绘图区
  const margin = { top: 10, right: 12, bottom: 30, left: 52 };
  const innerWidth = Math.max(outerWidth - margin.left - margin.right, 1);
  const innerHeight = Math.max(height - margin.top - margin.bottom, 1);

  const xIndexMap = new Map<number, number>();
  xValues.forEach((ts, index) => xIndexMap.set(ts, index));

  /*
   * 有柱时横轴换成"每个桶占一格、点落在格心"：柱是有宽度的，让它对齐端点的话，
   * 首末两根会各有一半画到绘图区外面去。折线跟着一起落到格心——两种坐标混用，
   * 上周期那条线就会整体偏出半格，读者会以为它比当前周期早了半个桶
   */
  const band = innerWidth / Math.max(xValues.length, 1);
  const xAt = (index: number) => {
    if (hasBars) {
      return margin.left + (index + 0.5) * band;
    }
    if (xValues.length <= 1) {
      return margin.left + innerWidth / 2;
    }
    return margin.left + (index / (xValues.length - 1)) * innerWidth;
  };

  /*
   * 柱宽取格距的一半，上限 44：7 天窗口在整幅宽的卡里一格有 190 多，不封顶柱子会画成近百像素的方块，
   * 那时读者比的是色块面积不是柱高；下限 2 是 30 天窗口挤到最窄卡时还看得见一根柱
   */
  const barWidth = Math.max(2, Math.min(band * 0.5, 44));

  const yAt = (value: number) => {
    const denominator = Math.max(yAxisTop - yAxisBottom, 1);
    const ratio = (value - yAxisBottom) / denominator;
    return margin.top + (1 - ratio) * innerHeight;
  };

  // 上限 8 是给 7 天窗口留的：它一共 8 个点，上限比点数小就得跳着标，日期看起来像少了几天。
  // 但刻度数还得跟着可用宽度走，固定 8 个在小倍数图里会挤成两两一对
  const xLabelPitch = showDateOnHourAxis ? 104 : 60;
  const xTickCount = Math.max(
    2,
    Math.min(showDateOnHourAxis ? 4 : 8, xValues.length, Math.floor(innerWidth / xLabelPitch) + 1)
  );
  // 从末点向前按固定步长取：最新的点必然有标签，且刻度间距处处相等。
  // 按首末两端插值再四舍五入会得到 1、2、1、2 的错落间距，标签就会看起来两两成对。
  const xStride = Math.max(1, Math.ceil((xValues.length - 1) / Math.max(xTickCount - 1, 1)));
  const xTickIndexes: number[] = [];
  for (let index = xValues.length - 1; index >= 0; index -= xStride) {
    xTickIndexes.unshift(index);
  }

  const activeTs = hoverIndex !== null ? xValues[hoverIndex] : null;

  const onMouseMove = (event: ReactMouseEvent<SVGRectElement>) => {
    const svgRect = event.currentTarget.ownerSVGElement?.getBoundingClientRect();
    if (!svgRect) return;

    const x = event.clientX - svgRect.left;
    const clampedX = Math.min(Math.max(x, margin.left), margin.left + innerWidth);
    const ratio = innerWidth <= 1 ? 0 : (clampedX - margin.left) / innerWidth;
    // 格心坐标下取"落在第几格"，不取"离哪个格心近"：两者在格心制下等价，但前者在最右边缘不会溢出一格
    const index = hasBars
      ? Math.min(xValues.length - 1, Math.floor(ratio * xValues.length))
      : Math.round(ratio * (xValues.length - 1));

    setHoverIndex(index);
    setHoverPosition({ x: clampedX, y: event.clientY - svgRect.top });
  };

  const onMouseLeave = () => {
    setHoverIndex(null);
    setHoverPosition(null);
  };

  const tooltipWidth = 190;
  const tooltipLeft = hoverPosition
    ? Math.min(Math.max(8, hoverPosition.x + 12), outerWidth - tooltipWidth - 8)
    : 0;
  const tooltipTop = hoverPosition ? Math.max(8, hoverPosition.y - 12) : 0;
  const palette = CHART_THEME[theme];

  const plotBottom = margin.top + innerHeight;

  const seriesGeometry = normalizedSeries.map((item, seriesIndex) => {
    const map = pointMaps[seriesIndex];
    const segments: PlotPoint[][] = [];
    let currentSegment: PlotPoint[] = [];

    xValues.forEach((ts) => {
      const value = map.get(ts);
      if (value === undefined || value === null || Number.isNaN(value)) {
        if (currentSegment.length > 0) {
          segments.push(currentSegment);
          currentSegment = [];
        }
        return;
      }
      const xIndex = xIndexMap.get(ts) || 0;
      currentSegment.push({
        x: xAt(xIndex),
        y: yAt(value),
        ts,
        value
      });
    });

    if (currentSegment.length > 0) {
      segments.push(currentSegment);
    }

    const asTrough = hasBars && isReferenceSeries(item);

    const linePath = segments
      .map((segment) => (asTrough ? buildStepPath(segment, band / 2) : buildSmoothPath(segment)))
      .filter(Boolean)
      .join(" ");
    const lastSegment = segments[segments.length - 1];
    const endpoint = lastSegment ? lastSegment[lastSegment.length - 1] : null;

    /*
     * 面积沿用线自己那条路径，再垂直落到基线闭合：另算一条会让填充边缘与线脱开一两像素。
     * 断点处各段独立闭合，缺数据的区间不会被填成"有值"。
     * 平滑面积要两个点才有一段曲线可闭合，底槽的一个点就是一格宽的一块，所以只有前者要滤
     */
    const areaPath = asTrough
      ? segments
          .map((segment) => buildStepAreaPath(segment, band / 2, plotBottom))
          .filter(Boolean)
          .join(" ")
      : segments
          .filter((segment) => segment.length > 1)
          .map((segment) => {
            const first = segment[0];
            const last = segment[segment.length - 1];
            const baseline = plotBottom.toFixed(2);
            return `${buildSmoothPath(segment)} L${last.x.toFixed(2)} ${baseline} L${first.x.toFixed(2)} ${baseline} Z`;
          })
          .join(" ");

    return { linePath, areaPath, endpoint, points: segments.flat(), asTrough };
  });

  // 同色两条线共用一份渐变定义：id 按色调去重，不按序列名，否则同一个 id 会在 defs 里出现两次
  const areaTones = Array.from(
    new Set(
      normalizedSeries
        .filter((item) => !isReferenceSeries(item) && item.kind !== "bar")
        .map((item) => item.tone)
    )
  );

  return (
    <div ref={containerRef} className="relative w-full" style={CHART_COLOR_VARS}>
      {/* 单序列不给图例：只有一种颜色时，标题已经说明画的是什么。 */}
      {showLegend && (
        <ChartLegend series={normalizedSeries} className="mb-2" style={{ color: palette.legend }} />
      )}

      <svg width={outerWidth} height={height} className="w-full overflow-visible">
        {showArea && (
          <defs>
            {areaTones.map((tone) => (
              <linearGradient key={tone} id={`${gradientPrefix}-${tone}`} x1="0" y1="0" x2="0" y2="1">
                {/* 顶端 0.16 已经是"看得见但读不成一块色"的上限，再浓就会盖过网格线 */}
                <stop offset="0%" stopColor={TONE_STROKE[tone]} stopOpacity={0.16} />
                <stop offset="100%" stopColor={TONE_STROKE[tone]} stopOpacity={0} />
              </linearGradient>
            ))}
          </defs>
        )}

        {showArea &&
          normalizedSeries.map((item, index) =>
            isReferenceSeries(item) || item.kind === "bar" || !seriesGeometry[index].areaPath ? null : (
              <path
                key={`${item.name}-area`}
                d={seriesGeometry[index].areaPath}
                fill={`url(#${gradientPrefix}-${item.tone})`}
                stroke="none"
              />
            )
          )}

        {thresholds.map((threshold) => {
          const clamped = Math.max(Math.min(threshold.value, yAxisTop), yAxisBottom);
          const y = yAt(clamped);
          const toneColor = getThresholdToneColor(threshold.tone);
          const bandHeight = Math.max(0, margin.top + innerHeight - y);
          if (bandHeight <= 0) return null;
          return (
            <rect
              key={`threshold-band-${threshold.value}-${threshold.label || ""}`}
              x={margin.left}
              y={y}
              width={innerWidth}
              height={bandHeight}
              fill={toneColor}
              opacity={0.04}
            />
          );
        })}

        {yTicks.map((tick) => {
          const y = yAt(tick);
          return (
            <g key={`${tick}-${y}`}>
              <line
                x1={margin.left}
                y1={y}
                x2={margin.left + innerWidth}
                y2={y}
                stroke={palette.grid}
              />
              <text
                x={margin.left - 8}
                y={y + 4}
                textAnchor="end"
                fill={palette.label}
                fontSize={12}
              >
                {formatYAxisValue(tick, yAxisType)}
              </text>
            </g>
          );
        })}

        {/*
          底槽夹在网格与柱之间：它是当前周期站上去的那块地形，柱压在它上面，主次一眼就分得开。
          压在网格之上是因为半透明的填充仍让网格透得过来，而反过来把网格画在填充上，
          那几道比参照色还淡的灰线会在底槽区域里显脏。
          上沿单画一条实色线：填充自己的边缘太虚，读不出"上周期这一格到底是多少"
        */}
        {normalizedSeries.map((item, seriesIndex) => {
          const { areaPath, linePath, asTrough } = seriesGeometry[seriesIndex];
          if (!asTrough || !areaPath) return null;
          const color = TONE_STROKE[item.tone || "reference"];
          return (
            <g key={`${item.name}-trough`}>
              <path d={areaPath} fill={color} opacity={TROUGH_FILL_OPACITY} stroke="none" />
              <path
                d={linePath}
                fill="none"
                stroke={color}
                strokeWidth={REFERENCE_STROKE_WIDTH}
                strokeLinejoin="miter"
                strokeLinecap="butt"
              />
            </g>
          );
        })}

        {/*
          柱画在网格之上、基线之下：网格线穿过柱身会把一根柱切成几段，而柱脚压住基线才像"立在轴上"。
          悬停时其余柱降到 0.45——它是跟着光标走的临时状态，读者不会把它读成"这几个桶被禁用了"
        */}
        {normalizedSeries.map((item, seriesIndex) =>
          item.kind !== "bar" ? null : (
            <g key={`${item.name}-bars`} fill={TONE_STROKE[item.tone || "primary"]}>
              {seriesGeometry[seriesIndex].points.map((point) => {
                /*
                 * 值不为 0 却算出不到 1px 的柱要保底画出来。原先按算出的高度丢掉，
                 * 于是"那天有 2 条消息"和"那天一条都没有"在图上长得一模一样——
                 * 前者读成后者是把有的数说成没有，比柱高差一点点要紧
                 */
                if (point.value <= 0) return null;
                const barHeight = Math.max(margin.top + innerHeight - point.y, MIN_BAR_HEIGHT);
                return (
                  <path
                    key={point.ts}
                    d={buildBarPath(point.x - barWidth / 2, point.y, barWidth, barHeight, 3)}
                    opacity={hoverIndex === null || xValues[hoverIndex] === point.ts ? 1 : 0.45}
                  />
                );
              })}
            </g>
          )
        )}

        {/*
          只留一条基线，且与网格线同色系：竖轴线和刻度小牙签都是在重复网格已经画出的框，
          三者叠在一起时最深的那道会把注意力从数据线上拿走
        */}
        <line
          x1={margin.left}
          y1={margin.top + innerHeight}
          x2={margin.left + innerWidth}
          y2={margin.top + innerHeight}
          stroke={palette.axis}
        />

        {xTickIndexes.map((index) => {
          const x = xAt(index);
          const ts = xValues[index];
          return (
            <text
              key={ts}
              x={x}
              y={margin.top + innerHeight + 18}
              textAnchor="middle"
              fill={palette.label}
              fontSize={12}
            >
              {formatXAxisValue(ts, xAxisMode, showDateOnHourAxis)}
            </text>
          );
        })}

        {thresholds.map((threshold) => {
          const clamped = Math.max(Math.min(threshold.value, yAxisTop), yAxisBottom);
          const y = yAt(clamped);
          const toneColor = getThresholdToneColor(threshold.tone);
          return (
            <g key={`threshold-${threshold.value}-${threshold.label || ""}`}>
              <line
                x1={margin.left}
                y1={y}
                x2={margin.left + innerWidth}
                y2={y}
                stroke={toneColor}
                strokeWidth={1}
                strokeDasharray="5 4"
                opacity={0.9}
              />
              {threshold.label ? (
                <text
                  x={margin.left + innerWidth - 4}
                  y={y - 4}
                  textAnchor="end"
                  fill={palette.label}
                  fontSize={10}
                >
                  {threshold.label}
                </text>
              ) : null}
            </g>
          );
        })}

        {/*
          默认只画线不铺面积：面积在多序列图里会互相盖住，把"线走到哪"换成"这块色有多大"。
          showArea 是给主流量图开的例外，且只铺实线那一条、透明度压到看不成色块，仍由线来读数。
          辉光一律不加
        */}
        {normalizedSeries.map((item, index) => {
          // 底槽已经在柱底下画完了，这一趟只走还是折线的那些序列
          if (item.kind === "bar" || seriesGeometry[index].asTrough) return null;
          return (
            <path
              key={item.name}
              d={seriesGeometry[index].linePath}
              fill="none"
              stroke={TONE_STROKE[item.tone || "primary"]}
              // 参照线细一档：线宽相等时它会与当前周期争主次
              strokeWidth={isReferenceSeries(item) ? REFERENCE_STROKE_WIDTH : 2.5}
              strokeLinejoin="round"
              strokeLinecap="round"
            />
          );
        })}

        {/*
          点稀的时候每个采样点都标出来：7 天窗口只有 8 个读数，光一条曲线读不出哪儿是真数据、
          哪儿是插值补出来的弯。间距小于 28px 就退回只标末点——标记直径 6px，
          再密下去两两相接会连成一串珠子，反而盖住线的走向。
          参照序列一个点都不标：标记的意思是"这里有个准确读数，去看它"，而上周期是拿来垫底的背景，
          它的每个值在悬停里都给得出来。原先给它标叉号，一排 × 压在图上更像一排报错
        */}
        {normalizedSeries.map((item, index) => {
          if (item.kind === "bar" || isReferenceSeries(item)) return null;
          const { points, endpoint } = seriesGeometry[index];
          const color = TONE_STROKE[item.tone || "primary"];
          const gap = xValues.length > 1 ? innerWidth / (xValues.length - 1) : innerWidth;
          const marked = gap >= 28 ? points : endpoint ? [endpoint] : [];
          if (marked.length === 0) return null;
          return (
            <g key={`${item.name}-markers`}>
              {/* 末点多一圈淡晕：整条线都带标记时，"最新一个读数在哪" 得再挑出来一次 */}
              {endpoint ? (
                <circle cx={endpoint.x} cy={endpoint.y} r={5.5} fill={color} opacity={0.18} />
              ) : null}
              {marked.map((point) => (
                <circle
                  key={point.ts}
                  cx={point.x}
                  cy={point.y}
                  r={3}
                  fill={color}
                  stroke={palette.pointStroke}
                  strokeWidth={1.5}
                />
              ))}
            </g>
          );
        })}

        {activeTs !== null ? (
          <g>
            {/* 有柱时不再画竖直指示线：被点亮的那根柱自己就指出了位置，再穿一条线过去等于把柱劈成两半 */}
            {hasBars ? null : (
              <line
                x1={xAt(hoverIndex || 0)}
                y1={margin.top}
                x2={xAt(hoverIndex || 0)}
                y2={margin.top + innerHeight}
                stroke={palette.hoverLine}
                strokeDasharray="4 4"
              />
            )}
            {normalizedSeries.map((item, index) => {
              // 底槽不跟着光标标点：上沿在一格里是一整段平线，标个点也指不出比"这一格"更细的位置，
              // 而这一格的读数 tooltip 里就有
              if (item.kind === "bar" || seriesGeometry[index].asTrough) return null;
              const value = pointMaps[index].get(activeTs);
              if (value === undefined || value === null || Number.isNaN(value)) return null;
              return (
                <circle
                  key={item.name}
                  cx={xAt(hoverIndex || 0)}
                  cy={yAt(value)}
                  r={3.5}
                  fill={TONE_STROKE[item.tone || "primary"]}
                  stroke={palette.pointStroke}
                  strokeWidth={1.5}
                />
              );
            })}
          </g>
        ) : null}

        <rect
          x={margin.left}
          y={margin.top}
          width={innerWidth}
          height={innerHeight}
          fill="transparent"
          onMouseMove={onMouseMove}
          onMouseLeave={onMouseLeave}
        />
      </svg>

      {activeTs !== null && hoverPosition ? (
        <div
          className="pointer-events-none absolute z-10 rounded-md border px-3 py-2 text-xs shadow-sm"
          style={{
            left: tooltipLeft,
            top: tooltipTop,
            width: tooltipWidth,
            borderColor: palette.tooltipBorder,
            backgroundColor: palette.tooltipBg
          }}
        >
          <div className="mb-1" style={{ color: palette.tooltipSecondary }}>
            {formatTooltipTime(activeTs, xAxisMode)}
          </div>
          <div className="space-y-1">
            {normalizedSeries.map((item, index) => {
              const value = pointMaps[index].get(activeTs);
              return (
                <div key={item.name} className="flex items-center justify-between gap-3">
                  <div className="flex items-center gap-1.5" style={{ color: palette.tooltipSecondary }}>
                    {/* 色块形状跟着画法走：柱与底槽都是块，给方角；圆点在这里会指向图上并不存在的一个点 */}
                    <span
                      className={cn(
                        "h-2 w-2",
                        item.kind === "bar" || seriesGeometry[index].asTrough
                          ? "rounded-[2px]"
                          : "rounded-full"
                      )}
                      style={{ backgroundColor: TONE_STROKE[item.tone || "primary"] }}
                    />
                    <span>{item.name}</span>
                  </div>
                  <span className="font-medium" style={{ color: palette.tooltipText }}>
                    {value === undefined || value === null ? "-" : formatYAxisValue(value, yAxisType)}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      ) : null}
    </div>
  );
}
