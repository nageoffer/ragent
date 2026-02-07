import type { ReactNode } from "react";

import { cn } from "@/lib/utils";

export type StatCardTone = "cyan" | "emerald" | "indigo" | "amber";

interface StatCardProps {
  title: string;
  value: string;
  unit?: string;
  icon: ReactNode;
  tone: StatCardTone;
}

export function StatCard({ title, value, unit, icon, tone }: StatCardProps) {
  return (
    <article className="trace-list-stat-card">
      <div className={cn("trace-list-stat-icon", `is-${tone}`)}>{icon}</div>
      <div className="trace-list-stat-content">
        <p className="trace-list-stat-title">{title}</p>
        <div className="trace-list-stat-value-row">
          <p className="trace-list-stat-value">{value}</p>
          {unit ? <span className="trace-list-stat-unit">{unit}</span> : null}
        </div>
      </div>
    </article>
  );
}
