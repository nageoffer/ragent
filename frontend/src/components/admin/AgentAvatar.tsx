import {
  Aperture,
  Atom,
  Compass,
  Gem,
  Hexagon,
  Orbit,
  Rocket,
  Shapes,
  Sparkles,
  Zap,
  type LucideIcon
} from "lucide-react";

import { cn } from "@/lib/utils";

export interface AgentAvatarPreset {
  key: string;
  Icon: LucideIcon;
  /** 底色渐变，与图标同表存放，避免颜色在 CSS、图标在 TS 两处对齐 */
  gradient: string;
  shadow: string;
}

export const AGENT_AVATARS: AgentAvatarPreset[] = [
  { key: "orbit-indigo", Icon: Orbit, gradient: "linear-gradient(135deg, #6366F1, #8B5CF6)", shadow: "rgba(99, 102, 241, 0.85)" },
  { key: "sparkles-violet", Icon: Sparkles, gradient: "linear-gradient(135deg, #8B5CF6, #D946EF)", shadow: "rgba(139, 92, 246, 0.85)" },
  { key: "hexagon-teal", Icon: Hexagon, gradient: "linear-gradient(135deg, #14B8A6, #06B6D4)", shadow: "rgba(20, 184, 166, 0.85)" },
  { key: "gem-amber", Icon: Gem, gradient: "linear-gradient(135deg, #F59E0B, #F97316)", shadow: "rgba(245, 158, 11, 0.85)" },
  { key: "compass-rose", Icon: Compass, gradient: "linear-gradient(135deg, #F43F5E, #EC4899)", shadow: "rgba(244, 63, 94, 0.85)" },
  { key: "atom-sky", Icon: Atom, gradient: "linear-gradient(135deg, #0EA5E9, #6366F1)", shadow: "rgba(14, 165, 233, 0.85)" },
  { key: "rocket-emerald", Icon: Rocket, gradient: "linear-gradient(135deg, #10B981, #14B8A6)", shadow: "rgba(16, 185, 129, 0.85)" },
  { key: "shapes-slate", Icon: Shapes, gradient: "linear-gradient(135deg, #64748B, #475569)", shadow: "rgba(100, 116, 139, 0.85)" },
  { key: "aperture-fuchsia", Icon: Aperture, gradient: "linear-gradient(135deg, #A855F7, #EC4899)", shadow: "rgba(168, 85, 247, 0.85)" },
  { key: "zap-lime", Icon: Zap, gradient: "linear-gradient(135deg, #84CC16, #10B981)", shadow: "rgba(132, 204, 22, 0.85)" }
];

/** 新建智能体时给个随机预设，省得一屏卡片全是同一个色 */
export function randomAvatarKey(): string {
  return AGENT_AVATARS[Math.floor(Math.random() * AGENT_AVATARS.length)].key;
}

// 后端只存 key 不校验白名单，认不出的取值按 seed 散列到某个预设，保证永远画得出东西
function presetFor(avatar?: string | null, seed?: string): AgentAvatarPreset {
  const matched = AGENT_AVATARS.find((preset) => preset.key === avatar);
  if (matched) {
    return matched;
  }
  let hash = 0;
  for (const char of seed || "") {
    hash = (hash * 31 + char.charCodeAt(0)) % 1_000_000_007;
  }
  return AGENT_AVATARS[hash % AGENT_AVATARS.length];
}

interface AgentAvatarProps {
  avatar?: string | null;
  /** 认不出 avatar 时的散列种子，传智能体 id 可保证同一个智能体每次同色 */
  seed?: string;
  /** 控制外框尺寸 如 "h-11 w-11" */
  className?: string;
  /** 控制内部字形尺寸 如 "h-5 w-5" */
  iconClassName?: string;
}

/**
 * 智能体头像：渐变底 + 线条字形，取值来自预设表
 * <p>
 * 样式全部走 utilities 而非 globals.css —— 弹窗里的选择器由 portal 渲染在 .admin-layout 之外
 */
export function AgentAvatar({ avatar, seed, className, iconClassName }: AgentAvatarProps) {
  const { Icon, gradient, shadow } = presetFor(avatar, seed);
  return (
    <span
      className={cn(
        "inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl text-white",
        className
      )}
      style={{ backgroundImage: gradient, boxShadow: `0 6px 16px -8px ${shadow}` }}
    >
      <Icon className={cn("h-5 w-5", iconClassName)} />
    </span>
  );
}
