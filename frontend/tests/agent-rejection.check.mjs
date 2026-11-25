/**
 * 流建立前的拒绝：时间线展示服务端原因；侧栏轮数以服务端历史为准，之后只在普通提问收到 meta 时加一
 * 运行：node tests/agent-rejection.check.mjs
 */
import assert from "node:assert/strict";
import { build } from "esbuild";
import { mkdirSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const output = resolve(root, ".output/agent-rejection");
mkdirSync(output, { recursive: true });
const originalFetch = globalThis.fetch;
let api;
let originalAdapter;
try {
  await build({
    stdin: {
      contents: [
        'export { useAgentChatStore } from "./src/stores/agentChatStore";',
        'export { api } from "./src/services/api";',
        'export { buildTimelineRows } from "./src/lib/agentTimeline";'
      ].join(" "),
      resolveDir: root, loader: "ts"
    },
    outfile: resolve(output, "store.mjs"), bundle: true, platform: "node", format: "esm",
    packages: "external", tsconfig: resolve(root, "tsconfig.app.json"), define: { "import.meta.env": "{}" }
  });
  const mod = await import(pathToFileURL(resolve(output, "store.mjs")));
  const store = mod.useAgentChatStore;
  ({ api } = mod);
  originalAdapter = api.defaults.adapter;
  const histories = {};
  api.defaults.adapter = async (config) => {
    const match = config.url.match(/\/conversations\/([^/]+)\/messages$/);
    const data = match ? histories[match[1]] ?? [] : [];
    return { data: { code: "0", data }, status: 200, statusText: "OK", headers: {}, config };
  };

  const replies = [];
  globalThis.fetch = async (url, init) => replies.shift()(url, init);
  const json = (body) => () => new Response(JSON.stringify(body), { headers: { "content-type": "application/json" } });
  const sse = (frames) => () => {
    const text = frames.map(([event, data]) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`).join("");
    return new Response(text, { headers: { "content-type": "text/event-stream" } });
  };
  const turnsOf = (id) => store.getState().sessions.find((session) => session.id === id)?.turns;
  const errorRow = (assistant) =>
    mod.buildTimelineRows({ id: "t", index: 1, assistants: [assistant] }).find((row) => row.channel === "error")?.text;

  // 首问被审核拒绝：服务端没受理，不发 meta
  replies.push(json({ code: "A000001", message: "抱歉，这个请求我无法处理。" }));
  await store.getState().sendMessage("帮我伪造退款凭证");
  const rejected = store.getState().messages.at(-1);
  assert.equal(rejected.status, "error");
  assert.equal(errorRow(rejected), "抱歉，这个请求我无法处理。");
  console.log("ok 被拒的提问在时间线上显示服务端原因，而不是「请稍后重试」");

  // 第二问正常受理
  replies.push(sse([
    ["meta", { conversationId: "c-1", taskId: "t-1" }],
    ["message", { type: "answer", delta: "退款已提交" }],
    ["finish", { messageId: "m-1", title: "退款进度", messageStatus: "NORMAL" }],
    ["done", "[DONE]"]
  ]));
  await store.getState().sendMessage("我的退款到哪一步了");
  assert.equal(turnsOf("c-1"), 1, "被拒的那问不算一轮");
  console.log("ok 第一问被拒、第二问成功，侧栏显示 1 轮");

  // 第三问在 meta 之前断网：不知道服务端是否落库，不算数，时间线仍是通用文案
  replies.push(() => { throw new TypeError("Failed to fetch"); });
  await store.getState().sendMessage("还有别的问题");
  assert.equal(turnsOf("c-1"), 1);
  assert.equal(errorRow(store.getState().messages.at(-1)), "生成失败，请稍后重试");
  console.log("ok meta 之前断网不加轮数，时间线保留通用文案");

  // 侧栏记着 1 轮，另一台设备又问了一轮；切回来加载出 2 轮历史后，再问一次应是 3 轮
  store.getState().startNewChat();
  store.setState({ sessions: [{ id: "c-2", title: "多端会话", turns: 1 }] });
  histories["c-2"] = [
    { id: "u-1", role: "user", content: "第一问" },
    { id: "a-1", role: "assistant", content: "第一答" },
    { id: "u-2", role: "user", content: "另一台设备上的第二问" },
    { id: "a-2", role: "assistant", content: "第二答" }
  ];
  await store.getState().loadMessages("c-2");
  assert.equal(turnsOf("c-2"), 2, "加载历史后按服务端的提问数校准");
  replies.push(sse([
    ["meta", { conversationId: "c-2", taskId: "t-3" }],
    ["message", { type: "answer", delta: "第三答" }],
    ["finish", { messageId: "a-3", title: "多端会话", messageStatus: "NORMAL" }],
    ["done", "[DONE]"]
  ]));
  await store.getState().sendMessage("第三问");
  assert.equal(turnsOf("c-2"), 3);
  console.log("ok 切回会话以服务端历史校准轮数，之后的提问在此基础上加一");
} finally {
  globalThis.fetch = originalFetch;
  if (api) api.defaults.adapter = originalAdapter;
  rmSync(output, { recursive: true, force: true });
}
