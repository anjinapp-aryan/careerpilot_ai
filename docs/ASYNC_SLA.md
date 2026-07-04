# Async Execution SLA

Scope: every async, event-driven stage in CareerPilot — the Phase 2D resume-tailoring pipeline,
the Phase 2E execution engine, and the Phase 3A workflow engine. All of them share one shape
(bounded per-stage executor + `@TransactionalEventListener(AFTER_COMMIT)` + never-throw worker),
so the operational contract below is uniform.

## Execution model

- **Trigger:** a worker listens for the *prior* stage's domain event and only runs after that
  stage's transaction has committed (`phase = AFTER_COMMIT`). No polling, no cron for the chain
  itself (retention is the one scheduled job — see [RETENTION.md](RETENTION.md)).
- **Isolation:** each stage owns a dedicated bounded `ThreadPoolTaskExecutor` (never shared), with
  `AbortPolicy`. One saturated stage cannot starve another. Sizing is per-stage configurable
  (`<stage>.executor.core-pool-size` / `max-pool-size` / `queue-capacity`).
- **Failure:** a worker never propagates. On error it records a dead-letter row (Phase 3A:
  `workflow_dead_letter`; 2D/2E: the stage's own job row flips to `FAILED`) and returns. The
  workflow is halted at that branch, not corrupted.

## Service-level objectives

These are **targets for a single stage under nominal load**, not hard guarantees (all AI-backed
stages depend on the shared provider rate limiter and external LLM latency).

| Class of stage | Target p95 latency | Notes |
|---|---|---|
| Deterministic, no-LLM (status detection, timeline, analytics, career-intelligence, email stub) | < 500 ms | Pure DB + in-memory compute. |
| LLM-backed (resume tailoring, ATS optimization, gap analysis, cover letter) | < 30 s | Bounded by provider latency + rate-limiter spacing; failover adds one provider round-trip. |
| Queue wait (any stage) | < queue-capacity × stage latency | If `executorQueueSize` approaches `executorQueueCapacity`, the diagnostics health flips to `DOWN` and new work is rejected (AbortPolicy) rather than unbounded-queued. |

## How to observe SLA

- **Per stage:** `GET /api/diagnostics/<stage>` (no auth) — returns `enabled`, counters, live
  `executorQueueSize` / `executorQueueCapacity`, and a `health` verdict
  (`NOT_CONFIGURED | UP | DEGRADED | DOWN`). `DEGRADED` = failure rate > 30%; `DOWN` = queue full.
- **Aggregate:** `GET /api/diagnostics/observability` — worst-of rollup across all workflow and
  execution stages plus provider/cache/retention health, with an `overall` verdict.
- **Per workflow instance:** `GET /api/workflow/correlation/{id}` (auth, tenant-scoped) — the
  reconstructed per-stage trace; `.../graph` and `.../events` for visualization/debugging.

## Breach response

1. `DEGRADED` on a stage → check that stage's dead-letter rows / job `FAILED` rows for the common
   exception; usually a provider outage (see provider health in `/observability`).
2. `DOWN` (queue full) → the upstream is producing faster than the stage drains. Raise that stage's
   `queue-capacity` / `max-pool-size` (env override, no code change) or disable the upstream
   trigger flag to shed load.
3. All stages of a phase `NOT_CONFIGURED` is **not** a breach — it is the intended dark default.
