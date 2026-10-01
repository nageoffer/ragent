// 视觉自验脚手架：预置 zustand 状态直接渲染 Agent 页外壳，验完即删
import ReactDOM from "react-dom/client";
import { MemoryRouter } from "react-router-dom";

import { AgentChatInput } from "@/components/agent/AgentChatInput";
import { AgentLayout } from "@/components/agent/AgentLayout";
import { AgentMessageList } from "@/components/agent/AgentMessageList";
import { useAgentChatStore } from "@/stores/agentChatStore";
import { useAuthStore } from "@/stores/authStore";
import type { AgentMessage, AgentRawFrame, AgentSession } from "@/types/agent";
import "@/styles/globals.css";

const params = new URLSearchParams(window.location.search);
const view = params.get("view") ?? "convo";
// role=user 用来验非管理员看到的空态（少一颗「去后台添加」）
const role = params.get("role") ?? "admin";
// expanded 视图把折叠块直接置为展开态
const openAll = view === "expanded";

// 时间摊开覆盖四个分组桶 含同名会话与闲聊短句的真实压力样本
const sessions: AgentSession[] = [
  { id: "s-current", title: "华东区销售数据分析", lastTime: new Date().toISOString(), turns: 2 },
  { id: "s-6", title: "数据安全怎么做的", lastTime: new Date(Date.now() - 12 * 60000).toISOString(), turns: 1 },
  { id: "s-7", title: "数据安全怎么做的", lastTime: new Date(Date.now() - 25 * 60000).toISOString(), turns: 3 },
  { id: "s-2", title: "差旅报销流程咨询", lastTime: new Date(Date.now() - 40 * 60000).toISOString(), turns: 1 },
  { id: "s-8", title: "你好", lastTime: new Date(Date.now() - 3 * 3600e3).toISOString(), turns: 5 },
  { id: "s-3", title: "高优先级工单跟进", lastTime: new Date(Date.now() - 864e5).toISOString(), turns: 4 },
  { id: "s-4", title: "新员工入职材料清单", lastTime: new Date(Date.now() - 5 * 864e5).toISOString(), turns: 1 },
  { id: "s-5", title: "季度 OKR 对齐会纪要", lastTime: new Date(Date.now() - 20 * 864e5).toISOString(), turns: 3 }
];

const at = (m: number) => new Date(Date.now() - m * 60000).toISOString();
const hms = (m: number) => new Date(Date.now() - m * 60000).toTimeString().slice(0, 8);

const answerMarkdown = `根据知识库中的《华东区季度销售报表》，上季度整体情况如下：

| 城市 | 销售额（万元） | 环比 |
| ---- | ---- | ---- |
| 上海 | 1,842 | +12.4% |
| 杭州 | 1,286 | +8.1% |
| 南京 | 967 | -3.2% |

**补货建议：**

1. 上海、杭州保持增长，建议按 \`safety_stock = 日均销量 × 1.5\` 上调安全库存
2. 南京环比下滑，先排查渠道库存积压，再决定是否补货

> 数据口径为含税出货额，明细见报表第 3 节。`;

const answer2 = `公司差旅报销依据《差旅费管理办法（2025 修订版）》执行，北京属于一类城市：

- 住宿上限 **650 元/晚**，需提供增值税专用发票
- 餐补 **150 元/天**，无需发票、按出差天数打包发放
- 市内交通实报实销，打车需附行程单

出差五天预计可报销上限约 **4,000 元**（不含往返大交通）。`;

// 服务端计时 fixture
const T0 = Date.now() - 6 * 60000;
const batched = (batchId: string, callIndex: number, startedAt: number, durationMs: number) => ({
  batchId,
  callIndex,
  startedAt,
  endedAt: startedAt + durationMs,
  durationMs
});
// 文本块计时 fixture
const streamed = (startedAt: number, durationMs: number) => ({
  startedAt,
  endedAt: startedAt + durationMs,
  durationMs
});

/**
 * timeline 视图截图自验：并行 / 单批 / 续跑 / 拒绝 / 中断 / 无计时
 */
