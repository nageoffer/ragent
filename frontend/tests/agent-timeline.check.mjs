/**
 * 时间线投影层的可复现验证：node tests/agent-timeline.check.mjs
 * 仓库没有前端单测框架 本轮不引入 —— 直接编出 src/lib/agentTimeline.ts 再按真实场景断言
 * 覆盖：单工具 / 同名并行 / 耗时不同的并行 / 批未收口 / 确认后续跑 / 拒绝 / 中断 / 刷新回放 / 两代口径兼容
 * 以及时刻语义（工具行取执行起点）/ running 阶段无起点 / 并行标注 / 耗时刻度
 */
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const outDir = resolve(root, ".output/agent-timeline");

rmSync(outDir, { recursive: true, force: true });
execFileSync("npx", ["tsc", "-p", "tests/tsconfig.timeline.json"], { cwd: root, stdio: "inherit" });

const {
  applyConfirmStatus,
  applyTextBlockSeal,
  applyToolFrame,
  buildTimelineRows,
  formatDuration,
  replayBlock,
  settleToolBlocks
} = await import(resolve(outDir, "lib/agentTimeline.js"));

let seq = 0;
const ctx = { allocId: () => (seq += 1), fallbackAt: "09:41:00" };
// 一条调用的三帧 与 AgentStreamEventBridge 发出的生产帧同形 帧上有什么字段以那边为准：
// pending 是模型声明这次调用 块此刻建起 所以带声明时刻与序号 没有批也没有起止
// running 是框架把它排进执行队列 归了批 但工具体还没开工 起止仍然是空的
// 终态才由 applyExecutionTimes 从事实源补上起止与耗时 durationSource=tool 只随耗时一起来
const T0 = 1767229264000;
const DECLARED_AT = "2026-01-01T09:41:02";
const hmsOf = (epochMs) => new Date(epochMs).toTimeString().slice(0, 8);
const pending = (id, name, index = 0) => ({
  kind: "tool",
  toolCallId: id,
  name,
  displayName: name,
  status: "pending",
  at: DECLARED_AT,
  callIndex: index
});
const running = (id, name, batch, index) => ({
  ...pending(id, name, index),
  status: "running",
  batchId: batch
});
const settled = (id, name, batch, index, startedAt, ms, extra = {}) => ({
  ...running(id, name, batch, index),
  status: "done",
  ok: true,
  result: `${id}-result`,
  startedAt,
  endedAt: startedAt + ms,
  durationMs: ms,
  durationSource: "tool",
  ...extra
});

const feed = (frames, blocks = []) =>
  frames.reduce((acc, frame) => applyToolFrame(acc, frame, ctx), blocks);
const turnOf = (...assistants) => ({
  id: "t",
  index: 1,
  user: { id: "u", role: "user", content: "问题", createdAt: "2026-01-01T09:41:00" },
  assistants: assistants.map((blocks, i) => ({
    id: `a${i}`,
    role: "assistant",
    content: "",
    status: "done",
    blocks
  }))
});
const toolRows = (rows) => rows.filter((row) => row.channel === "tool");
const batchRows = (rows) => rows.filter((row) => row.channel === "batch");

const cases = [];
const test = (name, fn) => cases.push([name, fn]);

test("单工具：三帧落在同一个块上 耗时挂在这一行 不起批头", () => {
  const blocks = feed([
    pending("c1", "search_knowledge"),
    running("c1", "search_knowledge", "r1-0", 0),
    settled("c1", "search_knowledge", "r1-0", 0, T0, 1200)
  ]);
  assert.equal(blocks.length, 1, "三帧不该长出三个块");
  assert.equal(blocks[0].status, "done");
  assert.equal(blocks[0].durationMs, 1200);
  assert.equal(blocks[0].at, "09:41:02", "块时刻是声明时刻 后两帧同值不改写");

  const rows = buildTimelineRows(turnOf(blocks));
  assert.equal(batchRows(rows).length, 0, "一个工具的批不该多出一行批头");
  assert.equal(toolRows(rows).length, 1);
  assert.equal(toolRows(rows)[0].durationMs, 1200, "单工具批的窗口就是这次调用的窗口");
});

