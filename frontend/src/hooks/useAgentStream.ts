import type {
  AgentCompletionPayload,
  AgentConfirmPayload,
  AgentHintPayload,
  AgentMessageDelta,
  AgentMetaPayload,
  AgentToolProgress
} from "@/types/agent";

export interface AgentStreamHandlers {
  onMeta?: (payload: AgentMetaPayload) => void;
  onMessage?: (payload: AgentMessageDelta) => void;
  onThinking?: (payload: AgentMessageDelta) => void;
  onTool?: (payload: AgentToolProgress) => void;
  onHint?: (payload: AgentHintPayload) => void;
  onConfirm?: (payload: AgentConfirmPayload) => void;
  onFinish?: (payload: AgentCompletionPayload) => void;
  onDone?: () => void;
  onCancel?: (payload: AgentCompletionPayload) => void;
  onError?: (error: Error) => void;
  onEvent?: (event: string, payload: unknown) => void;
}

export interface AgentStreamOptions {
  url: string;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  // 带 body 即走 POST：确认裁决是有副作用的动作 不该塞进 query 让浏览器随手重发
  body?: unknown;
}

function parseData(raw: string): unknown {
  if (!raw) return "";
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}

async function readSseStream(
  response: Response,
  handlers: AgentStreamHandlers,
  signal?: AbortSignal
) {
  if (!response.body) {
    throw new Error("流式响应为空");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  let eventName = "message";
  let dataLines: string[] = [];
  let terminated = false;

  const dispatchEvent = () => {
    if (dataLines.length === 0) {
      eventName = "message";
      return;
    }
    const raw = dataLines.join("\n");
    const payload = parseData(raw);
    handlers.onEvent?.(eventName, payload);

    switch (eventName) {
      case "meta":
        handlers.onMeta?.(payload as AgentMetaPayload);
        break;
      case "message":
        {
          const messagePayload = payload as AgentMessageDelta;
          if (messagePayload?.type === "think") {
            handlers.onThinking?.(messagePayload);
          }
          handlers.onMessage?.(messagePayload);
        }
        break;
      case "tool":
        handlers.onTool?.(payload as AgentToolProgress);
        break;
      case "hint":
        handlers.onHint?.(payload as AgentHintPayload);
        break;
      case "confirm":
        handlers.onConfirm?.(payload as AgentConfirmPayload);
        break;
      case "finish":
        handlers.onFinish?.(payload as AgentCompletionPayload);
        break;
      case "done":
        terminated = true;
        handlers.onDone?.();
        break;
      case "cancel":
        handlers.onCancel?.(payload as AgentCompletionPayload);
        break;
      default:
        break;
    }

    eventName = "message";
    dataLines = [];
  };

  for (;;) {
    if (signal?.aborted) {
      reader.cancel();
      break;
    }
    const { value, done } = await reader.read();
    if (done) {
      dispatchEvent();
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split(/\r?\n/);
    buffer = lines.pop() ?? "";
    for (const line of lines) {
      if (!line) {
        dispatchEvent();
        continue;
      }
      if (line.startsWith(":")) {
        continue;
      }
      if (line.startsWith("event:")) {
        eventName = line.slice(6).trim();
        continue;
      }
      if (line.startsWith("data:")) {
        dataLines.push(line.slice(5).trim());
      }
    }
  }

  // 后端每条出口都以 done 封尾 没收到它就是连接被掐断
  // 当成功静默收场 这一轮会永远停在「等待响应」
  // 主动取消由上层自己收尾 不算异常
  if (!terminated && !signal?.aborted) {
    throw new Error("连接已中断，本轮回答未完成");
  }
}

// 只发一次 失败就交给上层：Agent 一轮里可能已经执行过写操作
// 而客户端没有办法自证「这一轮在服务端没跑起来」——连一帧都没收到也可能只是回程断了
// 重发就意味着整轮重跑 写操作再执行一遍 与 HITL 的不重复提交直接冲突
async function streamOnce(
  options: AgentStreamOptions,
  handlers: AgentStreamHandlers
): Promise<void> {
  const { url, headers, signal, body } = options;
  const post = body !== undefined;

  const response = await fetch(url, {
    method: post ? "POST" : "GET",
    headers: {
      Accept: "text/event-stream",
      ...(post ? { "Content-Type": "application/json" } : {}),
      ...headers
    },
    body: post ? JSON.stringify(body) : undefined,
    signal
  });

  if (!response.ok) {
    throw new Error(`SSE 请求失败（${response.status}）`);
  }

  // @IdempotentSubmit 拦截时返回 200 + JSON 体而非事件流 文案取自 JSON 体
  const contentType = response.headers.get("content-type") || "";
  if (!contentType.includes("text/event-stream")) {
    const errBody = (await response.json().catch(() => null)) as { message?: string } | null;
    throw new Error(errBody?.message || "请求失败");
  }

  await readSseStream(response, handlers, signal);
}

export function createAgentStreamResponse(
  options: AgentStreamOptions,
  handlers: AgentStreamHandlers
) {
  const controller = new AbortController();
  const mergedOptions = {
    ...options,
    signal: options.signal ?? controller.signal
  };

  return {
    start: () => streamOnce(mergedOptions, handlers),
    cancel: () => controller.abort()
  };
}
