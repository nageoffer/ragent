import { useMemo } from "react";
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { type DashboardTrends } from "@/services/dashboardService";

import { trendToAreaData } from "../utils/dashboardDataTransform";

interface ActiveUsersAreaChartProps {
  trend: DashboardTrends | null;
  loading?: boolean;
}

export const ActiveUsersAreaChart = ({ trend, loading }: ActiveUsersAreaChartProps) => {
  const data = useMemo(() => trendToAreaData(trend, 12), [trend]);

  if (loading) {
    return (
      <Card className="rounded-xl border border-slate-200 bg-white shadow-sm">
        <CardHeader className="pb-2">
          <div className="h-5 w-28 animate-pulse rounded bg-slate-100" />
        </CardHeader>
        <CardContent>
          <div className="h-52 w-full animate-pulse rounded bg-slate-100" />
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className="rounded-xl border border-slate-200 bg-white shadow-sm transition-shadow duration-200 hover:shadow-md">
      <CardHeader className="pb-2">
        <CardTitle className="text-base font-semibold text-slate-900">活跃用户趋势</CardTitle>
        <p className="text-sm text-slate-500">活跃用户数量变化</p>
      </CardHeader>
      <CardContent>
        <div className="h-52">
          {data.length === 0 ? (
            <div className="flex h-full items-center justify-center text-sm text-slate-400">暂无数据</div>
          ) : (
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={data} margin={{ top: 4, right: 4, bottom: 0, left: -12 }}>
                <defs>
                  <linearGradient id="activeUsersGradient" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#10B981" stopOpacity={0.3} />
                    <stop offset="95%" stopColor="#10B981" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" vertical={false} />
                <XAxis
                  dataKey="label"
                  tick={{ fontSize: 11, fill: "#64748b" }}
                  tickLine={false}
                  axisLine={{ stroke: "#e2e8f0" }}
                />
                <YAxis
                  tick={{ fontSize: 11, fill: "#64748b" }}
                  tickLine={false}
                  axisLine={false}
                />
                <Tooltip
                  contentStyle={{
                    backgroundColor: "rgba(255,255,255,0.95)",
                    border: "1px solid #e2e8f0",
                    borderRadius: 8,
                    fontSize: 12
                  }}
                  labelStyle={{ color: "#64748b" }}
                />
                <Area
                  type="monotone"
                  dataKey="value"
                  name="活跃用户"
                  stroke="#10B981"
                  strokeWidth={2}
                  fill="url(#activeUsersGradient)"
                />
              </AreaChart>
            </ResponsiveContainer>
          )}
        </div>
      </CardContent>
    </Card>
  );
};
