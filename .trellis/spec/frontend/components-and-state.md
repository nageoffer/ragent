# Frontend Components And State

> Component authoring, styling, and Zustand conventions.

---

## Components

Function components with hooks. No class components. `React.forwardRef` only where a Radix primitive
needs to forward one (e.g. `components/ui/button.tsx`).

### Props

```tsx
export interface AgentTurnProps {
  message: AgentMessage;
  isStreaming?: boolean;
}

export function AgentTurn({ message, isStreaming = false }: AgentTurnProps) { ... }
```

- `interface XxxProps` for props; `type` only for unions and literal unions
  (`type Role = "user" | "assistant"`).
- **Named exports everywhere.** The single default export in the app is `App.tsx`.
- No barrel files — import the concrete module. `src/types/index.ts` is the only `index.ts`.

### Styling

- `cn()` from `@/lib/utils` (clsx + tailwind-merge) merges conditional classes.
- Variants use `cva` + `VariantProps`, exported alongside the component
  (`buttonVariants`, `badgeVariants`).
- Tailwind tokens come from `src/styles/globals.css` and `tailwind.config.cjs`; `darkMode: ["class"]`.
  There is no CSS-in-JS and no component-scoped stylesheet.
- Chinese UI strings are written inline. There is no i18n framework — do not add one.

### UI primitives

`components/ui/` contains shadcn/ui components (kebab-case files) built on Radix. To add one, follow
the existing file shape — cva variants, `cn()`, `data-*` attributes — rather than hand-rolling a
one-off. Note that `sheet.tsx`, `tabs.tsx` and `progress.tsx` exist **only** under the stray
`frontend/@/` directory and are not usable; see [Directory Structure](./directory-structure.md).

### Forms

Admin forms use `react-hook-form` + `zod` + `@hookform/resolvers`. Example consumers:
`CreateKnowledgeBaseDialog`, `KnowledgeDocumentsPage`, `IntentEditPage`, `IngestionPage`.

---

## State (Zustand)

Stores live in `src/stores/<name>Store.ts` and export `use<Name>Store`.

```ts
interface AuthState {
  user: User | null;
  token: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (u: string, p: string) => Promise<void>;
  logout: () => Promise<void>;
  checkAuth: () => Promise<void>;
}

export const useAuthStore = create<AuthState>((set, get) => ({ ... }));
```

### Rules

- **Subscribe with a selector** so a component only re-renders on the slice it reads:

  ```ts
  const messages = useChatStore((state) => state.messages);
  ```

  Returning the whole store (`useChatStore()`) subscribes to everything. `useChat.ts` and `useAuth.ts`
  currently do this — do not extend that pattern to new code.

- **No middleware.** No `immer`, no `persist`, no `devtools`, no slices. Persistence is manual through
  `@/utils/storage` (a safe localStorage wrapper).
- **Cross-store writes happen through `getState()`**, e.g. `authStore.login` calls
  `useChatStore.getState().cancelGeneration()` and resets chat state. Keep such coupling rare and
  explicit.
- **Boot order matters.** `src/main.tsx` calls store initializers imperatively before render:

  ```ts
  useThemeStore.getState().initialize();
  useAuthStore.getState().checkAuth();
  ```

  A store that needs to hydrate on startup must expose an `initialize()` and be called from there.

- **Local state stays local.** Loading flags, dialog open state and form state belong in `useState` or
  `react-hook-form` — not in a store.

### Existing stores

| Store | Owns |
|---|---|
| `authStore` | user, token, login/logout, auth expiry |
| `chatStore` | classic RAG chat: sessions, messages, streaming |
| `agentChatStore` | agent chat: sessions, messages, blocks, raw frames |
| `engineStore` | which engine (`workflow` / `agent`) the UI is showing |
| `themeStore` | light/dark mode |

---

## Hooks

- `useStreamResponse` / `useAgentStream` — SSE consumption; see [API And Streaming](./api-and-streaming.md).
- `useChat` / `useAuth` — thin wrappers over the stores (whole-store subscription; see above).
- A new hook goes in `src/hooks/`. If it is pure and stateless, it belongs in `src/lib/` instead.

---

## Rendering Large Content

Chat transcripts use `react-virtuoso` for virtualization and `react-markdown` (with `remark-gfm`,
`remark-cjk-friendly`, `rehype-raw`, `rehype-sanitize`) for markdown. When adding markdown-rendering
paths, extend the existing renderer (`MarkdownRenderer.tsx` / `AgentMarkdownRenderer.tsx`) rather than
mounting a second `ReactMarkdown` with its own plugin list.