function timelineMessages(): AgentMessage[] {
  const leave = { toolCallId: "c9", name: "submit_leave", displayName: "提交请假单" };
  const expense = { toolCallId: "c8", name: "submit_expense", displayName: "提交报销单" };
  const room1 = { toolCallId: "m1", name: "meeting_room_book", displayName: "预订会议室" };
  const room2 = { toolCallId: "m2", name: "meeting_room_book", displayName: "预订会议室" };
  const rule = { toolCallId: "m3", name: "search_knowledge", displayName: "知识库检索" };
  return [
    { id: "tu1", role: "user", content: "同时查一下华东和华南两个区的季度销售", createdAt: at(6) },
    {
      id: "ta1",
      role: "assistant",
      content: "",
      status: "done",
      createdAt: at(6),
      elapsedMs: 7200,
      blocks: [
        {
          id: 1,
          kind: "reasoning",
          at: hms(6),
          ...streamed(T0 - 2100, 2100),
          text: "两个区互不依赖，一次并行检索就够，不必来回两轮。"
        },
        {
          id: 2,
          kind: "tool",
          at: hms(6),
          name: "search_knowledge",
          displayName: "知识库检索",
          status: "done",
          toolCallId: "p1",
          ...batched("r1-1", 0, T0, 1500),
          result: JSON.stringify([{ doc: "华东区季度销售报表.xlsx", score: 0.92 }])
        },
        {
          id: 3,
          kind: "tool",
          at: hms(6),
          name: "search_knowledge",
          displayName: "知识库检索",
          status: "done",
          toolCallId: "p2",
          ...batched("r1-1", 1, T0, 1500),
          result: JSON.stringify([{ doc: "华南区季度销售报表.xlsx", score: 0.89 }])
        },
        {
          id: 4,
          kind: "answer",
          at: hms(5),
          ...streamed(T0 + 1500, 3600),
          text: "两区合计 4,286 万元，华东占比 68%。"
        }
      ]
    },
    { id: "tu2", role: "user", content: "帮我提交下周一到周三的年假", createdAt: at(4) },
    {
      id: "ta2",
      role: "assistant",
      content: "",
      status: "done",
      messageStatus: "AWAITING_CONFIRM",
      createdAt: at(4),
      elapsedMs: 1800,
      blocks: [
        {
          id: 11,
          kind: "confirm",
          at: hms(4),
          status: "approved",
          calls: [{ ...leave, fields: [{ name: "days", label: "请假区间", value: "周一至周三" }] }]
        },
        // 挂起那条开了头 与续跑是同一次调用
        { id: 12, kind: "tool", at: hms(4), status: "awaiting", ...leave }
      ]
    },
    {
      id: "ta3",
      role: "assistant",
      content: "",
      status: "done",
      createdAt: at(3),
      elapsedMs: 2400,
      blocks: [
        {
          id: 13,
          kind: "tool",
          at: hms(3),
          status: "done",
          ...leave,
          ...batched("r2-0", 0, T0 + 60000, 800),
          result: JSON.stringify({ requestNo: "LV-20260901-0007", state: "已提交" })
        },
        {
          id: 14,
          kind: "answer",
          at: hms(3),
          ...streamed(T0 + 60800, 1500),
          text: "已提交，单号 LV-20260901-0007。"
        }
      ]
    },
    { id: "tu3", role: "user", content: "再把上个月的打车费报销提一下", createdAt: at(2) },
    {
      id: "ta4",
      role: "assistant",
      content: "",
      status: "done",
      messageStatus: "AWAITING_CONFIRM",
      createdAt: at(2),
      elapsedMs: 1600,
      blocks: [
        {
          id: 21,
          kind: "confirm",
          at: hms(2),
          status: "denied",
          calls: [{ ...expense, fields: [{ name: "amount", label: "金额", value: "￥862.00" }] }]
        },
        { id: 22, kind: "tool", at: hms(2), status: "awaiting", ...expense }
      ]
    },
    {
      id: "ta5",
      role: "assistant",
      content: "",
      status: "done",
      createdAt: at(2),
      elapsedMs: 900,
      // 拒绝无执行窗口
      blocks: [
        { id: 23, kind: "tool", at: hms(2), status: "denied", ...expense },
        { id: 24, kind: "answer", at: hms(2), text: "已取消，没有提交这笔报销。" }
      ]
    },
    { id: "tu4", role: "user", content: "顺便看下明天上海的天气", createdAt: at(1) },
    {
      id: "ta6",
      role: "assistant",
      content: "",
      status: "done",
      messageStatus: "INTERRUPTED",
      createdAt: at(1),
      elapsedMs: 5200,
      blocks: [
        // 老会话 无批次无计时
        {
          id: 31,
          kind: "tool",
          at: hms(1),
          name: "search_knowledge",
          displayName: "知识库检索",
          status: "done",
          result: "（老会话数据 无计时字段）"
        },
        {
          id: 32,
          kind: "tool",
          at: hms(1),
          name: "weather_query",
          displayName: "天气查询",
          status: "interrupted",
          toolCallId: "w1"
        }
      ]
    },
    { id: "tu5", role: "user", content: "订两间会议室，顺便查下会议室使用规定", createdAt: at(1) },
    {
      id: "ta7",
      role: "assistant",
      content: "",
      status: "done",
      messageStatus: "AWAITING_CONFIRM",
      createdAt: at(1),
      elapsedMs: 2100,
      // 卡里点名两间会议室 同批检索只是跟着停
      blocks: [
        { id: 41, kind: "tool", at: hms(1), status: "awaiting", ...room1 },
        { id: 42, kind: "tool", at: hms(1), status: "awaiting", ...room2 },
        { id: 43, kind: "tool", at: hms(1), status: "awaiting", ...rule },
        {
          id: 44,
          kind: "confirm",
          at: hms(1),
          status: "pending",
          calls: [
            { ...room1, fields: [{ name: "slot", label: "时段", value: "周三 10:00" }] },
            { ...room2, fields: [{ name: "slot", label: "时段", value: "周三 14:00" }] }
          ]
        }
      ]
    },
    { id: "tu6", role: "user", content: "把这两份手册都加载一下", createdAt: at(1) },
    {
      id: "ta8",
      role: "assistant",
      content: "",
      status: "done",
      createdAt: at(1),
      elapsedMs: 700,
      // 同名两次调用 折叠会丢一份耗时
      blocks: [
        {
          id: 51,
          kind: "tool",
          at: hms(1),
          status: "done",
          toolCallId: "k1",
          name: "load_skill",
          displayName: "加载技能手册",
          ...batched("r5-0", 0, T0 + 300000, 5),
          // 逐条口径 缺此项会退回整批分支
          durationSource: "tool",
          result: "（手册已加载）"
        },
        {
          id: 52,
          kind: "tool",
          at: hms(1),
          status: "done",
          toolCallId: "k2",
          name: "load_skill",
          displayName: "加载技能手册",
          ...batched("r5-0", 1, T0 + 300001, 9),
          durationSource: "tool",
          result: "（手册已加载）"
        },
        { id: 53, kind: "answer", at: hms(1), text: "两份手册都已加载。" }
      ]
    }
  ];
}

