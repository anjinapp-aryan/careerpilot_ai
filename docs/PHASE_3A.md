# Phase 3A — Enterprise Application Tracking Workflow Engine

> **Status: implemented, ships DARK, NOT deployed.** Migrations V36–V43 are written but **NOT
> applied** to Neon. Every feature flag defaults `false`. No docker rebuild, no flag flipped, no
> push. With stock flags, creating an application produces **zero** Phase 3A rows. Enabling is a
> separate, later, human-gated decision (see §11 Production Rollout).

Phase 3A turns CareerPilot from a job-recommendation platform into a **career operating system**: it
tracks every application *after* it exists — Applied → Viewed → Assessment → Interview → Offer →
Accepted/Rejected — as an **event-driven workflow**, plus **Phase 3A.0**, a global correlation +
dead-letter framework every future workflow rides on.

It is isolated in a new `ai.careerpilot.workflow` bounded context with its own `workflow.*` flag
namespace so **Phase 2E stays byte-for-byte untouched** (zero regression). Where the 3A spec's names
collided with 2E, they were renamed — see §13 Collision Map.

---

## 1. Workflow Architecture

A **linear event chain of nine independent workers**, not a pipeline object. Each worker is a Spring
`@TransactionalEventListener(AFTER_COMMIT)` that consumes one event, does its stage's work on a
**dedicated bounded executor**, and publishes the next event. There is no orchestrator class; the
graph *is* the set of listeners, exactly like Phase 2D/2E.

```
WorkflowEntryBridge  (DARK gate — mints correlationId, publishes ApplicationCreatedEvent)
  → ApplicationTrackingWorker   →  ApplicationTrackedEvent
  → StatusDetectionWorker       →  StatusDetectedEvent
  → TimelineWorker              →  TimelineUpdatedEvent
  → EmailIntelligenceWorker     →  EmailProcessedEvent
  → InterviewDetectionWorker    →  InterviewDetectedEvent
  → InterviewTrackingWorker     →  InterviewTrackedEvent
  → OfferDetectionWorker        →  OfferReceivedEvent | ApplicationAcceptedEvent | ApplicationRejectedEvent
  → AnalyticsWorker             →  AnalyticsComputedEvent
  → CareerIntelligenceWorker    (TERMINAL — publishes nothing, marks correlation COMPLETED)
```

**Why event-driven:** each stage fails, scales, and is canaried independently. A saturated or failing
stage cannot stall or corrupt another — its queue is its own, its failure is a dead-letter row.

**Design invariant — no fabricated outcomes.** `OfferDetectionWorker` emits a terminal event *only*
when the lifecycle has genuinely reached OFFER_RECEIVED / NEGOTIATION / ACCEPTED / REJECTED. If the
application is still in progress, the workflow simply completes at that stage without inventing an
offer or rejection.

## 2. State Machine (3A.1)

`ApplicationStatusMachine` is a pure, dependency-free transition table over 16 states:

```
DRAFT → SUBMITTED → VIEWED → UNDER_REVIEW → ASSESSMENT → TECHNICAL_INTERVIEW
      → {MANAGER_INTERVIEW, SYSTEM_DESIGN, HR_INTERVIEW} → FINAL_ROUND
      → OFFER_RECEIVED → NEGOTIATION → ACCEPTED
Terminal: ACCEPTED, REJECTED, WITHDRAWN, EXPIRED
```

- **Forward edges** are an explicit map (no skipping, no going backwards).
- **REJECTED / WITHDRAWN / EXPIRED** are reachable from *any* active (non-terminal) state.
- **Terminal states have no outgoing edges** — approving an already-rejected application is refused.
- `canTransition(from,to)` refuses self, null, and unknown statuses.

`ApplicationLifecycleService` validates every change through this machine, refuses illegal ones
(logged + audited, never thrown), and records each accepted change append-only in
`application_status_history` + `application_lifecycle_audit`. It is exhaustively unit-tested
(`ApplicationStatusMachineTest`, 37 cases).

## 3. Event Model

Every 3A event is a Java `record` implementing **`BaseWorkflowEvent`**:

```java
UUID eventId();  UUID correlationId();  UUID userId();
UUID jobId();    UUID applicationId();  Instant timestamp();
```

Only **new 3A events** implement the interface — existing 2A–2E event records are untouched (records
cannot extend a class, and the interface is additive). Each stage's static `from(prev, …)` factory
mints a fresh `eventId` and **carries the `correlationId` (and user/job/application identity)
forward** — this is what stitches nine independently-published events into one traceable instance
(verified by `BaseWorkflowEventTest`).

