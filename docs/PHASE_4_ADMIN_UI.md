# Phase 4 — Admin UI (4.9)

## Today

[AdminDashboard.tsx](../frontend/src/pages/AdminDashboard.tsx) (route `/admin`, `AdminOnly`) already
renders: discovery/embedding/provider KPIs, discovery **provider health** table, pool-by-country +
skill-heatmap charts, **salary intelligence**, **platform observability** rollup (workflow/execution/
providers/cache/retention health + provider chain), **data retention** controls, and **duplicate
clusters** — plus admin actions (run discovery, backfill embeddings/enrichment/dedup, run retention).

So the spec's "Provider Health / Workflow Health / Retention Health" are **already present**. 4.9 is a
**completeness pass** adding the health dimensions not yet surfaced.

## Gap: surface the remaining diagnostics

The backend exposes ~40 diagnostics endpoints. Missing from the UI:

| Spec item | Add panel | Endpoint(s) |
|---|---|---|
| **Queue Health** | Per-stage executor queue depth + throughput | `/api/diagnostics/resume-tailoring/queue`, `/ats-optimization/queue`; workflow-engine stages via `/api/diagnostics/{application-tracking,application-timeline,email-intelligence,interview-tracking,application-analytics,career-intelligence}` |
| **Execution Health** | 2E execution engine status | `/api/execution/tracking`, `/api/diagnostics/application-execution`, `/browser`, `/ats`, `/analytics` |
| **Cache Health** | Match-cache stats | `/api/diagnostics/match-cache` |
| **Pipeline Stage Health** | 2D stages beyond tailoring/ATS | `/api/diagnostics/{gap-analysis,ats-explainability,cover-letter,application-package,auto-apply-package}` |
| Workflow Health (deepen) | correlation + dead-letter counts | `/api/diagnostics/workflow-correlation`, `/workflow-dead-letter` |

## Layout

Keep the existing top-to-bottom card flow. Add a new **"Engine health matrix"** section (after
Observability) — a compact table/grid, one row per engine stage, columns: `Stage · Flag(enabled/off)
· Health(NOT_CONFIGURED/UP/DEGRADED/DOWN) · Queue(size/cap) · Throughput/Errors`. This reuses the
exact `StageRows` pattern already built in [Workflow.tsx](../frontend/src/pages/Workflow.tsx)'s
`StageDiagnostics` (lift it into a shared `components/admin/EngineHealthTable.tsx`, or a shared
`components/diagnostics/StageHealthTable.tsx` reused by both pages).

## Health tone mapping (reuse existing)

The `HEALTH_TONE` map (`UP/HEALTHY→success, DEGRADED→warning, DOWN→danger, NOT_CONFIGURED/UNKNOWN→
neutral`) is already defined in both Dashboard and Workflow. Extract to `lib/` (e.g. `lib/health.ts`)
so all three consumers (Dashboard, Workflow, Admin) share one definition — a tidy dedup, not a
behavior change.

## Dark-tolerance

Most of these engines are dark by default → every panel shows `NOT_CONFIGURED` / "off". That is the
**expected healthy state** on a stock stack, not an error. Panels must read cleanly at stock defaults
(a grid of neutral "off" badges), matching how the existing observability rollup already renders.

## No-auth note

Most `/api/diagnostics/*` endpoints are intentionally **unauthenticated** (for uptime monitoring),
but the Admin page itself is `AdminOnly`. The `/api/admin/*` and `/api/execution/*` and retention
endpoints are role-gated and already handle 403 → "unavailable" gracefully (see the retention query's
`try/catch → null`). New admin-only queries follow that same pattern.

## Actions (unchanged + none added)

The existing admin mutations (run discovery, backfills, retention purge) are retained. 4.9 adds
**read-only** health panels only — no new destructive admin action.