function buildMessages(): { messages: AgentMessage[]; isStreaming: boolean } {
  if (view === "welcome") {
    return { messages: [], isStreaming: false };
  }

  if (view === "timeline") {
    return { messages: timelineMessages(), isStreaming: false };
  }

  const turns: AgentMessage[] = [
    {
      id: "u1",
      role: "user",
      content: "分析一下华东区上季度的销售数据，给出下季度的补货建议",
      createdAt: at(9)
    },
    {
      id: "a1",
      role: "assistant",
      content: "",
      status: "done",
      createdAt: at(9),
      elapsedMs: 26300,
      blocks: [
        {
          id: 11,
          kind: "reasoning",
          at: hms(9),
          ...streamed(T0 - 180000, 3400),
          text: "用户需要销售数据分析与补货建议，先检索知识库中的季度报表，再结合环比趋势给出结论。\n需要注意南京的负增长是否与渠道库存有关。",
          open: false
        },
        {
          id: 12,
          kind: "tool",
          at: hms(9),
          durationMs: 1200,
          name: "search_knowledge",
          displayName: "知识库检索",
          status: "done",
          result: JSON.stringify([
            { doc: "华东区季度销售报表.xlsx", score: 0.92, chunk: "上海 1842 万，环比 +12.4%..." },
            { doc: "渠道库存周报.pdf", score: 0.87, chunk: "南京渠道库存周转天数升至 46 天..." },
            { doc: "补货策略 SOP.docx", score: 0.81, chunk: "安全库存 = 日均销量 × 1.5..." }
          ]),
          open: false
        },
        {
          id: 13,
          kind: "answer",
          at: hms(8),
          ...streamed(T0 - 175400, 21600),
          text: answerMarkdown
        }
      ]
    },
    {
      id: "u2",
      role: "user",
      content: "公司差旅报销流程是怎么规定的？出差北京五天大概能报多少",
      createdAt: at(4)
    },
    {
      id: "a2",
      role: "assistant",
      content: "",
      status: "done",
      createdAt: at(4),
      elapsedMs: 9400,
      blocks: [
        {
          id: 21,
          kind: "tool",
          at: hms(4),
          durationMs: 800,
          name: "search_knowledge",
          displayName: "知识库检索",
          status: "done",
          // 门面合成的 markdown 文本：验证折叠摘要压平 不露 --- ### 等记号
          result:
            "根据当前可用信息，差旅报销规定如下：\n\n---\n\n### 一、住宿标准\n\n- 一类城市（北京、上海、深圳）**650 元/晚**\n- 需提供增值税专用发票\n\n### 二、餐补\n\n- 150 元/天，按出差天数打包发放",
          open: false
        },
        {
          id: 22,
          kind: "answer",
          at: hms(3),
          ...streamed(T0 + 175000, 8100),
          text: answer2
        }
      ]
    }
  ];

  if (view === "failed") {
    turns.push(
      { id: "u3", role: "user", content: "顺便查下明天上海的天气", createdAt: at(1) },
      {
        id: "a3",
        role: "assistant",
        content: "",
        status: "done",
        createdAt: at(1),
        elapsedMs: 16200,
        blocks: [
          {
            id: 31,
            kind: "tool",
            at: hms(1),
            durationMs: 10000,
            name: "weather_query",
            displayName: "天气查询",
            status: "failed",
            result: "Error: MCP 调用超时（10s），weather 服务未响应",
            open: false
          },
          {
            id: 32,
            kind: "hint",
            at: hms(1),
            text: "已达到最大迭代次数，正在生成当前执行结果的总结"
          },
          {
            id: 33,
            kind: "answer",
            at: hms(0),
            text: "天气服务暂时不可用，稍后可以再试。销售与报销两部分结论不受影响。"
          }
        ]
      }
    );
    return { messages: turns, isStreaming: false };
  }

  if (view === "streaming") {
    turns.push(
      { id: "u3", role: "user", content: "帮我看看最近的高优先级工单处理情况", createdAt: at(0) },
      { id: "a3", role: "assistant", content: "", status: "streaming", createdAt: at(0), blocks: [] }
    );
    return { messages: turns, isStreaming: true };
  }

  if (view === "running") {
    turns.push(
      { id: "u3", role: "user", content: "帮我看看最近的高优先级工单处理情况", createdAt: at(0) },
      {
        id: "a3",
        role: "assistant",
        content: "",
        status: "streaming",
        createdAt: at(0),
        blocks: [
          {
            id: 41,
            kind: "reasoning",
            at: hms(0),
            text: "需要调用工单系统接口查询 P0/P1 工单，再按处理时长排序。",
            open: true
          },
          {
            id: 42,
            kind: "tool",
            at: hms(0),
            name: "ticket_query",
            displayName: "工单查询",
            status: "running"
          }
        ]
      }
    );
    return { messages: turns, isStreaming: true };
  }

  return { messages: turns, isStreaming: false };
}

