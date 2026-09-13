# Code Reuse Thinking Guide

> **Purpose**: Stop and think before creating new code — does it already exist, and where is the single place this belongs?

---

## The Problem

**Duplicated code is the #1 source of inconsistency bugs.**

In this repository the cost is concrete:

- 7 Maven modules with a one-way dependency chain. A rule copied from `rag` into `agent` stops receiving fixes.
- Boot-time validators (`ParserRegistry`, `RetrievalChannelConfigValidator`, `ChatTierConfigValidator`) enforce invariants over declarations. If two places declare the same thing, only one of them is validated.
- Database schema exists twice by design — `resources/database/schema_pg.sql` for new environments and `resources/database/upgrades/<version>/` for existing ones. Copy-pasting instead of following the upgrade convention desynchronizes them.

---

## Before Writing New Code

### Step 1: Search First

```bash
# Interface or class that already does this
grep -rn "interface .*Client\|interface .*Service\|interface .*Channel" --include=*.java .

# The same constant or config key
grep -rn "recall-budget\|max-iters" --include=*.java --include=*.yaml .

# Who already registers into the same extension point
grep -rn "implements DocumentParser\|implements SearchChannel\|implements IngestionNode" --include=*.java .
```

### Step 2: Ask These Questions

| Question | If Yes... |
|----------|-----------|
| Does an interface/implementation pair already exist? | Implement the interface; do not add a parallel path |
| Is this registered in a registry or enum? | Register in the one place, in the same commit |
| Am I copying a query/validation/conversion? | **STOP** — extract to the owning layer |
| Does the same value live in a `@ConfigurationProperties` class and a literal? | Bind the property; do not re-declare the literal |
| Am I adding a second place that must agree with an existing one? | Add a boot-time check instead of a comment |

---

## Common Duplication Patterns

### Pattern 1: Parallel model-client paths

`infra-ai` splits every AI capability into a transport interface, per-provider clients, and a routing service:

```java
infra/chat/ChatClient.java                       // transport: one provider, one HTTP shape
infra/chat/BaiLianChatClient.java                // + AIHubMix / SiliconFlow / Ollama
infra/chat/LLMService.java                       // public entry the rag module calls
infra/chat/RoutingLLMService.java                // tier selection, failover, health
```

**Bad**: adding a provider-specific `if` inside `RAGChatServiceImpl` or in a pipeline node.
**Good**: implement `ChatClient` / `EmbeddingClient` / `RerankClient`, extend the shared
`AbstractOpenAIStyleChatClient` base when the provider speaks the OpenAI wire format, then declare
the provider under `ai.providers`, the candidate under `ai.chat.candidates`, and the tier membership
under `ai.chat.tiers` in `bootstrap/src/main/resources/application.yaml`. Tier selection, first-packet
probing, circuit breaking and downgrade are then reused for free.

**Rule**: the orchestration layer calls `LLMService` / `EmbeddingService` / `RerankService`, never a
concrete `*Client`.

### Pattern 2: A new extension registered everywhere but in the registry

Extension points are enum + registry + startup self-check. `ParserRegistry` is the canonical one —
it fails the application at boot if an extension is declared but unclaimed:

```java
// rag/.../core/parser/registry/ParserRegistry.java
private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "csv",
        "md", "markdown", "txt", "text", "html", "htm", "json", "xml", "rtf",
        "png", "jpg", "jpeg", "svg");
```

**Bad**: implement `DocumentParser` and rely on a wildcard fallback to catch your format.
**Good**: implement the interface, add the value to `ParserType`, add the extension to
`SUPPORTED_EXTENSIONS`. Missing one of the three fails at startup with a named error instead of
silently degrading at query time.

The same shape applies to `SearchChannel` + `SearchChannelProperties`, `IngestionNode`,
`PostProcessor`, and the MCP executors in `mcp-server`. When you add one, grep for the enum and the
validator that guard it.

### Pattern 3: Repeated constants and config keys

**Bad**: a numeric default in a Java field, the same number in `application.yaml`, and a third copy
in a prompt template.

