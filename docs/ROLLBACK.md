# Rollback Playbook

Every capability added in Phases 2D → 3B ships **dark** behind feature flags and is reversible by
flipping a flag — no redeploy of prior code, no data migration to undo. This is the canonical list
of "how to turn it off."

## Principle

- **Flags default `false`.** Enabling is a deliberate, staged human decision (canary one stage,
  verify its diagnostics, then chain the next).
- **Rollback = flag off.** Instant and total: event listeners no-op, engines return early,
  read-only endpoints 404 on absent data. No code path changes.
- **Migrations are additive and idempotent.** They only ever *add* tables/columns and are safe to
  leave applied after a rollback — an unused table is inert. Never modify an applied migration.

## Per-surface rollback

| Surface | Disable | Effect when off |
|---|---|---|
| Phase 3A workflow engine (per stage) | `WORKFLOW_TRACKING_ENABLED`, `WORKFLOW_TIMELINE_ENABLED`, `EMAIL_INTELLIGENCE_ENABLED`, `INTERVIEW_TRACKING_ENABLED`, `WORKFLOW_ANALYTICS_ENABLED`, `CAREER_INTELLIGENCE_ENABLED` (+ each `*_TRIGGER_ENABLED`) → `false` | Worker no-ops; no `workflow_*` rows written. Trace API 404s (no correlation). |
| Phase 3A entry bridge | `WORKFLOW_TRACKING_TRIGGER_ENABLED=false` | No `ApplicationCreatedEvent` minted; whole chain inert. |
| Phase 2D pipeline (per stage) | `RESUME_TAILORING_ENABLED`, `ATS_OPTIMIZATION_ENABLED`, `GAP_ANALYSIS_ENABLED`, `ATS_EXPLAINABILITY_ENABLED`, `COVER_LETTER_ENABLED`, `APPLICATION_PACKAGE_ENABLED`, `AUTO_APPLY_PACKAGE_ENABLED` (+ each `*_TRIGGER...ENABLED`) → `false` | Stage engine disabled + won't auto-fire off prior event. |
| Phase 2E execution engine | `APPLICATION_EXECUTION_ENABLED`, `BROWSER_AUTOMATION_ENABLED`, `ATS_CONNECTOR_ENABLED`, `APPLICATION_TRACKING_ENABLED`, `APPLICATION_ANALYTICS_ENABLED` → `false` | Engine inert; no execution rows. |
| Data retention (Phase 3B prep) | `RETENTION_ENABLED=false` | Scheduler fires but purges nothing. **Note:** already-deleted rows are not recoverable except from a DB backup — see [RETENTION.md](RETENTION.md). |
| Candidate profile source-of-truth | `JOBS_MATCHING_PROFILE_SOURCE_ENABLED=false`, `CANDIDATE_PROFILE_ENABLED=false` | Matching falls back to legacy `WorkflowRun` + `candidate_preferences` path. |

## Read-only surfaces need no rollback

The trace/graph/events/summary/diagnostics endpoints and the aggregate `/observability` endpoint are
pure read-models with no side effects. With dark flags there is simply no data, so they 404 (per
correlation) or report `NOT_CONFIGURED` (aggregate). Nothing to disable.

## Verifying a rollback

1. `GET /api/diagnostics/observability` → the rolled-back phase's stages read `NOT_CONFIGURED`.
2. Create the triggering action (e.g. submit an application) → confirm **no** new rows in the
   phase's tables (dark = zero rows).
3. For retention: `GET /api/admin/retention/status` → `enabled:false`.

## What rollback does *not* do

- It does not drop tables or delete rows written while a flag was on. Turn the flag off to stop
  *new* writes; clean up existing rows via retention (if you choose to enable it) or manually.
- It does not touch Phase 2E contracts or Phase 3A event/table contracts — those are frozen; new
  work is always additive alongside them.
