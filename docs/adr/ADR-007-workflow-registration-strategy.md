# ADR-007: Workflow Registration Strategy

**Status**: Accepted
**Date**: 2026-07-29
**Phase**: 10A — AI Execution Platform Generalization

## Context

Before Phase 10A, resolving which workflow to run in `ai.careerpilot.runtime` required an
`ai.careerpilot.missionexecution.ExecutionDecision`, which in turn required a
`ai.careerpilot.workflowplanner.WorkflowType` enum value. Adding any new workflow to the platform
would have meant modifying that enum — a change to a package (`workflowplanner`) explicitly listed
as architecturally frozen. Skill Gap Intelligence (Phase 10) avoided this entirely by bypassing
`ai.careerpilot.runtime` and calling `WorkflowRegistryService.latestForType(String)` directly — a
plain string-keyed lookup with no enum dependency, which already worked without any change.

This proved the enum coupling was never load-bearing for registry resolution — it was only ever
a convenience for the one caller shape (`ExecutionDecision`-driven) that existed in Phase 9.

## Decision

**Registration and resolution on both sides now key on a plain string, not an enum.**

- **Java**: `WorkflowRegistryAdapter.resolve(String workflowType)` is the primary method
  (`DefaultWorkflowRegistryAdapter` delegates straight to the pre-existing, unmodified
  `WorkflowRegistryService.latestForType(String)`). `resolve(WorkflowType)` remains as a
  convenience default method for callers that already have a `WorkflowType` — it derives the
  string and delegates — but it is no longer the only entry point.
- **`WorkflowExecutionRequest.workflowId()`** (a plain string) is now the canonical identity field.
  `executionDecision()` is optional (nullable): present when a Mission Execution Engine caller
  wants its policy/priority context carried through, absent for an ad-hoc or generically-dispatched
  request. `WorkflowExecutionRequest.forDecision(...)` preserves the ergonomic, enum-driven call
  shape for the Mission Execution Engine path without making it mandatory.
- **Python**: `WorkflowDispatchRegistry.register(WorkflowRegistration)` keys on the same string
  (`workflow_id`) that the Java-side Workflow Registry (`workflow_definition.workflow_id`) already
  uses as its business key — one shared identifier space, no translation table.

## What is explicitly preserved

- `ai.careerpilot.workflowplanner.WorkflowType` is **not modified** — still frozen, still the
  richer 15-value taxonomy the Workflow Planner uses for its own planning concerns. It is simply no
  longer a hard prerequisite for `ai.careerpilot.runtime` resolution.
- `ai.careerpilot.missionexecution.ExecutionDecision` is **not modified**. `WorkflowExecutionContext`
  still carries it (nullable) for policy/priority propagation into metrics/logging.
- Skill Gap's own registration (`workflow_definition` row `SKILL_GAP_INTELLIGENCE_V1`, seeded by
  `V79__skill_gap_analysis.sql`) required **zero change** — it already used the string-keyed path
  this ADR generalizes.

## Consequences

- A future workflow (Resume, ATS, Interview, Job Discovery, Career Strategy, …) registers with one
  `workflow_definition` row (Java) and one `WorkflowRegistration` (Python) — no enum change on
  either side, ever.
- Two identifier concepts remain intentionally distinct and must not be conflated: the Workflow
  Registry's `workflow_type` (the resolution key, e.g. `"SKILL_GAP_INTELLIGENCE"`) and its
  `workflow_id` (the versioned business key, e.g. `"SKILL_GAP_INTELLIGENCE_V1"`) — both existed
  before Phase 10A and are unchanged; this ADR does not merge them.

## Alternatives considered

- **Add new `WorkflowType` enum values per workflow.** Rejected: reintroduces the exact coupling
  this ADR removes, and requires touching a frozen package for every future workflow.
- **A separate string-to-enum translation table.** Rejected: unnecessary indirection — the string
  key already exists (`workflow_definition.workflow_type`) and is already the source of truth.