const { messages, isStreaming } = buildMessages();

// 原始帧抽屉预置几条样例帧
const frames: AgentRawFrame[] = [
  { id: 1, ts: hms(1), name: "meta", data: { conversationId: "s-current", taskId: "t-1024" } },
  { id: 2, ts: hms(1), name: "message", data: { type: "reasoning", delta: "先检索知识库…" } },
  {
    id: 3,
    ts: hms(1),
    name: "block",
    data: { kind: "tool", name: "search_knowledge", displayName: "知识库检索", status: "pending" }
  },
  {
    id: 4,
    ts: hms(0),
    name: "block",
    data: { kind: "tool", name: "search_knowledge", displayName: "知识库检索", status: "done", ok: true, result: "[…]" }
  },
  { id: 5, ts: hms(0), name: "message", data: { type: "answer", delta: "根据知识库…" } }
];

useAuthStore.setState({ user: { userId: "1", username: "admin", role } as never });
useAgentChatStore.setState({
  sessions,
  currentSessionId: "s-current",
  // expanded 视图预置折叠块为展开态
  messages: openAll
    ? messages.map((message) => ({
        ...message,
        blocks: message.blocks?.map((block) => ({ ...block, open: true }))
      }))
    : messages,
  isLoading: false,
  sessionsLoaded: true,
  isStreaming,
  frames
});

function HarnessApp() {
  // 订阅 store 让 toggleBlockOpen 生效 便于截图前点开折叠块
  const liveMessages = useAgentChatStore((state) => state.messages);
  const liveStreaming = useAgentChatStore((state) => state.isStreaming);
  return (
    <MemoryRouter>
      <AgentLayout>
        <AgentMessageList
          messages={liveMessages}
          isLoading={false}
          isStreaming={liveStreaming}
          sessionKey="s-current"
        />
        <AgentChatInput />
      </AgentLayout>
    </MemoryRouter>
  );
}

ReactDOM.createRoot(document.getElementById("root")!).render(<HarnessApp />);
