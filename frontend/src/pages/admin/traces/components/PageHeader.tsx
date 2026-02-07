import type { ReactNode } from "react";

import { Card, CardContent } from "@/components/ui/card";

export type TraceHeaderKpi = {
  key: string;
  icon: ReactNode;
  label: string;
  value: string;
};

interface PageHeaderProps {
  tag: string;
  title: string;
  description: string;
  kpis: TraceHeaderKpi[];
}

export function PageHeader({ tag, title, description, kpis }: PageHeaderProps) {
  return (
    <Card className="trace-list-header-card">
      <CardContent className="trace-list-header-content">
        <div className="trace-list-header-copy">
          <p className="trace-list-header-tag">{tag}</p>
          <h1 className="trace-list-header-title">{title}</h1>
          <p className="trace-list-header-description">{description}</p>
        </div>
        <div className="trace-list-kpi-group">
          {kpis.map((kpi) => (
            <div
              key={kpi.key}
              className={`trace-list-kpi-pill trace-list-kpi-pill--${kpi.key.toLowerCase()}`}
            >
              <span className="trace-list-kpi-icon">{kpi.icon}</span>
              <div className="trace-list-kpi-text">
                <p className="trace-list-kpi-label">{kpi.label}</p>
                <p className="trace-list-kpi-value">{kpi.value}</p>
              </div>
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}
