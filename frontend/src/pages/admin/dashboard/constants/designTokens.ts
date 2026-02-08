export const dashboardSpacing = {
  page: "px-6 py-4",
  section: "mb-6",
  card: "p-5",
  cardGap: "gap-4",
  inline: "gap-2"
} as const;

export const dashboardTypography = {
  pageTitle: "text-2xl font-semibold text-slate-900",
  sectionTitle: "text-lg font-medium text-slate-900",
  cardTitle: "text-sm font-medium text-slate-700",
  kpiValue: "text-3xl font-bold tracking-tight text-slate-900",
  kpiLabel: "text-xs font-medium uppercase tracking-wide text-slate-500",
  body: "text-sm text-slate-600",
  caption: "text-xs text-slate-500"
} as const;

export const dashboardCardStyles = {
  base: "rounded-xl border border-slate-200 bg-white shadow-sm",
  hover: "transition-all duration-200 hover:border-slate-300 hover:shadow-md",
  kpi: "rounded-xl border border-slate-200 bg-white p-5",
  chart: "rounded-xl border border-slate-200 bg-white p-5"
} as const;
