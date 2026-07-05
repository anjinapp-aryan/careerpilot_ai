# Phase 4 — UI Architecture

## Stack (existing — no additions required)

| Concern | Library | Notes |
|---|---|---|
| Framework | React 18.3 + TypeScript 5.6 | |
| Build | Vite 5.4 | `@` alias → `src/` |
| Server state | TanStack Query 5 | single `QueryClient`, `invalidateQueries` after mutations |
| Client state | Zustand 5 (`persist` for auth) | 3 slices today: `auth`, `sidebar`, `copilot` |
| HTTP | axios 1.7 | one instance in [lib/api.ts](../frontend/src/lib/api.ts), bearer interceptor + 401→logout |
| Routing | react-router-dom 6.27 | nested routes under `AppShell` outlet |
| Drag & drop | @dnd-kit/core 6 | Applications Kanban |
| Charts | recharts 3 | Dashboard + Admin |
| Animation | framer-motion 12 | page transitions, drawers |
| Icons | lucide-react | |
| Styling | Tailwind 3.4 + CSS vars | `hsl(var(--*))` theme tokens; `cn()` merge helper |

**Phase 4 adds zero dependencies.** Every gap in `PHASE_4_MASTER.md §3` is buildable with the above.

## The three-column shell (do not redesign)

```
┌──────────┬───────────────────────────────────────┬─────────────┐
│ Sidebar  │  TopBar                                │  Copilot    │
│ (fixed,  │───────────────────────────────────────│  (right     │
│  dark,   │  <main> scroll region                 │   rail,     │
│  lg+)    │    <AnimatePresence>                   │   collapsi- │
│          │      <Outlet/>  ← routed page          │   ble; SSE) │
│  Command │    </AnimatePresence>                  │             │
│  Palette │                                        │  drawer on  │
│          │                                        │  mobile     │
└──────────┴───────────────────────────────────────┴─────────────┘
```

Source of truth: [AppShell.tsx](../frontend/src/components/app-shell/AppShell.tsx). All Phase 4 pages
mount inside `<Outlet/>`; none touch the shell except to add sidebar nav entries in
[nav.ts](../frontend/src/components/app-shell/nav.ts).

## Layering rules (existing conventions Phase 4 must follow)

1. **Pages** (`src/pages/*.tsx`) — one file per route, own the top-level queries/mutations, compose
   feature components. New Phase 4 pages: `Recommendations.tsx`, `CareerIntelligence.tsx`.
2. **Feature components** (`src/components/<feature>/*`) — reusable, presentational-ish; receive data
   via props or own a scoped query. New feature dirs: `components/recommendations/`,
   `components/career/`, `components/relevance/`.
3. **UI primitives** (`src/components/ui/*`) — Card, Button, Badge, Dialog, Tabs, Skeleton,
   EmptyState, Tooltip, Toast, etc. **Reuse these; do not introduce a new primitive kit.** A drawer
   is `Dialog size="lg"` (as the lifecycle drawer already is).
4. **Hooks** (`src/hooks/*`) — encapsulate query logic (`useJobs`, `useResumes`, `useWorkflowStatus`,
   `useCopilot`). New: `useRecommendations`, `useCareerIntelligence`, `useJobRelevance`.
5. **lib** (`src/lib/*`) — `api`, `auth`, `cn`, `copilotStream`, `jobTelemetry`, `theme`.

## Dark-tolerance contract (mandatory for every Phase 4 fetch)

Most Phase-4 target endpoints ship dark. Follow the pattern already used by `PlatformIntelligence`
and `ApplicationDetailDialog`:

```ts
useQuery({
  queryKey: [...],
  queryFn: async () => { try { return (await api.get(url)).data; } catch { return null; } },
  retry: false,          // one shot; a 404/403 is "not enabled", not an error to retry
  staleTime: 30_000,     // don't hammer a dark endpoint
});
// render: loading → skeleton; null/empty → quiet "Not enabled yet" copy; data → real UI
```

No Phase 4 surface may render a red error or a crash when its backend flag is off.

## Data-freshness model (unchanged)

No SSE/WebSocket except the Copilot stream. Server state refreshes on:
- mount (query),
- explicit `invalidateQueries` after a mutation,
- the existing `useWorkflowStatus` short-poll while a run is active.

Phase 4 keeps this model. The Workflow correlation views and the Recommendation actions refetch on
action; they do **not** add live sockets.

## New-surface blueprint (applies to 4.5 / 4.6 / 4.8)

Each new workspace = `PageHeader` + `Tabs` (where the spec lists tabs) + a list/grid of Cards +
per-item actions as mutations + dark-tolerant empty states. This is the exact shape of the existing
Jobs and Applications pages; new pages are assembled from the same parts.