test("同名并行：按 toolCallId 配对不串块 按 callIndex 排序 逐条各记各的耗时", () => {
  // 后announce的先回结果 帧序有意打乱：只要 id 对得上 顺序就不该影响归属
  const blocks = feed([
    pending("c2", "search_knowledge", 1),
    pending("c1", "search_knowledge", 0),
    running("c1", "search_knowledge", "r1-1", 0),
    running("c2", "search_knowledge", "r1-1", 1),
    settled("c2", "search_knowledge", "r1-1", 1, T0, 1500),
    settled("c1", "search_knowledge", "r1-1", 0, T0, 1500)
  ]);
  assert.equal(blocks.length, 2, "两次调用两个块");
  assert.equal(blocks[0].toolCallId, "c2");
  assert.equal(blocks[0].result, "c2-result", "同名并行的结果不许张冠李戴");
  assert.equal(blocks[1].result, "c1-result");

  const rows = buildTimelineRows(turnOf(blocks));
  const head = batchRows(rows);
  assert.equal(head.length, 1);
  assert.equal(head[0].batchSize, 2);
  assert.equal(head[0].durationMs, 1500, "两条同起同止 窗口就是这 1500");
  assert.equal(head[0].ts, hmsOf(T0), "批头时刻取最早的 startedAt");
  const tools = toolRows(rows);
  assert.deepEqual(
    tools.map((row) => row.block.toolCallId),
    ["c1", "c2"],
    "展示序按 callIndex 不按到达序"
  );
  assert.deepEqual(
    tools.map((row) => row.durationMs),
    [1500, 1500],
    "逐条计时的数据 每一行都该显示自己那次的耗时"
  );
});

test("两个耗时不同的并行工具：批头是墙上窗口 不是两条之和 也不是其中任一条", () => {
  // 序号靠后的那条反而先开工：起点若图省事取 ordered[0] 就会晚 200ms
  // c2 [T0, T0+2000) c1 [T0+200, T0+2700) 窗口 = 2700 —— 与 2500 / 2000 / 4500 都不等
  const blocks = feed([
    pending("c1", "search_knowledge", 0),
    pending("c2", "leave_submit", 1),
    running("c2", "leave_submit", "r5-0", 1),
    running("c1", "search_knowledge", "r5-0", 0),
    settled("c2", "leave_submit", "r5-0", 1, T0, 2000),
    settled("c1", "search_knowledge", "r5-0", 0, T0 + 200, 2500)
  ]);

  const rows = buildTimelineRows(turnOf(blocks));
  const head = batchRows(rows)[0];
  assert.equal(head.durationMs, 2700, "窗口 = 最晚收工 − 最早开工");
  assert.notEqual(head.durationMs, 4500, "重叠区间相加会把重叠段算两遍");
  assert.notEqual(head.durationMs, 2500, "随手拿组内第一条的耗时当整批 就漏掉了它之前已经在跑的那条");
  assert.equal(head.ts, hmsOf(T0), "起点取最早开工的那条 不是序号最小的那条");
  assert.deepEqual(
    toolRows(rows).map((row) => row.durationMs),
    [2500, 2000],
    "谁慢谁快必须逐行看得出来 这正是批头那个数看不出的事"
  );
});

test("批没收口：有工具开了工却没有终点 批头只报起点不报耗时", () => {
  // 断在半路的那条 落库时 applyExecutionTimes 会把真起点补上 只是没有终点
  const blocks = settleToolBlocks(
    feed([
      pending("c1", "search_knowledge", 0),
      pending("c2", "leave_submit", 1),
      running("c1", "search_knowledge", "r6-0", 0),
      running("c2", "leave_submit", "r6-0", 1),
      settled("c1", "search_knowledge", "r6-0", 0, T0, 300),
      { ...running("c2", "leave_submit", "r6-0", 1), startedAt: T0 + 100 }
    ]),
    "interrupted"
  );

  const head = batchRows(buildTimelineRows(turnOf(blocks)))[0];
  assert.equal(head.ts, hmsOf(T0), "起点是真的 照报");
  assert.equal(
    head.durationMs,
    undefined,
    "拿已收工那条的终点当整批终点 会把断在半路的那条抹掉"
  );
});

