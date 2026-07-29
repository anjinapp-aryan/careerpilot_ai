# CareerPilot AI — Workflow Development Guide

**This is the entry point.** Start here, then follow the links.

## What you're building

A new AI business workflow (Resume Intelligence, Job Discovery Intelligence, ATS Optimisation,
Interview Intelligence, Career Strategy, Application Intelligence, Daily Career Coach, Autonomous
Career Agent, or similar) that answers one business question, end-to-end, from a `CareerMission`
through the Python AI Execution Plane and back.

## Before you write any code

1. Read `docs/architecture/WORKFLOW_STANDARD.md` in full — especially the "Deviation from the
   reference implementation" section at the top. Skill Gap Intelligence is the reference, but its
   HTTP-client/endpoint shape is a historical exception you must **not** copy.
2. Fill out `docs/workflows/workflow-definition-checklist.md` — scope your workflow's business
   question, inputs, outputs, and agents before touching a template.

## Build order

1. **Java package** — copy `docs/workflows/java-workflow-template.md`, replace placeholders.
2. **Register the workflow** — write the migration (`workflow_definition` seed row + result table).
3. **Python graph** — copy `docs/workflows/python-workflow-template.md`, replace placeholders.
4. **Business agents** — implement each node; deterministic where the data already exists, AI
   only for genuine reasoning (see the Standard §4/§6 and Skill Gap's own agents for the pattern:
   Mission Context and Skill Gap nodes are deterministic, Market Intelligence/Learning Roadmap/
   Mission Readiness call the AI Gateway).
5. **Register the graph** — one function, one call from `main.py`. No new endpoint.
6. **Write tests** — follow `docs/architecture/WORKFLOW_STANDARD.md` §7's checklist exactly; it
   mirrors Skill Gap's own 10 Java + 8+11 Python tests.
7. **Enable the feature flag** — `<workflow>.workflow.enabled` plus the shared `runtime.enabled`.

That's it. No new HTTP client, no new endpoint, no new dispatcher, no new runtime, no changes to
any frozen package (`ai.careerpilot.runtime`, `ai.careerpilot.workflowregistry`,
`ai.careerpilot.mission`, `ai.careerpilot.workflowplanner`, `ai.careerpilot.missionexecution`,
`agent-service/app/main.py` beyond two lines, `agent-service/app/graph.py`, `app/state.py`).

## Before you open a PR

Run through:
- `docs/development/workflow-review-checklist.md` (what your reviewer will check)
- `docs/workflows/production-readiness-checklist.md` (before flipping the flag in production)
- `docs/architecture/WORKFLOW_STANDARD.md` §10, "Architecture compliance checklist"

## Reference material

| Document | Purpose |
|---|---|
| `docs/architecture/WORKFLOW_STANDARD.md` | The authoritative specification — read this first |
| `docs/workflows/java-workflow-template.md` | Copy-pasteable Java skeleton |
| `docs/workflows/python-workflow-template.md` | Copy-pasteable Python skeleton |
| `docs/workflows/workflow-definition-checklist.md` | Scope your workflow before building |
| `docs/development/workflow-review-checklist.md` | What a PR reviewer checks |
| `docs/workflows/production-readiness-checklist.md` | Go-live checklist |
| `docs/adr/ADR-006/007/008` | Why the platform is shaped this way |
| `ai.careerpilot.skillgap` (backend source) | The reference implementation — read it, don't copy its client/endpoint shape |
| `agent-service/app/skillgap/` (source) | The reference implementation, Python side |

## Common mistakes (from Skill Gap's own build history — learn from them)

- **LangGraph node/state-key collision.** A node id must never equal a state key it writes to.
  Skill Gap's own Mission Context node hit this (`ValueError: 'mission_context' is already being
  used as a state key`) and was fixed by naming the node `mission_context_agent`. Check this
  before your first test run, not after.
- **Never fabricate a computed number.** If an AI call can produce it deterministically from data
  you already have, compute it in Python without an AI Gateway round trip (see Skill Gap's
  `mission_readiness_node` — the score/confidence/progress numbers are all deterministic; only the
  narrative goes through AI).
- **Don't build a second `AgentServiceClient`.** This was the correct call for Skill Gap in Phase
  10 (before the generic dispatcher existed) and is explicitly the wrong call for every workflow
  built after Phase 10A. Use `WorkflowRuntime.execute(...)`.
