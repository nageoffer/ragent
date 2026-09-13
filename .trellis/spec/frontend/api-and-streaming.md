# Frontend API And Streaming

> How the console talks to the backend: the `Result` envelope, service typing, and SSE consumption.

---

## The Envelope

Every non-streaming backend endpoint returns `Result<T>`:

```json
{ "code": "0", "message": "ok", "data": { }, "requestId": "..." }
```

`code === "0"` means success. Everything else is an error, and the message is user-facing Chinese.

The unwrapping happens **once**, in the axios response interceptor in `src/services/api.ts`:

```ts
api.interceptors.response.use(
  (response) => {
    const payload = response.data;
    if (payload && typeof payload === "object" && "code" in payload) {
      if (payload.code !== "0") {
        const message = payload.message || "请求失败";
        const isAuthExpired = typeof message === "string" && message.includes("未登录");
        if (isAuthExpired) { storage.clearAuth(); window.location.href = "/login"; }
        return Promise.reject(new Error(message));
      }
      return payload.data;      // <-- callers receive `data`, not the envelope
    }
    return payload;
  },
  (error) => { /* 401 -> clearAuth + /login; toast.error(message) */ }
);
```

Consequences you must respect when writing a service function:

- **The resolved value is already `data`.** Do not write `const res = await api.get(...); res.data.data`.
- **The generic signature is `api.get<T, R>` with `R = T`**, because the interceptor changed the
  runtime shape. This is the established pattern:

  ```ts
  export async function listSessions() {
    return api.get<ConversationVO[], ConversationVO[]>("/conversations");
  }
  ```

- **Errors arrive as `Error` with the backend message.** The interceptor already toasts; only add a
  `catch` when you need to change component state (e.g. clearing a loading flag).
- **`requestId` is not surfaced today.** It is dropped by the interceptor and never typed. Do not
  invent a field for it; if trace correlation is needed, add it to the interceptor first.

### Response types

Mirror the backend `*VO` as an `export interface` **inside the service file that consumes it** —
`ConversationVO` in `sessionService.ts`, `AgentMessageVO` in `agentService.ts`, and so on. This is the
dominant convention; `src/types/` holds frontend-shared domain models (`User`, `Message`,
`AgentBlock`), not raw backend DTOs.

Pagination responses use `PageResult<T>` — currently re-declared per service file rather than shared.

---

## Adding An Endpoint

1. Add the function to `src/services/<domain>Service.ts` (create the file if the domain is new).
2. Import `api` from `@/services/api`. (`knowledgeService.ts` uses a relative `./api` import — do not
   copy that.)
3. Declare the request/response interfaces in the same file.
4. Use `api.get<T, T>(...)` / `api.post<T, T>(...)`; use `void` for bodyless responses.
5. The auth header is attached automatically by the request interceptor — never set it manually here.

---

## SSE Streaming

Streaming does **not** use `EventSource`; the endpoints require an `Authorization` header, so the
project hand-rolls an SSE parser over `fetch` + `ReadableStream`.

Two hooks, one per chat surface:

| Hook | Endpoint | Events handled |
|---|---|---|
| `src/hooks/useStreamResponse.ts` | `GET /rag/v3/chat` (classic RAG chat) | `meta`, `message`, `finish`, `done`, `cancel`, `reject`, `title`, `error` |
| `src/hooks/useAgentStream.ts` | `GET /agent/v1/chat` (agent chat) | `meta`, `message`, `tool`, `hint`, `finish`, `done`, `cancel`, `error` |

Both export a factory returning `{ start, cancel }`, use an `AbortController`, buffer SSE lines
manually (`event:` / `data:`), send `Accept: text/event-stream`, and retry with exponential backoff
(default `retryCount: 2`).

### Rules

- **The event name set is a contract with the backend.** It is defined by `AgentSSEEventType` on the
  Java side and switched on in the hook. Adding an event means changing both, in the same change.
- **`message` carries a typed sub-payload.** The hook branches on `messagePayload.type`; `"think"`
  routes to the reasoning display, anything else to the answer text.
- **Streaming URLs are built by hand** in `chatStore.ts` / `agentChatStore.ts`, bypassing axios:

  ```ts
  const url = `${API_BASE_URL}/rag/v3/chat${query}`;
  ```

  The token must be re-read and attached manually for these requests. Do not try to route a stream
  through `api` — the interceptor would consume the body.
- **`cancel` is a real request.** The UI's stop button calls the matching `POST /stop` endpoint with
  the `taskId` from the `meta` event; it is not just an `AbortController.abort()`.
- **The backend closes the stream with `done`**, and `AgentRunHandle` deliberately completes rather
  than fails the emitter because the `text/event-stream` response is already committed. Do not add a
  UI path that assumes a non-200 status for stream errors — read the `error` event instead.

---

## Anti-Patterns

- Reading `response.data.code` in a service function — the interceptor already handled it.
- Creating a second axios instance, or calling `fetch` for JSON.
- Hardcoding `"0"` comparisons outside `api.ts`.
- Adding an SSE event to the frontend switch without adding the matching value to `AgentSSEEventType`
  (or vice versa).
- Duplicating the base-URL/`Authorization` setup in a new store instead of reusing the existing
  streaming factory in `useStreamResponse.ts` / `useAgentStream.ts`.
