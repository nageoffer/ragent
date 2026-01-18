import type { CompletionPayload, MessageDeltaPayload, StreamMetaPayload } from "@/types";

export interface StreamHandlers {
  onMeta?: (payload: StreamMetaPayload) => void;
  onMessage?: (payload: MessageDeltaPayload) => void;
  onThinking?: (payload: MessageDeltaPayload) => void;
  onFinish?: (payload: CompletionPayload) => void;
  onDone?: () => void;
  onCancel?: (payload: CompletionPayload) => void;
  onReject?: (payload: MessageDeltaPayload) => void;
  onTitle?: (payload: { title: string }) => void;
  onError?: (error: Error) => void;
  onEvent?: (event: string, payload: unknown) => void;
}

export interface StreamOptions {
  url: string;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  retryCount?: number;
  retryDelayMs?: number;
}

function parseData(raw: string): unknown {
  if (!raw) return "";
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}

async function readSseStream(response: Response, handlers: StreamHandlers, signal?: AbortSignal) {
  if (!response.body) {
    throw new Error("流式响应为空");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  let eventName = "message";
  let dataLines: string[] = [];

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
        handlers.onMeta?.(payload as StreamMetaPayload);
        break;
      case "message":
        {
          const messagePayload = payload as MessageDeltaPayload;
          if (messagePayload?.type === "think") {
            handlers.onThinking?.(messagePayload);
          }
          handlers.onMessage?.(messagePayload);
        }
        break;
      case "finish":
        handlers.onFinish?.(payload as CompletionPayload);
        break;
      case "done":
        handlers.onDone?.();
        break;
      case "cancel":
        handlers.onCancel?.(payload as CompletionPayload);
        break;
      case "reject":
        handlers.onReject?.(payload as MessageDeltaPayload);
        break;
      case "title":
        handlers.onTitle?.(payload as { title: string });
        break;
      case "error":
        handlers.onError?.(new Error(String((payload as { error?: string })?.error || payload)));
        break;
      default:
        break;
    }

    eventName = "message";
    dataLines = [];
  };

  while (true) {
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
}

async function streamWithRetry(
  options: StreamOptions,
  handlers: StreamHandlers
): Promise<void> {
  const { url, headers, signal } = options;
  const retryCount = options.retryCount ?? 2;
  const retryDelayMs = options.retryDelayMs ?? 600;

  let attempt = 0;
  while (attempt <= retryCount) {
    try {
      const response = await fetch(url, {
        method: "GET",
        headers: {
          Accept: "text/event-stream",
          ...headers
        },
        signal
      });

      if (!response.ok) {
        throw new Error(`SSE 请求失败（${response.status}）`);
      }

      await readSseStream(response, handlers, signal);
      return;
    } catch (error) {
      const err = error as Error;
      if (signal?.aborted) {
        throw err;
      }
      if (attempt >= retryCount) {
        throw err;
      }
      await new Promise((resolve) => setTimeout(resolve, retryDelayMs * Math.pow(2, attempt)));
      attempt += 1;
    }
  }
}

export function createStreamResponse(options: StreamOptions, handlers: StreamHandlers) {
  const controller = new AbortController();
  const mergedOptions = {
    ...options,
    signal: options.signal ?? controller.signal
  };

  return {
    start: () => streamWithRetry(mergedOptions, handlers),
    cancel: () => controller.abort()
  };
}
