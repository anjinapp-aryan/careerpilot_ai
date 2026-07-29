# ADR-008: Workflow Execution Contract

**Status**: Accepted
**Date**: 2026-07-29
**Phase**: 10A — AI Execution Platform Generalization

## Context

Skill Gap Intelligence (Phase 10) shipped its own bespoke response shape
(`SkillGapAgentResponse`/`SkillGapAnalysisResponse`, hardcoding `readinessScore`,
`criticalSkillGaps`, etc.) rather than reusing the generic envelope `ai.careerpilot.runtime`
already modeled (`WorkflowExecutionResult`/`WorkflowExecutorOutcome`) — because nothing wired
`WorkflowRuntime` up yet, that generic envelope went unused. Each of the 3+ structured-AI-calling
Python agents also repeated the same try/except/log/fallback boilerplate around
`gateway.generate_structured_response(...)`.

## Decision

**Standardize on one generic execution envelope, reused by every future dispatched workflow, on
both languages.**

- **Python → Java wire contract** (`agent-service/app/dispatcher/router.py`'s
  `WorkflowRunResponse`, and its Java binding `AgentServiceDtos.WorkflowDispatchResponse`):
  `workflowId`, `executionId`, `correlationId`, `status`, `durationMs`, an **opaque**
  `output: Map<String, Object>` (workflow-specific, uninterpreted by the transport layer), and
  `errors: List<String>`.
- **Java-side result** (`ai.careerpilot.runtime.WorkflowExecutionResult`, unchanged in shape,
  Phase 9): generic execution metadata (`workflowId`, `executionId`, `executionStatus`,
  `startTime`/`endTime`/`duration`, `metrics`) plus the same opaque `outputPayload`. This record
  already had the right shape before Phase 10A — it was simply unused; this ADR is what makes it
  the pattern going forward, not a redesign of it.
- **Shared AI invocation helper** (`agent-service/app/agent_support.py`'s
  `call_structured_agent(...)`): extracts the repeated try/except/log/fallback shape into one
  function. It never decides what an agent asks for or how to interpret a success result — each
  agent still owns its `SYSTEM`/`SCHEMA`/prompt and its `on_success` field mapping. Applied to
  three of Skill Gap's agents (`resume_intelligence`, `market_intelligence`, `learning_roadmap`,
  `mission_readiness`) with byte-for-byte preserved log wording, error-message prefixes, and
  fallback shapes — proven by the pre-existing Skill Gap test suite passing unchanged after the
  extraction.

## What is explicitly preserved

- `SkillGapAgentResponse`/`SkillGapDtos.SkillGapAnalysisResponse` and the dedicated
  `POST /skill-gap/runs` contract are **not modified** — Skill Gap's REST API shape is exactly as
  Phase 10 shipped it. This ADR governs the contract *future* workflows should use via the generic
  dispatcher, not a retrofit of Skill Gap's already-shipped one.
- Every agent's `SYSTEM` prompt, JSON `SCHEMA`, and prompt-building logic is untouched — only the
  surrounding try/except/log/fallback infrastructure moved into the shared helper.

## Consequences

- A future workflow's Java integration needs no new DTO — `WorkflowDispatchResponse`/
  `WorkflowExecutionResult` already fit any workflow whose output is a reasonable
  `Map<String, Object>`.
- A future workflow's Python agents get retry/error-handling consistency for free by calling
  `call_structured_agent(...)`, without the helper ever needing to know what any given agent's
  prompt or schema looks like.
- `WorkflowMetrics`/`InMemoryWorkflowMetrics` (Phase 9, already implemented) now receives
  `missionId` in addition to `workflowVersion`/`executionPolicy` (when present)/`correlationId`/
  `retryCount` — extending, not restructuring, the existing metrics map shape.

## Alternatives considered

- **A typed response class per workflow (status quo, per Skill Gap).** Rejected as the *default*
  going forward: works for one workflow, but means N bespoke DTOs for N workflows — the opaque
  envelope avoids that without losing type safety where it matters (the Java Control Plane never
  needed typed knowledge of workflow-specific business fields in the first place).
- **A code-generation step per workflow output schema.** Rejected as premature — no evidence yet
  that manual `Map<String, Object>` access is a real pain point at N=2 workflows; revisit if/when
  a third or fourth workflow's Java caller needs strongly-typed field access.