test("老数据多工具批：整批共享那一份耗时 只挂批头 逐行不显示", () => {
  // 没有 durationSource 的块一律按老口径读：那个数量的是整批 不是某一条
  const legacy = [0, 1].map((index) =>
    replayBlock(
      {
        kind: "tool",
        at: "2026-01-01T09:41:04",
        name: `tool_${index}`,
        displayName: `tool_${index}`,
        status: "done",
        result: "[…]",
        toolCallId: `old-${index}`,
        batchId: "r7-0",
        callIndex: index,
        startedAt: T0,
        endedAt: T0 + 1500,
        durationMs: 1500
      },
      700 + index
    )
  );

  const rows = buildTimelineRows(turnOf(legacy));
  assert.equal(batchRows(rows)[0].durationMs, 1500, "老口径下这个数本就是整批的");
  assert.ok(
    toolRows(rows).every((row) => row.durationMs == null),
    "老数据逐行挂一遍会被读成每条都跑了这么久"
  );
});

test("确认后续跑：同一个 toolCallId 只成一行 且落在确认卡之后", () => {
  const confirm = {
    id: 100,
    kind: "confirm",
    at: "09:41:05",
    status: "approved",
    calls: [{ toolCallId: "c9", name: "submit_leave", displayName: "提交请假单" }]
  };
  // 挂起那条消息里工具只开了个头 收尾时按 awaiting 落定
  const awaiting = settleToolBlocks(
    feed([pending("c9", "submit_leave")], []),
    "awaiting"
  );
  assert.equal(awaiting[0].status, "awaiting");
  // 续跑是新的一条助手消息 块从空数组重新长
  const resumed = feed([
    running("c9", "submit_leave", "r2-0", 0),
    settled("c9", "submit_leave", "r2-0", 0, T0, 800)
  ]);

  const rows = buildTimelineRows(turnOf([confirm, ...awaiting], resumed));
  const tools = toolRows(rows);
  assert.equal(tools.length, 1, "一次调用两个阶段 不能读成执行了两次");
  assert.equal(tools[0].block.status, "done");
  assert.equal(tools[0].durationMs, 800);
  const card = rows.find((row) => row.channel === "confirm");
  assert.equal(card.outcomes[0].status, "done", "卡内逐项结局认的是续跑那个块");
  assert.ok(rows.indexOf(card) < rows.indexOf(tools[0]), "执行发生在点头之后 行序也该如此");
});

test("同批两次同名同结果的调用：各占一行 身份与耗时都留得住", () => {
  // 折叠曾把这两行并成 ×2 只留第一条的身份与耗时 而 Langfuse 那边始终是两个 TOOL 节点 对不上账
  const blocks = feed([
    running("d1", "load_skill", "r1-0", 0),
    running("d2", "load_skill", "r1-0", 1),
    settled("d1", "load_skill", "r1-0", 0, T0, 5, { result: "同一份手册" }),
    settled("d2", "load_skill", "r1-0", 1, T0 + 1, 9, { result: "同一份手册" })
  ]);

  const tools = toolRows(buildTimelineRows(turnOf(blocks)));
  assert.equal(tools.length, 2, "两次调用两行");
  assert.deepEqual(
    tools.map((row) => row.block.toolCallId),
    ["d1", "d2"],
    "后一条的身份不许被前一条吞掉"
  );
  assert.deepEqual(
    tools.map((row) => row.durationMs),
    [5, 9],
    "各报各的耗时 不共用第一条那份"
  );
});