The 11 events: `ApplicationCreatedEvent` (entry), `ApplicationTrackedEvent`, `StatusDetectedEvent`,
`TimelineUpdatedEvent`, `EmailProcessedEvent`, `InterviewDetectedEvent`, `InterviewTrackedEvent`,
`OfferReceivedEvent`, `ApplicationAcceptedEvent`, `ApplicationRejectedEvent`, `AnalyticsComputedEvent`.

## 4. Correlation Framework (3A.0)

`WorkflowCorrelationService` mints a `workflow_correlation` row at workflow entry
(`start(...) → correlationId`) and upserts it on every stage transition (`advance(correlationId,
stage, status)`). It is **pure bookkeeping and never throws** — a tracking failure must never break
the workflow it observes; `start()` returns a usable id even if the insert fails, so the chain can
proceed with correlation best-effort. Statuses: STARTED → IN_PROGRESS → COMPLETED / FAILED /
DEAD_LETTERED.

## 5. Dead-Letter Strategy (3A.0)

`AbstractWorkflowWorker.dispatch(event, stage, body)` centralises **two** failure-isolation
boundaries every worker shares:

1. the `executor.execute(...)` submit itself (a rejected task from a full bounded queue → `AbortPolicy`), and
2. the stage body running on the executor thread.

Both are wrapped: any `Throwable` becomes a `workflow_dead_letter` row via
`WorkflowDeadLetterService` and **never propagates**. The dead-letter service is the last line of
defence — it swallows its *own* persistence failure (there is nowhere safe left to escalate) and
truncates oversized payload/exception to 8000 chars. A non-zero dead-letter count is the system
working as designed (a failed stage captured, not lost), not an unhealthy signal.

## 6. Database Model (additive, idempotent, NOT applied)

All DDL is `CREATE TABLE IF NOT EXISTS` — additive only, safe to run against an existing schema.

| Migration | Table(s) | Sub-phase |
|---|---|---|
| V36 | `workflow_correlation` | 3A.0 |
| V37 | `workflow_dead_letter` | 3A.0 |
| V38 | `application_lifecycle`, `application_status_history`, `application_lifecycle_event`, `application_lifecycle_audit` | 3A.1 |
| V39 | `application_timeline` | 3A.2 |
| V40 | `application_email`, `email_extraction`, `email_audit` | 3A.3 |
| V41 | `interview`, `interview_feedback`, `interview_timeline` | 3A.4 |
| V42 | `application_analytics` | 3A.5 |
| V43 | `career_intelligence` | 3A.6 |

Consistent with the existing convention (V4, V9, V16, V24–V35), these are **hand-applied against
Neon by a human**, not by this work. `flyway.baseline-on-migrate: true` handles the existing schema.

## 7. Service Model

Every engine service follows the proven 2D/2E shape: **flag-gated dark** (`isEnabled()` → disabled is
a pure no-op returning empty/zero), **append-only**, **never throws** (repo failure is caught, counted
in metrics, logged, and swallowed so an async worker can call it safely).

| Service | Flag | Behaviour |
|---|---|---|
| `ApplicationLifecycleService` | `workflow.tracking.enabled` | state machine, history, audit |
| `TimelineService` | `workflow.timeline.enabled` | append-only timeline |
| `EmailIntelligenceService` | `email.intelligence.enabled` | **INERT** — deterministic keyword classifier, no mailbox, no LLM |
| `InterviewService` | `interview.tracking.enabled` | interview rounds + feedback |
| `ApplicationAnalyticsService` | `workflow.analytics.enabled` | outcome rates from lifecycle rows |
| `CareerIntelligenceService` | `career.intelligence.enabled` | probabilities from analytics (no LLM) |

Pure logic is extracted for direct unit testing: `ApplicationStatusMachine`, `EmailClassifier`,
`CareerProbability`, `ApplicationAnalyticsService.rate(...)`.

## 8. Executor Model