**Good**: one `@ConfigurationProperties` class owns the value and its default; the yaml overrides it;
a validator fails the boot when the combination is impossible. Existing examples:
`SearchChannelProperties`, `AIModelProperties` (`ai.providers` / `ai.selection` / `ai.chat.tiers`),
`MemoryProperties`, `RAGRateLimitProperties`. `RetrievalConfigEnvironmentPostProcessor` and
`RetrievalConfigFailureAnalyzer` exist precisely to turn a bad budget combination into a startup
failure with a readable message.

### Pattern 4: Repeated extraction from the same payload

**Bad**: two callers each hand-parse the same stored JSON.

```java
// every reader invents its own definition of a valid payload
String json = node.getOutputJson();
Map<String, Object> out = objectMapper.readValue(json, new TypeReference<>() {});
String vectorId = (String) out.get("vectorId");
```

**Good**: one codec next to the data owner, imported by every reader —
`knowledge/support/IngestionSpecCodec` and `ingestion/domain/*` play this role for pipeline node
specs and outputs, with `IngestionPipelineNodeMapperTest` and `ConditionalEvaluatorTest` covering
the decode rules.

**Rule**: when the same stored/streamed field is decoded in 2+ places, extract the codec before
adding the third reader.

---

## When to Abstract

**Abstract when**:
- The same logic appears 3+ times
- The logic has invariants worth validating at startup
- The abstraction is an interface in `framework` or `infra-ai` that several modules consume

**Don't abstract when**:
- Used once
- It is a trivial mapping between two DTOs
- The abstraction would cross a module boundary that the dependency chain forbids

Module dependency direction is fixed and worth re-reading before extracting anything:

```
framework ← infra-ai
framework ← system
framework, system, infra-ai ← rag ← agent ← bootstrap
mcp-server (standalone)
```

`framework` depends on nothing internal. Putting shared code in `framework` makes it available
everywhere; putting it in `rag` makes it available only to `agent` and `bootstrap`.

---

## After Batch Modifications

When you have made the same change in several files:

1. **Search** for remaining occurrences of the old form (including yaml, SQL and prompt templates).
2. **Check the paired artifacts**: adding a config key usually means a `@ConfigurationProperties`
   field, an `application.yaml` default, and a validator or test.
3. **Check the mirrors**: `resources/database/schema_pg.sql` and the latest
   `resources/database/upgrades/<version>/` script must describe the same final shape.

### Exhaustive Handling of Enum-Driven Branches

Java `switch` on an enum without a `default` is exhaustive for the compiler, but `if/else` chains
and `switch` with a `default` branch are not. This project passes enums across layers
(`AgentSSEEventType`, `ParserType`, `ModelCapability`, `Tier`, `ParseProfile`, `RetrievalChannel`),
so a new enum value silently lands in the fallback branch.

**Bad** — new tier silently behaves like the old one:

```java
if (tier == Tier.FAST) {
    return fastCandidates;
} else {
    return defaultCandidates;   // a new Tier value lands here with no warning
}
```

**Good** — every value has an explicit branch, or the compiler is allowed to enforce it:

```java
return switch (tier) {
    case FAST -> fastCandidates;
    case STANDARD -> standardCandidates;
    case DEEP -> deepCandidates;
};
```

**Prevention**: when you add a value to an enum that crosses a layer boundary, grep for every
`switch`/`if` on it and add the explicit branch. Then run
`mvn -q -pl <module> test -Dtest=<ValidatorOrTestClass>` — the config validators and the tier tests
are the intended safety net for exactly this class of change.

---

## Checklist Before Commit

- [ ] Searched for an existing interface, registry entry, or config key before adding a new one
- [ ] No AI provider, parser, channel or node logic added outside its registry
- [ ] New enum value: every `switch`/`if` on that enum updated
- [ ] New config key: bound via `@ConfigurationProperties`, defaulted in yaml, no literal duplicate
- [ ] Shared logic placed in the lowest module allowed by the dependency chain
- [ ] Schema change: `schema_pg.sql` and a new `upgrades/<version>/` script agree
