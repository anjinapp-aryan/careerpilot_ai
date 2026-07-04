# Workflow Event Chain

The Phase 3A workflow engine is **event-driven**, not a linear orchestrator. Each stage is a worker
that listens for the prior stage's event, does its work, advances the shared correlation row, and
publishes the next event. There is no central saga/engine object — the chain *is* the event graph.

## The chain (Phase 3A)

```
ApplicationSubmittedEvent (2E, never emitted in stock build)
   │  [WorkflowEntryBridge — DARK gate: workflow.tracking.trigger.enabled]
   ▼  mints correlationId
ApplicationCreatedEvent
   → ApplicationTrackingWorker   → ApplicationTrackedEvent      (stage TRACKING)
   → StatusDetectionWorker       → StatusDetectedEvent          (stage STATUS_DETECTION)
   → TimelineWorker              → TimelineUpdatedEvent         (stage TIMELINE)
   → EmailIntelligenceWorker     → EmailProcessedEvent          (stage EMAIL_INTELLIGENCE)
   → InterviewDetectionWorker    → InterviewDetectedEvent       (stage INTERVIEW_DETECTION)
   → InterviewTrackingWorker     → InterviewTrackedEvent        (stage INTERVIEW_TRACKING)
   → OfferDetectionWorker        → OfferReceivedEvent | ApplicationRejectedEvent
   │                               | ApplicationAcceptedEvent   (stage OFFER_DETECTION, conditional)
   → AnalyticsWorker             → AnalyticsComputedEvent       (stage ANALYTICS)
   → CareerIntelligenceWorker    → (terminal — publishes nothing)  (stage CAREER_INTELLIGENCE)
```

Stage names in parentheses are the exact strings written to `workflow_correlation.workflow_stage`
by `WorkflowCorrelationService.advance(...)`. The ordered catalog mapping *stage ↔ correlation
string ↔ dead-letter workflow ↔ worker class* is the single source of truth in
`workflow/trace/WorkflowStage.java`.

## Correlation and dead-letter

- **`workflow_correlation`** — one row per workflow instance, keyed by `correlationId`, upserted on
  every stage transition. `workflow_stage` is the *frontier* (how far the engine advanced);
  `status` ∈ `STARTED | IN_PROGRESS | COMPLETED | FAILED | DEAD_LETTERED`.
- **`workflow_dead_letter`** — one row per captured failure (correlationId, workflow, stage,
  payload, exception). Every worker's catch block writes here instead of throwing.
- Only these two tables carry `correlationId`. Domain artifact tables
  (`application_lifecycle`, `application_timeline`, `interview`, `application_analytics`,
  `career_intelligence`) are keyed by `userId`+`jobId` and joined via the correlation row.

## Reconstructing a run (read-model)

`WorkflowTraceService` projects the chain read-only from those tables — it never re-runs anything:

- `GET /api/workflow/correlation/{id}` — full trace: per-step status
  (`SUCCESS | FAILED | RUNNING | PENDING | NOT_STARTED | SKIPPED`) + dead-letters. Workflow status
  `RUNNING | COMPLETED | FAILED | PARTIAL`. Missing stages are **never inferred** (absence ⇒
  `NOT_STARTED`; dead-letter ⇒ `FAILED`). Offer detection ⇒ `SKIPPED` when the engine advanced past
  it without an offer/accept/reject outcome (the worker legitimately emits nothing).
- `GET /api/workflow/correlation/{id}/graph` — `{nodes[], edges[]}` for visualization (one node per
  stage carrying its status, sequential edges).
- `GET /api/workflow/correlation/{id}/events` — ordered raw-event projection (one event per stage
  that actually occurred; deterministic ids) for support/debugging.
- `GET /api/workflow/correlation/{id}/summary` — counts roll-up.

## Adding a stage

1. Add the event record implementing `BaseWorkflowEvent` (carries `correlationId` forward).
2. Add the worker (same shape: gated, dedicated executor, advance correlation, dead-letter on fail).
3. Add one `WorkflowStage` enum value (keeps the trace/graph/events projections correct with no
   further change).