test("随批等待：确认卡没点名的那条不报待确认", () => {
  const confirm = {
    id: 300,
    kind: "confirm",
    at: "09:41:05",
    status: "pending",
    calls: [{ toolCallId: "w1", name: "meeting_room_book", displayName: "预订会议室" }]
  };
  // 同一批里还有一个只读工具 它自己不需要授权 只是整批停下才跟着等
  const awaiting = settleToolBlocks(
    feed([pending("w1", "meeting_room_book", 0), pending("w2", "search_knowledge", 1)], []),
    "awaiting"
  );

  const rows = buildTimelineRows(turnOf([...awaiting, confirm]));
  const tools = toolRows(rows);
  assert.equal(tools.length, 1, "卡里点名的那条已并进卡 不再单独成行");
  assert.equal(tools[0].block.toolCallId, "w2");
  assert.equal(tools[0].batchWaiting, true, "它在等别人被授权 不是在等自己被授权");
});

test("兼容同一次调用存在多个拒绝记录：由确认卡展示最新状态", () => {
  // 构造重复记录验证兼容性；当前框架的用户拒绝路径不会发布工具结果事件。
  const confirm = {
    id: 200,
    kind: "confirm",
    at: "09:41:05",
    status: "denied",
    calls: [{ toolCallId: "c8", name: "submit_leave", displayName: "提交请假单" }]
  };
  const original = feed([{ ...pending("c8", "submit_leave"), status: "denied" }]);
  const denied = applyToolFrame(
    [],
    { toolCallId: "c8", name: "submit_leave", displayName: "提交请假单", status: "denied" },
    ctx
  );

  const rows = buildTimelineRows(turnOf([...original, confirm], denied));
  assert.equal(toolRows(rows).length, 0);
  assert.equal(rows.find((row) => row.channel === "confirm").outcomes[0], denied[0]);
});

test("用户拒绝：原工具块与确认卡均为 denied，无需新增工具事件", () => {
  const tool = replayBlock({ ...pending("declined", "submit_leave"), status: "denied" }, ++seq);
  const card = replayBlock({ kind: "confirm", status: "denied", at: DECLARED_AT,
    calls: [{ toolCallId: "declined", name: "submit_leave" }] }, ++seq);
  const rows = buildTimelineRows(turnOf([tool, card]));
  assert.equal(toolRows(rows).length, 0);
  assert.equal(rows.find(row => row.channel === "confirm").outcomes[0], tool);
});

test("本地拒绝只修改卡片点名的 awaiting 调用，不按同名或同批推断", () => {
  const tools = feed([
    ...["a", "b", "unclaimed"].map(id => ({ ...pending(id, "same_tool"), status: "awaiting" })),
    { ...pending("done", "same_tool"), status: "done", result: "原始成功结果" },
    { ...pending(" ", "same_tool"), status: "awaiting" }
  ]);
  const card = replayBlock({ kind: "confirm", at: DECLARED_AT, status: "pending",
    calls: ["a", "b", "done", " "].map(toolCallId => ({ toolCallId, name: "same_tool" })) }, ++seq);
  const original = [...tools, card];
  const updated = applyConfirmStatus(original, card.id, "denied");
  assert.deepEqual(updated.map(block => block.status),
    ["denied", "denied", "awaiting", "done", "awaiting", "denied"]);
  assert.equal(original[0].status, "awaiting", "纯更新不修改旧状态对象");
  assert.equal(updated[3], original[3]);
  assert.equal(applyConfirmStatus(original, -1, "denied"), original);
  assert.equal(applyConfirmStatus(original, tools[0].id, "denied"), original);
});

