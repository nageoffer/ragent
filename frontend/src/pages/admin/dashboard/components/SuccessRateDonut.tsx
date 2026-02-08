import { useMemo } from "react";
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { type DashboardPerformance } from "@/services/dashboardService";

import { performanceToDonut } from "../utils/dashboardDataTransform";

interface SuccessRateDonutProps {
  performance: DashboardPerformance | null;
  loading?: boolean;
}

export const SuccessRateDonut = ({ performance, loading }: SuccessRateDonutProps) => {
  const segments = useMemo(() => performanceToDonut(performance), [performance]);
  const successRate = performance?.successRate;

  if (loading) {
    return (
      <Card className="rounded-xl border border-slate-200 bg-white shadow-sm">
        <CardHeader className="pb-2">
          <div className="h-5 w-28 animate-pulse rounded bg-slate-100" />
        </CardHeader>
        <CardContent>
          <div className="flex h-44 items-center justify-center">
            <div className="h-32 w-32 animate-pulse rounded-full bg-slate-100" />
          </div>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className="rounded-xl border border-slate-200 bg-white shadow-sm transition-shadow duration-200 hover:shadow-md">
      <CardHeader className="pb-2">
        <CardTitle className="text-base font-semibold text-slate-900">成功率分布</CardTitle>
        <p className="text-sm text-slate-500">请求成功/错误占比</p>
      </CardHeader>
      <CardContent>
        <div className="relative h-44">
          {segments.length === 0 ? (
            <div className="flex h-full items-center justify-center text-sm text-slate-400">暂无数据</div>
          ) : (
            <>
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={segments}
                    cx="50%"
                    cy="50%"
                    innerRadius={48}
                    outerRadius={68}
                    paddingAngle={2}
                    dataKey="value"
                    strokeWidth={0}
                  >
                    {segments.map((segment) => (
                      <Cell key={segment.name} fill={segment.color} />
                    ))}
                  </Pie>
                  <Tooltip
                    contentStyle={{
                      backgroundColor: "rgba(255,255,255,0.95)",
                      border: "1px solid #e2e8f0",
                      borderRadius: 8,
                      fontSize: 12
                    }}
                    formatter={(value: number) => [`${value.toFixed(1)}%`, ""]}
                  />
                </PieChart>
              </ResponsiveContainer>
              <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
                <div className="text-center">
                  <div className="text-2xl font-bold text-slate-900">
                    {successRate !== null && successRate !== undefined ? `${successRate.toFixed(1)}%` : "-"}
                  </div>
                  <div className="text-xs text-slate-500">成功率</div>
                </div>
              </div>
            </>
          )}
        </div>
        <div className="mt-2 flex items-center justify-center gap-4 text-xs">
          {segments.map((segment) => (
            <div key={segment.name} className="flex items-center gap-1.5">
              <span className="h-2.5 w-2.5 rounded-sm" style={{ backgroundColor: segment.color }} />
              <span className="text-slate-600">{segment.name}</span>
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  );
};
