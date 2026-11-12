/**
 * 两条可控制的 SSE 连接穿插发帧，验证会话切换和定向停止
 */
import assert from "node:assert/strict";
import { build } from "esbuild";
import { mkdirSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const output = resolve(root, ".output/agent-concurrency");
mkdirSync(output, { recursive: true });
const originalFetch = globalThis.fetch;
let store;
let api;
let originalAdapter;
try {
  await build({
    stdin: { contents: 'export { useAgentChatStore } from "./src/stores/agentChatStore"; export { api } from "./src/services/api";',
      resolveDir: root, loader: "ts" },
    outfile: resolve(output, "store.mjs"), bundle: true, platform: "node", format: "esm",
    packages: "external", tsconfig: resolve(root, "tsconfig.app.json"), define: { "import.meta.env": "{}" }
  });
  ({ useAgentChatStore: store, api } = await import(pathToFileURL(resolve(output, "store.mjs"))));
  originalAdapter = api.defaults.adapter;
  const connections = [];
  const stops = [];
  const historyReads = [];
  const histories = {
    B: [
      { id: "saved-B", role: "assistant", content: "B 服务端版本", durationMs: 123,
        blocks: [{ kind: "answer", text: "B 服务端版本", at: "10:20:30" }] },
      { id: "other-device", role: "assistant", content: "其他设备新增的一轮" }
    ],
    late: [{ id: "saved-late", role: "assistant", content: "后台生成（服务端）" }]
  };
  api.defaults.adapter = async (config) => {
    let data = null;
    if (/\/stop\?taskId=/.test(config.url)) {
      stops.push(config.url);
    } else {
      const match = config.url.match(/\/conversations\/([^/]+)\/messages$/);
      assert.ok(match, `未预期的请求：${config.url}`);
      historyReads.push(match[1]);
      data = histories[match[1]] ?? [];
    }
    return { data: { code: "0", data }, status: 200, statusText: "OK", headers: {}, config };
  };
  globalThis.fetch = async (_url, init) => {
    let controller;
    const body = new ReadableStream({ start(value) { controller = value; } });
    const encoder = new TextEncoder();
    const connection = {
      signal: init.signal,
      send(event, data) { controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)); },
      close() { controller.close(); }
    };
    connections.push(connection);
    return new Response(body, { headers: { "content-type": "text/event-stream" } });
  };
  const waitFor = async (predicate) => {
    const deadline = Date.now() + 1000;
    while (!predicate()) {
      if (Date.now() > deadline) throw new Error("等待 SSE 状态超时");
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
  };
  const content = () => store.getState().messages.at(-1)?.content;
  const a = store.getState().sendMessage("会话 A");
  const ca = connections[0];
  ca.send("meta", { conversationId: "A", taskId: "task-A" });
  ca.send("message", { type: "answer", delta: "A1" });
  await waitFor(() => content() === "A1");
  store.getState().startNewChat();
  assert.equal(ca.signal.aborted, false);
  assert.equal(stops.length, 0);
  const b = store.getState().sendMessage("会话 B");
  const cb = connections[1];
  cb.send("meta", { conversationId: "B", taskId: "task-B" });
  cb.send("message", { type: "answer", delta: "B1" });
  await waitFor(() => content() === "B1");
  ca.send("message", { type: "answer", delta: "A2" });
  await waitFor(() => store.getState().conversationStates.A.messages.at(-1).content === "A1A2");
  assert.equal(content(), "B1");
  assert.equal(store.getState().streamTaskId, "task-B");
  console.log("ok 两条流穿插更新，后台回答、任务 ID 和帧不串入当前会话");

  await store.getState().loadMessages("A");
  assert.equal(content(), "A1A2");
  assert.equal(store.getState().isStreaming, true);
  assert.equal(store.getState().frames.some((frame) => frame.data?.taskId === "task-B"), false);
  assert.equal(stops.length, 0);
  store.getState().cancelGeneration();
  await waitFor(() => stops.length === 1);
  assert.match(stops[0], /task-A$/);
  assert.equal(store.getState().conversationStates.B.cancelRequested, false);
  ca.send("cancel", { messageId: "saved-A", title: "A 已停止" });
  ca.send("done", "[DONE]");
  ca.close();
  await a;
  assert.equal(store.getState().messages.at(-1).status, "cancelled");
  await store.getState().loadMessages("B");
  assert.equal(store.getState().isStreaming, true);
  assert.equal(content(), "B1");
  cb.send("message", { type: "answer", delta: "B2" });
  cb.send("finish", { messageId: "saved-B", title: "B 完成" });
  cb.send("done", "[DONE]");
  cb.close();
  await b;
  assert.equal(content(), "B1B2");
  assert.equal(store.getState().isStreaming, false);
  assert.deepEqual(historyReads, [], "活跃会话切换不应重新读取历史");
  console.log("ok 切回活跃会话无需拉历史，停止 A 不影响 B 完成");

  store.getState().startNewChat();
  await store.getState().loadMessages("B");
  assert.equal(content(), "其他设备新增的一轮");
  assert.equal(store.getState().messages[0].content, "B 服务端版本");
  assert.equal(store.getState().messages[0].elapsedMs, 123);
  assert.equal(store.getState().messages[0].blocks[0].at, "10:20:30");
  assert.deepEqual(historyReads, ["B"]);
  await store.getState().loadMessages("B");
  assert.deepEqual(historyReads, ["B"], "当前会话的重复导航不应重新加载");
  console.log("ok 已结束会话切回来重拉服务端历史，包括时间戳和其他设备的新轮次");

  store.getState().startNewChat();
  const late = store.getState().sendMessage("迟到的 META");
  const cl = connections[2];
  store.getState().startNewChat();
  const selectedDraft = store.getState().currentViewKey;
  cl.send("meta", { conversationId: "late", taskId: "task-late" });
  cl.send("message", { type: "answer", delta: "后台生成" });
  await waitFor(() => store.getState().conversationStates.late?.messages.at(-1).content === "后台生成");
  assert.equal(store.getState().currentViewKey, selectedDraft);
  assert.equal(store.getState().currentSessionId, null);
  assert.equal(store.getState().messages.length, 0);
  cl.send("finish", { messageId: "saved-late" });
  cl.send("done", "[DONE]");
  cl.close();
  await late;
  await store.getState().loadMessages("late");
  assert.equal(content(), "后台生成（服务端）");
  console.log("ok META 迟到不抢回页面，后台完成后仍能切回查看");

  store.getState().startNewChat();
  const stale = store.getState().sendMessage("退出前的流");
  const cs = connections[3];
  store.getState().reset();
  assert.equal(cs.signal.aborted, true);
  cs.send("meta", { conversationId: "stale", taskId: "stale-task" });
  cs.send("message", { type: "answer", delta: "旧账号内容" });
  cs.close();
  await stale;
  assert.equal(store.getState().messages.length, 0);
  assert.deepEqual(store.getState().conversationStates, {});
  console.log("ok 账号重置关闭全部连接，迟到帧不会复活旧会话");
  let finishHistory;
  api.defaults.adapter = (config) => new Promise((resolve) => {
    finishHistory = () => resolve({ data: { code: "0", data: [{ id: "old", role: "assistant", content: "旧账号历史" }] },
      status: 200, statusText: "OK", headers: {}, config });
  });
  const history = store.getState().loadMessages("old-history");
  await waitFor(() => typeof finishHistory === "function");
  store.getState().reset();
  finishHistory();
  await history;
  assert.equal(store.getState().messages.length, 0);
  assert.deepEqual(store.getState().conversationStates, {});
  let finishSessions;
  api.defaults.adapter = (config) => new Promise((resolve) => {
    finishSessions = () => resolve({ data: { code: "0", data: [{ conversationId: "old-session", title: "旧账号列表" }] },
      status: 200, statusText: "OK", headers: {}, config });
  });
  const sessions = store.getState().loadSessions();
  await waitFor(() => typeof finishSessions === "function");
  store.getState().reset();
  finishSessions();
  await sessions;
  assert.deepEqual(store.getState().sessions, []);
  console.log("ok 账号重置后，迟到的历史与会话列表响应不会回填");

  store.setState({ currentViewKey: "deleted", currentSessionId: "deleted", messages: [{ id: "gone", role: "user", content: "已删除" }] });
  api.defaults.adapter = async (config) => ({ data: { code: "0", data: null },
    status: 200, statusText: "OK", headers: {}, config });
  await store.getState().deleteSession("deleted");
  assert.equal(store.getState().currentSessionId, null);
  assert.equal(store.getState().messages.length, 0);
  assert.equal("deleted" in store.getState().conversationStates, false);
  console.log("ok 删除当前会话后返回空白页，不重新缓存已删除消息");
  console.log("7/7 passed");
} finally {
  store?.getState().reset();
  globalThis.fetch = originalFetch;
  if (api && originalAdapter) api.defaults.adapter = originalAdapter;
  rmSync(output, { recursive: true, force: true });
}
