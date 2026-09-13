# Frontend Directory Structure

> Where frontend files live, and how to decide where a new one goes.

---

## Layout

```
frontend/
├── index.html                  app entry → src/main.tsx
├── harness/                    offline UI scaffold (index.html + main.tsx), not part of the build
├── vite.config.ts              alias "@" → ./src, dev proxy /api → http://localhost:9090
├── tsconfig.app.json           strict TS config (the one that matters)
├── .eslintrc.cjs               legacy ESLint config (not flat config)
├── .prettierrc                 printWidth 100, double quotes, semicolons
└── src/
    ├── components/ui/          shadcn/ui primitives — kebab-case files
    ├── components/common/      app chrome: Avatar, Loading, Toast, ErrorBoundary, EngineGate
    ├── components/layout/      Header, Sidebar, MainLayout
    ├── components/chat/        classic RAG chat UI
    ├── components/agent/       agent chat UI
    ├── components/admin/       reusable admin widgets
    ├── components/document/    DocumentPreview, DocxPreview, PdfPreview
    ├── components/session/     SessionItem, SessionList
    ├── pages/                  route components; pages/admin/<feature>/ per feature
    ├── hooks/                  useAuth, useChat, useStreamResponse, useAgentStream
    ├── lib/                    pure helpers: utils.ts (cn), source.ts, csvToMarkdown.ts
    ├── services/               one file per backend domain + shared api.ts
    ├── stores/                 Zustand stores
    ├── types/                  shared domain types (index.ts, agent.ts)
    ├── utils/                  storage.ts, helpers.ts, error.ts, time.ts
    └── styles/globals.css      the only stylesheet
```

The `@` alias resolves to `./src` in both `vite.config.ts` and `tsconfig.app.json`. Use it for every
cross-directory import.

---

## Placement Rules

| You are adding | It goes in | Notes |
|---|---|---|
| A Radix/shadcn primitive | `components/ui/` | kebab-case filename, e.g. `alert-dialog.tsx` |
| A reusable presentational piece for one domain | `components/<domain>/` | `chat`, `agent`, `admin`, `document`, `session`, `layout` |
| Something domain-agnostic used by 2+ domains | `components/common/` | |
| A route component | `pages/` | admin features nested under `pages/admin/<feature>/` |
| A subcomponent used by one page only | `pages/<...>/components/` | e.g. `pages/admin/traces/components/` |
| A backend call | `services/<domain>Service.ts` | export functions, not a class |
| A server response type | inline in the service file that consumes it | `export interface XxxVO` |
| A type shared across chat/agent/admin | `types/` | `index.ts` for chat, `agent.ts` for agent |
| Global client state | `stores/<name>Store.ts` | hook exported as `use<Name>Store` |
| Component-local state | `useState` in the component | do not add a store |
| A pure, stateless function | `lib/` | |
| A small side-effecting helper | `utils/` | `storage`, `time`, `error` |
| A new custom hook | `hooks/` | |

---

## Naming

- **Components and pages**: `PascalCase.tsx` (`AgentTurn.tsx`, `ChatPage.tsx`).
- **`components/ui/` only**: `kebab-case.tsx` — this is the shadcn convention and the only place it applies.
- **Stores**: `camelCaseStore.ts` exporting `useCamelCaseStore`.
- **Services**: `camelCaseService.ts` exporting plain async functions.
- **Props/VO interfaces**: `interface XxxProps`, `interface XxxVO`.
- **Union/literal types**: `type Role = "user" | "assistant"`.
- Frontend-only view types derivable from a server type get a `UI` suffix (`AgentBlock` →
  `AgentBlockUI` in `types/agent.ts`) so the server contract stays distinguishable.

---

## Anti-Patterns

- **Do not add files under `frontend/@/`.** That directory exists by accident — a `shadcn add` run
  treated the `@` alias as a literal path, producing `@/components/ui/*` as real files. It is
  git-tracked dead code that duplicates `src/components/ui/`. Never import from it, and do not treat
  the components that exist only there (`sheet.tsx`, `tabs.tsx`, `progress.tsx`) as available.
- **Do not add a barrel `index.ts`.** The only one is `src/types/index.ts`; everything else imports
  the concrete file.
- **Do not put a page-level component in `components/`** or vice versa — the split is by reuse, not
  by size.
- **Do not edit `vite.config.js` / `vite.config.d.ts`.** They are committed `tsc` artifacts; the
  source of truth is `vite.config.ts`.
