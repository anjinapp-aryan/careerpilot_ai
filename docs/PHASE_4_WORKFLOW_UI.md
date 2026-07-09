# Phase 4 — Workflow UI (4.6)

## Two distinct "workflows" — keep them separate

The product has **two** engines the UI must not conflate:

1. **LangGraph agent pipeline** (existing, surfaced on `/workflow`) — `resume_intelligence →
   job_discovery → ats_optimization → interview_prep → career_strategy → salary_intelligence →
   human_approval → application_tracking`. Driven by `POST /api/workflows/run`, monitored by
   `RunCard` + `WorkflowStatusStepper` + `useWorkflowStatus`. **Unchanged in Phase 4.**

2. **Phase 3A correlation/event workflow engine** (dark; **not yet surfaced**) — the
   `ApplicationCreated → Tracking → StatusDetection → Timeline → EmailIntelligence → Interview →
   Offer → Analytics → CareerIntelligence` event chain, with a correlation id, an event log, and a
   dead-letter queue. **This is the Phase 4.6 gap.**

The spec's diagram (Approve → Resume Tailoring → ATS → Gap → Safety → Approval Queue → Execution →
Tracking → Interview → Career Intelligence) is the **conceptual end-to-end** spanning Phase 2D
(tailoring/ATS/gap), 2E (safety/execution), and 3A (tracking/interview/career-intel). Phase 4.6
visualizes it through the **Phase 3A correlation trace**, which is the one place all these stages
report a unified `correlationId → stage → status` timeline.

## Placement

Add a **`CorrelationExplorer`** section to the existing `/workflow` page, below the current run
list and `StageDiagnostics`. It is a self-contained, dark-tolerant panel: on a stock stack every
correlation endpoint returns empty and the panel shows "Workflow correlation engine is not enabled
yet" — exactly like the Applications lifecycle drawer does today.

## Correlation selection

The panel needs a `correlationId`. Sources, in priority order:
1. A correlation id surfaced on an application's lifecycle row (`GET /api/workflow/applications/
   {jobId}/lifecycle` already returns lifecycle metadata; if it carries `correlationId`, deep-link
   from the Kanban drawer's "view workflow trace" action).
2. A picker fed by `GET /api/diagnostics/workflow-correlation` (engine health also lists recent
   correlations if exposed) — otherwise a manual id input for ops/debug.

Store the selected id in `useWorkflowExplorerUi` (Zustand) so it survives tab switches.

## Four views (Tabs)

| Tab | Endpoint | Rendering |
|---|---|---|
| **Timeline** | `GET /api/workflow/correlation/{id}` (+ `/summary` for header) | Vertical stage timeline: each stage row = name · status badge (RUNNING/COMPLETED/FAILED) · timestamp · duration. Reuse the visual language of `WorkflowStatusStepper` (vertical variant) and the Applications event timeline. |
| **Graph** | `GET /api/workflow/correlation/{id}/graph` | Node-edge stage graph. **No new graph library** — render as a horizontal/vertical flow of `Card`-like nodes connected by CSS/SVG edges (the existing `PipelineOverview` stepper already draws connected chips; extend that). Color nodes by status. |
| **Raw Events** | `GET /api/workflow/correlation/{id}/events` | Chronological event table: `eventType · stage · payload (collapsible <pre>) · timestamp`. Read-only. |
| **Dead Letter** | `GET /api/diagnostics/workflow-dead-letter` | Table of failed events: `workflow · stage · exception · retryCount · createdAt`. This is engine-wide (not per-correlation); show a badge count and a "0 dead-lettered — healthy" empty state. |

## Graph rendering decision

The hard constraints forbid new heavy deps and the existing app has no graph library. **Render the
graph with the primitives already in use** (flex/grid + framer-motion + SVG connectors), matching
`PipelineOverview`'s connected-chip pattern. A linear/branching stage flow does not need d3/reactflow;
if a future need for free-form graphs arises it is a separate, isolated decision. This keeps Phase 4
dependency-free and on-brand.

## States

- **Loading** → skeletons (existing `Skeleton`).
- **Dark / no data** → quiet "not enabled yet" card (never an error).
- **Failed correlation** → the Timeline surfaces the failed stage in danger tone; the Dead-Letter tab
  carries the exception detail. This mirrors how `RunCard` surfaces a FAILED LangGraph stage.

## Health integration

The existing `StageDiagnostics` (reads `/api/diagnostics/observability`) stays as the at-a-glance
engine health. `CorrelationExplorer` is the drill-down. Cross-link: a DEGRADED/DOWN workflow health
badge links to the Dead-Letter tab.

## Explicitly unchanged

`POST /api/workflows/run`, `/resume`, the LangGraph stepper, the run cards, the approval gate, and
`useWorkflowStatus` polling are all untouched. Phase 4.6 is purely additive read-only visualization
of the Phase 3A trace.
