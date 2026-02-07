import {
  useEffect,
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
};

export type ChartYAxisType = "number" | "percent" | "duration";

export type ChartXAxisMode = "date" | "hour";

interface SimpleLineChartProps {
  series: TrendSeries[];
  height?: number;
  yAxisType?: ChartYAxisType;
  xAxisMode?: ChartXAxisMode;
}

const FALLBACK_TONES: ChartTone[] = ["primary", "success", "warning", "danger", "info", "neutral"];

const CHART_COLOR_VARS: CSSProperties = {
  ["--chart-primary" as string]: "#2563eb",
  ["--chart-success" as string]: "#16a34a",
  ["--chart-warning" as string]: "#d97706",
  ["--chart-danger" as string]: "#dc2626",
  ["--chart-info" as string]: "#0891b2",
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

const buildYAxisTicks = (minValue: number, maxValue: number, yAxisType: ChartYAxisType) => {
  if (yAxisType === "percent") {
    return [100, 75, 50, 25, 0];
  }

  const range = Math.max(maxValue - minValue, 1);
  const step = getNiceStep(range / 4);
  const tickMin = Math.floor(minValue / step) * step;
  const tickMax = Math.ceil(maxValue / step) * step;
  const ticks: number[] = [];

  for (let cursor = tickMax; cursor >= tickMin - step / 2; cursor -= step) {
    ticks.push(Number(cursor.toFixed(6)));
    if (ticks.length > 8) break;
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
  xAxisMode = "date"
}: SimpleLineChartProps) {
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
    return pointMaps.flatMap((map) => Array.from(map.values())).filter((value) => Number.isFinite(value));
  }, [pointMaps]);

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
    return buildYAxisTicks(minValue, maxValue, yAxisType);
  }, [maxValue, minValue, yAxisType]);

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

  if (!hasData) {
    return (
      <div className="flex h-[180px] items-center justify-center text-sm text-slate-400">
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

  const buildPath = (seriesIndex: number) => {
    const map = pointMaps[seriesIndex];
    let path = "";
    let started = false;
    xValues.forEach((ts) => {
      const value = map.get(ts);
      if (value === undefined || value === null || Number.isNaN(value)) {
        started = false;
        return;
      }
      const index = xIndexMap.get(ts) || 0;
      const x = xAt(index);
      const y = yAt(value);
      path += `${started ? " L" : "M"}${x.toFixed(2)} ${y.toFixed(2)}`;
      started = true;
    });
    return path;
  };

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

  return (
    <div ref={containerRef} className="relative w-full" style={CHART_COLOR_VARS}>
      <div className="mb-2 flex flex-wrap items-center gap-3 text-xs text-slate-500">
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
        {yTicks.map((tick) => {
          const y = yAt(tick);
          return (
            <g key={`${tick}-${y}`}>
              <line
                x1={margin.left}
                y1={y}
                x2={margin.left + innerWidth}
                y2={y}
                stroke="#e2e8f0"
                strokeDasharray="3 3"
              />
              <text
                x={margin.left - 8}
                y={y + 4}
                textAnchor="end"
                className="fill-slate-400 text-[11px]"
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
          stroke="#cbd5e1"
        />

        <line
          x1={margin.left}
          y1={margin.top + innerHeight}
          x2={margin.left + innerWidth}
          y2={margin.top + innerHeight}
          stroke="#cbd5e1"
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
                stroke="#94a3b8"
              />
              <text
                x={x}
                y={margin.top + innerHeight + 18}
                textAnchor="middle"
                className="fill-slate-400 text-[11px]"
              >
                {formatXAxisValue(ts, xAxisMode, showDateOnHourAxis)}
              </text>
            </g>
          );
        })}

        {normalizedSeries.map((item, index) => (
          <path
            key={item.name}
            d={buildPath(index)}
            fill="none"
            stroke={TONE_STROKE[item.tone || "primary"]}
            strokeWidth={2}
            strokeLinejoin="round"
            strokeLinecap="round"
          />
        ))}

        {activeTs !== null ? (
          <g>
            <line
              x1={xAt(hoverIndex || 0)}
              y1={margin.top}
              x2={xAt(hoverIndex || 0)}
              y2={margin.top + innerHeight}
              stroke="#94a3b8"
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
                  stroke="#ffffff"
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
          className="pointer-events-none absolute z-10 rounded-md border border-slate-200 bg-white/95 px-3 py-2 text-xs shadow-sm"
          style={{ left: tooltipLeft, top: tooltipTop, width: tooltipWidth }}
        >
          <div className="mb-1 text-slate-500">{formatTooltipTime(activeTs, xAxisMode)}</div>
          <div className="space-y-1">
            {normalizedSeries.map((item, index) => {
              const value = pointMaps[index].get(activeTs);
              return (
                <div key={item.name} className="flex items-center justify-between gap-3">
                  <div className="flex items-center gap-1.5 text-slate-500">
                    <span
                      className="h-2 w-2 rounded-full"
                      style={{ backgroundColor: TONE_STROKE[item.tone || "primary"] }}
                    />
                    <span>{item.name}</span>
                  </div>
                  <span className="font-medium text-slate-700">
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
