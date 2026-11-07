import { useCallback, useEffect, useRef, useState } from "react";
import {
  CanvasEvent,
  Graph,
  GraphEvent,
  NodeEvent,
  type EdgeData,
  type GraphData,
  type IElementEvent,
  type NodeData
} from "@antv/g6";
import { Circle as GCircle } from "@antv/g";
import { Library, Loader2, Minus, Plus, RefreshCw, Search, Settings, Share2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import {
  getKnowledgeGraph,
  searchGraphEntities,
  type GraphView
} from "@/services/knowledgeGraphService";
import {
  getDocuments,
  getKnowledgeBases,
  type KnowledgeBase,
  type KnowledgeDocument
} from "@/services/knowledgeService";
import { getErrorMessage } from "@/utils/error";

// 实体类型配色板，按类型首次出现顺序分配，超出后循环取色
const TYPE_PALETTE = [
  "#5B8FF9",
  "#61DDAA",
  "#F6BD16",
  "#7262FD",
  "#78D3F8",
  "#F08BB4",
  "#FF9845",
  "#9661BC",
  "#269A99",
  "#D96D6C"
];
const FALLBACK_COLOR = "#94A3B8";

// 概览时常显标签的目标数量：按度数取前 ~45 个高连接实体常显，密集图自动抬高门槛以压掉大范围文字遮挡，其余悬浮查看
const LABEL_BUDGET = 45;

// 边「淡网」：低透明度冷灰、让线退成节点背后的网。稠密图里边一花就毁整屏
const EDGE_STROKE = "#CBD5E1";
const EDGE_OPACITY = 0.34;
// 曲线控制点偏移：曲线态用 20、直线态用 0（改 curveOffset 即可拉直，无需换边类型 / 重排）
const EDGE_CURVE_OFFSET = 20;
// 连线形状 / 箭头：模块级单例，齿轮设置面板可切；曲线=贝塞尔(offset 20)、直线=offset 0；淡箭头=概览显小号 simple 箭头。默认 直线 + 淡箭头
let edgeCurved = false;
let edgeArrow = true;

// 节点视觉主题：稠密的饱和大圆点像「打翻的糖果罐」，靠缩小叶子拉层级 + 弱化色块 + 砍光晕，收成耐看的「星座网」。
// pastel 柔彩（提亮粉彩填充）｜ outline 描边（近白填充 + 类型色圆环，色只作点缀）｜ glass 玻璃（半透明色填充）｜ vivid 鲜彩（原样）
type VizTheme = "pastel" | "outline" | "glass" | "vivid";
// 当前视觉主题：模块级单例，renderGraph 节点样式与齿轮设置面板都读它；默认「描边」
let vizTheme: VizTheme = "outline";

/** 两个 #RRGGBB 按 t∈[0,1] 线性混合，返回 #rrggbb；非法输入回退 a。用于主题提亮粉彩 / 描边近白填充 */
function mixHex(a: string, b: string, t: number): string {
  const pa = /^#?([0-9a-fA-F]{6})$/.exec(a.trim());
  const pb = /^#?([0-9a-fA-F]{6})$/.exec(b.trim());
  if (!pa || !pb) {
    return a;
  }
  const ia = parseInt(pa[1], 16);
  const ib = parseInt(pb[1], 16);
  const r = Math.round(((ia >> 16) & 255) + (((ib >> 16) & 255) - ((ia >> 16) & 255)) * t);
  const g = Math.round(((ia >> 8) & 255) + (((ib >> 8) & 255) - ((ia >> 8) & 255)) * t);
  const b2 = Math.round((ia & 255) + ((ib & 255) - (ia & 255)) * t);
  return `#${((1 << 24) | (r << 16) | (g << 8) | b2).toString(16).slice(1)}`;
}

/**
 * 主题化节点完整样式：一次给出 尺寸 / 填充 / 填充透明度 / 描边 / 描边宽 / 光晕。
 * 非 vivid 主题统一把叶子缩小到 13 起、拉开层级、腾出留白；各主题再各自弱化色块（提亮 / 描边 / 半透明）与光晕
 */
function themeNodeStyle(
  theme: VizTheme,
  baseColor: string,
  degree: number
): {
  size: number;
  fill: string;
  fillOpacity: number;
  stroke: string;
  lineWidth: number;
  shadowColor: string;
  shadowBlur: number;
} {
  const d = Math.min(degree, 12);
  const isHub = degree >= 6;
  if (theme === "vivid") {
    // 原样：饱和填充、白环、强光晕、大节点
    return {
      size: 24 + d * 3,
      fill: baseColor,
      fillOpacity: 1,
      stroke: "#ffffff",
      lineWidth: isHub ? 2 : 1.5,
      shadowColor: withAlpha(baseColor, 0.45),
      shadowBlur: 16
    };
  }
  const size = 13 + d * 3;
  if (theme === "outline") {
    // 近白填充 + 类型色圆环：色只作点缀不再成片，最能压住「太花」又保留类型区分
    return {
      size,
      fill: mixHex(baseColor, "#ffffff", 0.86),
      fillOpacity: 1,
      stroke: baseColor,
      lineWidth: isHub ? 2.5 : 1.8,
      shadowColor: withAlpha(baseColor, 0.14),
      shadowBlur: 4
    };
  }
  if (theme === "glass") {
    // 半透明色填充 + 白环：像磨砂玻璃、交叠处透色，更轻
    return {
      size,
      fill: baseColor,
      fillOpacity: 0.5,
      stroke: "#ffffff",
      lineWidth: 1.25,
      shadowColor: withAlpha(baseColor, 0.3),
      shadowBlur: 12
    };
  }
  // pastel 柔彩：向白提亮成粉彩、白环、柔光
  const soft = mixHex(baseColor, "#ffffff", 0.5);
  return {
    size,
    fill: soft,
    fillOpacity: 1,
    stroke: "#ffffff",
    lineWidth: isHub ? 2 : 1.5,
    shadowColor: withAlpha(soft, 0.34),
    shadowBlur: 10
  };
}

// 实体类型英中映射，LightRAG 抽取出的类型多为英文，展示时转中文，未收录的类型回退原始串
const ENTITY_TYPE_LABELS: Record<string, string> = {
  person: "人物",
  organization: "组织",
  location: "地点",
  geo: "地理",
  event: "事件",
  concept: "概念",
  category: "类别",
  method: "方法",
  artifact: "制品",
  content: "内容",
  data: "数据",
  product: "产品",
  technology: "技术",
  equipment: "设备",
  activity: "活动",
  subject: "主题",
  time: "时间",
  role: "角色",
  document: "文档",
  unknown: "未知"
};

/** 实体类型转中文展示名，未收录的类型回退原始串 */
function entityTypeLabel(type: string): string {
  const key = type.trim().toLowerCase();
  return ENTITY_TYPE_LABELS[key] || type;
}

/** 依据类型集合构建 类型→颜色 映射，无类型归入「其他」用灰色 */
function buildTypeColors(nodes: GraphView["nodes"]): Record<string, string> {
  const colors: Record<string, string> = {};
  let index = 0;
  for (const node of nodes) {
    const type = node.type?.trim();
    if (!type) {
      continue;
    }
    if (!colors[type]) {
      colors[type] = TYPE_PALETTE[index % TYPE_PALETTE.length];
      index += 1;
    }
  }
  return colors;
}

function escapeHtml(text: string): string {
  return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

/** #RRGGBB → rgba(r,g,b,a)，用于节点同色光晕；非法输入回退原串 */
function withAlpha(hex: string, alpha: number): string {
  const match = /^#?([0-9a-fA-F]{6})$/.exec(hex.trim());
  if (!match) {
    return hex;
  }
  const int = parseInt(match[1], 16);
  return `rgba(${(int >> 16) & 255}, ${(int >> 8) & 255}, ${int & 255}, ${alpha})`;
}

/**
 * 居中并缩放到给定节点集，实现「居中放大」式聚焦
 * <p>
 * 取景以「质心 + 去离群半径」为准而非满包围盒：核心实体的自我网络常含个别被斥得很远的弱连接邻居，
 * 满包围盒会把这些离群点一并塞进视口，导致稠密簇挤到一侧、另一侧大片空白；这里截去最远约 15%，
 * 用稠密处定缩放、以质心（视觉重心）为中心，让元素密度铺满画面不偏侧
 * <p>
 * G6 的 focusElement 只平移且按包围盒中心，故不用它：先 zoomTo 定比例，再用「中心锚点」把世界质心
 * 平移到视口中心（getViewportByCanvas 求其当前屏幕位、按像素 delta 平移，与散焦还原同款技巧）
 */
function zoomToNodes(graph: Graph, ids: string[], animate: boolean, maxZoom = 1.8) {
  if (ids.length === 0) {
    return;
  }
  const pts: Array<[number, number]> = [];
  for (const id of ids) {
    const pos = graph.getElementPosition(id);
    const x = Number(pos[0]);
    const y = Number(pos[1]);
    if (Number.isFinite(x) && Number.isFinite(y)) {
      pts.push([x, y]);
    }
  }
  // 位置尚不可用（极端时序）时退回纯平移，不强行改缩放
  if (pts.length === 0) {
    void graph.focusElement(ids, animate);
    return;
  }
  // 质心=元素质量中心，比包围盒中心更贴近视觉重心，右偏 / 长尾分布下取景更居中
  let cx = 0;
  let cy = 0;
  for (const [x, y] of pts) {
    cx += x;
    cy += y;
  }
  cx /= pts.length;
  cy /= pts.length;
  // 到质心距离的 85 分位为取景半径：节点太少无离群概念时取最大距离，否则截去最远约 15% 的离群点
  const dists = pts.map(([x, y]) => Math.hypot(x - cx, y - cy)).sort((a, b) => a - b);
  const radius = pts.length <= 5 ? dists[dists.length - 1] : dists[Math.floor(dists.length * 0.85)];
  const span = Math.max(radius * 2, 1);
  const [vw, vh] = graph.getSize();
  const padding = 120;
  // 世界单位在 zoom=1 时约等于视口像素，缩放比 = 可用像素 / 取景直径；封顶 maxZoom 免过放大、封底 0.2 免过缩
  const raw = Math.min((vw - padding * 2) / span, (vh - padding * 2) / span);
  const zoom = Math.max(Math.min(raw, maxZoom), 0.2);
  void graph.zoomTo(zoom, animate).then(() => {
    const p = graph.getViewportByCanvas([cx, cy] as ReturnType<Graph["getCanvasByViewport"]>);
    return graph.translateBy([vw / 2 - Number(p[0]), vh / 2 - Number(p[1])], animate);
  });
}

// 取景过扫倍数：在「全部可见」贴合比例上再放大这么多倍，把空角与稀疏边缘裁出画外、让主体铺满整帧
// （1=恰好贴合，会留白且四角空；>1 越大越铺满越裁边）。仅影响初始 / 重载取景，被裁部分可继续缩放 / 平移查看
const FIT_OVERSCAN = 1.3;

/**
 * 进入 / 重载时的取景：在「保持节点当前大小」前提下把图铺满视口，并主动过扫裁掉四角空白与稀疏边缘
 * <p>
 * 不用 G6 的 fitView——它为把离群点也塞进视口会把比例压得很小、节点糊成一团。这里取全体包围盒算「全部可见」贴合比例后
 * 再乘 FIT_OVERSCAN 过扫放大：力导收敛成圆团、其包围盒四角恒空，贴合取景会把这些空角摆在视口四角、显得中间挤四周空；
 * 过扫把空角与最外圈稀疏节点推出画外，视口四角转而落在更稠密的团内区域，从而铺满整帧。用户明确「美观优先、允许展示不全」，
 * 故不再追求全部塞进视口——被裁的边缘节点可缩放 / 拖拽查看。缩放夹在 [0.68, 1.8]：底限保证大图节点不被缩糊、顶限避免小图过放；
 * 末以包围盒中心为锚点平移到视口中心
 */
function fitAllNodes(graph: Graph, animate: boolean): Promise<void> {
  const nodes = graph.getNodeData();
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const node of nodes) {
    const pos = graph.getElementPosition(String(node.id));
    const x = Number(pos[0]);
    const y = Number(pos[1]);
    if (!Number.isFinite(x) || !Number.isFinite(y)) {
      continue;
    }
    minX = Math.min(minX, x);
    minY = Math.min(minY, y);
    maxX = Math.max(maxX, x);
    maxY = Math.max(maxY, y);
  }
  // 位置尚不可用（极端时序）时退回 G6 自适应
  if (!Number.isFinite(minX)) {
    return graph.fitView();
  }
  const cx = (minX + maxX) / 2;
  const cy = (minY + maxY) / 2;
  const [vw, vh] = graph.getSize();
  const padding = 16;
  // 「全部可见」贴合比例，再乘过扫倍数放大以裁掉空角、铺满整帧
  const contain = Math.min(
    (vw - padding * 2) / Math.max(maxX - minX, 1),
    (vh - padding * 2) / Math.max(maxY - minY, 1)
  );
  const zoom = Math.max(Math.min(contain * FIT_OVERSCAN, 1.8), 0.68);
  return graph.zoomTo(zoom, animate).then(() => {
    const p = graph.getViewportByCanvas([cx, cy] as ReturnType<Graph["getCanvasByViewport"]>);
    return graph.translateBy([vw / 2 - Number(p[0]), vh / 2 - Number(p[1])], animate);
  });
}

// 方形化强度：0=不动（圆团），1=贴合成方块。取 0.55 得「超椭圆」观感，填角又不至于把角落连线拉过长
const RESHAPE_SQUARENESS = 0.55;

/**
 * 力导天然收敛成圆团，放进矩形视口既左右留白、四角更是恒空——圆内接于矩形，四个角落必然填不到。收敛后做两步重塑：
 * ① 若团偏高，按视口宽高比做「保面积」各向异性拉伸（横拉 scaleX、纵压 1/scaleX，面积不变故节点不缩小）；
 * ② 角向「方形化」：把每个点按其方位角朝所在象限的角落外推，圆团 → 圆角方形，直接把节点铺进四角、消掉「中间挤四角空」
 * <p>
 * 方形化 boost = 1 + K·(欧氏半径 / 切比雪夫半径 − 1)：正比于「该方向的方形边界比圆边界远多少」，故轴向≈不动、
 * 越靠对角线外推越多（对角处最大 √2）。它只依方位角、与半径无关（同一射线上远近点同比例外推），
 * 内部密度沿径向不变、中心不空。纵向压缩靠碰撞半径预留的 22px 呼吸余量吸收。仅用于力导，径向布局保持其同心圆形态
 */
async function reshapeToViewport(graph: Graph) {
  const nodes = graph.getNodeData();
  if (nodes.length < 3) {
    return;
  }
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  const pts: Array<{ id: string; x: number; y: number }> = [];
  for (const node of nodes) {
    const id = String(node.id);
    const pos = graph.getElementPosition(id);
    const x = Number(pos[0]);
    const y = Number(pos[1]);
    if (!Number.isFinite(x) || !Number.isFinite(y)) {
      continue;
    }
    pts.push({ id, x, y });
    minX = Math.min(minX, x);
    minY = Math.min(minY, y);
    maxX = Math.max(maxX, x);
    maxY = Math.max(maxY, y);
  }
  const spanX = maxX - minX;
  const spanY = maxY - minY;
  if (!Number.isFinite(spanX) || spanX < 1 || spanY < 1) {
    return;
  }
  const [vw, vh] = graph.getSize();
  const targetAspect = vw / vh;
  const currentAspect = spanX / spanY;
  // ① 偏高才横向拉伸到视口比例；已够宽就不拉（纵向再拉会引发竖向重叠）。方形化两种情况都做，故不再提前返回
  let sx = 1;
  let sy = 1;
  if (currentAspect < targetAspect) {
    sx = Math.min(Math.sqrt(targetAspect / currentAspect), 1.6);
    sy = 1 / sx;
  }
  const cx = (minX + maxX) / 2;
  const cy = (minY + maxY) / 2;
  // 拉伸后的半跨，用于把点归一化进 [-1,1] 方框——方形化即朝这个方框的四角推
  const halfX = (spanX * sx) / 2;
  const halfY = (spanY * sy) / 2;
  const positions: Record<string, [number, number]> = {};
  for (const p of pts) {
    // 先各向异性拉伸
    let dx = (p.x - cx) * sx;
    let dy = (p.y - cy) * sy;
    // 再角向方形化：越靠对角线越往角落外推，轴向几乎不动
    const nx = dx / halfX;
    const ny = dy / halfY;
    const cheb = Math.max(Math.abs(nx), Math.abs(ny));
    if (cheb > 1e-6) {
      const boost = 1 + RESHAPE_SQUARENESS * (Math.hypot(nx, ny) / cheb - 1);
      dx *= boost;
      dy *= boost;
    }
    positions[p.id] = [cx + dx, cy + dy];
  }
  await graph.translateElementTo(positions, false);
}

// 聚焦前的相机快照（缩放 + 屏幕中心对应的世界坐标），散焦时精确还原，避免停在放大后的比例回不去；每次重建图在 renderGraph 里清空
let savedView: { zoom: number; center: ReturnType<Graph["getCanvasByViewport"]> } | null = null;

// 聚焦节点关系边的流动动画句柄：仅对当前聚焦节点的关系边逐帧推进 lineDashOffset 形成「光点沿边流转」，
// 散焦即停，把动效开销与语义都限定在选中实体的少数关系上，避免全图恒动的性能与视觉干扰
let flowRaf = 0;
let flowShapes: Array<{ style: { lineDashOffset?: number } }> = [];

/** 停止流动动画并松开对边图形的引用 */
function stopEdgeFlow() {
  if (flowRaf) {
    cancelAnimationFrame(flowRaf);
    flowRaf = 0;
  }
  flowShapes = [];
}

/**
 * 启动流动动画：取聚焦节点各关系边的主路径（key 图形），逐帧推进虚线偏移
 * <p>
 * 虚线本身由 flow 边态提供（清态时靠 base 的 lineDash:0 回退），这里只驱动偏移量；lineDashOffset
 * 不在任何样式声明中、G6 从不管理它，故相机 / 状态重绘都不会清掉，直接改 key 图形 style 即可逐帧渲染
 */
function startEdgeFlow(graph: Graph, edgeIds: string[]) {
  stopEdgeFlow();
  const canvas = graph.getCanvas();
  for (const id of edgeIds) {
    // getElementById 返回 @antv/g 图元，其运行期是 G6 边元素、含 getShape('key') 取主路径；类型不暴露故防御式取用
    const el = canvas.document.getElementById(id) as unknown as {
      getShape?: (name: string) => { style: { lineDashOffset?: number } } | undefined;
    } | null;
    const key = el?.getShape?.("key");
    if (key) {
      flowShapes.push(key);
    }
  }
  if (flowShapes.length === 0) {
    return;
  }
  const step = (ts: number) => {
    // 每 600ms 走完一个 12px 虚线周期，负向推进令短划朝箭头（target）流动，呼应关系方向
    const offset = -((ts / 600) % 1) * 12;
    for (const shape of flowShapes) {
      shape.style.lineDashOffset = offset;
    }
    flowRaf = requestAnimationFrame(step);
  };
  flowRaf = requestAnimationFrame(step);
}

/** 用户偏好减少动效（无障碍）时跳过入场与涟漪，直接静态呈现 */
function prefersReducedMotion(): boolean {
  return (
    typeof window !== "undefined" &&
    typeof window.matchMedia === "function" &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches
  );
}

// 入场动画：取景落定后播放一次的开场揭示——「连线生长」。节点先按「到质心距离」轻微径向错峰、由略小(0.86)到原尺寸淡入快速就位，
// 随后每条边沿其路径由短到长「描边生长」（拿不到路径长度的边回退为淡入），像关系网络被逐步激活。
// 与 startEdgeFlow 同套路用 rAF 直改 @antv/g 元素 style：节点缩放走 setLocalScale（只改分解变换的 scale 分量、与平移解耦，
// 不把节点打回原点），边生长碰 key 路径的 lineDash/offset、回退淡入碰 group.opacity；预置隐藏发生在 reshape/取景之后
// （不被布局重绘清掉）、首帧绘制之前（不闪一下全显），结束写回基线并松手交还状态样式，随即交给常态微动接管「一直在动」
let entranceRaf = 0;
let entranceNodes: Array<{
  el: { setLocalScale: (scale: number) => void; style: { opacity?: number } };
  delay: number;
  done?: boolean;
}> = [];
// 边两种揭示：key 非空=连线生长（按路径长 len 推 lineDashOffset 由 len→0 描边），key 为空=回退随两端节点淡入（碰 group.opacity）
let entranceEdges: Array<{
  style: { opacity?: number };
  key: { style: { lineDash?: number | number[]; lineDashOffset?: number } } | null;
  len: number;
  delay: number;
  done?: boolean;
}> = [];

const ENTRANCE_NODE_DUR = 520;
const ENTRANCE_EDGE_DUR = 460;
const ENTRANCE_EDGE_LAG = 120;
// 节点错峰总铺开时长：径向由质心向外，取较短的 400ms 让节点快速就位，随后把主戏交给边的描边生长
const ENTRANCE_NODE_STAGGER = 400;
// 节点起始缩放：略小以有「聚拢成形」感，随淡入回到原尺寸
const ENTRANCE_NODE_SCALE_FROM = 0.86;

/** 把入场元素写回基线（全显、原始缩放、边回实线）并松开引用；元素可能已随图销毁，防御式忽略 */
function snapEntranceToBase() {
  for (const item of entranceNodes) {
    try {
      item.el.style.opacity = 1;
      item.el.setLocalScale(1);
    } catch {
      /* 图已销毁 */
    }
  }
  for (const item of entranceEdges) {
    try {
      // 边回到淡网静息透明度与实线（而非写死 1），否则会盖掉淡网基线、让边重新变实变浓
      item.style.opacity = EDGE_OPACITY;
      if (item.key) {
        item.key.style.lineDash = 0;
        item.key.style.lineDashOffset = 0;
      }
    } catch {
      /* 图已销毁 */
    }
  }
  entranceNodes = [];
  entranceEdges = [];
}

/** 停止入场动画并写回基线 */
function stopEntrance() {
  if (entranceRaf) {
    cancelAnimationFrame(entranceRaf);
    entranceRaf = 0;
  }
  snapEntranceToBase();
}

/**
 * 播放一次「连线生长」开场：节点按到质心距离轻微径向错峰淡入就位、边随后沿路径描边生长；预置隐藏须在取景之后调用（避免被 reshape 重绘覆盖）。
 * 结束写回基线并回调（用于随后启动 hub 涟漪与微动）。偏好减少动效或无元素时直接完成
 */
function runEntrance(graph: Graph, onDone: () => void) {
  stopEntrance();
  if (prefersReducedMotion()) {
    onDone();
    return;
  }
  const canvas = graph.getCanvas();
  const data = graph.getNodeData();
  // 先求质心与各节点到质心的位置，用于「由内向外」的径向错峰
  let cx = 0;
  let cy = 0;
  let cnt = 0;
  const posById: Record<string, { x: number; y: number }> = {};
  for (const datum of data) {
    const id = String(datum.id);
    const pos = graph.getElementPosition(id);
    const x = Number(pos[0]);
    const y = Number(pos[1]);
    if (!Number.isFinite(x) || !Number.isFinite(y)) {
      continue;
    }
    posById[id] = { x, y };
    cx += x;
    cy += y;
    cnt += 1;
  }
  if (cnt > 0) {
    cx /= cnt;
    cy /= cnt;
  }
  let maxDist = 1;
  for (const id in posById) {
    maxDist = Math.max(maxDist, Math.hypot(posById[id].x - cx, posById[id].y - cy));
  }
  // 每节点错峰 delay 正比于到质心的归一化距离：中心先亮、逐圈向外，节点快速就位
  const nodeDelay: Record<string, number> = {};
  for (const id in posById) {
    const p = posById[id];
    nodeDelay[id] = (Math.hypot(p.x - cx, p.y - cy) / maxDist) * ENTRANCE_NODE_STAGGER;
    const el = canvas.document.getElementById(id) as unknown as {
      setLocalScale: (scale: number) => void;
      style: { opacity?: number };
    } | null;
    if (!el) {
      continue;
    }
    // setLocalScale 绕节点局部原点(0,0=节点中心)缩放，且与 style.x/y 的平移解耦，不会把节点打回原点
    el.style.opacity = 0;
    el.setLocalScale(ENTRANCE_NODE_SCALE_FROM);
    entranceNodes.push({ el, delay: nodeDelay[id] });
  }
  // 边的静息透明度（淡网 0.34）：grow 期间边即以此透明度可见、揭示交给 lineDash；回退淡入则渐入到此值
  const edgeOpacity = EDGE_OPACITY;
  for (const datum of graph.getEdgeData()) {
    const el = canvas.document.getElementById(String(datum.id)) as unknown as {
      style: { opacity?: number };
      getShape?: (
        name: string
      ) => { style: { lineDash?: number | number[]; lineDashOffset?: number } } | undefined;
    } | null;
    if (!el) {
      continue;
    }
    // 边在两端节点都浮现后再生长：取较晚一端的 delay 加滞后
    const sd = nodeDelay[String(datum.source)] ?? 0;
    const td = nodeDelay[String(datum.target)] ?? 0;
    const delay = Math.max(sd, td) + ENTRANCE_EDGE_LAG;
    // 连线生长：拿 key 主路径与其总长，用 lineDash 做「描边由短到长」；拿不到长度则回退淡入
    const key = el.getShape?.("key");
    const measurable = key as unknown as { getTotalLength?: () => number } | undefined;
    let len = 0;
    if (measurable?.getTotalLength) {
      try {
        len = measurable.getTotalLength();
      } catch {
        len = 0;
      }
    }
    if (key && len > 0) {
      el.style.opacity = edgeOpacity; // 生长期间边以变体静息透明度可见，揭示交给 lineDash
      key.style.lineDash = [len, len];
      key.style.lineDashOffset = len; // 起始整条隐入 gap
      entranceEdges.push({ style: el.style, key, len, delay });
      continue;
    }
    el.style.opacity = 0;
    entranceEdges.push({ style: el.style, key: null, len: 0, delay });
  }
  if (entranceNodes.length === 0) {
    snapEntranceToBase();
    onDone();
    return;
  }
  // 纯 ease-out 缓动：末端精确落到 1、无越界回弹，去掉「弹跳」这个显廉价的动作
  const easeOutCubic = (p: number) => 1 - Math.pow(1 - p, 3);
  // 总时长取节点波前与边波前的较晚者，留 60ms 收尾
  let maxNodeDelay = 0;
  for (const item of entranceNodes) {
    maxNodeDelay = Math.max(maxNodeDelay, item.delay);
  }
  let maxEdgeDelay = 0;
  for (const item of entranceEdges) {
    maxEdgeDelay = Math.max(maxEdgeDelay, item.delay);
  }
  const total = Math.max(maxNodeDelay + ENTRANCE_NODE_DUR, maxEdgeDelay + ENTRANCE_EDGE_DUR) + 60;
  let startTs = 0;
  const step = (ts: number) => {
    if (!startTs) {
      startTs = ts;
    }
    const elapsed = ts - startTs;
    // 只写正在动的波前：未到 delay 的仍是预置隐藏、无需触碰；到 p=1 的定格后标记 done 不再遍历写，压低大图每帧开销
    for (const item of entranceNodes) {
      if (item.done || elapsed < item.delay) {
        continue;
      }
      const p = Math.min((elapsed - item.delay) / ENTRANCE_NODE_DUR, 1);
      const e = easeOutCubic(p);
      item.el.style.opacity = e;
      item.el.setLocalScale(ENTRANCE_NODE_SCALE_FROM + (1 - ENTRANCE_NODE_SCALE_FROM) * e);
      if (p >= 1) {
        item.done = true;
      }
    }
    for (const item of entranceEdges) {
      if (item.done || elapsed < item.delay) {
        continue;
      }
      const p = Math.min((elapsed - item.delay) / ENTRANCE_EDGE_DUR, 1);
      const e = easeOutCubic(p);
      if (item.key) {
        // 描边由短到长：offset 从 len→0，配合 lineDash=[len,len] 让实线段逐步覆盖整条
        item.key.style.lineDashOffset = item.len * (1 - e);
      } else {
        // 回退淡入：渐入到变体静息透明度（而非 1），与淡网基线一致
        item.style.opacity = e * edgeOpacity;
      }
      if (p >= 1) {
        item.done = true;
      }
    }
    if (elapsed >= total) {
      entranceRaf = 0;
      snapEntranceToBase();
      onDone();
      return;
    }
    entranceRaf = requestAnimationFrame(step);
  };
  entranceRaf = requestAnimationFrame(step);
}

// 常态 hub 涟漪：仅给度数最高的少数核心实体（renderGraph 里按 deg>=6 取至多 4 个存入 hubRippleSpec）叠一层向外扩散的柔光环
// 点出重点又不喧闹。环是 append 到节点组的 @antv/g Circle——子图形以局部 (0,0)=节点中心为原点、随相机缩放，pointerEvents:none 不拦交互；
// rAF 逐帧推 r 与描边透明度形成水波。聚焦时停（focus 有自己的边流动戏份），散焦重建
let hubRippleRaf = 0;
let hubRippleSpec: Array<{ id: string; color: string; baseR: number }> = [];
let hubRippleRings: Array<{ ring: GCircle; baseR: number; phase: number }> = [];
// 聚焦时把涟漪限定在仍点亮的 hub（焦点自身或其 hub 邻居）；null 表示全量（常态）
let hubRippleFilter: Set<string> | null = null;

const HUB_RIPPLE_PERIOD = 2600;
const HUB_RIPPLE_SPREAD = 22;
const HUB_RIPPLE_RINGS = 2;

/** 停止涟漪并移除环图形，保留 spec 以便散焦后重建 */
function stopHubRipple() {
  if (hubRippleRaf) {
    cancelAnimationFrame(hubRippleRaf);
    hubRippleRaf = 0;
  }
  for (const item of hubRippleRings) {
    try {
      item.ring.remove();
    } catch {
      /* 图已销毁 */
    }
  }
  hubRippleRings = [];
}

/** 彻底清除涟漪（含 spec 与过滤），用于重建图 */
function clearHubRipple() {
  stopHubRipple();
  hubRippleSpec = [];
  hubRippleFilter = null;
}

/** 依 hubRippleSpec 为各核心实体建环并逐帧扩散；无 spec / 偏好减少动效则不建 */
function buildHubRipple(graph: Graph) {
  stopHubRipple();
  if (hubRippleSpec.length === 0 || prefersReducedMotion()) {
    return;
  }
  const canvas = graph.getCanvas();
  for (const spec of hubRippleSpec) {
    // 聚焦过滤：只给仍点亮的 hub 建环，淡出的 hub 跳过
    if (hubRippleFilter && !hubRippleFilter.has(spec.id)) {
      continue;
    }
    const host = canvas.document.getElementById(spec.id) as unknown as {
      appendChild?: (child: GCircle) => void;
    } | null;
    if (!host?.appendChild) {
      continue;
    }
    for (let k = 0; k < HUB_RIPPLE_RINGS; k++) {
      const ring = new GCircle({
        style: {
          cx: 0,
          cy: 0,
          r: spec.baseR,
          fill: "none",
          stroke: spec.color,
          lineWidth: 2,
          strokeOpacity: 0,
          pointerEvents: "none",
          zIndex: -2
        }
      });
      host.appendChild(ring);
      hubRippleRings.push({ ring, baseR: spec.baseR, phase: k / HUB_RIPPLE_RINGS });
    }
  }
  if (hubRippleRings.length === 0) {
    return;
  }
  const step = (ts: number) => {
    for (const item of hubRippleRings) {
      const p = (((ts / HUB_RIPPLE_PERIOD + item.phase) % 1) + 1) % 1;
      item.ring.style.r = item.baseR + p * HUB_RIPPLE_SPREAD;
      // 越向外越淡、越细，收口成柔和水波
      item.ring.style.strokeOpacity = (1 - p) * 0.35;
      item.ring.style.lineWidth = 2 - p;
    }
    hubRippleRaf = requestAnimationFrame(step);
  };
  hubRippleRaf = requestAnimationFrame(step);
}

// 常态微动：入场落定后，让每个节点绕其基准位做极小幅（数 px）的正弦漂移——整张图像悬浮的星丛般持续轻轻游动，
// 满足「页面一直在动」又不牺牲可读性：标签始终水平、空间关系稳定、点击命中随视觉元素同步移动。
// 直接 setLocalPosition 改 @antv/g 节点组的平移分量（与 setLocalScale 一样只碰单个变换分量，不清缩放），
// 绕过数据模型故 minimap / 布局不被逐帧惊动、开销只在画布重绘。振幅/周期/相位按节点 id 哈希派生（跨重捕获稳定不跳变），
// 各轴周期不等故每个节点画一枚微椭圆而非直线来回。聚焦时暂停（放大的子网静止便于细看）、散焦恢复，
// 被拖拽的节点当帧跳过、落点后重设基准，偏好减少动效或大图（>300 逐帧移动开销偏高）则不启用
let ambientRaf = 0;
let ambientStart = 0;
let ambientNodes: Array<{
  id: string;
  el: { setLocalPosition: (x: number, y: number) => void };
  baseX: number;
  baseY: number;
  ax: number;
  ay: number;
  wx: number;
  wy: number;
  px: number;
  py: number;
}> = [];
// 正在拖拽的节点 id：ambient 当帧跳过它交给 drag-element，落点后在 DRAG_END 重设其基准
let ambientDragId = "";

const AMBIENT_MAX_NODES = 300;
const AMBIENT_RAMP = 900;

/** 字符串稳定哈希，从节点 id 派生每节点的漂移参数，保证同一节点每次派生一致、重捕获不跳变 */
function hashStr(text: string): number {
  let h = 0;
  for (let i = 0; i < text.length; i++) {
    h = (h * 31 + text.charCodeAt(i)) >>> 0;
  }
  return h;
}

/** 停止常态微动并把各节点写回基准位（元素可能已随图销毁，防御式忽略） */
function stopAmbient() {
  if (ambientRaf) {
    cancelAnimationFrame(ambientRaf);
    ambientRaf = 0;
  }
  for (const item of ambientNodes) {
    try {
      item.el.setLocalPosition(item.baseX, item.baseY);
    } catch {
      /* 图已销毁 */
    }
  }
  ambientNodes = [];
  ambientStart = 0;
}

/** 采集全体节点的基准位与漂移参数（入场后 / 散焦后启动时调用） */
function captureAmbient(graph: Graph) {
  ambientNodes = [];
  const canvas = graph.getCanvas();
  for (const datum of graph.getNodeData()) {
    const id = String(datum.id);
    const el = canvas.document.getElementById(id) as unknown as {
      setLocalPosition: (x: number, y: number) => void;
    } | null;
    if (!el) {
      continue;
    }
    const pos = graph.getElementPosition(id);
    const baseX = Number(pos[0]);
    const baseY = Number(pos[1]);
    if (!Number.isFinite(baseX) || !Number.isFinite(baseY)) {
      continue;
    }
    const h = hashStr(id);
    ambientNodes.push({
      id,
      el,
      baseX,
      baseY,
      // 振幅 3.5~6.9px；x/y 周期 3.5~7s 且互不相等；相位散布 [0,2π)
      ax: 3.5 + (h % 35) / 10,
      ay: 3.5 + ((h >> 3) % 35) / 10,
      wx: (2 * Math.PI) / (3500 + (h % 3500)),
      wy: (2 * Math.PI) / (3500 + ((h >> 5) % 3500)),
      px: (h % 628) / 100,
      py: ((h >> 7) % 628) / 100
    });
  }
}

/** 启动常态微动；偏好减少动效 / 无节点 / 大图则不启用（仍保留 hub 涟漪） */
function startAmbient(graph: Graph) {
  stopAmbient();
  if (prefersReducedMotion()) {
    return;
  }
  captureAmbient(graph);
  if (ambientNodes.length === 0 || ambientNodes.length > AMBIENT_MAX_NODES) {
    ambientNodes = [];
    return;
  }
  const step = (ts: number) => {
    if (!ambientStart) {
      ambientStart = ts;
    }
    // 振幅在起始 ~0.9s 内从 0 缓入，避免入场结束瞬间的位移突跳，也让「活起来」更从容
    const ramp = Math.min((ts - ambientStart) / AMBIENT_RAMP, 1);
    for (const item of ambientNodes) {
      if (item.id === ambientDragId) {
        continue;
      }
      item.el.setLocalPosition(
        item.baseX + ramp * item.ax * Math.sin(ts * item.wx + item.px),
        item.baseY + ramp * item.ay * Math.sin(ts * item.wy + item.py)
      );
    }
    ambientRaf = requestAnimationFrame(step);
  };
  ambientRaf = requestAnimationFrame(step);
}

/**
 * 运行期切换视觉主题：改模块量 vizTheme，再 updateNodeData 把节点标记为脏以触发 draw 重跑样式映射（映射按 live vizTheme 现算、
 * 才是真正决定最终样式者；updateNodeData 的 style 载荷只为标脏、与映射同值）。只重绘不重排、节点不动。
 * 同步刷新 hub 涟漪的半径与颜色以贴合新尺寸；draw 会重建图元，故先停涟漪与微动、draw 后再重建，避免其直接改的图元被 draw 波及
 */
function applyTheme(graph: Graph, theme: VizTheme) {
  vizTheme = theme;
  stopEdgeFlow();
  stopAmbient();
  stopHubRipple();
  graph.updateNodeData(
    graph.getNodeData().map((node) => ({
      id: String(node.id),
      style: {
        ...themeNodeStyle(
          theme,
          String(node.data?.baseColor ?? FALLBACK_COLOR),
          Number(node.data?.degree ?? 0)
        )
      }
    }))
  );
  // 涟漪半径 / 颜色贴合主题化后的新尺寸与新填充
  for (const spec of hubRippleSpec) {
    const datum = graph.getNodeData(spec.id);
    const s = themeNodeStyle(
      theme,
      String(datum?.data?.baseColor ?? FALLBACK_COLOR),
      Number(datum?.data?.degree ?? 0)
    );
    spec.baseR = s.size / 2;
    spec.color = s.fill;
  }
  void graph.draw().then(() => {
    buildHubRipple(graph);
    startAmbient(graph);
  });
}

/**
 * 运行期切换连线形状 / 箭头：模块量 edgeCurved/edgeArrow 由 handler 改好，再 updateEdgeData 把边标记为脏以触发 draw 重跑样式映射
 * （curveOffset/endArrow 映射按 live 模块量现算，0=直线）。只重绘不重排。同 applyTheme：draw 前后停 / 重建涟漪与微动
 */
function applyEdgeLook(graph: Graph) {
  stopEdgeFlow();
  stopAmbient();
  stopHubRipple();
  graph.updateEdgeData(
    graph.getEdgeData().map((edge) => ({
      id: String(edge.id),
      style: { curveOffset: edgeCurved ? EDGE_CURVE_OFFSET : 0, endArrow: edgeArrow }
    }))
  );
  void graph.draw().then(() => {
    buildHubRipple(graph);
    startAmbient(graph);
  });
}

/**
 * 点击聚焦：淡出与该节点无关的元素，把该节点及其邻居居中放大
 * 只吃 graph 实例（首次聚焦顺带把相机快照到 savedView），供点击 / 空白 / Esc 复用，规避渲染闭包过期
 */
function applyFocus(graph: Graph, id: string) {
  // 聚焦优先：入场动画若在播则立即落定，把动效焦点让给选中实体
  stopEntrance();
  // 暂停常态微动：聚焦是「静下来细看」的场景，让被放大的子网稳住、不再游动
  stopAmbient();
  // 首次进入聚焦时记下当前相机（缩放 + 屏幕中心对应的世界坐标），供散焦还原；A→B 连续切换不覆盖，保留最初视角
  if (savedView === null) {
    const [w, h] = graph.getSize();
    savedView = { zoom: graph.getZoom(), center: graph.getCanvasByViewport([w / 2, h / 2]) };
  }
  const neighborIds = graph.getNeighborNodesData(id).map((node) => String(node.id));
  const keepNodes = new Set<string>([id, ...neighborIds]);
  const keepEdges = new Set(graph.getRelatedEdgesData(id).map((edge) => String(edge.id)));
  const states: Record<string, string[]> = {};
  for (const node of graph.getNodeData()) {
    // 相关节点置 related 点亮标签（含低度数邻居），无关节点 inactive 淡出
    states[String(node.id)] = keepNodes.has(String(node.id)) ? ["related"] : ["inactive"];
  }
  for (const edge of graph.getEdgeData()) {
    states[String(edge.id)] = keepEdges.has(String(edge.id)) ? ["flow"] : ["inactive"];
  }
  void graph.setElementState(states);
  // 居中放大到该节点的自我网络（focusElement 只平移，靠 zoomToNodes 补上缩放）
  zoomToNodes(graph, [id, ...neighborIds], true);
  // 仅给聚焦节点的关系边启动流动动画，开销与语义都限定在选中实体的关系上
  startEdgeFlow(graph, [...keepEdges]);
  // 保留核心涟漪，但仅限仍点亮的 hub（焦点自身或其 hub 邻居）；淡出的 hub 不再脉动，免得在暗色 veil 下喧闹
  hubRippleFilter = keepNodes;
  buildHubRipple(graph);
}

/** 散焦：清空淡出状态，并把相机还原到聚焦前的快照（无快照则不动），避免停在放大比例回不去 */
function clearFocus(graph: Graph) {
  // 先停流动动画，避免继续写入即将清态的边图形
  stopEdgeFlow();
  const states: Record<string, string[]> = {};
  for (const node of graph.getNodeData()) {
    states[String(node.id)] = [];
  }
  for (const edge of graph.getEdgeData()) {
    states[String(edge.id)] = [];
  }
  void graph.setElementState(states);
  // 还原聚焦前的相机：先复原缩放，再把快照的世界中心点平移回屏幕中心（getPosition/translateTo 跨缩放不可逆，故用中心锚点，已 headless 实测精确复原）
  if (savedView) {
    const view = savedView;
    savedView = null;
    const [w, h] = graph.getSize();
    void graph.zoomTo(view.zoom, true).then(() => {
      const p = graph.getViewportByCanvas(view.center);
      return graph.translateBy([w / 2 - p[0], h / 2 - p[1]], true);
    });
  }
  // 散焦恢复全量 hub 涟漪：此前处于聚焦过滤态才重建，避免空白处反复点击导致光环频繁重启闪烁
  if (hubRippleFilter !== null) {
    hubRippleFilter = null;
    buildHubRipple(graph);
  } else if (hubRippleRaf === 0) {
    buildHubRipple(graph);
  }
  // 恢复常态微动：仅在其确已停止时重启（空白处反复点击时它仍在跑，不重启免得相位回弹突跳）
  if (ambientRaf === 0) {
    startAmbient(graph);
  }
}

export function KnowledgeGraphPage() {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const graphRef = useRef<Graph | null>(null);
  // 当前聚焦节点 id，用 ref 供 G6 事件回调同步读取，实现再次点击同一节点即取消聚焦
  const focusedIdRef = useRef<string>("");

  const [loading, setLoading] = useState(false);
  const [view, setView] = useState<GraphView | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [activeEntity, setActiveEntity] = useState<string>("");
  const [focusName, setFocusName] = useState<string>("");
  const [zoomPct, setZoomPct] = useState(100);
  // 视觉设置：主题 / 连线形状 / 箭头，置于齿轮设置面板；默认 描边 + 直线 + 淡箭头
  const [vizThemeState, setVizThemeState] = useState<VizTheme>("outline");
  const [edgeCurvedState, setEdgeCurvedState] = useState(false);
  const [edgeArrowState, setEdgeArrowState] = useState(true);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const settingsRef = useRef<HTMLDivElement | null>(null);
  // 布局类型：力导（默认、工作视图）/ concentric 径向分层（展示视图）；经 ref 供 renderGraph 空依赖闭包读取
  const [layoutType, setLayoutType] = useState<"d3-force" | "concentric">("d3-force");
  const layoutRef = useRef<"d3-force" | "concentric">("d3-force");

  // 查询参数
  const [keyword, setKeyword] = useState("");
  const [depth, setDepth] = useState("2");
  const [limit, setLimit] = useState("200");

  // 实体搜索联想
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [suggestOpen, setSuggestOpen] = useState(false);
  const blurTimerRef = useRef<number | null>(null);

  // 知识库 / 文档范围筛选
  const [scopeOpen, setScopeOpen] = useState(false);
  const scopePanelRef = useRef<HTMLDivElement | null>(null);
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [activeKb, setActiveKb] = useState<KnowledgeBase | null>(null);
  const [docs, setDocs] = useState<KnowledgeDocument[]>([]);
  const [docsLoading, setDocsLoading] = useState(false);
  const [activeDoc, setActiveDoc] = useState<KnowledgeDocument | null>(null);
  // 当前范围（collection/doc），用 ref 保存以免 setState 同一 tick 过期，loadGraph 从此读取
  const scopeRef = useRef<{ collection?: string; doc?: string }>({});

  // 渲染图谱：每次加载重建实例，规避样式回调闭包过期
  const renderGraph = useCallback((data: GraphView) => {
    const container = containerRef.current;
    if (!container) {
      return;
    }
    // 重建前停掉上一张图的所有动效（边流动 / 入场 / hub 涟漪），清空聚焦相机快照，避免残留 rAF 与还原到上一张图视角
    // 先停后 destroy：让写回基线落在仍存活的元素上
    stopEdgeFlow();
    stopEntrance();
    stopAmbient();
    clearHubRipple();
    graphRef.current?.destroy();
    savedView = null;

    const colors = buildTypeColors(data.nodes);
    // 节点度数用于尺寸映射，连接越多的实体越大
    const degree: Record<string, number> = {};
    for (const edge of data.edges) {
      degree[edge.source] = (degree[edge.source] || 0) + 1;
      degree[edge.target] = (degree[edge.target] || 0) + 1;
    }
    // 碰撞半径按度数：节点视觉半径(12~30) + 22 呼吸间距，度数越高留白越多
    // 力导据此把邻居摊成环而非堆成实心球，是「均匀散布」的主力；间距取 22 让整图面积贴近视口、取景比例更接近 1（节点不缩小）
    // 22 的余量也是后续 reshapeToViewport 纵向压缩时不产生视觉重叠的安全垫
    const radiusById: Record<string, number> = {};
    for (const node of data.nodes) {
      radiusById[node.id] = 12 + Math.min(degree[node.id] || 0, 12) * 1.5 + 22;
    }
    // 标签分级门槛：取按度数降序第 LABEL_BUDGET 位的度数，度数达门槛的实体才常显标签
    // 实体越多门槛自动抬高，把概览常显标签控制在 ~45 个上下，压掉密集处的文字遮挡
    const sortedDegrees = data.nodes.map((node) => degree[node.id] || 0).sort((a, b) => b - a);
    const labelMinDegree =
      sortedDegrees.length > LABEL_BUDGET ? Math.max(sortedDegrees[LABEL_BUDGET], 1) : 0;
    // 常态涟漪的核心实体：取度数最高、且达「核心」门槛（deg>=6，与描边 / 标签加粗同一档）的至多 4 个作为脉动对象
    // 小图或无高连接实体时自然为空、不做涟漪；color / baseR 对齐主题化后的节点填充与视觉半径，让光环从节点边缘向外扩散
    hubRippleSpec = [...data.nodes]
      .filter((node) => (degree[node.id] || 0) >= 6)
      .sort((a, b) => (degree[b.id] || 0) - (degree[a.id] || 0))
      .slice(0, 4)
      .map((node) => {
        const s = themeNodeStyle(
          vizTheme,
          colors[node.type?.trim() || ""] || FALLBACK_COLOR,
          degree[node.id] || 0
        );
        return { id: node.id, color: s.fill, baseR: s.size / 2 };
      });
    // 节点 id→名称映射，供边悬浮展示「实体A → 实体B」
    const nameById: Record<string, string> = {};
    for (const node of data.nodes) {
      nameById[node.id] = node.name;
    }

    const nodes: NodeData[] = data.nodes.map((node) => ({
      id: node.id,
      data: {
        name: node.name,
        type: node.type || "",
        description: node.description || "",
        degree: degree[node.id] || 0,
        // 该节点的「类型原色」，供主题化（提亮 / 描边 / 半透明）与运行期切主题时重算样式
        baseColor: colors[node.type?.trim() || ""] || FALLBACK_COLOR
      }
    }));
    // 边 id 加前缀与节点 id 去重：LightRAG 的节点/边 id 是两套独立整数序列会大量重叠，
    // 而 G6 节点与边共用同一 id 空间且要求全局唯一，撞 id 会让 render 抛 getPorts 异常、布局中止全堆在原点
    const edges: EdgeData[] = data.edges.map((edge) => ({
      id: `e_${edge.id}`,
      source: edge.source,
      target: edge.target,
      data: {
        label: edge.label || "",
        description: edge.description || "",
        sourceName: nameById[edge.source] || edge.source,
        targetName: nameById[edge.target] || edge.target
      }
    }));

    const graphData: GraphData = { nodes, edges };

    // 每次调用都按「当前 vizTheme」现算该节点主题化样式；draw() 会重新执行样式映射，故切主题只需改 vizTheme + draw 即生效
    const styleOf = (d: NodeData) =>
      themeNodeStyle(
        vizTheme,
        String(d.data?.baseColor ?? FALLBACK_COLOR),
        Number(d.data?.degree ?? 0)
      );

    const graph = new Graph({
      container,
      autoResize: true,
      data: graphData,
      layout:
        layoutRef.current === "concentric"
          ? {
              // 径向分层：度数越高越靠中心，hub 居中、其余按度数一圈圈向外；nodeSize 供碰撞检测取最大视觉尺寸
              type: "concentric",
              sortBy: "degree",
              preventOverlap: true,
              nodeSize: 60,
              equidistant: true
            }
          : {
              type: "d3-force",
              // 关闭力导逐帧动画：一次性落到收敛位置，去掉进入后 5-10 秒可见漂移与随之而来的自动缩小
              animation: false,
              // 局部斥力：strength 提供把簇撑开、填满页面的推力，distanceMax 封顶让斥力只作用于邻域——
              // 远处的簇不再互相排斥，整张图不会越算越大（离群点被无限外推正是长线与「中间挤四周空」的根源）
              manyBody: { strength: -150, distanceMax: 280, theta: 0.9 },
              // 连线：理想长度给足呼吸感，strength 抬到 0.55 压制 hub↔hub 的长线（d3 默认 1/度数，度数一高弹簧极弱、被斥力拉长）
              link: { distance: 95, strength: 0.55 },
              // 碰撞半径按节点实际视觉大小 + 呼吸间距（radiusById）：叶子摊成环而非堆成实心球，稠密簇被均匀撑开
              collide: {
                radius: (node: { id: string }) => radiusById[node.id] ?? 34,
                strength: 1,
                iterations: 2
              },
              // 轻微对称向心力让团更紧凑成圆（作为 reshapeToViewport 的输入），横向铺满交给收敛后的保面积拉伸而非硬调力
              x: { strength: 0.05 },
              y: { strength: 0.05 }
            },
      node: {
        style: {
          // 尺寸 / 填充 / 填充透明度 / 描边 / 光晕统一按当前视觉主题现算（styleOf 读 live vizTheme）：
          // 非 vivid 主题缩小叶子拉层级、弱化色块（提亮 / 描边 / 半透明）、砍光晕，整图从「糖果罐」收成「星座网」
          size: (d: NodeData) => styleOf(d).size,
          fill: (d: NodeData) => styleOf(d).fill,
          fillOpacity: (d: NodeData) => styleOf(d).fillOpacity,
          stroke: (d: NodeData) => styleOf(d).stroke,
          lineWidth: (d: NodeData) => styleOf(d).lineWidth,
          // 同色柔光光晕：色/强度由主题决定（描边主题最弱、鲜彩最强），居中不偏移
          shadowColor: (d: NodeData) => styleOf(d).shadowColor,
          shadowBlur: (d: NodeData) => styleOf(d).shadowBlur,
          shadowOffsetX: 0,
          shadowOffsetY: 0,
          // 显式声明 opacity / labelOpacity 基线：G6 v5 清状态只回退到基础样式里已声明的键，缺省会让散焦后停在 inactive 的淡出值不复原
          opacity: 1,
          // 标签文本始终渲染，显隐交给 labelOpacity——这样聚焦时能用 related 态把被门槛隐去的低度数邻居标签整体点亮
          labelText: (d: NodeData) => String(d.data?.name ?? ""),
          labelPlacement: "bottom",
          // 度数层次：hub 标签更大更粗更深，叶子收敛，压掉满屏标签的杂乱
          labelFontSize: (d: NodeData) => 10 + Math.min(Number(d.data?.degree ?? 0), 12) * 0.4,
          labelFontWeight: (d: NodeData) => (Number(d.data?.degree ?? 0) >= 6 ? 600 : 400),
          labelFill: (d: NodeData) => (Number(d.data?.degree ?? 0) >= 6 ? "#1E293B" : "#64748B"),
          // 度数达门槛才常显（1），否则隐去（0）压掉密集处文字遮挡；聚焦时由 related 态覆盖为 1
          labelOpacity: (d: NodeData) => (Number(d.data?.degree ?? 0) >= labelMinDegree ? 1 : 0),
          labelBackground: true,
          labelBackgroundFill: "#ffffff",
          labelBackgroundOpacity: (d: NodeData) =>
            Number(d.data?.degree ?? 0) >= labelMinDegree ? 0.6 : 0,
          labelBackgroundRadius: 4
        },
        // 聚焦态：无关节点 inactive 淡出并彻底隐去标签；相关节点 related 覆盖 labelOpacity=1，
        // 把聚焦节点及其邻居的标签（含被门槛隐去的低度数邻居）整体点亮——聚焦就是要看清这些关联主题
        state: {
          inactive: {
            opacity: 0.12,
            labelOpacity: 0,
            labelBackgroundOpacity: 0
          },
          related: {
            labelOpacity: 1,
            labelBackgroundOpacity: 0.6
          }
        }
      },
      edge: {
        // 直线改二次贝塞尔曲线，中心辐射状的图更舒展，双向 / 平行关系也不再完全重叠
        type: "quadratic",
        style: {
          // 淡网基线：低透明度冷灰。形状与箭头可现场切——curveOffset 控制曲/直（0=直线）、endArrow 控制概览是否显淡箭头。
          // 这些键都显式声明，保证聚焦态覆盖后清态能回退（G6 v5 清态只回退 base 已声明的键）
          stroke: EDGE_STROKE,
          lineWidth: 1,
          // 函数读 live edgeCurved/edgeArrow：draw() 会重新执行样式映射，故切曲/直、切箭头只需改模块量 + draw 即生效
          curveOffset: () => (edgeCurved ? EDGE_CURVE_OFFSET : 0),
          endArrow: () => edgeArrow,
          endArrowType: "simple",
          endArrowSize: 6,
          opacity: EDGE_OPACITY,
          lineDash: 0
        },
        // 边不再常显英文标签（关系文案移入悬浮提示）
        state: {
          // 聚焦时无关边淡出
          inactive: {
            opacity: 0.05
          },
          // 聚焦节点的关系边：着色加粗 + 虚线 + 显方向，配合 lineDashOffset 逐帧推进形成流动。
          // 显式点亮 opacity 与 endArrow：无论概览把边压得多淡 / 是否显箭头，聚焦子网都清晰可辨、方向明确
          flow: {
            stroke: "#6366F1",
            lineWidth: 1.5,
            lineDash: [6, 6],
            opacity: 0.95,
            endArrow: true
          }
        }
      },
      // 只保留缩放 + 平移 + 拖拽节点；聚焦淡出改由 applyFocus/clearFocus 独占管理，
      // 不用内置 click-select / hover-activate，避免两套状态机争用 inactive 态导致散焦后颜色不复原
      behaviors: [
        // 缩放交给 Ctrl+滚轮 / 触控板捏合（捏合会合成 ctrlKey 滚轮），纯滚动留给平移
        { type: "zoom-canvas", sensitivity: 1.6, trigger: ["Control"] },
        "drag-canvas",
        "drag-element"
      ],
      plugins: [
        { type: "grid-line", follow: true },
        {
          type: "minimap",
          size: [180, 120],
          padding: 8,
          // 视口指示框：靛蓝描边 + 淡填充，一眼看清当前视野在全图中的位置
          maskStyle: { border: "1.5px solid #6366F1", background: "rgba(99, 102, 241, 0.12)" },
          // 缩略图容器：圆角 + 描边 + 柔和投影 + 近白底，与玻璃工具栏一致、节点在白底上更清晰
          containerStyle: {
            borderRadius: "12px",
            border: "1px solid #E2E8F0",
            boxShadow: "0 6px 16px rgba(15, 23, 42, 0.10)",
            background: "rgba(255, 255, 255, 0.92)",
            overflow: "hidden"
          }
        },
        {
          type: "tooltip",
          trigger: "hover",
          // 聚焦态下淡出（inactive）的节点/边不弹提示：半隐藏的元素不应再响应悬浮，把注意力留给聚焦子网
          enable: (event: IElementEvent) => {
            if (!focusedIdRef.current) {
              return true;
            }
            const targetId = event?.target?.id;
            if (targetId == null) {
              return true;
            }
            return !(graphRef.current?.getElementState(String(targetId)) ?? []).includes(
              "inactive"
            );
          },
          getContent: async (_event: unknown, items: Array<{ data?: Record<string, unknown> }>) => {
            const datum = items?.[0]?.data;
            if (!datum) {
              return "";
            }
            // 节点卡片：名称 + 中文类型 + 描述
            if (datum.name !== undefined) {
              const name = escapeHtml(String(datum.name ?? ""));
              const type = escapeHtml(entityTypeLabel(String(datum.type ?? "")));
              const desc = escapeHtml(String(datum.description ?? ""));
              return `<div style="max-width:280px;padding:4px 2px">
                <div style="font-weight:600;color:#0f172a;margin-bottom:2px">${name}</div>
                ${type ? `<div style="font-size:12px;color:#6366f1;margin-bottom:4px">${type}</div>` : ""}
                ${desc ? `<div style="font-size:12px;color:#475569;line-height:1.5;white-space:pre-line">${desc}</div>` : ""}
              </div>`;
            }
            // 边卡片：关系两端 + 可读关系描述（描述缺失回退关键词），替代画布上难懂的英文标签
            if (datum.sourceName !== undefined) {
              const from = escapeHtml(String(datum.sourceName ?? ""));
              const to = escapeHtml(String(datum.targetName ?? ""));
              const detail = escapeHtml(String(datum.description || datum.label || ""));
              return `<div style="max-width:280px;padding:4px 2px">
                <div style="font-size:11px;color:#94a3b8;margin-bottom:2px">关系</div>
                <div style="font-weight:600;color:#0f172a;margin-bottom:4px">${from} <span style="color:#6366f1">→</span> ${to}</div>
                ${detail ? `<div style="font-size:12px;color:#475569;line-height:1.5;white-space:pre-line">${detail}</div>` : ""}
              </div>`;
            }
            return "";
          }
        }
      ]
    });

    // 力导收敛后：先按视口比例把圆团横向摊开铺满页面（reshapeToViewport，仅力导），再按「保持节点当前大小」取景
    // （fitAllNodes 内部把比例夹在 [0.68,1.3]，避免为塞离群点把节点缩糊）。取景落定后播一次性入场动画，结束即启动常态 hub 涟漪与微动
    // 入场在 reshape / 取景之后预置隐藏并逐帧淡入，故不被布局重绘清掉、也不闪一下全显
    graph.on(GraphEvent.AFTER_LAYOUT, () => {
      const settled =
        layoutRef.current === "d3-force"
          ? reshapeToViewport(graph).then(() => fitAllNodes(graph, false))
          : fitAllNodes(graph, false);
      void settled.then(() =>
        runEntrance(graph, () => {
          buildHubRipple(graph);
          startAmbient(graph);
        })
      );
    });
    // 视口缩放 / 平移后同步右下角缩放比读数，wheel、捏合、按钮触发的变换都会走到这里
    graph.on(GraphEvent.AFTER_TRANSFORM, () => {
      setZoomPct(Math.round(graph.getZoom() * 100));
    });
    // 点击节点聚焦：淡出无关、居中放大；再次点击同一节点 / 点击空白 / Esc 均散焦还原
    graph.on<IElementEvent>(NodeEvent.CLICK, (event) => {
      const id = event.target?.id;
      if (id == null) {
        return;
      }
      const nodeId = String(id);
      // 再次点击当前已聚焦节点即取消聚焦，让淡出态能被同一操作对称撤销
      if (focusedIdRef.current === nodeId) {
        clearFocus(graph);
        focusedIdRef.current = "";
        setFocusName("");
        return;
      }
      applyFocus(graph, nodeId);
      focusedIdRef.current = nodeId;
      const datum = graph.getNodeData(nodeId);
      setFocusName(String(datum?.data?.name ?? nodeId));
    });
    graph.on(CanvasEvent.CLICK, () => {
      clearFocus(graph);
      focusedIdRef.current = "";
      setFocusName("");
    });
    // 拖拽某节点时 ambient 当帧跳过它（交给 drag-element 独占），落点后把它的基准位重设到落点，免得被漂移拉回原处
    graph.on<IElementEvent>(NodeEvent.DRAG_START, (event) => {
      ambientDragId = String(event.target?.id ?? "");
    });
    graph.on<IElementEvent>(NodeEvent.DRAG_END, (event) => {
      const id = String(event.target?.id ?? "");
      const item = ambientNodes.find((n) => n.id === id);
      if (item) {
        const pos = graph.getElementPosition(id);
        item.baseX = Number(pos[0]);
        item.baseY = Number(pos[1]);
      }
      ambientDragId = "";
    });
    graphRef.current = graph;
    void graph.render();
  }, []);

  const loadGraph = useCallback(
    async (entity?: string, overrides?: { depth?: number; limit?: number }) => {
      setLoading(true);
      setErrorMsg(null);
      try {
        const result = await getKnowledgeGraph({
          entity: entity || undefined,
          collection: scopeRef.current.collection,
          doc: scopeRef.current.doc,
          depth: overrides?.depth ?? Number(depth),
          limit: overrides?.limit ?? Number(limit)
        });
        setView(result);
        setActiveEntity(entity || "");
        setFocusName("");
        focusedIdRef.current = "";
        renderGraph(result);
      } catch (error) {
        const message = getErrorMessage(error, "加载图谱失败");
        setErrorMsg(message);
        setView(null);
        stopEdgeFlow();
        stopEntrance();
        stopAmbient();
        clearHubRipple();
        graphRef.current?.destroy();
        graphRef.current = null;
      } finally {
        setLoading(false);
      }
    },
    [depth, limit, renderGraph]
  );

  // 首次进入加载全图
  useEffect(() => {
    void loadGraph();
    return () => {
      stopEdgeFlow();
      stopEntrance();
      stopAmbient();
      clearHubRipple();
      graphRef.current?.destroy();
      graphRef.current = null;
    };
    // 仅首挂载执行，后续加载由交互触发
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 挂载拉知识库列表，供范围筛选；失败置空、不阻断图谱
  useEffect(() => {
    getKnowledgeBases()
      .then((list) => setKbs(list || []))
      .catch(() => setKbs([]));
  }, []);

  // 纯滚动平移画布：React onWheel 是被动监听 preventDefault 无效，故用原生非被动监听
  useEffect(() => {
    const container = containerRef.current;
    if (!container) {
      return;
    }
    const onWheel = (event: WheelEvent) => {
      const graph = graphRef.current;
      if (!graph) {
        return;
      }
      // Ctrl / ⌘ / 触控板捏合交给 G6 缩放，纯滚动才平移
      if (event.ctrlKey || event.metaKey) {
        return;
      }
      event.preventDefault();
      void graph.translateBy([-event.deltaX, -event.deltaY], false);
    };
    container.addEventListener("wheel", onWheel, { passive: false });
    return () => container.removeEventListener("wheel", onWheel);
  }, []);

  // Esc 散焦还原
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && graphRef.current) {
        clearFocus(graphRef.current);
        focusedIdRef.current = "";
        setFocusName("");
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  // 实体联想搜索（防抖）
  useEffect(() => {
    if (!suggestOpen) {
      return;
    }
    const word = keyword.trim();
    let active = true;
    const handle = window.setTimeout(() => {
      searchGraphEntities(word, 10)
        .then((list) => {
          if (active) {
            setSuggestions(list || []);
          }
        })
        .catch(() => {
          if (active) {
            setSuggestions([]);
          }
        });
    }, 200);
    return () => {
      active = false;
      window.clearTimeout(handle);
    };
  }, [keyword, suggestOpen]);

  // 齿轮 / 范围弹层点击外部关闭
  useEffect(() => {
    if (!settingsOpen && !scopeOpen) {
      return;
    }
    const handleClick = (event: MouseEvent) => {
      const target = event.target as Node;
      if (settingsOpen && settingsRef.current && !settingsRef.current.contains(target)) {
        setSettingsOpen(false);
      }
      if (scopeOpen && scopePanelRef.current && !scopePanelRef.current.contains(target)) {
        setScopeOpen(false);
      }
    };
    document.addEventListener("mousedown", handleClick);
    return () => document.removeEventListener("mousedown", handleClick);
  }, [settingsOpen, scopeOpen]);

  const handleSuggestFocus = () => {
    if (blurTimerRef.current) {
      window.clearTimeout(blurTimerRef.current);
      blurTimerRef.current = null;
    }
    setSuggestOpen(true);
  };

  const handleSuggestBlur = () => {
    blurTimerRef.current = window.setTimeout(() => setSuggestOpen(false), 150);
  };

  const handleSelectEntity = (entity: string) => {
    setKeyword(entity);
    setSuggestOpen(false);
    void loadGraph(entity);
  };

  const handleSearch = () => {
    setSuggestOpen(false);
    void loadGraph(keyword.trim());
  };

  // 全图：清空实体聚焦与库/文档范围，回到真·全局全图
  const handleReset = () => {
    setKeyword("");
    setSuggestOpen(false);
    setActiveKb(null);
    setActiveDoc(null);
    setDocs([]);
    scopeRef.current = {};
    void loadGraph();
  };

  // 右下角缩放控制：相对放大 / 缩小、重置为 100%，读数由 AFTER_TRANSFORM 同步
  const handleZoomIn = () => {
    void graphRef.current?.zoomBy(1.2, true);
  };
  const handleZoomOut = () => {
    void graphRef.current?.zoomBy(0.8, true);
  };
  const handleZoomReset = () => {
    void graphRef.current?.zoomTo(1, true);
  };

  // 深度/节点上限改动即时重载当前视图，保持当前聚焦实体
  const handleDepthChange = (value: string) => {
    setDepth(value);
    void loadGraph(activeEntity || undefined, { depth: Number(value), limit: Number(limit) });
  };

  const handleLimitChange = (value: string) => {
    setLimit(value);
    void loadGraph(activeEntity || undefined, { depth: Number(depth), limit: Number(value) });
  };

  // 切换布局：只用现有数据重排、不重新请求后端；经 ref 让 renderGraph 空依赖闭包读到新布局
  const handleLayoutChange = (type: "d3-force" | "concentric") => {
    setLayoutType(type);
    layoutRef.current = type;
    if (view) {
      renderGraph(view);
    }
  };

  // 视觉设置切换：改主题 / 连线形状 / 箭头即时重绘（只重绘、不重排）
  const handlePickTheme = (theme: VizTheme) => {
    setVizThemeState(theme);
    const graph = graphRef.current;
    if (graph) {
      applyTheme(graph, theme);
    }
  };
  const handlePickEdgeCurved = (curved: boolean) => {
    setEdgeCurvedState(curved);
    edgeCurved = curved;
    const graph = graphRef.current;
    if (graph) {
      applyEdgeLook(graph);
    }
  };
  const handlePickEdgeArrow = (arrow: boolean) => {
    setEdgeArrowState(arrow);
    edgeArrow = arrow;
    const graph = graphRef.current;
    if (graph) {
      applyEdgeLook(graph);
    }
  };

  // 拉某知识库的文档列表，供二级范围选择
  const loadKbDocs = useCallback(async (kbId: string) => {
    setDocsLoading(true);
    try {
      const list = await getDocuments(kbId, { size: 200 });
      setDocs(list);
    } catch {
      setDocs([]);
    } finally {
      setDocsLoading(false);
    }
  }, []);

  // 选知识库：切库并清文档选择，重载该库子图（传 null 表示全部知识库）
  const handleSelectKb = (kb: KnowledgeBase | null) => {
    setActiveKb(kb);
    setActiveDoc(null);
    setDocs([]);
    scopeRef.current = { collection: kb?.collectionName };
    if (kb) {
      void loadKbDocs(kb.id);
    }
    void loadGraph(activeEntity || undefined);
  };

  // 选文档：重载该文档子图（传 null 表示整个知识库）
  const handleSelectDoc = (doc: KnowledgeDocument | null) => {
    setActiveDoc(doc);
    scopeRef.current = { collection: activeKb?.collectionName, doc: doc?.id };
    void loadGraph(activeEntity || undefined);
  };

  const scopeLabel = activeDoc ? activeDoc.docName : activeKb ? activeKb.name : "全部";
  const stats = view ? { nodes: view.nodes.length, edges: view.edges.length } : null;
  const isEmpty = !loading && !errorMsg && view !== null && view.nodes.length === 0;

  return (
    <div className="relative h-full w-full overflow-hidden bg-white">
      {/* G6 画布铺满整个视口；衬一层极淡径向渐变增加空间纵深，画布透明故渐变透出、与网格线叠成柔和台面 */}
      <div
        ref={containerRef}
        className="h-full w-full"
        style={{
          background: "radial-gradient(ellipse at 50% 42%, #ffffff 0%, #f4f6fa 58%, #e9edf4 100%)"
        }}
      />

      {/* 左上角悬浮控件：搜索 + 范围 + 全图 + 显示设置，以及聚焦态与图例 */}
      <div className="absolute left-4 top-4 z-10 flex max-w-[calc(100%-2rem)] flex-col gap-2">
        <div className="flex items-center gap-1 rounded-2xl border border-slate-200 bg-white/90 p-1.5 shadow-sm backdrop-blur">
          <div className="relative w-64" onFocus={handleSuggestFocus} onBlur={handleSuggestBlur}>
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
            <Input
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  handleSearch();
                }
              }}
              placeholder="搜索实体，回车聚焦其子图..."
              className="h-9 rounded-xl border-transparent bg-transparent pl-9 shadow-none focus-visible:ring-0"
              autoComplete="off"
            />
            {suggestOpen && suggestions.length > 0 ? (
              <div
                className="absolute z-20 mt-1 max-h-64 w-full overflow-auto rounded-xl border border-slate-200 bg-white py-1 shadow-lg"
                onMouseDown={(event) => event.preventDefault()}
              >
                {suggestions.map((item) => (
                  <button
                    key={item}
                    type="button"
                    onMouseDown={(event) => {
                      event.preventDefault();
                      handleSelectEntity(item);
                    }}
                    className="block w-full truncate px-3 py-1.5 text-left text-sm text-slate-700 hover:bg-slate-50"
                  >
                    {item}
                  </button>
                ))}
              </div>
            ) : null}
          </div>

          {/* 范围：按知识库 / 文档筛选子图 */}
          <div className="relative" ref={scopePanelRef}>
            <Button
              variant="ghost"
              size="sm"
              className={cn(
                "h-9 shrink-0 gap-1 rounded-xl px-2.5 text-slate-600",
                scopeOpen && "bg-slate-100 text-slate-900"
              )}
              onClick={() => setScopeOpen((open) => !open)}
              title="按知识库 / 文档筛选"
            >
              <Library className="h-4 w-4" />
              <span className="max-w-[7rem] truncate">{scopeLabel}</span>
            </Button>
            {scopeOpen ? (
              <div className="absolute left-0 top-full z-20 mt-2 w-64 space-y-3 rounded-2xl border border-slate-200 bg-white p-3 shadow-lg">
                <div>
                  <div className="mb-1.5 text-xs font-medium text-slate-500">知识库</div>
                  <div className="max-h-40 space-y-1 overflow-auto">
                    <button
                      type="button"
                      onClick={() => handleSelectKb(null)}
                      className={cn(
                        "block w-full truncate rounded-lg border px-2.5 py-1 text-left text-sm transition",
                        !activeKb
                          ? "border-indigo-500 bg-indigo-50 text-indigo-600"
                          : "border-slate-200 text-slate-600 hover:bg-slate-50"
                      )}
                    >
                      全部知识库
                    </button>
                    {kbs.map((kb) => (
                      <button
                        key={kb.id}
                        type="button"
                        onClick={() => handleSelectKb(kb)}
                        className={cn(
                          "block w-full truncate rounded-lg border px-2.5 py-1 text-left text-sm transition",
                          activeKb?.id === kb.id
                            ? "border-indigo-500 bg-indigo-50 text-indigo-600"
                            : "border-slate-200 text-slate-600 hover:bg-slate-50"
                        )}
                      >
                        {kb.name}
                      </button>
                    ))}
                  </div>
                </div>
                {activeKb ? (
                  <div>
                    <div className="mb-1.5 flex items-center gap-1.5 text-xs font-medium text-slate-500">
                      文档
                      {docsLoading ? <Loader2 className="h-3 w-3 animate-spin" /> : null}
                    </div>
                    <div className="max-h-40 space-y-1 overflow-auto">
                      <button
                        type="button"
                        onClick={() => handleSelectDoc(null)}
                        className={cn(
                          "block w-full truncate rounded-lg border px-2.5 py-1 text-left text-sm transition",
                          !activeDoc
                            ? "border-indigo-500 bg-indigo-50 text-indigo-600"
                            : "border-slate-200 text-slate-600 hover:bg-slate-50"
                        )}
                      >
                        整个知识库
                      </button>
                      {docs.map((doc) => (
                        <button
                          key={doc.id}
                          type="button"
                          onClick={() => handleSelectDoc(doc)}
                          className={cn(
                            "block w-full truncate rounded-lg border px-2.5 py-1 text-left text-sm transition",
                            activeDoc?.id === doc.id
                              ? "border-indigo-500 bg-indigo-50 text-indigo-600"
                              : "border-slate-200 text-slate-600 hover:bg-slate-50"
                          )}
                        >
                          {doc.docName}
                        </button>
                      ))}
                      {!docsLoading && docs.length === 0 ? (
                        <div className="px-2.5 py-1 text-xs text-slate-400">该知识库暂无文档</div>
                      ) : null}
                    </div>
                  </div>
                ) : null}
              </div>
            ) : null}
          </div>

          <Button
            variant="ghost"
            size="sm"
            className="h-9 shrink-0 gap-1 rounded-xl px-2.5 text-slate-600"
            onClick={handleReset}
            disabled={loading}
            title="重置为全图"
          >
            <RefreshCw className={cn("h-4 w-4", loading && "animate-spin")} />
            全图
          </Button>

          <div className="relative" ref={settingsRef}>
            <Button
              variant="ghost"
              size="icon"
              className={cn(
                "h-9 w-9 shrink-0 rounded-xl text-slate-500",
                settingsOpen && "bg-slate-100 text-slate-900"
              )}
              onClick={() => setSettingsOpen((open) => !open)}
              title="显示设置"
            >
              <Settings className="h-4 w-4" />
            </Button>
            {settingsOpen ? (
              <div className="absolute right-0 top-full z-20 mt-2 w-56 space-y-3 rounded-2xl border border-slate-200 bg-white p-3 shadow-lg">
                <div>
                  <div className="mb-1.5 text-xs font-medium text-slate-500">主题</div>
                  <div className="flex gap-1">
                    {(
                      [
                        { key: "outline", label: "描边" },
                        { key: "pastel", label: "柔彩" },
                        { key: "glass", label: "玻璃" },
                        { key: "vivid", label: "鲜彩" }
                      ] as Array<{ key: VizTheme; label: string }>
                    ).map((opt) => (
                      <button
                        key={opt.key}
                        type="button"
                        onClick={() => handlePickTheme(opt.key)}
                        className={cn(
                          "flex-1 rounded-lg border py-1 text-sm transition",
                          vizThemeState === opt.key
                            ? "border-indigo-500 bg-indigo-50 text-indigo-600"
                            : "border-slate-200 text-slate-600 hover:bg-slate-50"
                        )}
                      >
                        {opt.label}
                      </button>
                    ))}
                  </div>
                </div>
                <div>
                  <div className="mb-1.5 text-xs font-medium text-slate-500">连线</div>
                  <div className="flex gap-1">
                    {(
                      [
                        { curved: false, label: "直线" },
                        { curved: true, label: "曲线" }
                      ] as Array<{ curved: boolean; label: string }>
                    ).map((opt) => (
                      <button
                        key={opt.label}
                        type="button"
                        onClick={() => handlePickEdgeCurved(opt.curved)}
                        className={cn(
                          "flex-1 rounded-lg border py-1 text-sm transition",
                          edgeCurvedState === opt.curved
                            ? "border-indigo-500 bg-indigo-50 text-indigo-600"
                            : "border-slate-200 text-slate-600 hover:bg-slate-50"
                        )}
                      >
                        {opt.label}
                      </button>
                    ))}
                  </div>
                </div>
                <div>
                  <div className="mb-1.5 text-xs font-medium text-slate-500">箭头</div>
                  <div className="flex gap-1">
                    {(
                      [
                        { arrow: true, label: "淡" },
                        { arrow: false, label: "无" }
                      ] as Array<{ arrow: boolean; label: string }>
                    ).map((opt) => (
                      <button
                        key={opt.label}
                        type="button"
                        onClick={() => handlePickEdgeArrow(opt.arrow)}
                        className={cn(
                          "flex-1 rounded-lg border py-1 text-sm transition",
                          edgeArrowState === opt.arrow
                            ? "border-indigo-500 bg-indigo-50 text-indigo-600"
                            : "border-slate-200 text-slate-600 hover:bg-slate-50"
                        )}
                      >
                        {opt.label}
                      </button>
                    ))}
                  </div>
                </div>
                <div>
                  <div className="mb-1.5 text-xs font-medium text-slate-500">布局</div>
                  <div className="flex gap-1">
                    {[
                      { key: "d3-force", label: "力导" },
                      { key: "concentric", label: "径向" }
                    ].map((opt) => (
                      <button
                        key={opt.key}
                        type="button"
                        onClick={() => handleLayoutChange(opt.key as "d3-force" | "concentric")}
                        className={cn(
                          "flex-1 rounded-lg border py-1 text-sm transition",
                          layoutType === opt.key
                            ? "border-indigo-500 bg-indigo-50 text-indigo-600"
                            : "border-slate-200 text-slate-600 hover:bg-slate-50"
                        )}
                      >
                        {opt.label}
                      </button>
                    ))}
                  </div>
                </div>
                <div>
                  <div className="mb-1.5 text-xs font-medium text-slate-500">深度</div>
                  <div className="flex gap-1">
                    {["1", "2", "3"].map((value) => (
                      <button
                        key={value}
                        type="button"
                        onClick={() => handleDepthChange(value)}
                        className={cn(
                          "flex-1 rounded-lg border py-1 text-sm transition",
                          depth === value
                            ? "border-indigo-500 bg-indigo-50 text-indigo-600"
                            : "border-slate-200 text-slate-600 hover:bg-slate-50"
                        )}
                      >
                        {value}
                      </button>
                    ))}
                  </div>
                </div>
                <div>
                  <div className="mb-1.5 text-xs font-medium text-slate-500">节点上限</div>
                  <div className="flex gap-1">
                    {["100", "200", "500"].map((value) => (
                      <button
                        key={value}
                        type="button"
                        onClick={() => handleLimitChange(value)}
                        className={cn(
                          "flex-1 rounded-lg border py-1 text-sm transition",
                          limit === value
                            ? "border-indigo-500 bg-indigo-50 text-indigo-600"
                            : "border-slate-200 text-slate-600 hover:bg-slate-50"
                        )}
                      >
                        {value}
                      </button>
                    ))}
                  </div>
                </div>
              </div>
            ) : null}
          </div>
        </div>

        {activeEntity ? (
          <span className="w-fit rounded-full bg-indigo-50 px-2.5 py-0.5 text-xs text-indigo-600 shadow-sm">
            聚焦：{activeEntity}
          </span>
        ) : null}
      </div>

      {/* 右上角统计：含截断提示，替代原琥珀横幅 */}
      {stats ? (
        <div className="absolute right-4 top-4 z-10 rounded-full border border-slate-200 bg-white/90 px-3 py-1 text-xs text-slate-500 shadow-sm backdrop-blur">
          {stats.nodes} 实体 · {stats.edges} 关系
          {view?.truncated ? <span className="text-amber-600"> · 已截断</span> : null}
        </div>
      ) : null}

      {/* 底部聚焦提示：点击节点后出现，指引退出方式 */}
      {focusName ? (
        <div className="absolute bottom-4 left-1/2 z-10 -translate-x-1/2 rounded-full border border-slate-200 bg-white/90 px-3 py-1 text-xs text-slate-500 shadow-sm backdrop-blur">
          聚焦 <span className="font-medium text-slate-700">{focusName}</span> · Esc 或点击空白退出
        </div>
      ) : null}

      {/* 右下角缩放控制：叠在缩略图上方，手动放大 / 缩小 / 重置比例 */}
      <div className="absolute bottom-40 right-4 z-10 flex w-9 flex-col overflow-hidden rounded-xl border border-slate-200 bg-white/90 shadow-sm backdrop-blur">
        <button
          type="button"
          onClick={handleZoomIn}
          className="flex h-8 w-full items-center justify-center text-slate-600 transition hover:bg-slate-100"
          title="放大"
        >
          <Plus className="h-4 w-4" />
        </button>
        <button
          type="button"
          onClick={handleZoomReset}
          className="w-full border-y border-slate-200 py-1 text-center text-[10px] tabular-nums text-slate-500 transition hover:bg-slate-100"
          title="重置为 100%"
        >
          {zoomPct}%
        </button>
        <button
          type="button"
          onClick={handleZoomOut}
          className="flex h-8 w-full items-center justify-center text-slate-600 transition hover:bg-slate-100"
          title="缩小"
        >
          <Minus className="h-4 w-4" />
        </button>
      </div>

      {loading ? (
        <div className="absolute inset-0 flex items-center justify-center bg-white/60">
          <Loader2 className="h-6 w-6 animate-spin text-indigo-500" />
        </div>
      ) : null}

      {errorMsg ? (
        <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 text-center">
          <Share2 className="h-10 w-10 text-slate-300" />
          <p className="text-sm text-slate-500">{errorMsg}</p>
          <p className="text-xs text-slate-400">
            请确认已部署 LightRAG 图谱栈，并在后端开启 rag.graph.type=lightrag
          </p>
        </div>
      ) : null}

      {isEmpty ? (
        <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 text-center">
          <Share2 className="h-10 w-10 text-slate-300" />
          <p className="text-sm text-slate-500">暂无图谱数据</p>
          <p className="text-xs text-slate-400">导入并索引文档后，图谱将自动构建</p>
        </div>
      ) : null}
    </div>
  );
}
