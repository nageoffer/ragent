import { afterEach, describe, expect, it, vi } from "vitest";

import { createStreamResponse } from "@/hooks/useStreamResponse";

function sseBody(...frames: Array<[string, string]>): ReadableStream<Uint8Array> {
  const text = frames
    .map(([name, data]) => `event: ${name}\ndata: ${data}\n\n`)
    .join("");
  return new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(text));
      controller.close();
    }
  });
}

function okResponse(body: ReadableStream<Uint8Array>): Response {
  return new Response(body, {
    status: 200,
    headers: { "content-type": "text/event-stream" }
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("createStreamResponse", () => {
  it("按 meta → message → finish → done 顺序派发事件与 payload", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      okResponse(
        sseBody(
          ["meta", '{"conversationId":"conv-1","taskId":"task-1"}'],
          ["message", '{"type":"response","delta":"你好"}'],
          ["finish", '{"messageId":"m-1","title":"会话标题"}'],
          ["done", "[DONE]"]
        )
      )
    );
    vi.stubGlobal("fetch", fetchMock);

    const events: string[] = [];
    const metaPayloads: unknown[] = [];
    const messagePayloads: unknown[] = [];
    const finishPayloads: unknown[] = [];
    const done = vi.fn();

    await createStreamResponse(
      { url: "/rag/v3/chat", retryCount: 0 },
      {
        onMeta: (payload) => {
          events.push("meta");
          metaPayloads.push(payload);
        },
        onMessage: (payload) => {
          events.push("message");
          messagePayloads.push(payload);
        },
        onFinish: (payload) => {
          events.push("finish");
          finishPayloads.push(payload);
        },
        onDone: () => {
          events.push("done");
          done();
        }
      }
    ).start();

    expect(events).toEqual(["meta", "message", "finish", "done"]);
    expect(metaPayloads).toEqual([{ conversationId: "conv-1", taskId: "task-1" }]);
    expect(messagePayloads).toEqual([{ type: "response", delta: "你好" }]);
    expect(finishPayloads).toEqual([{ messageId: "m-1", title: "会话标题" }]);
    expect(done).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(
      "/rag/v3/chat",
      expect.objectContaining({
        method: "GET",
        headers: expect.objectContaining({ Accept: "text/event-stream" })
      })
    );
  });

  it("跨 chunk 断行仍能完整解析事件", async () => {
    const text =
      'event: meta\ndata: {"conversationId":"c","taskId":"t"}\n\nevent: message\ndata: {"type":"response","delta":"分片"}\n\n';
    const encoder = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        const bytes = encoder.encode(text);
        controller.enqueue(bytes.slice(0, 17));
        controller.enqueue(bytes.slice(17, 40));
        controller.enqueue(bytes.slice(40));
        controller.close();
      }
    });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(okResponse(stream)));

    const messages: unknown[] = [];
    await createStreamResponse(
      { url: "/x", retryCount: 0 },
      {
        onMeta: vi.fn(),
        onMessage: (payload) => messages.push(payload)
      }
    ).start();

    expect(messages).toEqual([{ type: "response", delta: "分片" }]);
  });

  it("error 事件触发 onError", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(okResponse(sseBody(["error", '{"error":"服务异常"}'])))
    );

    const onError = vi.fn();
    await createStreamResponse(
      { url: "/x", retryCount: 0 },
      { onError }
    ).start();

    expect(onError).toHaveBeenCalledTimes(1);
    expect(onError.mock.calls[0][0]).toBeInstanceOf(Error);
    expect(onError.mock.calls[0][0].message).toBe("服务异常");
  });

  it("HTTP 非 2xx 抛错（重试耗尽后）", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 500 }));
    vi.stubGlobal("fetch", fetchMock);

    const start = createStreamResponse(
      { url: "/x", retryCount: 1, retryDelayMs: 1 },
      {}
    ).start;

    await expect(start()).rejects.toThrow("SSE 请求失败（500）");
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("Abort 取消后以 AbortError 结束且不重试", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn((_url: string, init?: RequestInit) => {
        const signal = init?.signal;
        const stream = new ReadableStream<Uint8Array>({
          start(controller) {
            signal?.addEventListener("abort", () => {
              controller.error(new DOMException("aborted", "AbortError"));
            });
          }
        });
        return Promise.resolve(okResponse(stream));
      })
    );

    const { start, cancel } = createStreamResponse({ url: "/x", retryCount: 2 }, {});
    const promise = start();
    // 等 readSseStream 进入 pending read 后再 abort，模拟真实 fetch 流被信号中断
    await new Promise((resolve) => setTimeout(resolve, 0));
    cancel();

    await expect(promise).rejects.toMatchObject({ name: "AbortError" });
  });

  it("首次失败后重试成功", async () => {
    const ok = okResponse(
      sseBody(["meta", '{"conversationId":"c","taskId":"t"}'], ["done", "[DONE]"])
    );
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(Promise.reject(new Error("network down")))
      .mockResolvedValueOnce(ok);
    vi.stubGlobal("fetch", fetchMock);

    const onMeta = vi.fn();
    const onDone = vi.fn();
    await createStreamResponse(
      { url: "/x", retryCount: 2, retryDelayMs: 1 },
      { onMeta, onDone }
    ).start();

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(onMeta).toHaveBeenCalledTimes(1);
    expect(onDone).toHaveBeenCalledTimes(1);
  });
});
