# Workflow PR Review Checklist

For reviewers of a PR introducing a new CareerPilot AI workflow. Reject or request changes if any
box is unchecked without a documented, explicit justification in the PR description.

## Architecture compliance

- [ ] No new Java HTTP client class — the service calls `WorkflowRuntime.execute(...)`
- [ ] No new Python `router.py` / `APIRouter` / route added to `main.py`
- [ ] `agent-service/app/main.py` diff is exactly one import + one registration call
- [ ] Zero diff in `ai.careerpilot.runtime`, `ai.careerpilot.workflowregistry`,
      `ai.careerpilot.mission`, `ai.careerpilot.workflowplanner`, `ai.careerpilot.missionexecution`
- [ ] Zero diff in `agent-service/app/graph.py`, `app/state.py`, any other existing workflow's files
- [ ] Zero diff in any *other* workflow's Java package or Python package

## Java

- [ ] Package layout matches `docs/workflows/java-workflow-template.md`
- [ ] Two independent flags checked: `<workflow>.workflow.enabled` and `runtime.enabled` (via
      `ObjectProvider<WorkflowRuntime>.getIfAvailable()`, not a hard dependency)
- [ ] Mission ownership enforced (`findByIdAndUserId`, `MissionNotFoundException` on miss)
- [ ] Service never lets an exception from `WorkflowRuntime.execute` propagate uncaught (it
      shouldn't need to — `execute` never throws — but verify no additional risky code was added
      around the call)
- [ ] Entity/repository/DTO naming matches the Standard's naming table (§9)
- [ ] `package-info.java` present, states Java/Python ownership split explicitly

## Python

- [ ] Package layout matches `docs/workflows/python-workflow-template.md`
- [ ] Every node function is named `<agent_name>_node(state) -> dict`
- [ ] Every AI-calling node uses `agent_support.call_structured_agent(...)` — no hand-rolled
      try/except/log/fallback around `get_workflow_ai_gateway()`
- [ ] Every deterministic computation stays deterministic — no AI call added "just in case"
- [ ] State model groups fields into the five standard sections with comment banners (mission
      metadata / execution metadata / business inputs / business outputs / cross-cutting)
- [ ] No node id collides with a state key it writes (compile the graph once locally and confirm
      no `ValueError` before pushing)
- [ ] `register_<workflow>_workflow(registry)` registered from `main.py`, nowhere else

## Testing

- [ ] Every checklist item in `docs/architecture/WORKFLOW_STANDARD.md` §7 has a corresponding test
- [ ] Full `mvn test` run attached/linked, count increased, zero failures
- [ ] Full `pytest` run attached/linked, count increased, zero failures
- [ ] At least one end-to-end test invokes the workflow through the generic dispatcher
      (`POST /workflows/{workflowId}/runs`), not only unit-level node tests

## Documentation

- [ ] `package-info.java` (Java) and `__init__.py` docstring (Python) both present
- [ ] `CLAUDE.md` gained a new "### Phase N — `<Workflow>`" section, following the existing format
      (what shipped / what's NOT touched / feature flags / test counts)
- [ ] Feature flag documented in `CLAUDE.md`'s configuration table
- [ ] `docs/workflows/workflow-definition-checklist.md` was filled out before implementation began
      (linked in the PR description)

## Data & AI discipline

- [ ] No fabricated signal — every output field traces to either a real input, a real deterministic
      computation, or an explicit AI Gateway call with a documented prompt/schema
- [ ] No duplicated business logic — if an existing service/entity already computes something this
      workflow needs, it's reused, not recomputed
- [ ] Naming doesn't collide with an existing, conceptually different component (grep for the
      chosen `<Workflow>` name across the codebase before finalizing — see Skill Gap's own
      documented near-collision with `learning.career.goal.SkillGapIntelligenceService`)
