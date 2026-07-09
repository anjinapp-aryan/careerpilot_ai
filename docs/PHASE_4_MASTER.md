# Phase 4 — Frontend Integration Layer + Career Workspace (Master Plan)

> **Status: architecture + implementation plan only. No code written in this phase.**
> This document and its siblings (`PHASE_4_*.md`) are the deliverable. Implementation is a
> later, separately-approved step.

## 0. What Phase 4 actually is

The spec frames the frontend as a "prototype." That is not accurate to the working tree. The
React app at [frontend/](../frontend/) already ships:

- The **exact three-column paradigm** the spec asks for — [AppShell.tsx](../frontend/src/components/app-shell/AppShell.tsx)
  renders `Sidebar (left) + TopBar + <Outlet/> + CopilotPanel (right rail) + CommandPalette`.
- Nine routed pages (Dashboard, Resumes, ResumeOptimization, Jobs, Applications, Workflow,
  AdminDashboard, Login, Register), guarded by `Private` + `AdminOnly` in [App.tsx](../frontend/src/App.tsx).
- A **streaming, page-context-aware AI Copilot** ([CopilotPanel.tsx](../frontend/src/components/copilot/CopilotPanel.tsx))
  already wired to the SSE `POST /api/copilot/stream` surface.
- A working **Kanban** (dnd-kit) on Applications, a **Recommended/Domestic/International/Browse**
  tabbed Jobs page, a live **LangGraph workflow** monitor, and a full **Admin** ops dashboard.
- State via **Zustand** (auth, sidebar, copilot) + **TanStack Query** for all server state.

So Phase 4 is **gap-fill and deepening over a ~70%-built app, not a greenfield rebuild.** This
matches the standing project reality recorded for Phase 3B UI integration. The value of this plan
is therefore its **honest gap analysis** (§3) — what the enterprise backend exposes that the UI
does *not yet* consume — not a re-listing of screens that already exist.

The guiding principle: **every Phase 4 addition is additive and degrades gracefully.** Most of the
backend surface it targets (Phase 2D resume pipeline, Phase 3A workflow engine, career
intelligence, soft-relevance fields) ships **dark** — disabled flags return empty/404. Every new UI
element must render a quiet "not enabled yet" state on a stock stack, exactly as the existing
`PlatformIntelligence` dashboard cards and the Applications lifecycle drawer already do.

## 1. Hard constraints (carried verbatim, honored)

No backend redesign, no DB/migrations/queues/Kafka/vector-DB, no new backend domains, no GraphQL,
no modification of Phase 2E / Phase 3A / recommendation-engine contracts. **Phase 4 touches
`frontend/` only.** The one permitted backend-adjacent change is *additive* Copilot quick-actions
(§Phase 4.10), which route through the already-extensible `CopilotSkillRouter` — and even that is
optional and deferred; the default plan adds no backend code.

## 2. Deliverable filename convention (deviation, documented)

The spec lists bare filenames (`ROLLBACK.md`, `UI_ARCHITECTURE.md`, …). `docs/ROLLBACK.md` **already
exists** (Phase 2E-era) and must not be clobbered — the same collision the Phase 3B.1 / 3B.1.1
deliverables hit and solved by prefixing. For consistency and safety, **all twelve Phase 4
deliverables are prefixed `PHASE_4_`**:

| Spec name | File written |
|---|---|
| PHASE_4_MASTER.md | `PHASE_4_MASTER.md` |
| UI_ARCHITECTURE.md | `PHASE_4_UI_ARCHITECTURE.md` |
| SCREEN_MAP.md | `PHASE_4_SCREEN_MAP.md` |
| COMPONENT_MAP.md | `PHASE_4_COMPONENT_MAP.md` |
| API_INTEGRATION_MAP.md | `PHASE_4_API_INTEGRATION_MAP.md` |
| STATE_MANAGEMENT.md | `PHASE_4_STATE_MANAGEMENT.md` |
| ROUTING.md | `PHASE_4_ROUTING.md` |
| WORKFLOW_UI.md | `PHASE_4_WORKFLOW_UI.md` |
| KANBAN_UI.md | `PHASE_4_KANBAN_UI.md` |
| COPILOT_UI.md | `PHASE_4_COPILOT_UI.md` |
| ADMIN_UI.md | `PHASE_4_ADMIN_UI.md` |
| ROLLBACK.md | `PHASE_4_ROLLBACK.md` |

## 3. Gap analysis — the core of this plan

Legend: **✅ built** · **🟡 partial** · **🔴 gap**. "Endpoint exists" means the backend route is
already live; the gap is purely UI consumption.