test("同意后的真实结果可见：成功、失败、中断均保留，确认卡维持 approved", () => {
  for (const extra of [
    { status: "done", result: "真实成功结果" },
    { status: "failed", result: "真实错误详情" },
    { status: "interrupted", result: "部分执行结果" }
  ]) {
    const old = feed([{ ...pending("real", "submit_leave"), status: "awaiting" }]);
    const card = replayBlock({ kind: "confirm", status: "approved", at: DECLARED_AT,
      calls: [{ toolCallId: "real", name: "submit_leave" }] }, ++seq);
    const resumed = feed([{ ...pending("real", "submit_leave"), ...extra }]);
    const rows = buildTimelineRows(turnOf([...old, card], resumed));
    assert.equal(toolRows(rows).length, 1, JSON.stringify(extra));
    assert.equal(toolRows(rows)[0].block, resumed[0]);
    assert.equal(toolRows(rows)[0].block.result, extra.result);
    const confirmRow = rows.find(row => row.channel === "confirm");
    assert.equal(confirmRow.block.status, "approved");
    assert.equal(confirmRow.outcomes[0], resumed[0]);
  }
});

test("失效卡不会投影成拒绝；没有确认关联的同名拒绝工具正常显示", () => {
  const tools = feed([
    { ...pending("expired", "submit_leave"), status: "awaiting" },
    { ...pending("unclaimed", "submit_leave"), status: "denied" },
    { name: "submit_leave", status: "denied" }
  ]);
  const card = replayBlock({ kind: "confirm", status: "expired", at: DECLARED_AT,
    calls: [{ toolCallId: "expired", name: "submit_leave" }] }, ++seq);
  const rows = buildTimelineRows(turnOf([...tools, card]));
  assert.deepEqual(toolRows(rows).map(row => row.block.toolCallId), ["unclaimed", undefined]);
  const confirmRow = rows.find(row => row.channel === "confirm");
  assert.equal(confirmRow.block.status, "expired");
  assert.equal(confirmRow.outcomes[0].status, "awaiting");
});

test("中断：收尾还没等到终态帧的工具一律落中断 不冒充完成", () => {
  const blocks = settleToolBlocks(
    feed([pending("c1", "a", 0), running("c1", "a", "r3-0", 0), pending("c2", "b", 1)]),
    "interrupted"
  );
  assert.deepEqual(
    blocks.map((block) => block.status),
    ["interrupted", "interrupted"],
    "pending 与 running 一视同仁"
  );
});

test("刷新回放：落库块投影出的行与流式那条一模一样", () => {
  const live = feed([
    pending("c1", "search_knowledge", 0),
    pending("c2", "search_knowledge", 1),
    running("c1", "search_knowledge", "r1-1", 0),
    running("c2", "search_knowledge", "r1-1", 1),
    settled("c1", "search_knowledge", "r1-1", 0, T0, 1500),
    settled("c2", "search_knowledge", "r1-1", 1, T0, 1500)
  ]);
  // 落库形态：块时刻带日期 前端没写进去的字段一律为 null
  const persisted = live.map((block) => ({
    kind: "tool",
    at: DECLARED_AT,
    text: null,
    name: block.name,
    displayName: block.displayName,
    status: block.status,
    result: block.result,
    toolCallId: block.toolCallId,
    calls: null,
    batchId: block.batchId,
    callIndex: block.callIndex,
    startedAt: block.startedAt,
    endedAt: block.endedAt,
    durationMs: block.durationMs,
    durationSource: block.durationSource
  }));
  let rid = 900;
  const replayed = persisted.map((block) => replayBlock(block, (rid += 1)));

  const shape = (rows) =>
    rows.map(({ channel, ts, count, batchSize, durationMs, block }) => ({
      channel,
      ts,
      count,
      batchSize,
      durationMs,
      status: block?.status,
      toolCallId: block?.toolCallId,
      // 口径丢了不会让哪一行消失 只会让批头与逐行的数悄悄换个含义 得跟着比
      durationSource: block?.durationSource
    }));
  assert.deepEqual(
    shape(buildTimelineRows(turnOf(replayed))),
    shape(buildTimelineRows(turnOf(live))),
    "刷新前后必须是同一条记录"
  );
});

