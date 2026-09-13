# Frontend Development Guidelines

> React 18 + TypeScript + Vite console for Ragent AI, under `frontend/`.

---

## Overview

The frontend is a single-page React app (no SSR, no test framework) that talks to the Spring Boot
backend over HTTP and SSE. It has two chat surfaces — the classic RAG chat and the Agent chat — plus
a full admin console. Backend responses arrive wrapped in the project's `Result` envelope and are
unwrapped once, centrally, in the axios interceptor.

Read these files before writing frontend code:

- [Directory Structure](./directory-structure.md) — where a new file belongs
- [API And Streaming](./api-and-streaming.md) — the envelope, service typing, and SSE consumption
- [Components And State](./components-and-state.md) — component, prop, styling and Zustand rules
- [Quality Guidelines](./quality-guidelines.md) — commands, strictness, and known debt

---

## Pre-Development Checklist

Before writing frontend code:

- [ ] Read [API And Streaming](./api-and-streaming.md) if the change touches a request or a stream
- [ ] Confirmed the backend `*VO` shape; mirror it as an interface in the owning service file
- [ ] Decided where the file goes using [Directory Structure](./directory-structure.md)
- [ ] Checked whether a component in `components/<domain>/` already does this
- [ ] If an SSE event name or payload changed, updated **both** the hook switch and the backend enum

---

## Quality Check

After writing frontend code, run from `frontend/`:

```bash
npx tsc -b --noEmit     # type check (strict mode, noUnusedLocals)
npm run lint            # eslint . --ext .ts,.tsx --max-warnings 0
npm run format          # prettier --write .
```

`npm run lint` runs with `--max-warnings 0`, so a single unused import fails the command. There is no
frontend test runner — manual verification is done through the dev server, and
`frontend/harness/` exists for rendering UI states without a backend.

---

## Ground Rules

- **No new dependencies without a reason.** The stack is deliberately small; state is Zustand, forms
  are react-hook-form + zod, UI primitives are shadcn/ui on Radix.
- **Named exports only** (the single exception is `App.tsx`).
- **Chinese UI strings are written inline.** There is no i18n layer; do not introduce one.
- **Never call `fetch` directly for JSON.** Use `api` from `@/services/api`; only streaming endpoints
  bypass it, because they need the raw response body.
- **Document reality, not aspiration.** Where this spec describes a current inconsistency, follow the
  dominant pattern and do not spread the exception.
