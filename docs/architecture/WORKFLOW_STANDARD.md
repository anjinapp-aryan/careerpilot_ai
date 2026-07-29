# CareerPilot AI — Workflow Development Standard

**Status**: Official, Phase 10B
**Applies to**: every AI business workflow built after Phase 10A (Resume Intelligence, Job
Discovery Intelligence, ATS Optimisation, Interview Intelligence, Career Strategy, Application
Intelligence, Daily Career Coach, Autonomous Career Agent, and beyond).
**Reference implementation**: Skill Gap Intelligence (Phase 10), with one explicit, documented
deviation — see [Deviation from the reference implementation](#deviation-from-the-reference-implementation)
below. Read that section before copying anything from Skill Gap's code.

This document is the authoritative specification. `docs/workflows/java-workflow-template.md` and
`docs/workflows/python-workflow-template.md` are its copy-pasteable companions.

---

## Deviation from the reference implementation

**Skill Gap Intelligence predates the generic Workflow Dispatcher (Phase 10A).** It ships with its
own dedicated Python endpoint (`POST /skill-gap/runs`) and its own dedicated Java HTTP client
(`SkillGapAgentServiceClient`) — at the time it was built, `ai.careerpilot.runtime.WorkflowRuntime`
could only reach the main career graph, so a bespoke path was the lower-risk choice (see
ADR-006). Skill Gap is now frozen exactly as shipped; it is **not** being retrofitted onto the
generic platform.

**Every future workflow must NOT copy that part of Skill Gap's shape.** Per this phase's own
success criteria — "No new execution client. No new dispatcher. No new runtime." — a new workflow's
Java service calls the existing, generalized `ai.careerpilot.runtime.WorkflowRuntime` directly. It
does not create a `<Workflow>AgentServiceClient`, and its Python side does not create a dedicated
`POST /<workflow>/runs` router — it registers into the existing `WorkflowDispatchRegistry` instead.

Everything else about Skill Gap's shape — entity/repository/DTO/controller/persistence/history,
state model organization, agent structure, test coverage — **is** the standard. The one exception
is called out again at each relevant section below so it can't be missed by skimming.

---

## 1. Workflow lifecycle

Every workflow follows this path, unmodified from Phase 9/10A:

```
Career Mission
    │
    ▼
Mission Engine → Strategy Engine → Mission Orchestrator
    │                                   │
    │                        (future) Workflow Planner → Mission Execution Engine
    │                                   │
    ▼                                   ▼
<Workflow>WorkflowService  ──calls──►  WorkflowRuntime.execute(WorkflowExecutionRequest)
                                            │
                                            ▼
                                   WorkflowRegistryAdapter.resolve(workflowId)
                                            │
                                            ▼
                                   LangGraphWorkflowExecutor
                                            │
                              ═══ LANGUAGE BOUNDARY ═══
                                            │
                                            ▼
                          POST /workflows/{workflowId}/runs  (Workflow Dispatcher)
                                            │
                                            ▼
                                  WorkflowDispatchRegistry.get(workflowId)
                                            │
                                            ▼
                                   <workflow>'s own StateGraph
                                            │
                                            ▼
                                    Business Agents (nodes)
                                            │
                                            ▼
                          WorkflowRunResponse → WorkflowDispatchResponse
                                            │
                                            ▼
                          WorkflowExecutionResult → <Workflow>WorkflowService persists it
                                            │
                                            ▼
                                    <Workflow>Controller response
```

A **direct** Mission Orchestrator/Workflow Planner/Mission Execution Engine trigger is the intended
long-term entry point and remains unwired (Phase 8/Pre-Phase-9 status, unchanged). Until that
wiring phase happens, a workflow's own controller (`POST /api/<workflow>/{missionId}/run`) is the
trigger — exactly as Skill Gap does today. This is not a deviation; it is the currently-correct
call shape for every workflow, old and new.

---

## 2. Standard Java package structure

```
ai.careerpilot.<workflow>/
    <Workflow>WorkflowService.java      — the only orchestrator; trigger/latest/history
    <Workflow>NotFoundException.java    — extends NoSuchElementException, flat-404 convention
    package-info.java                   — ownership + architecture doc (see §8)

ai.careerpilot.api/
    <Workflow>Controller.java           — REST surface only, delegates to the service

ai.careerpilot.api.dto/
    <Workflow>Dtos.java                 — request/response records (nested inside one final class)

ai.careerpilot.domain/
    <Workflow>Analysis.java             — @Entity, one row per run (append-only history)

ai.careerpilot.repo/
    <Workflow>AnalysisRepository.java   — JpaRepository, owner-scoped finders

db/migration/
    V<next>__<workflow>_analysis.sql    — one workflow_definition seed row + one result table
```

**What is explicitly NOT in this list, by design**: no `<Workflow>AgentServiceClient`, no new
`WorkflowRuntimeConfiguration`-equivalent Java config class (the existing `WorkflowRuntimeConfiguration`
already constructs everything a workflow needs when `runtime.enabled=true`), no new controller
beyond the one REST surface above.

### `<Workflow>WorkflowService` — the standard shape

```java
@Service
public class <Workflow>WorkflowService {

    private final CareerMissionRepository missions;
    private final ObjectProvider<WorkflowRuntime> workflowRuntime; // optional — dark unless runtime.enabled=true
    private final <Workflow>AnalysisRepository analyses;

    @Value("${<workflow>.workflow.enabled:false}")
    private boolean enabled;

    @Transactional
    public <Workflow>AnalysisResponse trigger(UUID userId, UUID missionId) {
        if (!enabled) throw new IllegalStateException("<Workflow> workflow is not enabled");
        WorkflowRuntime runtime = workflowRuntime.getIfAvailable();
        if (runtime == null) throw new IllegalStateException("runtime.enabled must also be true");

        CareerMission mission = missions.findByIdAndUserId(missionId, userId)
                .orElseThrow(() -> new MissionNotFoundException(missionId));

        String executionId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        WorkflowExecutionRequest request = new WorkflowExecutionRequest(
                missionId, userId, "<WORKFLOW_TYPE>", null, buildInputs(mission), correlationId);

        <Workflow>Analysis analysis = analyses.save(<Workflow>Analysis.builder()
                .missionId(missionId).userId(userId).executionId(executionId)
                .correlationId(correlationId).status("RUNNING").build());

        WorkflowExecutionResult result = runtime.execute(request); // never throws
        analysis.setStatus(result.successful() ? "SUCCEEDED" : "FAILED");
        analysis.setResultJson(writeJson(result.outputPayload()));
        analysis.setErrorMessage(result.errors().isEmpty() ? null : String.join("; ", result.errors()));
        analysis.setCompletedAt(Instant.now());
        analyses.save(analysis);

        return toResponse(analysis);
    }

    public <Workflow>AnalysisResponse latest(UUID userId, UUID missionId) { /* same shape as Skill Gap */ }
    public List<<Workflow>AnalysisResponse> history(UUID userId, UUID missionId) { /* same shape as Skill Gap */ }
}
```

This is the one place the standard structurally differs from Skill Gap's shipped code:
`WorkflowRuntime.execute(request)` replaces `<Workflow>AgentServiceClient.startRun(payload)`. Two
independent flags gate it — `<workflow>.workflow.enabled` (this workflow) and `runtime.enabled`
(the shared platform) — the same layered-flag discipline already used elsewhere in this codebase
(e.g. International Job Discovery's `tiering`+`ranking` pair).

---

## 3. Standard Python package structure

```
agent-service/app/<workflow>/
    __init__.py                — package docstring: what this workflow is, that it's additive
    state.py                   — <Workflow>State TypedDict (see §4)
    graph.py                   — _build_<workflow>_graph() / get_compiled_<workflow>_graph()
    registration.py            — register_<workflow>_workflow(registry) — the ONLY dispatcher hook
    agents/
        __init__.py
        <agent_name>.py        — one file per node, exports <agent_name>_node(state) -> dict

agent-service/tests/
    test_<workflow>_workflow.py   — node + graph + end-to-end tests, mirrors test_skill_gap_workflow.py
```

**What is explicitly NOT in this list**: no `router.py`, no new `APIRouter`, no new FastAPI route,
no new entry in `app/main.py` beyond the one `register_<workflow>_workflow(get_dispatch_registry())`
call. This is the second place the standard differs from Skill Gap's shipped shape — Skill Gap's
`router.py`/dedicated endpoint is frozen, historical, and not to be imitated.

### `main.py` change for a new workflow (the entire footprint)

```python
from .<workflow>.registration import register_<workflow>_workflow
# ... inside the existing dispatcher bootstrap block:
register_<workflow>_workflow(get_dispatch_registry())
```

One import, one function call. No router include.

---

## 4. Standard workflow state model

A workflow's `TypedDict` state must group fields into five clearly-commented sections, in this
order, every time:

```python
class <Workflow>State(TypedDict, total=False):
    # --- Mission metadata ---
    mission_id: str
    user_id: str

    # --- Execution metadata ---
    workflow_id: str
    execution_id: str
    correlation_id: str

    # --- Business inputs (from the Java Control Plane) ---
    # ... workflow-specific fields ...

    # --- Business outputs (one block per agent, in graph order) ---
    # ... workflow-specific fields, one comment banner per agent ...

    # --- Cross-cutting ---
    errors: Annotated[list[str], operator.add]
```

**Never mix business state with LangGraph runtime state.** This `TypedDict` holds *your* workflow's
data only — it must never hold a node list, an edge list, a checkpoint id, or anything describing
graph topology or execution position (that's LangGraph's own internal machinery, invisible to node
code and untouched by this standard — see `ai.careerpilot.runtime.WorkflowState`'s Java-side
javadoc for the equivalent Control-Plane rule).

**Naming collision rule**: a node id must never equal a state key it writes to (LangGraph raises
`ValueError`). If your natural node name collides with its own output field (as Skill Gap's Mission
Context agent did — see `app/skillgap/graph.py`'s own comment), suffix the **node id** with
`_agent`; never rename the state field to dodge the collision.

---

## 5. Standard execution contract

### Request (Java → Python, one shape for every workflow)

```
WorkflowExecutionRequest(missionId, userId, workflowId, executionDecision?, inputs, correlationId)
    → AgentServiceClient.startWorkflowRun(workflowId, payload)
    → POST /workflows/{workflowId}/runs
    → WorkflowRunRequest(mission_id, user_id, execution_id, correlation_id, inputs: dict)
```

`inputs`/`payload` is always an opaque `Map<String, Object>` / `dict[str, Any]` — the transport
layer never inspects it. Building it from real domain data (e.g. `CareerMission` fields) is the
Java service's job, same as Skill Gap's `buildPayload`/`buildInputs`.

### Response (Python → Java, one shape for every workflow)

```
WorkflowRunResponse(workflowId, executionId, correlationId, status, durationMs, output: dict, errors: list[str])
    → WorkflowDispatchResponse (Java binding, identical fields)
    → WorkflowExecutorOutcome(status, outputPayload, executionRef, rawMetadata)
    → WorkflowExecutionResult(workflowId, executionId, executionStatus, startTime, endTime, duration,
                               outputPayload, executionLogs, warnings, errors, metrics)
```

`output`/`outputPayload` is always opaque. Do not create a workflow-specific typed response record
on the Java side unless a real, demonstrated need for compile-time field access appears (see
ADR-008's "alternatives considered" — this was deliberately rejected as premature at N=2 workflows).

### Standard error model

- `status` is always `"completed"` or `"error"` (Python) / the result's `executionStatus()` is
  `COMPLETED`/`FAILED`/`TIMED_OUT`/`CANCELLED`/`INTERRUPTED`/`RUNNING` (Java) — never a raw
  exception crossing the language boundary, and never an HTTP 500 from the dispatcher for a
  workflow-internal failure (only a genuinely unknown `workflowId` is a 404; only a transport-level
  failure is a 5xx).
- Every agent node catches its own exceptions and returns `{"errors": [f"<agent_name>: {e}"], ...safe fallback}` —
  never raises past its own node function. Use `agent_support.call_structured_agent(...)` for any
  node that calls the AI Gateway (see §6).
- `WorkflowRuntime.execute(...)` never throws past its own boundary (Phase 9 guarantee, unchanged)
  — a workflow's Java service never needs a try/catch around `runtime.execute(request)`.

---

## 6. Standard observability contract

**Structured logging** — every lifecycle transition logs, at minimum, `workflowId`, `executionId`,
`missionId`, `correlationId`:
- Java: `DefaultWorkflowRuntime`'s existing `runtime_execution_started`/`runtime_execution_completed`/
  `runtime_execution_failed`/`runtime_validation_failed`/`runtime_workflow_not_found` log lines
  already do this — a new workflow's own service should log its own `trigger`/`latest`/`history`
  calls with the same four fields, at `INFO` for success and `WARN`/`ERROR` for failure paths.
- Python: the dispatcher's existing `workflow_dispatch_started`/`workflow_dispatch_completed`/
  `workflow_dispatch_failed` log lines already carry `workflow_id`/`mission_id`/`execution_id`/
  `correlation_id`/`duration_ms`. A new workflow's own agents should log `<agent_name>: stage
  started`/`stage completed successfully`/`stage failed` — `agent_support.call_structured_agent`
  does this automatically for any AI-calling node.

**Metrics** — `WorkflowMetrics#record` (Java, `InMemoryWorkflowMetrics`) is called automatically by
`DefaultWorkflowRuntime` for every execution once a workflow goes through it — a new workflow gets
this for free by using `WorkflowRuntime.execute(...)` (see the deviation in §2). No new metrics
plumbing is ever required per workflow.

**Correlation ID** — generated once, at the top of `<Workflow>WorkflowService.trigger`, and threaded
through every layer on both sides (see §5's request/response shapes). Never regenerated mid-flight.

**Tracing** — no distributed tracing exists in this platform (explicit non-goal since Phase 9); do
not introduce a tracing dependency for a single workflow. `correlationId` plus structured logs is
the whole tracing story today.

---

## 7. Standard testing checklist

**Java** (mirror `SkillGapWorkflowServiceTest`'s 10 cases):
- [ ] Flag disabled → `IllegalStateException`, zero downstream interaction
- [ ] `runtime.enabled=false` (runtime bean absent) → `IllegalStateException`, distinct from the workflow's own flag
- [ ] Mission not found / not owned → `MissionNotFoundException`
- [ ] Successful run → `SUCCEEDED` status, result persisted, response shape correct
- [ ] `WorkflowRuntime` returns a `FAILED` result → service persists `FAILED` + error message, never throws
- [ ] `latest()` with no analysis yet → `<Workflow>AnalysisNotFoundException`
- [ ] `latest()` returns the most recent row
- [ ] `history()` ownership-checked, returns all rows newest-first
- [ ] Controller: thin delegation only, no logic to test beyond wiring (optional `@WebMvcTest` smoke test)

**Python** (mirror `test_skill_gap_workflow.py`'s 8 + `test_workflow_dispatcher.py`'s 11 patterns):
- [ ] Every deterministic node: pure unit tests, no mocking needed
- [ ] Every AI-calling node: mocked `app.agent_support.get_workflow_ai_gateway`, success + failure paths
- [ ] Graph structural test: `get_compiled_<workflow>_graph()` compiles, contains every expected node id
- [ ] Registration test: `register_<workflow>_workflow(registry)` then `registry.get("<WORKFLOW_ID>")` returns it
- [ ] Dispatcher end-to-end test: `POST /workflows/<WORKFLOW_ID>/runs` via `TestClient`, fake gateway,
      assert the full response shape and a known-input/known-output worked example
- [ ] Error-path test: a raising node/gateway degrades to `status="error"`, never a 500
- [ ] `main.py` still boots (`from app.main import app` succeeds) — proves the registration import
      has no circular-import or startup-time side effect

---

## 8. Standard documentation checklist

Every workflow ships with:
- [ ] `package-info.java` on the Java package — ownership statement (Java Control Plane
      responsibilities vs. Python AI Execution Plane responsibilities, matching the
      `ai.careerpilot.skillgap`/`ai.careerpilot.runtime` package-info style), explicit "what this
      package does NOT own," and the deviation note from §2/§3 if relevant.
- [ ] `agent-service/app/<workflow>/__init__.py` docstring — same ownership statement, Python side.
- [ ] A `CLAUDE.md` section (new "### Phase N — `<Workflow>`" entry) following every prior phase's
      established format: what shipped, what's explicitly NOT touched, the feature flag(s), the
      `mvn test`/`pytest` counts.
- [ ] An architecture diagram (reuse §1's lifecycle diagram, workflow-specific only if truly
      different — most workflows won't need a bespoke one).
- [ ] A sequence diagram only if the workflow's control flow genuinely diverges from §1 (e.g. it
      needs a human-approval pause) — otherwise §1 already documents it, don't duplicate.
- [ ] Feature flag documentation — one line in the `CLAUDE.md` "Configuration that affects
      behavior" table, matching every existing flag's row format.

No separate per-workflow README is required — `CLAUDE.md` plus `package-info.java`/`__init__.py`
is this platform's established documentation pattern (confirmed by grep: no existing workflow
package under this codebase has its own README.md).

---

## 9. Standard naming conventions

| Concept | Java | Python |
|---|---|---|
| Service | `<Workflow>WorkflowService` | — |
| Controller | `<Workflow>Controller` | — |
| Entity | `<Workflow>Analysis` | — |
| Repository | `<Workflow>AnalysisRepository` | — |
| DTOs | `<Workflow>Dtos.<Workflow>AnalysisResponse` | — |
| Not-found exception | `<Workflow>AnalysisNotFoundException` | — |
| Registry workflow type | `"<WORKFLOW_TYPE>"` (e.g. `"SKILL_GAP_INTELLIGENCE"`) | same string |
| Registry business key | `"<WORKFLOW_TYPE>_V1"` (e.g. `"SKILL_GAP_INTELLIGENCE_V1"`) | same string |
| Feature flag | `<workflow>.workflow.enabled` (e.g. `skillgap.workflow.enabled`) | — |
| State model | — | `<Workflow>State` |
| Graph builder | — | `_build_<workflow>_graph()` / `get_compiled_<workflow>_graph()` |
| Registration function | — | `register_<workflow>_workflow(registry)` |
| Agent node function | — | `<agent_name>_node(state) -> dict` |
| Agent module | — | `agents/<agent_name>.py` |

---

## 10. Architecture compliance checklist

A workflow is compliant with this standard if and only if:

- [ ] Zero new Java HTTP client classes (uses `AgentServiceClient.startWorkflowRun` via `WorkflowRuntime`)
- [ ] Zero new Python FastAPI routers (uses `WorkflowDispatchRegistry` registration only)
- [ ] Zero changes to `ai.careerpilot.runtime`, `ai.careerpilot.workflowregistry`,
      `ai.careerpilot.mission`, `ai.careerpilot.workflowplanner`, `ai.careerpilot.missionexecution`
- [ ] Zero changes to `agent-service/app/main.py` beyond one import + one registration call
- [ ] Zero changes to `agent-service/app/graph.py`, `app/state.py`, or any other existing workflow's files
- [ ] `workflow_definition` gains exactly one new seed row per workflow (metadata only)
- [ ] Result persistence gets exactly one new table (or reuses an existing generic one, if a future
      phase introduces one — none exists yet, so one new table per workflow today)
- [ ] Two independent feature flags: the workflow's own, plus (implicitly) `runtime.enabled`
- [ ] `mvn test` / `pytest` full-suite counts increase, never decrease, with zero failures

---

*Companion documents*: `docs/workflows/java-workflow-template.md`,
`docs/workflows/python-workflow-template.md`, `docs/development/WORKFLOW_DEVELOPMENT_GUIDE.md`,
`docs/development/workflow-review-checklist.md`, `docs/workflows/workflow-definition-checklist.md`,
`docs/workflows/production-readiness-checklist.md`.
