import { useMemo, useState } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  SimpleLineChart,
  type ChartThreshold,
  type ChartXAxisMode,
  type ChartYAxisType,
  type TrendSeries
} from "@/components/admin/SimpleLineChart";

interface TrendChartCardProps {
  title: string;
  subtitle?: string;
  series: TrendSeries[];
  compareSeries?: TrendSeries[];
  thresholds?: ChartThreshold[];
  xAxisMode: ChartXAxisMode;
  yAxisType?: ChartYAxisType;
  yAxisLabel?: string;
  currentValue?: string;
  loading?: boolean;
}

const LoadingBlock = ({ className }: { className: string }) => {
  return <div className={`animate-pulse rounded bg-slate-100 ${className}`} />;
};

const TONE_COLOR: Record<
  NonNullable<TrendSeries["tone"]>,
  string
> = {
  primary: "#3B82F6",
  success: "#10B981",
  warning: "#F59E0B",
  danger: "#EF4444",
  info: "#8B5CF6",
  neutral: "#64748B"
};

export const TrendChartCard = ({
  title,
  subtitle,
  series,
  compareSeries = [],
  thresholds = [],
  xAxisMode,
  yAxisType = "number",
  yAxisLabel,
  currentValue,
  loading
}: TrendChartCardProps) => {
  const [showCompare, setShowCompare] = useState(false);

  const mergedSeries = useMemo(() => {
    if (!showCompare || compareSeries.length === 0) {
      return series;
    }
    return [
      ...series,
      ...compareSeries.map((item) => ({
        ...item,
        lineStyle: "dashed" as const,
        tone: item.tone ?? "neutral"
      }))
    ];
  }, [compareSeries, series, showCompare]);

  if (loading) {
    return (
      <Card className="rounded-xl border border-slate-200 bg-white shadow-sm">
        <CardHeader className="pb-2">
          <LoadingBlock className="h-5 w-32" />
          <LoadingBlock className="mt-2 h-4 w-40" />
        </CardHeader>
        <CardContent>
          <LoadingBlock className="h-72 w-full" />
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className="rounded-xl border border-slate-200 bg-white shadow-sm transition-shadow duration-200 hover:shadow-md">
      <CardHeader className="pb-2">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <CardTitle className="text-base font-semibold text-slate-900">{title}</CardTitle>
            {subtitle ? <p className="mt-1 text-sm text-slate-500">{subtitle}</p> : null}
          </div>
          <div className="flex items-center gap-2">
            {currentValue ? (
              <Badge variant="outline" className="border-slate-200 bg-white text-slate-700">
                {currentValue}
              </Badge>
            ) : null}
            {compareSeries.length > 0 ? (
              <Button
                variant={showCompare ? "default" : "outline"}
                size="sm"
                onClick={() => setShowCompare((prev) => !prev)}
                className={
                  showCompare
                    ? "rounded-md border-blue-200 bg-blue-50 text-blue-600 hover:bg-blue-100"
                    : "rounded-md border-slate-200 text-slate-600 hover:bg-slate-50 hover:text-slate-900"
                }
              >
                同比
              </Button>
            ) : null}
          </div>
        </div>
      </CardHeader>
      <CardContent className="space-y-2">
        <div className="flex items-center gap-3 text-xs">
          {yAxisLabel ? <span className="text-slate-400">{yAxisLabel}</span> : null}
          {mergedSeries[0] ? (
            <span className="flex items-center gap-1.5">
              <span
                className="h-2.5 w-2.5 rounded-sm"
                style={{ backgroundColor: TONE_COLOR[mergedSeries[0].tone ?? "primary"] }}
              />
              <span className="font-medium text-slate-600">{mergedSeries[0].name}</span>
            </span>
          ) : null}
        </div>
        <div className="h-72">
          <SimpleLineChart
            series={mergedSeries}
            xAxisMode={xAxisMode}
            yAxisType={yAxisType}
            thresholds={thresholds}
            height={280}
            theme="light"
            yAxisTickCount={4}
          />
        </div>
      </CardContent>
    </Card>
  );
};
