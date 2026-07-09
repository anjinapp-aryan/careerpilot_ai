# Data Retention Policy

Additive, flag-gated purge of the append-only ledgers that otherwise grow unbounded. Introduced as
Phase 3B prep. **Disabled by default** — with stock config the daily scheduler fires but
`RetentionService` is a no-op and deletes nothing.

## What it purges

| Target table | Default window | Eligibility rule |
|---|---|---|
| `workflow_dead_letter` | 90 days | `created_at` older than cutoff |
| `workflow_correlation` | 180 days | `updated_at` older than cutoff **and** status is terminal (`COMPLETED` / `FAILED` / `DEAD_LETTERED`) — in-flight workflows are never reaped |
| `recommendation_audit` | 365 days | `created_at` older than cutoff |
| `execution_audit` (`application_execution_audit`) | 365 days | `created_at` older than cutoff |
| `resume_tailoring_audit` | 365 days | `created_at` older than cutoff |

No other table is touched. Nothing user-facing (applications, jobs, profiles, lifecycle) is ever
purged — only audit/dead-letter/correlation *ledgers*.

## Configuration

All keys default off / conservative. Set via env or `application.yml`.

| Env var | Default | Effect |
|---|---|---|
| `RETENTION_ENABLED` | `false` | Master switch. `false` ⇒ purge is a no-op. |
| `RETENTION_CRON` | `0 30 3 * * *` | Daily 03:30 schedule (off-peak). |
| `RETENTION_WORKFLOW_DEAD_LETTER_DAYS` | `90` | Dead-letter age window. |
| `RETENTION_WORKFLOW_CORRELATION_DAYS` | `180` | Terminal-correlation age window. |
| `RETENTION_RECOMMENDATION_AUDIT_DAYS` | `365` | Recommendation-audit age window. |
| `RETENTION_EXECUTION_AUDIT_DAYS` | `365` | Execution-audit age window. |
| `RETENTION_RESUME_TAILORING_AUDIT_DAYS` | `365` | Tailoring-audit age window. |

## Safety properties

- **Disabled by default**, feature-flagged, configurable — no schema change, no migration.
- **Per-target isolation:** each target purges in its own `REQUIRES_NEW` transaction with its own
  try/catch. One target failing (e.g. a lock timeout) is logged and returns `-1`; the others still
  run. No all-or-nothing giant transaction.
- **Cutoff is always in the past** (`now - days`); a mis-set window can only ever delete *older*
  rows, never future/recent ones. Correlations require terminal status on top of age.
- **Idempotent:** re-running deletes only what has newly aged past the window.

## Operating it

- **Scheduled:** enable `RETENTION_ENABLED=true`; the daily cron runs `purgeAll()`.
- **Manual (admin):** `POST /api/admin/retention/run` (OWNER/ADMIN role). Returns
  `{enabled, purged:{table→rowsDeleted}}`; when disabled, returns `enabled:false` and deletes
  nothing. `GET /api/admin/retention/status` reports the flag without running anything.
- **Rollback:** set `RETENTION_ENABLED=false` — instant and total. Already-deleted rows are gone
  (they are audit/dead-letter history, recoverable only from a DB backup), so **choose windows
  before enabling**. See [ROLLBACK.md](ROLLBACK.md).