test("文本块封口：起止与时刻一律换成服务端那份 直播与刷新是同一个数", () => {
  // 直播那头：块由首个增量建起 时刻是浏览器盖的章 还没有耗时
  const live = [
    { id: 700, kind: "reasoning", at: "09:41:03", text: "先并行检索两个区" },
    { id: 701, kind: "answer", at: "09:41:08", text: "两区合计 4,286 万元" }
  ];
  assert.equal(live[0].durationMs, undefined, "封口帧到达前不该有耗时");

  // 服务端按封口先后发帧：reasoning 那段先收口
  const seal = (kind, at, startedAt, ms) => ({
    kind,
    at,
    startedAt,
    endedAt: startedAt + ms,
    durationMs: ms
  });
  let sealed = applyTextBlockSeal(live, seal("reasoning", "2026-01-01T09:41:02", T0, 2100));
  sealed = applyTextBlockSeal(sealed, seal("answer", "2026-01-01T09:41:07", T0 + 2100, 3600));

  assert.deepEqual(
    sealed.map((block) => block.durationMs),
    [2100, 3600],
    "各认领自己那一段 不许串块"
  );
  assert.deepEqual(
    sealed.map((block) => block.at),
    ["09:41:02", "09:41:07"],
    "时刻也换成服务端那份 否则刷新后当场跳一下"
  );

  // 落库形态：服务端写进去的就是同一组值
  const persisted = sealed.map((block) => ({
    kind: block.kind,
    at: block.kind === "reasoning" ? "2026-01-01T09:41:02" : "2026-01-01T09:41:07",
    text: block.text,
    startedAt: block.startedAt,
    endedAt: block.endedAt,
    durationMs: block.durationMs
  }));
  let rid = 750;
  const replayed = persisted.map((block) => replayBlock(block, (rid += 1)));
  const shape = (rows) =>
    rows
      .filter((row) => row.channel !== "user")
      .map(({ channel, ts, durationMs }) => ({ channel, ts, durationMs }));
  assert.deepEqual(
    shape(buildTimelineRows(turnOf(replayed))),
    shape(buildTimelineRows(turnOf(sealed))),
    "文本行刷新前后必须是同一条记录"
  );
  // 行时刻读的是服务端起点 不是块上那个 at
  assert.deepEqual(
    shape(buildTimelineRows(turnOf(sealed))).map((row) => row.ts),
    [hmsOf(T0), hmsOf(T0 + 2100)],
    "文本行与工具行同一根轴 都取服务端起点"
  );
});

test("封口帧认不到主人就丢掉：无主的起止不许挂到别的块上", () => {
  const blocks = [{ id: 760, kind: "answer", at: "09:41:08", text: "正文" }];
  // kind 对不上：那段 reasoning 的耗时挂到 answer 上 就是把一段文字的耗时说成另一段的
  const wrongKind = applyTextBlockSeal(blocks, {
    kind: "reasoning",
    startedAt: T0,
    endedAt: T0 + 999,
    durationMs: 999
  });
  assert.equal(wrongKind[0].durationMs, undefined);
  assert.equal(wrongKind, blocks, "认不到就原样返回 不造新数组");

  // 已经收口的块不再被后到的帧改写
  const once = applyTextBlockSeal(blocks, {
    kind: "answer",
    startedAt: T0,
    endedAt: T0 + 100,
    durationMs: 100
  });
  const twice = applyTextBlockSeal(once, {
    kind: "answer",
    startedAt: T0,
    endedAt: T0 + 5000,
    durationMs: 5000
  });
  assert.equal(twice[0].durationMs, 100, "收口过的块不许被第二帧改写");
});

