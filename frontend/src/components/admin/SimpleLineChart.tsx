import {
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type MouseEvent as ReactMouseEvent
} from "react";

export type TrendPoint = {
  ts: number;
  value: number;
};

export type ChartTone = "primary" | "success" | "warning" | "danger" | "info" | "neutral";

export type TrendSeries = {
  name: string;
  data: TrendPoint[];
  tone?: ChartTone;
  lineStyle?: "solid" | "dashed";
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
}

const FALLBACK_TONES: ChartTone[] = ["primary", "success", "warning", "danger", "info", "neutral"];

const CHART_COLOR_VARS: CSSProperties = {
  ["--chart-primary" as string]: "#8b5cf6",
  ["--chart-success" as string]: "#22c55e",
  ["--chart-warning" as string]: "#f59e0b",
  ["--chart-danger" as string]: "#ef4444",
  ["--chart-info" as string]: "#06b6d4",
  ["--chart-neutral" as string]: "#64748b"
};

const TONE_STROKE: Record<ChartTone, string> = {
  primary: "var(--chart-primary)",
  success: "var(--chart-success)",
  warning: "var(--chart-warning)",
  danger: "var(--chart-danger)",
  info: "var(--chart-info)",
  neutral: "var(--chart-neutral)"
};

const CHART_THEME = {
  light: {
    grid: "#f1f5f9",
    axis: "#94a3b8",
    label: "#64748b",
    legend: "#475569",
    hoverLine: "#94a3b8",
    pointStroke: "#ffffff",
    tooltipBg: "rgba(255,255,255,0.95)",
    tooltipBorder: "#e2e8f0",
    tooltipText: "#334155",
    tooltipSecondary: "#64748b"
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

const formatCompactNumber = (value: number) => {
  if (Math.abs(value) >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (Math.abs(value) >= 1_000) return `${(value / 1_000).toFixed(1)}K`;
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

const buildMonotonePath = (points: PlotPoint[]) => {
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
    delta[i] = span === 0 ? 0 : (y[i + 1] - y[i]) / span;
  }

  const slope = new Array<number>(points.length).fill(0);
  slope[0] = Number.isFinite(delta[0]) ? delta[0] : 0;
  slope[points.length - 1] = Number.isFinite(delta[segmentCount - 1]) ? delta[segmentCount - 1] : 0;

  for (let i = 1; i < points.length - 1; i += 1) {
    const prev = delta[i - 1];
    const next = delta[i];
    if (!Number.isFinite(prev) || !Number.isFinite(next) || prev === 0 || next === 0 || prev * next < 0) {
      slope[i] = 0;
      continue;
    }
    const w1 = 2 * dx[i] + dx[i - 1];
    const w2 = dx[i] + 2 * dx[i - 1];
    slope[i] = (w1 + w2) / (w1 / prev + w2 / next);
    if (!Number.isFinite(slope[i])) {
      slope[i] = 0;
    }
  }

  let path = `M${x[0].toFixed(2)} ${y[0].toFixed(2)}`;
  for (let i = 0; i < segmentCount; i += 1) {
    const span = dx[i];
    if (!Number.isFinite(span) || span <= 0) {
      path += ` L${x[i + 1].toFixed(2)} ${y[i + 1].toFixed(2)}`;
      continue;
    }
    const c1x = x[i] + span / 3;
    const c1y = y[i] + (slope[i] * span) / 3;
    const c2x = x[i + 1] - span / 3;
    const c2y = y[i + 1] - (slope[i + 1] * span) / 3;
    path += ` C${c1x.toFixed(2)} ${c1y.toFixed(2)} ${c2x.toFixed(2)} ${c2y.toFixed(2)} ${x[i + 1].toFixed(2)} ${y[i + 1].toFixed(2)}`;
  }
  return path;
};

const buildAreaPath = (points: PlotPoint[], baselineY: number) => {
  if (points.length === 0) return "";
  const linePath = buildMonotonePath(points);
  if (!linePath) return "";
  const first = points[0];
  const last = points[points.length - 1];
  return `${linePath} L${last.x.toFixed(2)} ${baselineY.toFixed(2)} L${first.x.toFixed(2)} ${baselineY.toFixed(2)} Z`;
};

const getThresholdToneColor = (tone?: ChartThreshold["tone"]) => {
  if (tone === "critical") return "#ef4444";
  if (tone === "warning") return "#f59e0b";
  return "#0ea5e9";
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
  yAxisTickCount = 4
}: SimpleLineChartProps) {
  const gradientIdPrefix = useId().replace(/:/g, "");
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
    if (min === max) {
      max = min + 1;
    }
    return { minValue: min, maxValue: max };
  }, [values, yAxisType]);

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
  const margin = { top: 12, right: 16, bottom: 34, left: 56 };
  const innerWidth = Math.max(outerWidth - margin.left - margin.right, 1);
  const innerHeight = Math.max(height - margin.top - margin.bottom, 1);

  const xIndexMap = new Map<number, number>();
  xValues.forEach((ts, index) => xIndexMap.set(ts, index));

  const xAt = (index: number) => {
    if (xValues.length <= 1) {
      return margin.left + innerWidth / 2;
    }
    return margin.left + (index / (xValues.length - 1)) * innerWidth;
  };

  const yAt = (value: number) => {
    const denominator = Math.max(yAxisTop - yAxisBottom, 1);
    const ratio = (value - yAxisBottom) / denominator;
    return margin.top + (1 - ratio) * innerHeight;
  };

  const xTickCount = Math.min(showDateOnHourAxis ? 4 : 6, xValues.length);
  const xTickIndexes = Array.from({ length: xTickCount }, (_, index) => {
    if (xTickCount <= 1) return 0;
    return Math.round((index * (xValues.length - 1)) / (xTickCount - 1));
  }).filter((value, index, array) => array.indexOf(value) === index);

  const activeTs = hoverIndex !== null ? xValues[hoverIndex] : null;

  const onMouseMove = (event: ReactMouseEvent<SVGRectElement>) => {
    const svgRect = event.currentTarget.ownerSVGElement?.getBoundingClientRect();
    if (!svgRect) return;

    const x = event.clientX - svgRect.left;
    const clampedX = Math.min(Math.max(x, margin.left), margin.left + innerWidth);
    const ratio = innerWidth <= 1 ? 0 : (clampedX - margin.left) / innerWidth;
    const index = Math.round(ratio * (xValues.length - 1));

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
  const baselineY = margin.top + innerHeight;

  const seriesGeometry = normalizedSeries.map((_, seriesIndex) => {
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

    const linePath = segments.map((segment) => buildMonotonePath(segment)).filter(Boolean).join(" ");
    const areaPath = segments.map((segment) => buildAreaPath(segment, baselineY)).filter(Boolean).join(" ");
    const lastSegment = segments[segments.length - 1];
    const endpoint = lastSegment ? lastSegment[lastSegment.length - 1] : null;

    return { linePath, areaPath, endpoint };
  });

  return (
    <div ref={containerRef} className="relative w-full" style={CHART_COLOR_VARS}>
      <div className="mb-2 flex flex-wrap items-center gap-3 text-xs" style={{ color: palette.legend }}>
        {normalizedSeries.map((item) => (
          <div key={item.name} className="inline-flex items-center gap-1.5">
            <span
              className="h-2.5 w-2.5 rounded-full"
              style={{ backgroundColor: TONE_STROKE[item.tone || "primary"] }}
            />
            <span>{item.name}</span>
          </div>
        ))}
      </div>

      <svg width={outerWidth} height={height} className="w-full overflow-visible">
        <defs>
          {normalizedSeries.map((item, index) => {
            const toneColor = TONE_STROKE[item.tone || "primary"];
            return (
              <linearGradient
                key={`gradient-${item.name}-${index}`}
                id={`${gradientIdPrefix}-series-gradient-${index}`}
                x1="0"
                y1={margin.top}
                x2="0"
                y2={margin.top + innerHeight}
                gradientUnits="userSpaceOnUse"
              >
                <stop offset="0%" stopColor={toneColor} stopOpacity={0.16} />
                <stop offset="72%" stopColor={toneColor} stopOpacity={0.05} />
                <stop offset="100%" stopColor={toneColor} stopOpacity={0} />
              </linearGradient>
            );
          })}
        </defs>

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
                strokeDasharray="4 4"
              />
              <text
                x={margin.left - 8}
                y={y + 4}
                textAnchor="end"
                fill={palette.label}
                fontSize={11}
              >
                {formatYAxisValue(tick, yAxisType)}
              </text>
            </g>
          );
        })}

        <line
          x1={margin.left}
          y1={margin.top}
          x2={margin.left}
          y2={margin.top + innerHeight}
          stroke={palette.axis}
        />

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
            <g key={ts}>
              <line
                x1={x}
                y1={margin.top + innerHeight}
                x2={x}
                y2={margin.top + innerHeight + 4}
                stroke={palette.axis}
              />
              <text
                x={x}
                y={margin.top + innerHeight + 18}
                textAnchor="middle"
                fill={palette.label}
                fontSize={11}
              >
                {formatXAxisValue(ts, xAxisMode, showDateOnHourAxis)}
              </text>
            </g>
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

        {normalizedSeries.map((item, index) => (
          <path
            key={`${item.name}-area`}
            d={seriesGeometry[index].areaPath}
            fill={`url(#${gradientIdPrefix}-series-gradient-${index})`}
            stroke="none"
          />
        ))}

        {normalizedSeries.map((item, index) => (
          <path
            key={`${item.name}-glow`}
            d={seriesGeometry[index].linePath}
            fill="none"
            stroke={TONE_STROKE[item.tone || "primary"]}
            strokeWidth={2.2}
            strokeOpacity={0.1}
            strokeLinejoin="round"
            strokeLinecap="round"
          />
        ))}

        {normalizedSeries.map((item, index) => (
          <path
            key={item.name}
            d={seriesGeometry[index].linePath}
            fill="none"
            stroke={TONE_STROKE[item.tone || "primary"]}
            strokeWidth={2.2}
            strokeDasharray={item.lineStyle === "dashed" ? "6 4" : undefined}
            strokeLinejoin="round"
            strokeLinecap="round"
          />
        ))}

        {normalizedSeries.map((item, index) => {
          const endpoint = seriesGeometry[index].endpoint;
          if (!endpoint) return null;
          return (
            <g key={`${item.name}-endpoint`}>
              <circle
                cx={endpoint.x}
                cy={endpoint.y}
                r={5}
                fill={TONE_STROKE[item.tone || "primary"]}
                opacity={0.18}
              />
              <circle
                cx={endpoint.x}
                cy={endpoint.y}
                r={3}
                fill={TONE_STROKE[item.tone || "primary"]}
                stroke={palette.pointStroke}
                strokeWidth={1.5}
              />
            </g>
          );
        })}

        {activeTs !== null ? (
          <g>
            <line
              x1={xAt(hoverIndex || 0)}
              y1={margin.top}
              x2={xAt(hoverIndex || 0)}
              y2={margin.top + innerHeight}
              stroke={palette.hoverLine}
              strokeDasharray="4 4"
            />
            {normalizedSeries.map((item, index) => {
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
                    <span
                      className="h-2 w-2 rounded-full"
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
