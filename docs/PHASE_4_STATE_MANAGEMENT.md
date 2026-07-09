# Phase 4 — State Management

**No Redux. No new state library.** The app uses **Zustand** for client/UI state and **TanStack
Query** for all server state. Phase 4 stays inside that model.

## Client state — Zustand slices

### Existing (unchanged)

| Store | File | Shape | Persist |
|---|---|---|---|
| `useAuthStore` | [lib/auth.ts](../frontend/src/lib/auth.ts) | `{ token, user, sessionExpired, login, logout, expireSession, clearSessionExpired }` | localStorage (`careerpilot-auth`, token+user only) |
| `useSidebar` | [hooks/useSidebar.ts](../frontend/src/hooks/useSidebar.ts) | `{ mobileOpen, setMobileOpen }` | no |
| `useCopilot` | [hooks/useCopilot.ts](../frontend/src/hooks/useCopilot.ts) | `{ collapsed, toggleCollapsed, mobileOpen, setMobileOpen }` | (verify; rail collapse is UI-only) |

### New in Phase 4 (at most two, both optional)

| Store | Purpose | Shape | Persist |
|---|---|---|---|
| `useRecommendationsUi` | Remember selected priority tab + optimistic action state across remounts | `{ tab: 'critical'\|'high'\|'medium'\|'low'\|'archived', setTab }` | no |
| `useWorkflowExplorerUi` | Remember which correlation view + selected correlationId | `{ view: 'timeline'\|'graph'\|'events'\|'deadletter', correlationId, setView, setCorrelationId }` | no |

Everything else (Kanban column state, Career-Intelligence chart toggles, dashboard widget data) is
**derived from server state** and needs no client store. Prefer local `useState` for ephemeral
per-component UI; only promote to a Zustand slice when state must survive route changes.

## Server state — TanStack Query keys

Single `QueryClient`. Convention: `['<domain>', ...discriminators]`. Mutations call
`invalidateQueries` on the affected keys. New Phase-4 keys:

| Key | Endpoint | Invalidated by |
|---|---|---|
| `['recommendations', priority, page]` | `/api/recommendations` | approve/reject/save/archive mutations |
| `['recommendations','must-apply']` | `/api/recommendations/must-apply` | same |
| `['recommendations','human-review']` | `/api/recommendations/human-review` | approve/reject |
| `['recommendations','behavior-profile']` | `/api/recommendations/behavior-profile` | feedback mutation |
| `['job-relevance', jobId]` | `/api/jobs/{id}/relevance` | — (staleTime ∞; drawer) |
| `['career-intelligence']` | `/api/workflow/career-intelligence` | — |
| `['workflow','analytics']` | `/api/workflow/analytics` | — |
| `['workflow','correlation', id]` | `/api/workflow/correlation/{id}` (+`/graph`,`/events`) | — |
| `['workflow','dead-letter']` | `/api/diagnostics/workflow-dead-letter` | — |
| `['resume','tailored', id]` / `['resume','ats', id]` | `/api/resume/tailor*` / `/api/resume/ats*` | tailor/analyze mutations |
| `['dashboard','today']`, `['dashboard','approvals']`, `['dashboard','interviews']`, `['dashboard','resume-improvements']` | respective endpoints | dashboard refresh |

Existing keys reused: `['dashboard']`, `['jobs', ...]`, `['applications']`, `['workflows']`,
`['workflow-status', threadId]`, `['resumes']`, `['resume-versions', id]`, `['admin', ...]`,
`['copilot-conversations']`, `['candidate','preferences']`, `['observability']`.

## Cross-cutting rules (existing → Phase 4 must follow)

1. **Bearer token** injected by the axios request interceptor; **401 → `expireSession()`** by the
   response interceptor. New calls use the shared `api` instance — no per-call auth handling.
2. **Dark endpoints** use `retry:false` + a `try/catch → null` queryFn + `staleTime` so a disabled
   flag costs one cached 404 (see `PHASE_4_UI_ARCHITECTURE.md`).
3. **Optimistic updates** only where the existing app already does (Kanban `move` via `onMutate`
   snapshot/rollback). Recommendation actions may use the same optimistic pattern; otherwise refetch.
4. **No global event bus / context soup.** Page-to-Copilot coupling stays via the URL (Copilot reads
   `pathname` → `pageConfigForPath`), not shared state. A Phase-4 "explain this recommendation"
   deep-link passes a `contextId` through the existing copilot `send(text, action)` path.

## Data-flow diagram (representative — Recommendations)

```
Recommendations page
  useQuery(['recommendations', tab])  ──GET /api/recommendations?priority=tab──▶ backend
  RecommendationCard
    approve() ─mutation─▶ POST /api/recommendations/approve
      onSuccess → invalidate ['recommendations',*], ['dashboard'], ['applications']
  useRecommendationsUi.tab  (Zustand, survives remount)
  Copilot (right rail) reads pathname '/recommendations' → offers "explain rejection" action
```