test("旧数据：没有批次与计时就不显示耗时 更不拿到达时间补猜", () => {
  const legacy = [
    { kind: "tool", at: "09:41:04", name: "search_knowledge", displayName: "知识库检索", status: "done", result: "[…]" },
    { kind: "tool", at: "09:41:06", name: "search_knowledge", displayName: "知识库检索", status: "done", result: "[…]" }
  ].map((block, i) => replayBlock(block, 800 + i));
  assert.equal(legacy[0].at, "09:41:04", "老数据只有 HH:mm:ss 也要认");
  assert.equal(legacy[0].batchId, undefined);

  const rows = buildTimelineRows(turnOf(legacy));
  assert.equal(batchRows(rows).length, 0, "没有 batchId 就不许归批");
  assert.ok(
    toolRows(rows).every((row) => row.durationMs == null),
    "缺时间宁可不显示"
  );
  assert.equal(toolRows(rows).length, 2, "两次调用两行 同名同结果也不折叠");
});

test("旧端点：帧不带 toolCallId 时按名字回落 调用次数与状态推进不出错", () => {
  const noId = (name, status, extra = {}) => ({ name, displayName: name, status, ...extra });
  const n = (status, extra) => noId("search_knowledge", status, extra);
  // 逐段看状态推进：并成一行的错法只在中途露相 只验终局会被两条尾帧盖过去
  const announced = feed([n("pending"), n("pending")]);
  assert.deepEqual(
    announced.map((block) => block.status),
    ["pending", "pending"],
    "两次 pending 就是两次调用 不许并成一行"
  );
  const started = feed([n("running"), n("running")], announced);
  assert.deepEqual(
    started.map((block) => block.status),
    ["running", "running"],
    "两帧 running 要各推一个 不能把同一个推两遍"
  );
  const blocks = feed(
    [n("done", { result: "one", ok: true }), n("done", { result: "two", ok: true })],
    started
  );
  assert.deepEqual(
    blocks.map((block) => block.status),
    ["done", "done"]
  );
  assert.deepEqual(
    blocks.map((block) => block.result),
    ["one", "two"],
    "先开先闭：无 id 时只能按开始顺序对 结果对调是已知降级"
  );
});

test("工具行时刻取执行起点：与批头同一根轴 不再早于批头 没跑过的才退回声明时刻", () => {
  // 生产里模型 10:10:35 声明 工具体 10:10:37 开工 —— 声明时刻与执行起点相差两秒
  // 批头量的是执行 工具行也说的是这次执行 两边放同一根轴上 子行就不会早于父行
  const startedAt = T0 + 2_000;
  const blocks = feed([
    pending("c1", "current_date", 0),
    pending("c2", "leave_query", 1),
    pending("c3", "load_skill", 2),
    running("c1", "current_date", "r8-0", 0),
    running("c2", "leave_query", "r8-0", 1),
    running("c3", "load_skill", "r8-0", 2),
    settled("c3", "load_skill", "r8-0", 2, startedAt, 1),
    settled("c1", "current_date", "r8-0", 0, startedAt, 3),
    settled("c2", "leave_query", "r8-0", 1, startedAt, 5)
  ]);
  const rows = buildTimelineRows(turnOf(blocks));
  const head = batchRows(rows)[0];
  assert.equal(head.ts, hmsOf(startedAt));
  assert.deepEqual(
    toolRows(rows).map((row) => row.ts),
    [hmsOf(startedAt), hmsOf(startedAt), hmsOf(startedAt)],
    "工具行时刻是工具体开工那一刻 不是模型声明那一刻"
  );
  assert.ok(
    toolRows(rows).every((row) => row.ts >= head.ts),
    "子行不许早于批头"
  );
  assert.deepEqual(
    toolRows(rows).map((row) => row.block.at),
    ["09:41:02", "09:41:02", "09:41:02"],
    "声明时刻仍留在块上 只是不再充当执行时刻"
  );

  // 没进过工具体的只有声明时刻可讲：拒绝那条的块时刻就是拒绝事件到达的时刻
  const denied = applyToolFrame(
    [],
    {
      toolCallId: "c8",
      name: "submit_leave",
      displayName: "提交请假单",
      status: "denied",
      at: DECLARED_AT
    },
    ctx
  );
  assert.equal(toolRows(buildTimelineRows(turnOf(denied)))[0].ts, "09:41:02");
});

