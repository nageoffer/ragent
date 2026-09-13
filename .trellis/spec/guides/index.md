# Thinking Guides

> **Purpose**: Expand your thinking to catch things you might not have considered.

---

## Why Thinking Guides?

**Most bugs and tech debt come from "didn't think of that"**, not from lack of skill:

- Didn't think about what happens at layer boundaries → the frontend switch misses a new SSE event
- Didn't think about code patterns repeating → a new AI provider gets special-cased in the pipeline
- Didn't think about edge cases → an empty retrieval result becomes a `B` error instead of a fallback
- Didn't think about future maintainers → a JSONB column with three different readers

These guides help you **ask the right questions before coding**.

---

## Available Guides

| Guide | Purpose | When to Use |
|-------|---------|-------------|
| [Code Reuse Thinking Guide](./code-reuse-thinking-guide.md) | Interface / registry / config ownership | Before adding a provider, parser, channel, node or config key |
| [Cross-Layer Thinking Guide](./cross-layer-thinking-guide.md) | Data flow and boundary contracts | Features spanning controller, service, retrieval, persistence or the frontend |

---

## Quick Reference: Thinking Triggers

### When to Think About Cross-Layer Issues

- [ ] Feature touches 3+ boundaries (HTTP, SSE, service, retrieval, persistence, frontend)
- [ ] Data format changes between layers (`DO` ↔ domain ↔ `VO`, JSONB, node output JSON)
- [ ] **You are adding or renaming an SSE event type** → the frontend consumer is part of the contract
- [ ] Multiple consumers need the same data (`framework/convention` may already own the shape)
- [ ] **Work crosses a thread pool** → `TransmittableThreadLocal` registration, not a plain `ThreadLocal`
- [ ] You are adding an event kind, MQ payload, JSONL record, or config field
- [ ] You are not sure where some logic belongs

→ Read [Cross-Layer Thinking Guide](./cross-layer-thinking-guide.md)

### When to Think About Code Reuse

- [ ] You're writing similar code to something that exists
- [ ] You see the same pattern repeated 3+ times
- [ ] You're adding a new value to an enum that crosses modules
- [ ] **You're modifying any constant or config value** → the yaml, the properties class and the validator must agree
- [ ] **You're creating a new utility/helper function** ← Search first!
- [ ] You're about to add an `if (provider == ...)` or `if (type == ...)` in orchestration code
- [ ] Multiple branches derive state from the same `type` / `status` / `tier` value

→ Read [Code Reuse Thinking Guide](./code-reuse-thinking-guide.md)

---

## Pre-Modification Rule (CRITICAL)

> **Before changing ANY value, ALWAYS search first!**

```bash
# Backend: is this constant or key declared somewhere else too?
grep -rn "值" --include=*.java --include=*.yaml --include=*.sql .

# Frontend: is this endpoint or event name consumed elsewhere?
grep -rn "事件名或路径" frontend/src .
```

Configuration in this project is deliberately spread across a properties class, a yaml default and a
startup validator. Changing one without the others either fails at boot or — worse — silently changes
runtime behaviour.

---

## When Verifying Review Results

Both the reviewer and the author can be wrong. Before acting on a finding, check it against the code.

- Reviewer claims "user input can be trusted / untrusted" → Check the actual data source. Internal
  enum values, DB rows written by our own ingestion, and configured model ids are not external input.
- Reviewer flags "missing validation" → Is the value already guaranteed by a boot-time validator or
  bean validation on the `*Request`?
- Reviewer says "behaviour change" → Read the code comments and the test that covers it. Several
  behaviours here are deliberate, e.g. channel-level timeout degradation and fail-closed pipeline
  conditions.
- Reviewer identifies a "bug" in a test → Delete the feature under test mentally. If the test still
  passes, it was tautological.
- Reviewer flags a removed `default` branch → Java `switch` on an enum may be intentionally
  exhaustive; removing `default` is what makes the compiler catch the next enum value.

**Verification rule**: every CRITICAL/WARNING finding must be traced to the actual code before it is
prioritized. Budget a meaningful false-positive rate for automated reviews.

---

## How to Use This Directory

1. **Before coding**: skim the guide matching your change
2. **During coding**: if something feels repetitive or spans layers, check the guides
3. **After bugs**: add the new insight to the relevant guide

---

**Core Principle**: 30 minutes of thinking saves 3 hours of debugging.