| Sub-phase | Backend surface (already live) | Today's UI | Verdict | Phase-4 work |
|---|---|---|---|---|
| **4.1 Dashboard** | `GET /api/dashboard`, `/api/recommendations`, `/api/workflow/career-intelligence`, `/api/diagnostics/observability` | Rich dashboard: KPI grid, score/pipeline charts, `PlatformIntelligence` cards | 🟡 | Add KPIs **Offer Probability, Profile Completeness, Market Match Score**; add widgets **Today's opportunities, Pending approvals, Upcoming interviews, Recent resume improvements, Career probability trend** |
| **4.2 Resume** | `/api/resumes*`, `/api/resume/tailor*`, `/api/resume/ats*`, `/api/diagnostics/gap-analysis` | Library + Optimization Center (uses **LangGraph** path) | 🟡 | Surface the **Phase 2D async pipeline** (Tailoring→ATS→Gap) as a visual pipeline; ATS score history; tailoring audit — all dark-tolerant |
| **4.3 Jobs** | `/api/jobs/recommended`, `/api/jobs/discovered`, `/api/jobs/pool`, `GET /api/jobs/{id}/relevance` | Tabs + cards + enrichment insights | 🟡 | Add **Career Match %, Match Strength (EXCELLENT/STRONG/GOOD/WEAK), Priority, Must-Apply** badges to cards |
| **4.4 Explainability** | `GET /api/jobs/{id}/relevance` (Phase 3B / 3B.1.1) | `ExplainDialog` uses a *different* endpoint (`POST /api/jobs/{id}/explain`) | 🔴 | New **"Why am I seeing this?"** relevance drawer consuming `{score, strength, visible, reasons}` |
| **4.5 Recommendations** | Full `/api/recommendations*` engine (approve/reject/save/archive, must-apply, human-review, priority, feedback, behavior-profile) | Only `/api/jobs/recommended` list surfaced | 🔴 | New **Recommendation Workspace**: priority tabs (Critical/High/Medium/Low/Archived), lifecycle actions, confidence + reason + behavior signal |
| **4.6 Workflow** | Phase 3A: `GET /api/workflow/correlation/{id}`, `/graph`, `/events`, `/api/diagnostics/workflow-dead-letter` | LangGraph run monitor + engine diagnostics | 🔴 | Add **Timeline / Graph / Raw-Event / Dead-Letter** views for the Phase 3A correlation engine (dark-tolerant) |
| **4.7 Applications** | `/api/applications`, Phase 3A lifecycle read-model | 5-column Kanban + lifecycle drawer | 🟡 | Extend to **8 columns** (add Viewed/Withdrawn/Archived), show Resume Version + Workflow Status + Career Probability on cards |
| **4.8 Career Intelligence** | `/api/workflow/career-intelligence`, `/api/workflow/analytics`, `/api/recommendations/behavior-profile` | One dashboard card only | 🔴 | New **Career Intelligence page** with probability/market/skill-gap/salary/trajectory charts |
| **4.9 Admin** | ~40 diagnostics endpoints across `/api/diagnostics/*`, `/api/admin/*`, `/api/execution/*` | Discovery/provider/salary/skills/dedup/observability/retention | 🟡 | Add **Queue / Execution / Cache / per-stage pipeline** health panels — completeness only |
| **4.10 Copilot** | SSE `/api/copilot/stream`, `CopilotSkillRouter` (10 skills) | Full streaming panel, 4 page contexts, quick actions | 🟡 | Add page contexts + quick actions (explain rejection, suggest skills/applications, market trends); optional new backend skills |

**Net: 4 genuine new screens/surfaces (🔴 4.4, 4.5, 4.6, 4.8) and 5 deepenings (🟡).** No screen is a
from-scratch build; every one extends existing patterns already proven in the app.

## 4. Implementation phases (recommended order)

Ordered by dependency and value-per-effort. Each is independently shippable and independently
revertible (see `PHASE_4_ROLLBACK.md`).

1. **4.3 Jobs card enrichment** (small, high-visibility; unblocks 4.4) — add relevance/strength/
   priority/must-apply fields to `types/workflow.ts` + `JobBadges`.
2. **4.4 Explainability drawer** (small; reuses Dialog) — new `RelevanceDrawer` on `GET /jobs/{id}/relevance`.
3. **4.1 Dashboard widgets** (medium; all endpoints live) — new KPIs + widget row.
4. **4.5 Recommendation Workspace** (large; new route `/recommendations`) — the biggest backend/UI gap.
5. **4.7 Applications 8-column + card metadata** (medium) — extend existing Kanban.
6. **4.6 Workflow correlation views** (large; new tabs on `/workflow`) — Timeline/Graph/Event/DLQ.
7. **4.8 Career Intelligence page** (medium; new route `/career`) — charts on existing endpoints.
8. **4.2 Resume async pipeline** (medium) — surface Phase 2D stages (mostly dark today).
9. **4.9 Admin completeness** (small) — extra diagnostics panels.
10. **4.10 Copilot deepening** (small) — new contexts/actions.

## 5. Architecture / regression / performance / rollout (summaries)

- **Architecture** — see `PHASE_4_UI_ARCHITECTURE.md`. No new libraries required; the existing
  stack (React 18, Vite, TanStack Query, Zustand, dnd-kit, recharts, framer-motion, axios) covers
  every Phase 4 need. Two new Zustand slices at most (§`PHASE_4_STATE_MANAGEMENT.md`); everything
  else is TanStack Query keys.
- **Regression** — Phase 4 is frontend-only and additive. No existing route, store, or component
  contract is removed. New routes are added under the existing `Private`/`AdminOnly` guards. Risk
  is contained to new files + additive fields on `types/workflow.ts`.
- **Performance** — all new data is fetched via TanStack Query with `retry:false` + `staleTime`
  tuning (the established pattern for dark endpoints), so a disabled backend surface costs exactly
  one 404 per mount, cached. No polling added beyond the existing `useWorkflowStatus`.
- **Rollout** — see `PHASE_4_ROLLBACK.md`. Each sub-phase is a separate PR behind either a route
  that simply isn't linked yet or a build-time `VITE_*` feature flag, so partial delivery never
  breaks the shipped app.

## 6. Explicitly NOT in Phase 4

No backend code (except the optional, deferred Copilot skills), no deploy, no docker rebuild, no
migration, no flag flips, no new npm dependency, no redesign of the three-column layout, no change
to auth/JWT, no SSE/WebSocket added beyond the existing Copilot stream (the Workflow/Kanban surfaces
stay refetch-on-action, consistent with today's app).