test("running 阶段：工具体还没开工 批头不报时刻不报耗时 工具行退回声明时刻", () => {
  const blocks = feed([
    pending("c1", "current_date", 0),
    pending("c2", "leave_query", 1),
    running("c1", "current_date", "r9-0", 0),
    running("c2", "leave_query", "r9-0", 1)
  ]);
  const turn = turnOf(blocks);
  turn.assistants[0].status = "streaming";
  const rows = buildTimelineRows(turn);
  const head = batchRows(rows)[0];
  assert.equal(head.batchSize, 2);
  assert.equal(head.ts, "", "起点还没发生 不许拿声明时刻或到达时刻冒充");
  assert.equal(head.durationMs, undefined);
  assert.equal(head.parallel, undefined, "跑没跑完都不知道 并不并行更无从谈起");
  assert.deepEqual(
    toolRows(rows).map((row) => row.ts),
    ["09:41:02", "09:41:02"]
  );
});

test("并行标注：已记录的执行区间有重叠才标并行 首尾相接的串行不标 没收口不表态", () => {
  const parallel = feed([
    settled("c1", "current_date", "r10-0", 0, T0, 3),
    settled("c2", "leave_query", "r10-0", 1, T0, 5),
    settled("c3", "load_skill", "r10-0", 2, T0, 1)
  ]);
  assert.equal(batchRows(buildTimelineRows(turnOf(parallel)))[0].parallel, true);

  // c1 [T0, T0+100) 收工那一毫秒 c2 才开工：一毫秒都不重叠 就不是并行
  const serial = feed([
    settled("c1", "current_date", "r10-1", 0, T0, 100),
    settled("c2", "leave_query", "r10-1", 1, T0 + 100, 100)
  ]);
  assert.equal(batchRows(buildTimelineRows(turnOf(serial)))[0].parallel, false);

  // 老口径整批共享一份起止 读出来必然重叠 但那不是并行的证据
  const legacy = [0, 1].map((index) =>
    replayBlock(
      {
        kind: "tool",
        at: DECLARED_AT,
        name: `t${index}`,
        status: "done",
        toolCallId: `l${index}`,
        batchId: "r10-2",
        callIndex: index,
        startedAt: T0,
        endedAt: T0 + 1500,
        durationMs: 1500
      },
      600 + index
    )
  );
  assert.equal(batchRows(buildTimelineRows(turnOf(legacy)))[0].parallel, undefined);

  const open = settleToolBlocks(
    feed([
      settled("c1", "current_date", "r10-3", 0, T0, 300),
      { ...running("c2", "leave_query", "r10-3", 1), startedAt: T0 + 100 }
    ]),
    "interrupted"
  );
  assert.equal(batchRows(buildTimelineRows(turnOf(open)))[0].parallel, undefined);
});

test("耗时刻度：毫秒级的数不许显示成 0.0s 同一毫秒内收工写 <1ms", () => {
  assert.deepEqual(
    [0, 1, 3, 5, 49, 99, 100, 999, 1000, 1049, 9999, 10000, 59499, 65000].map(formatDuration),
    [
      "<1ms",
      "1ms",
      "3ms",
      "5ms",
      "49ms",
      "99ms",
      "100ms",
      "999ms",
      "1.0s",
      "1.0s",
      "10.0s",
      "10s",
      "59s",
      "1m05s"
    ]
  );
  assert.equal(formatDuration(undefined), "");
  assert.equal(formatDuration(-1), "");
  assert.equal(formatDuration(Number.NaN), "");
});

let failed = 0;
for (const [name, fn] of cases) {
  try {
    fn();
    console.log(`  ok  ${name}`);
  } catch (error) {
    failed += 1;
    console.log(`fail  ${name}\n      ${error.message}`);
  }
}
rmSync(outDir, { recursive: true, force: true });
console.log(`\n${cases.length - failed}/${cases.length} passed`);
process.exit(failed ? 1 : 0);
