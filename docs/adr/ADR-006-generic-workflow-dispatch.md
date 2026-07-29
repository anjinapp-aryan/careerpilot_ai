# ADR-006: Generic Workflow Dispatch

**Status**: Accepted
**Date**: 2026-07-29
**Phase**: 10A — AI Execution Platform Generalization

## Context

Phase 9 built `ai.careerpilot.runtime` (the "AI Execution Client") around a single Python endpoint,
`POST /runs` — the main 8-node career graph. Phase 10 (Skill Gap Intelligence) needed to invoke a
second, independent LangGraph graph and, in doing so, proved `WorkflowRuntime`/`AgentServiceClient`
could not reach it: `LangGraphWorkflowExecutor` was hardcoded to `AgentServiceClient.startRun`,
which only ever posts to `/runs`. Skill Gap shipped with its own dedicated endpoint
(`POST /skill-gap/runs`) and its own dedicated client (`SkillGapAgentServiceClient`) to avoid
either misusing `/runs` or modifying a live, shared, production entry point.

This is evidence, not speculation, that the one-endpoint assumption does not scale: N workflows
would mean N endpoints and N near-duplicate clients, each repeating the same request/response/
error-handling shape.

## Decision

Add a **second, generic dispatch endpoint** on the Python side — `POST /workflows/{workflowId}/runs`
— backed by an in-process `WorkflowDispatchRegistry` that maps a `workflow_id` string to a
`WorkflowRegistration` (graph factory + state mapper + output mapper). Any workflow that registers
itself becomes reachable through this one endpoint, with no new route.

On the Java side, add one new method to the existing `AgentServiceClient` —
`startWorkflowRun(String workflowId, Map<String, Object> payload)` — calling
`POST /workflows/{workflowId}/runs`. `LangGraphWorkflowExecutor` now calls this instead of
`startRun`, driven by `WorkflowExecutionContext.definition().workflowId()` (already resolved from
the Workflow Registry, Phase 4).

## What is explicitly preserved

- `POST /runs` and `POST /skill-gap/runs` are **untouched** — neither Python file's existing route
  handler changed behavior. Both remain independently callable exactly as before.
- `AgentServiceClient.startRun`/`resumeRun`/`getRun` and every existing caller (`WorkflowService`)
  are byte-for-byte unchanged — `startWorkflowRun` is a new, additive method.
- `SkillGapWorkflowService`/`SkillGapAgentServiceClient` are **not modified** — Skill Gap continues
  to run on its own dedicated path. The generic dispatcher additionally registers the same
  underlying skill-gap graph under `SKILL_GAP_INTELLIGENCE_V1`, proving the new path works using
  Skill Gap as a read-only reference — it does not migrate Skill Gap onto it.

## Consequences

- A future workflow needs one registration (Python) + reuse of `LangGraphWorkflowExecutor`/
  `AgentServiceClient.startWorkflowRun` (Java) — no new endpoint, no new client class.
- `WorkflowRuntime` is now genuinely swappable/generic, closing the gap ADR-006 exists to document.
- `/runs` remains the correct home for any future workflow that needs LangGraph's human-approval
  `NodeInterrupt`/checkpoint semantics; the generic dispatcher is intentionally stateless (no
  `PostgresSaver`) and is not a replacement for that pattern.

## Alternatives considered

- **Modify `/runs` to branch by `workflow_type`.** Rejected: touches a live, shared, already-in-
  production endpoint carrying the main career graph's human-approval semantics — higher regression
  risk than an additive endpoint, for no material benefit.
- **One endpoint per workflow (status quo).** Rejected: does not scale past two workflows without
  duplicating the same request/response/error boilerplate every time (see ADR-008).
