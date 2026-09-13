# Cross-Layer Thinking Guide

> **Purpose**: Think through data flow across layers before implementing.

---

## The Problem

**Most bugs in this project happen at layer boundaries**, not inside a single class.

A request crosses at least six boundaries, and each one has a contract:

```
Browser ─ HTTP/SSE ─► Controller ─► Service/pipeline ─► Retrieval channels
                          │                  │                 │
                          ▼                  ▼                 ▼
                     *Request/*VO       DO ↔ domain      RetrievedChunk
                          │                  │                 │
                          ▼                  ▼                 ▼
                    Result<T> envelope   PostgreSQL /      LLM prompt +
                    {code,message,data}  Milvus / ES       citation markup
```

The recurring failures are:

- A new SSE event type added on the backend that the frontend switch does not handle.
- A field written to a JSONB column with one shape and read back expecting another.
- A retrieval channel returning chunks whose metadata the post-processors and prompt formatter
  do not agree on.
- A stored pipeline-node output whose JSON contract the next node silently misreads.

---

## Boundary Contracts In This Repository

| Boundary | Contract owner | Rule |
|---|---|---|
| HTTP response | `framework/convention/Result.java` | Every non-streaming endpoint returns `Result<T>`; `code == "0"` means success |
| HTTP error | `framework/errorcode/BaseErrorCode`, `framework/exception/*` | Throw `ClientException` / `ServiceException` / `RemoteException`; never return an error inside a success envelope |
| SSE stream | `AgentSSEEventType` / `RAGChatController` + `framework/web/SseEmitterSender` | Event names are part of the public contract — the frontend switches on them |
| Controller → service | `*Request` DTOs, `*VO` responses | Request/VO types never leak past the controller into retrieval or persistence code |
| Service ↔ persistence | `*DO` entities, `dao/handler` | Domain objects do not carry MyBatis-Plus annotations; JSONB columns go through `JsonbTypeHandler` |
| Retrieval | `framework/convention/RetrievedChunk`, `SearchChannel` | Every channel emits `RetrievedChunk` regardless of backend (Milvus / ES / LightRAG / web) |
| Answer evidence | `framework/convention/GroundingChunk`, `SourceRef` | This is what gets persisted on the message, rendered as citations, and shown in the source viewer |
| Ingestion pipeline | `ingestion/domain/*`, `IngestionSpecCodec` | Node output JSON is a contract between nodes; decode through the codec, not ad-hoc maps |
| Module boundary | Maven dependency chain | `framework` → (`infra-ai`, `system`) → `rag` → `agent` → `bootstrap`; `mcp-server` standalone |

`framework/convention` exists specifically so the retrieval layer, the prompt layer, the persistence
layer and the frontend-facing VOs can agree on the same shapes. Adding a field there is a
cross-layer change; adding it to a private DTO instead is usually a mistake.

---

## Before Implementing Cross-Layer Features

### Step 1: Map the Data Flow

Write down the actual path before coding. For a RAG question the real path is:

```
RAGChatController.streamChat
  → ChatQueueLimiter.enqueue            (fair queueing, Redis ZSET)
  → StreamChatTraceRunner.run           (trace run + node)
  → StreamChatContext                   (assembled once, passed down)
  → StreamChatPipeline.execute          (ordered stages)
      → retrieval: channels in parallel → fusion → rerank → evidence gate
      → prompt assembly + citation markup
      → LLM streaming
  → StreamCallback → SseEmitter         (event per chunk)
```

Note that `StreamChatContext` is **built once** in `RAGChatServiceImpl` and passed down. Fields
needed by a later stage belong there, not in a static holder or a `ThreadLocal`.

### Step 2: Identify Boundaries

| Boundary | What actually goes wrong here |
|---|---|
| Frontend ↔ backend | SSE event name or payload field renamed; `Result.code` handled as an HTTP status |
| Controller ↔ service | Validation done in the service after the controller already accepted the input |
| Service ↔ DB | A JSONB field serialized with one shape, read with another; null vs empty-list |
| Service ↔ AI infra | Provider-specific field (e.g. `enable_thinking`) leaked into orchestration code |
| Retrieval channel ↔ post-processor | A channel that skips `RetrievedChunkKey` population breaks deduplication silently |
| HTTP thread ↔ async worker | User/trace context lost when work moves to a pool — this is what `TransmittableThreadLocal` in `framework/context` is for |

Thread crossing deserves special attention: a large part of this codebase runs on dedicated thread
pools (`ThreadPoolExecutorConfig`, `StreamAsyncExecutor`, `CollectionParallelRetriever`). Context that
lives in a plain `ThreadLocal` disappears at that boundary. `UserContext` and `RagTraceContext` are
registered with TTL so they survive; a new context holder must be registered the same way.

### Step 3: Define Contracts

For each boundary you touch, answer:

- Exact input shape and who validates it?
- Exact output shape — and is it already owned by a type in `framework/convention`?
- What errors can occur, and are they client errors (`A*`) or service errors (`B*`)?
- If it is persisted or streamed: is there an existing codec/event enum to extend?

---

## Common Cross-Layer Mistakes

### Mistake 1: Implicit format assumptions

**Bad**: treating a JSONB column as an opaque string in one reader and a typed object in another.
**Good**: declare the field, route it through `JsonbTypeHandler`, and read it as the declared type.

### Mistake 2: Scattered validation

**Bad**: the controller accepts the request, and three layers later the retrieval service throws
because a budget combination is impossible.
**Good**: bean validation on the `*Request` for shape, and a startup validator
(`RetrievalChannelConfigValidator`, `ChatTierConfigValidator`, `MemoryConfigValidator`) for
configuration invariants — the README calls this "配置防错", and it is enforced at boot, not at
request time.

### Mistake 3: Leaky abstractions

**Bad**: retrieval code branching on `if (provider == BAILIAN)`.
**Good**: the provider difference lives in the `*Client` implementation under `infra-ai`; the
orchestration layer sees only `LLMService` / `EmbeddingService` / `RerankService`.

### Mistake 4: Every consumer redefines the same payload

**Bad**: a streamed event's fields parsed inline at each consumer.

```java
Map<String, Object> payload = (Map<String, Object>) event.get("data");
String text = (String) payload.get("content");
```

**Good**: one typed owner for the event and one place that narrows it, so a field rename is a
compile error rather than a silent `null`.

**Rule**: for SSE events, JSONB payloads, stored pipeline outputs and MQ events, there is exactly
one owner of the shape — a `framework/convention` type, an enum, or a codec class. Consumers may
format fields; they must not redefine the contract.

### Mistake 5: A second cursor over the same ordering

**Bad**: inventing a new sort key or sequence when one already exists.
**Good**: reuse the existing ordering key. `RetrievedChunkKey` is the established identity for a
chunk across channels; conversation messages use the snowflake id as the tie-breaker
(`ConversationMessageServiceImplOrderTest` locks that behaviour in).

---

## Checklist for Cross-Layer Features

Before implementation:

- [ ] Mapped the complete data flow, including every thread-pool hop
- [ ] Identified which `framework/convention` type already owns the shape (if any)
- [ ] Decided where validation happens: bean validation vs boot-time validator
- [ ] Checked the module dependency chain still permits the new import

After implementation:

- [ ] Frontend switch/handler updated if an SSE event name or payload changed
- [ ] Consumer of a shared type recompiles against the type, not a local cast
- [ ] New context holder registered with TransmittableThreadLocal if it crosses a pool
- [ ] Error path returns a real error code through `GlobalExceptionHandler`, not a success envelope
- [ ] Tests updated at the boundary, not only inside the changed class
