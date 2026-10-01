/** 实际 SSE 解析 → Agent store → 时间线块；只替换网络响应，不调用在线服务。 */
import assert from "node:assert/strict";
import { build } from "esbuild";
import { mkdirSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const outDir = resolve(root, ".output/agent-block-stream");
mkdirSync(outDir, { recursive: true });
const originalFetch = globalThis.fetch;
try {
  await build({
    stdin: {
      contents: `export { useAgentChatStore } from "./src/stores/agentChatStore";
        export { buildTimelineRows } from "./src/lib/agentTimeline";
        export { api } from "./src/services/api";`,
      resolveDir: root,
      loader: "ts"
    },
    outfile: resolve(outDir, "store.mjs"),
    bundle: true,
    platform: "node",
    format: "esm",
    packages: "external",
    tsconfig: resolve(root, "tsconfig.app.json"),
    define: { "import.meta.env": "{}" }
  });
  const { useAgentChatStore: store, buildTimelineRows, api } =
    await import(pathToFileURL(resolve(outDir, "store.mjs")));
  const initial = store.getState();
  const text = (kind, delta) => ["message", { type: kind, delta }];
  const seal = (kind, start, end) => ["block", {
    kind, at: "2026-09-28T01:00:00", startedAt: start, endedAt: end, durationMs: end - start
  }];
  const tool = (id, status, extra = {}) => ["block", {
    kind: "tool", toolCallId: id, name: "query_order", displayName: "查询订单", status, ...extra
  }];
  const finish = ["finish", { messageId: "saved-1", messageStatus: "NORMAL" }];
  const done = ["done", "[DONE]"];
  const respondWith = (frames, onRequest = () => {}) => {
    const wire = new TextEncoder().encode(frames.map(([name, payload]) =>
      `event: ${name}\ndata: ${JSON.stringify(payload)}\n\n`).join(""));
    globalThis.fetch = async (...args) => {
      onRequest(...args);
      return new Response(new ReadableStream({
      start(controller) {
        // 包含拆开的 UTF-8 中文、事件名和 JSON，验证真实解析入口。
        for (let i = 0; i < wire.length; i += 7) controller.enqueue(wire.slice(i, i + 7));
        controller.close();
      }
      }), { headers: { "content-type": "text/event-stream" } });
    };
  };
  const run = async (frames) => {
    store.setState(initial, true);
    respondWith(frames);
    await store.getState().sendMessage("测试块更新");
    const assistant = store.getState().messages.at(-1);
    assert.notEqual(assistant.status, "error");
    return assistant;
  };

  // 旧思考块的计时晚于下一段回答到达，不能把新回答切成两块；工具更新则必须分块。
  let message = await run([
    text("reasoning", "先想"), text("answer", "先查"), seal("reasoning", 1000, 1100),
    text("answer", "订单"), tool("c1", "pending"), seal("answer", 1100, 1200),
    tool("c1", "running"), tool("c1", "done", { result: "订单一", durationMs: 8 }),
    text("answer", "查到了"), seal("answer", 1300, 1400), finish, done
  ]);
  assert.deepEqual(message.blocks.map(b => b.kind), ["reasoning", "answer", "tool", "answer"]);
  assert.deepEqual(message.blocks.map(b => b.text), ["先想", "先查订单", undefined, "查到了"]);
  assert.equal(message.blocks[2].status, "done");
  assert.equal(message.blocks[2].result, "订单一");
  assert.deepEqual(message.blocks.map(b => b.durationMs), [100, 100, 8, 100]);
  assert.equal(message.id, "saved-1");
  console.log("ok 混合 BLOCK 按 kind 分发，迟到的文本时间不切断新文字，工具仍会分块");

  message = await run([
    tool("c1", "pending"), tool("c2", "pending"), tool("c1", "running"), tool("c2", "running"),
    tool("c2", "done", { result: "订单二" }), tool("c1", "done", { result: "订单一" }), finish, done
  ]);
  assert.deepEqual(message.blocks.map(b => [b.toolCallId, b.result]), [["c1", "订单一"], ["c2", "订单二"]]);
  console.log("ok 同名并行工具反序完成，结果仍按 toolCallId 匹配");

  message = await run([
    tool("c1", "pending", { name: "cancel_order" }), tool("c2", "pending"),
    ["confirm", { messageId: "confirm-1", calls: [{ toolCallId: "c1", name: "cancel_order" }] }], done
  ]);
  assert.equal(message.messageStatus, "AWAITING_CONFIRM");
  assert.deepEqual(message.blocks.map(b => [b.kind, b.status]),
    [["tool", "awaiting"], ["tool", "awaiting"], ["confirm", "pending"]]);
  console.log("ok 确认卡仍使同批两个工具等待");

  message = await run([
    tool("c1", "pending"), tool("c1", "running"), text("error", "本轮中断，请核对"),
    ["finish", { messageId: "error-1", messageStatus: "INTERRUPTED" }], done
  ]);
  assert.deepEqual(message.blocks.map(b => b.kind), ["tool", "error"]);
  assert.equal(message.blocks[0].status, "interrupted");
  assert.equal(message.blocks[1].text, "本轮中断，请核对");
  console.log("ok 异常收尾保留错误提示并终结未完成工具");

  const confirmFixture = () => ({
    id: "confirm-1", role: "assistant", content: "", status: "done", messageStatus: "AWAITING_CONFIRM",
    blocks: [
      { id: 10001, kind: "tool", at: "01:00:00", name: "cancel_order", toolCallId: "c1", status: "awaiting" },
      { id: 10002, kind: "tool", at: "01:00:00", name: "cancel_order", toolCallId: "c2", status: "awaiting" },
      { id: 10003, kind: "confirm", at: "01:00:00", status: "pending", calls: [{ toolCallId: "c1", name: "cancel_order" }] }
    ]
  });
  const rowsOf = () => buildTimelineRows({ id: "turn", index: 1, assistants: store.getState().messages });
  // 只比较用户可见的状态和结果；回放重新分配本地块 ID。
  const visible = () => rowsOf().map(row => ({
    channel: row.channel, status: row.block?.status, toolCallId: row.block?.toolCallId,
    result: row.block?.result, batchWaiting: row.batchWaiting,
    outcomes: row.outcomes?.map(block => block && { status: block.status, result: block.result })
  }));
  const originalAdapter = api.defaults.adapter;
  const replayHistory = async (messages) => {
    api.defaults.adapter = async config => ({ data: { code: "0", data: messages },
      status: 200, statusText: "OK", headers: {}, config });
    try {
      await store.getState().loadMessages("conversation-1", true);
    } finally {
      api.defaults.adapter = originalAdapter;
    }
  };

  // 用户拒绝后框架继续生成回复，不发布该调用的工具结果事件；原块的状态由确认结算同步。
  {
    store.setState({ ...initial, currentSessionId: "conversation-1", messages: [confirmFixture()] }, true);
    const answer = "本次取消订单操作没有执行，订单保持不变";
    respondWith([
      ["meta", { conversationId: "conversation-1", taskId: "resume-1" }],
      text("answer", answer), finish, done
    ], (_url, init) => {
      assert.deepEqual(JSON.parse(init.body), { conversationId: "conversation-1", messageId: "confirm-1", approved: false });
      assert.equal(store.getState().messages[0].blocks[2].status, "submitting");
      assert.equal(store.getState().messages[0].blocks[0].status, "awaiting", "受理之前不提前拒绝工具");
    });
    await store.getState().confirmPendingTool("confirm-1", 10003, false);
    assert.deepEqual(store.getState().messages[0].blocks.map(block => block.status), ["denied", "awaiting", "denied"]);
    assert.equal(store.getState().messages[0].messageStatus, "NORMAL");
    assert.deepEqual(rowsOf().filter(row => row.channel === "tool").map(row => row.block.toolCallId), ["c2"]);
    assert.equal(rowsOf().find(row => row.channel === "confirm").outcomes[0].status, "denied");
    const live = visible();
    const saved = confirmFixture();
    saved.messageStatus = "NORMAL";
    saved.blocks[0].status = "denied";
    saved.blocks[2].status = "denied";
    assert.deepEqual(store.getState().messages[1].blocks.map(block => block.kind), ["answer"]);
    await replayHistory([saved, { id: "saved-1", role: "assistant", content: answer, messageStatus: "NORMAL",
      blocks: [{ kind: "answer", at: "01:00:01", text: answer }] }]);
    assert.deepEqual(visible(), live);
    console.log("ok 用户拒绝后原工具块与确认卡均为 denied，无需新增工具事件，实时与历史一致");
  }

  for (const status of ["done", "failed"]) {
    store.setState({ ...initial, currentSessionId: "conversation-1", messages: [confirmFixture()] }, true);
    const result = status === "done" ? "真实提交结果" : "真实执行错误";
    respondWith([["meta", { conversationId: "conversation-1", taskId: "resume-2" }],
      tool("c1", "running"), tool("c1", status, { result }), finish, done]);
    await store.getState().confirmPendingTool("confirm-1", 10003, true);
    assert.deepEqual(store.getState().messages[0].blocks.map(block => block.status), ["awaiting", "awaiting", "approved"]);
    const live = visible();
    const saved = confirmFixture();
    saved.messageStatus = "NORMAL";
    saved.blocks[2].status = "approved";
    await replayHistory([saved, { id: "saved-1", role: "assistant", content: "", messageStatus: "NORMAL",
      blocks: [tool("c1", status, { result })[1]] }]);
    assert.deepEqual(visible(), live);
    console.log(`ok 同意后 ${status}：卡片 approved、原工具保持 awaiting，实时与历史一致`);
  }

  store.setState({ ...initial, currentSessionId: "conversation-1", messages: [confirmFixture()] }, true);
  const expired = confirmFixture();
  expired.messageStatus = "NORMAL";
  expired.blocks[2].status = "expired";
  api.defaults.adapter = async config => {
    assert.deepEqual(store.getState().messages[0].blocks.map(block => block.status),
      ["awaiting", "awaiting", "submitting"], "历史回查覆盖本地消息前，不得把失效请求当成用户拒绝");
    return { data: { code: "0", data: [expired] }, status: 200, statusText: "OK", headers: {}, config };
  };
  try {
    // 失效请求没有 meta；确认入口会重新加载服务端卡片。
    respondWith([["error", { message: "待确认的操作已失效" }], done]);
    await store.getState().confirmPendingTool("confirm-1", 10003, false);
    assert.deepEqual(store.getState().messages[0].blocks.map(block => block.status), ["awaiting", "awaiting", "expired"]);
  } finally {
    api.defaults.adapter = originalAdapter;
  }
  console.log("ok 确认失效时通过历史回查恢复真实状态，不标记为用户拒绝");

  console.log("8/8 passed");
} finally {
  globalThis.fetch = originalFetch;
  rmSync(outDir, { recursive: true, force: true });
}
