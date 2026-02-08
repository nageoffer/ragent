import { useId } from "react";

interface SparklineProps {
  data: number[];
  color?: string;
  fillOpacity?: number;
  strokeWidth?: number;
}

type Point = { x: number; y: number };

const WIDTH = 200;
const HEIGHT = 48;
const PADDING = 4;

const buildPoints = (data: number[]): Point[] => {
  const min = Math.min(...data);
  const max = Math.max(...data);
  const range = max - min || 1;
  return data.map((value, index) => ({
    x: PADDING + (index / Math.max(data.length - 1, 1)) * (WIDTH - PADDING * 2),
    y: HEIGHT - PADDING - ((value - min) / range) * (HEIGHT - PADDING * 2)
  }));
};

const toLinePath = (points: Point[]) => {
  return points.map((point, index) => `${index === 0 ? "M" : "L"} ${point.x.toFixed(1)} ${point.y.toFixed(1)}`).join(" ");
};

const toAreaPath = (linePath: string, points: Point[]) => {
  if (!points.length) return "";
  const first = points[0];
  const last = points[points.length - 1];
  return `${linePath} L ${last.x} ${HEIGHT - PADDING} L ${first.x} ${HEIGHT - PADDING} Z`;
};

export const Sparkline = ({
  data,
  color = "#3B82F6",
  fillOpacity = 0.15,
  strokeWidth = 2
}: SparklineProps) => {
  const gradientId = useId().replace(/:/g, "");

  if (!data || data.length < 2) {
    return <div className="h-full animate-pulse rounded bg-slate-100" />;
  }

  const points = buildPoints(data);
  const linePath = toLinePath(points);
  const areaPath = toAreaPath(linePath, points);
  const lastPoint = points[points.length - 1];

  return (
    <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} className="h-full w-full" preserveAspectRatio="none">
      <defs>
        <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity={fillOpacity} />
          <stop offset="100%" stopColor={color} stopOpacity={0} />
        </linearGradient>
      </defs>

      <path d={areaPath} fill={`url(#${gradientId})`} />
      <path
        d={linePath}
        fill="none"
        stroke={color}
        strokeWidth={strokeWidth}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle cx={lastPoint.x} cy={lastPoint.y} r={4} fill="#ffffff" stroke={color} strokeWidth={2} />
    </svg>
  );
};
