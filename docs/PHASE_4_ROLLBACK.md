# Phase 4 — Rollback & Rollout

## Why rollback is cheap here

Phase 4 is **frontend-only and additive**. It writes no migration, adds no table, flips no backend
flag, ships no backend code (except the optional deferred Copilot skills). There is **no persisted
state to unwind** — rollback is `git revert` of a frontend PR, rebuild the `dist/` bundle, redeploy
the static frontend. The backend is never touched, so no service restart or data reversal is needed.

## Migration review

**None.** No `V*.sql`, no schema change, no new column. Every Phase-4 datum is read from an
already-live endpoint. This section exists to state explicitly that nothing was added to reverse.

## Unit of rollback = one sub-phase PR

Each of the 10 sub-phases (4.1–4.10) is an independent, revertible PR:

| Sub-phase | Blast radius if reverted | Revert effect |
|---|---|---|
| 4.1 Dashboard widgets | `Dashboard.tsx` + new widget files | Dashboard returns to current KPIs/charts |
| 4.2 Resume pipeline | `ResumeOptimization.tsx` + `components/resume/*` | Optimization Center returns to LangGraph-only view |
| 4.3 Job card badges | `types/workflow.ts` (+ fields), `JobBadges.tsx` | Cards lose match-strength/priority badges; **additive type fields are inert if left** |
| 4.4 Relevance drawer | `components/relevance/*`, one trigger button | "Why am I seeing this?" button disappears |
| 4.5 Recommendations page | new route `/recommendations` + nav entry | Route 404→`/`; nav entry gone; Jobs Recommended tab unaffected |
| 4.6 Workflow correlation | `CorrelationExplorer` on `/workflow` | Panel gone; run monitor + diagnostics intact |
| 4.7 Kanban 8-col | `Applications.tsx` COLUMNS + card fields | Board returns to 5 writable columns |
| 4.8 Career Intelligence | new route `/career` + nav entry | Route gone; Dashboard career card unaffected |
| 4.9 Admin panels | `AdminDashboard.tsx` + shared health table | Extra health panels gone; existing admin intact |
| 4.10 Copilot actions | `copilotActions.ts` (+ optional backend skills) | New quick actions gone; router falls back to general assistant |

Because new routes are simply **not linked** until their nav entry lands, a half-finished sub-phase
can sit merged-but-dark behind an unlinked route without affecting the shipped app.

## Optional build-time gating

For extra safety during rollout, gate the two **new pages** behind a `VITE_` build flag (e.g.
`VITE_PHASE4_RECOMMENDATIONS`, `VITE_PHASE4_CAREER`). Default off in the bundle → route renders a
"coming soon" stub; flip on per-environment. This is optional — the "unlinked route" approach already
provides safe partial delivery without env plumbing.

## Recommended rollout order (value-per-risk)

1. **4.3 → 4.4** (small, high-visibility, unblock each other): job card badges + relevance drawer.
2. **4.1** dashboard widgets (all endpoints live; instant value).
3. **4.7** Kanban extension (contained; extends proven component).
4. **4.5** Recommendations workspace (biggest gap; new route, canary behind nav entry).
5. **4.6** Workflow correlation views (dark backend → validate empty states first).
6. **4.8** Career Intelligence page (dark; charts on live-but-empty data).
7. **4.2** Resume async pipeline (mostly dark; validate against a flag-on environment).
8. **4.9** Admin completeness.
9. **4.10** Copilot deepening (last; optional backend skills as a separate follow-up).

## Dark-backend canary discipline

Sub-phases 4.2, 4.5, 4.6, 4.8, and much of 4.9 target **dark** backend engines. Rollout sequence per
such surface:
1. Ship the UI dark-tolerant → confirm it renders the "not enabled yet" state cleanly on the stock
   stack (no red errors, no crashes).
2. In a non-prod environment, flip the corresponding backend flag (e.g. `WORKFLOW_TRACKING_ENABLED`,
   `RESUME_TAILORING_ENABLED`, `CAREER_INTELLIGENCE_ENABLED`) and verify the UI now populates.
3. Only then consider enabling the backend flag in prod. **Enabling backend flags is a separate,
   backend-owned decision — out of Phase 4 scope.**

## Verification checklist (build-time only — nothing deployed)

- [ ] `cd frontend && npm run build` (tsc + vite) passes with all new files.
- [ ] Every new query uses `retry:false` + dark-tolerant `try/catch → null` (grep the new hooks).
- [ ] No new npm dependency added (`git diff frontend/package.json` empty except none).
- [ ] Existing routes/pages render unchanged (no regression to Dashboard/Jobs/Applications/Workflow).
- [ ] Additive `types/workflow.ts` fields are all optional (`?`) so existing consumers still compile.
- [ ] The three-column `AppShell` layout is byte-unchanged.
- [ ] No backend file modified (except optional, separately-reviewed Copilot skill handlers).

## What stays untouched

Auth/JWT, the axios instance + interceptors, the three-column shell, the LangGraph workflow path, the
Kanban optimistic-move mechanics, the Copilot SSE transport, and **all backend code, migrations,
flags, and deployment**. Phase 4 is reversible to the current commit with a single frontend revert.
