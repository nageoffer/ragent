/**
 * 图表与标记的颜色单一来源，SimpleLineChart 与 Dashboard 各卡共用。
 * 只有 accent 是页面主色；其余四个是状态色，仅出现在成功/待确认/中断/失败上。
 * 括号里是对白底的对比度（实测），低于 3:1 的三个只用于 ≥6px 的条与圆点，且必须有紧邻文字兜底。
 */
export const VIZ_COLORS = {
  /** 4.28:1 · 唯一主色：流量线、比例条、覆盖率条 */
  accent: "#4F6EF7",
  /** 4.74:1 · 第二条主色线，只用来区分同排小倍数图里的第二张，不带状态语义 */
  accentAlt: "#665CF6",
  /** 2.62:1 · 正常/成功。不用于数字，只用于条与点 */
  good: "#12B76A",
  /** 2.35:1 · 待确认，一格待办队列 */
  warning: "#F79009",
  /** 3.76:1 · 中断与失败 */
  critical: "#F04438",
  /** 2.58:1 · 其他状态与残余桶，有意中性化 */
  neutral: "#98A2B3",
  /**
   * 1.74:1 · 参照物（上周期那条线、压缩前底槽）。
   * 它靠"比主序列淡两档"承载层次，若哪天它成了某张图里唯一的序列，必须换回 accent
   */
  reference: "#B8C4E8"
} as const;

/** 主色的浅底与深一档，用于图标片底色与 hover。 */
export const VIZ_SURFACE = {
  accentSoft: "#EEF2FF",
  accentHover: "#405FE8",
  goodSoft: "#ECFDF3"
} as const;