`WorkflowExecutorsConfig` defines **seven dedicated, bounded** `ThreadPoolTaskExecutor` beans, one per
stage — **no shared pools**, `AbortPolicy`, all sizes `${…}`-overridable:
`applicationTrackingExecutor`, `statusDetectionExecutor`, `timelineExecutor`,
`emailIntelligenceExecutor`, `interviewTrackingExecutor`, **`careerAnalyticsExecutor`** (renamed to
dodge 2E's `analyticsExecutor` bean), `careerIntelligenceExecutor`. One stage's saturated queue can
never starve another's.

## 9. Diagnostics

`WorkflowDiagnosticsController` — **8 no-auth, counts-only** endpoints (no application content, no
PII), same `stage(...)` health verdict (NOT_CONFIGURED | UP | DEGRADED | DOWN) as 2E:

```
/api/diagnostics/application-tracking    /api/diagnostics/application-timeline
/api/diagnostics/email-intelligence      /api/diagnostics/interview-tracking
/api/diagnostics/application-analytics   /api/diagnostics/career-intelligence
/api/diagnostics/workflow-correlation    /api/diagnostics/workflow-dead-letter
```

At stock defaults every engine stage reads `enabled:false` / `health:"NOT_CONFIGURED"` — proving the
whole workflow registers without activating (asserted by `WorkflowDiagnosticsControllerTest`). Human
read/seed surface: `WorkflowController` (`/api/workflow/*`, JWT-authenticated, manual
`userId.equals(...)` multi-tenant checks).

## 10. Rollback Strategy

**Flag-off is instant and total.** Every stage is gated by two flags (`<stage>.enabled` engine +
`<stage>.trigger.enabled` chain); setting any to `false` returns that stage to a pure no-op with no
code change. Because migrations are additive and idempotent, and nothing reads a 3A table unless its
flag is on, disabling leaves existing rows inert. There is no data migration to reverse. Worst case
(disable everything) returns the system to exactly the pre-3A behaviour.

## 11. Production Rollout Plan

Dark by default → canary **one stage at a time**, engine-first:

1. **Apply migrations V36–V43** to Neon (additive; verify tables exist).
2. `WORKFLOW_TRACKING_ENABLED=true` (engine only) → verify `/api/diagnostics/application-tracking`
   reads `UP`. No workflow fires yet (trigger still off).
3. `WORKFLOW_TRACKING_TRIGGER_ENABLED=true` → the entry bridge + tracking + status-detection begin
   firing off `ApplicationSubmittedEvent`/manual seed. Watch dead-letter + correlation diagnostics.
4. Repeat engine-then-trigger for timeline → email → interview → analytics → career, verifying each
   stage's diagnostics before advancing.
5. Any regression: flip that stage's flag(s) `false` (instant rollback).

Never flip a `*_TRIGGER_ENABLED` before its `*_ENABLED` is verified UP.

## 12. Risk Assessment

| Risk | Mitigation |
|---|---|
| Naming collision with 2E breaks the build | New `workflow.*` namespace + renamed table/bean/env (§13); 2E untouched; full suite green (525) |
| A stage failure stalls the workflow | Bounded executors + `AbortPolicy` + dead-letter isolation on both hops; never propagates |
| Correlation/audit failure breaks the workflow | Correlation + dead-letter services never throw; best-effort |
| Accidental activation | All flags default `false`; migrations not applied; diagnostics prove NOT_CONFIGURED |
| Fabricated offer/rejection | OfferDetectionWorker emits terminal events only from a genuinely-terminal lifecycle |
| Email/interview "AI" over-promises | Email classifier is a deterministic keyword stub (no mailbox, no LLM) — clearly documented INERT |
| PII leak via diagnostics | Endpoints are counts-only, no application content |

## 13. Sign-off Checklist & Collision Map

**Collision map (3A spec name → name used):**

| Spec | Collides with 2E | Used |
|---|---|---|
| table `application_tracking` | yes (V34) | `application_lifecycle` (+ status_history / lifecycle_event / lifecycle_audit) |
| bean `analyticsExecutor` | yes | `careerAnalyticsExecutor` |
| flags `application.tracking.*` / `application.analytics.*` | yes | `workflow.tracking.*` / `workflow.analytics.*` |
| env `APPLICATION_TRACKING_ENABLED` / `APPLICATION_ANALYTICS_ENABLED` | yes | `WORKFLOW_TRACKING_ENABLED` / `WORKFLOW_ANALYTICS_ENABLED` |
| `workflow_correlation`, `workflow_dead_letter`, `application_timeline`, `application_email`, `interview`, `application_analytics`, `career_intelligence` | no | kept as-is |

**Sign-off:**

- [x] Workflow architecture (event-driven, 9 workers, no orchestrator)
- [x] 16-state machine, exhaustively tested, terminal states sealed
- [x] Event model on `BaseWorkflowEvent`, correlation propagated across the chain
- [x] Correlation framework (V36) + dead-letter framework (V37), both never-throw
- [x] Additive idempotent migrations V36–V43 — **written, NOT applied**
- [x] Flag-gated dark services, never-throw, append-only
- [x] 7 dedicated bounded executors, no shared pools, `AbortPolicy`
- [x] 8 no-auth counts-only diagnostics; human `/api/workflow/*` surface with tenant checks
- [x] Flag-off rollback (instant + total); documented one-stage-at-a-time rollout
- [x] Phase 2E byte-for-byte untouched
- [x] **525 tests green (397 baseline + 128 new), zero failures, zero regression**
- [ ] Migrations applied to Neon — *deferred, human decision*
- [ ] Any flag flipped — *deferred, human decision*
- [ ] Deployed / docker rebuilt — *deferred, human decision*
