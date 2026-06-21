# CareerPilot AI — Stabilization Task Progress (handoff notes)

Principal-engineer task: fix 11 production issues with REAL, regression-safe code (no mocks/TODOs).
Stack: backend (Spring Boot/Java, `backend/`), agent-service (FastAPI+LangGraph, `agent-service/`),
frontend (React/Vite/TS, `frontend/`). Run via `docker compose --env-file .env up -d`.

## ROOT-CAUSE FINDINGS (confirmed by reading code)

- **Issue 1 (Approve/Reject do nothing):** `frontend/src/pages/Workflow.tsx` `handleResume` is a raw
  async fn — NO pending state, NO error surfacing. Backend resume path is correct
  (`WorkflowController.resume` -> `WorkflowService.resume` -> `AgentServiceClient.resumeRun` ->
  agent `/runs/resume` does `graph.update_state` + `graph.invoke(None)`). Resume runs
  application_tracking which calls AI (~10-30s); user sees zero feedback so it looks dead. If it
  500s (DeepSeek 404 etc) it's swallowed silently. FIX = convert to React Query `useMutation` with
  per-threadId pending + inline error + disabled buttons/spinner.
- **Issue 3 (timeline empty):** Was empty before the WorkflowAgent DTO existed. Now GET
  `/api/workflows/{threadId}` returns `agents` and `useWorkflowStatus` reads `data.agents`. Gap:
  `completedAt` always null, no provider, no duration. FIX = execution-metadata backbone (below).
- **Issue 5 (Kafka "Couldn't resolve kafka:29092"):** `WorkflowEventProducer.publish` calls
  `kafka.send` with no try/catch; fails when backend runs off-docker or Kafka down. No enable flag.
  FIX = wrap send in try/catch (never throw into workflow) + `careerpilot.kafka.enabled` flag.
- **Issue 7 (start UX):** Already a proper mutation in `WorkflowForm.tsx` (loading/disabled/error/
  success all handled). Mostly OK — verify only.
- **Issue 10 (DeepSeek 404):** `.env` `NVIDIA_DEEPSEEK_MODEL=deepseek-ai/deepseek-v4-flash` is VALID
  per NVIDIA catalog (deepseek-v4-flash exists, namespace `deepseek-ai/`). BUT defaults are wrong:
  `agent-service/app/config.py` line 45 = `nvidia/deepseek-v4-flash` (wrong ns);
  `backend/src/main/resources/application.yml` line 83 = `deepseek-v4-flash` (no ns). FIX both
  defaults to `deepseek-ai/deepseek-v4-flash`. Failover already handles DeepSeek failure -> Qwen/Gemini.

## EXECUTION-METADATA BACKBONE (fixes Issues 3, 8, 9; underpins 2, 4)

Goal: each agent node records {stage, name, status, started_at, completed_at, duration_ms, provider,
error}. Frontend timeline shows status + provider + duration; pipeline becomes runtime-driven.

1. **agent-service/app/state.py** — DONE: added
   `agent_execution: Annotated[list[dict], operator.add]`.
2. **agent-service/app/workflow_ai_gateway.py** — TODO: record provider actually used per stage.
   Add module-level thread-safe `_stage_providers: dict[str,str]` + lock; in
   `generate_structured_response`, on success set `_stage_providers[stage] = provider.name`. Add
   `get_stage_provider(stage) -> str|None`. (Gateway already knows fallback; provider.name is the
   actual one used.)
3. **agent-service/app/graph.py** — TODO: wrap every node EXCEPT `human_approval` with an
   `_instrument(node_name, display_name, fn)` decorator that records timing/status/provider/error
   into `agent_execution`. DO NOT wrap `human_approval` (it raises NodeInterrupt — wrapping risks
   breaking HITL + duplicate entries on resume). Order/names:
   resume_intelligence=Resume Intelligence, job_discovery=Job Discovery,
   ats_optimization=ATS Optimization, interview_prep=Interview Preparation,
   career_strategy=Career Strategy, salary_intelligence=Salary Intelligence,
   application_tracking=Application Tracking. status=FAILED if result has truthy `errors` else
   COMPLETED; provider via get_stage_provider(node_name). Checkpointing means completed nodes don't
   re-run on resume, so no duplicate entries.
4. **backend WorkflowDtos.WorkflowAgent** — TODO: add fields `provider`, `durationMs` (keep `name`,
   `status`, `completedAt`). Record is positional — update the constructor call in WorkflowService.
5. **backend WorkflowService.extractAgentTimeline** — TODO: build map node->entry from
   `state.agent_execution`; for each of 8 display stages emit WorkflowAgent. human_approval status
   from `awaiting_human_approval` (WAITING_FOR_APPROVAL if true else COMPLETED-if-decided/PENDING).
   Stages with an execution entry use its status/completedAt(=completed_at)/provider/durationMs.
   Missing -> PENDING. Mark FAILED from entry.status.
6. **frontend/src/types/workflow.ts** — TODO: add `provider?: string; durationMs?: number;` to
   WorkflowAgent.
7. **frontend/src/components/workflow/WorkflowStatusStepper.tsx** — TODO: render provider + duration
   under each step label.

## OTHER FIXES

- **Issue 1:** rewrite `handleResume` in `frontend/src/pages/Workflow.tsx` as `useMutation`
  (`resumeMutation`), track `pendingId`, pass `isPending`/`error` into `RunCard`, disable buttons +
  spinner while pending, show inline error on failure. Invalidate `workflows`,
  `workflow-status`, `dashboard` on success.
- **Issue 5:** `backend/.../kafka/WorkflowEventProducer.java` — inject
  `@Value("${careerpilot.kafka.enabled:true}") boolean enabled`; if disabled, return; wrap
  `kafka.send(...)` in try/catch + `.whenComplete` async-failure log so failures never break the
  workflow. Add `careerpilot.kafka.enabled: ${KAFKA_ENABLED:true}` to application.yml.
- **Issue 10:** fix model defaults in config.py (line 45) + application.yml (line 83) to
  `deepseek-ai/deepseek-v4-flash`.
- **Issue 2/4:** stepper already polls (useWorkflowStatus, 5s until COMPLETED/FAILED). With richer
  agent_execution statuses the timeline is runtime-driven. Unify status mapping (RUN_STATUS in
  Workflow.tsx already covers COMPLETED/ERROR/FAILED/RUNNING/INTERRUPTED). Ensure backend never emits
  states outside {RUNNING, INTERRUPTED, COMPLETED, ERROR}. mapStatus already does this.

## VERIFY AT END
- agent-service: `python -c "import app.graph, app.workflow_ai_gateway, app.state"` (syntax/import).
- backend: `cd backend && mvn -q -DskipTests compile`.
- frontend: `cd frontend && npm run build`.
- Then `docker compose --env-file .env up -d --build` and drive a workflow from UI.

## STATUS
- [x] state.py agent_execution field
- [x] Issue 10: config.py + application.yml DeepSeek/Qwen model default namespaces fixed
- [x] Issue 5: WorkflowEventProducer Kafka enable-flag + try/catch + async whenComplete; application.yml careerpilot.kafka.enabled flag
- [x] Issue 1: Workflow.tsx handleResume → useMutation (resumeMutation) with per-thread pending spinner, disabled buttons, inline error in RunCard
- [x] Issue 8: workflow_ai_gateway.py _stage_providers map + get_stage_provider(); records actual provider on success
- [x] Issue 3/9: graph.py _instrument() wraps all nodes except human_approval; appends {stage,name,status,started_at,completed_at,duration_ms,provider,error} to agent_execution + structured stage_start/stage_end logs
- [x] Backend DTO WorkflowAgent: added provider + durationMs
- [x] WorkflowService.extractAgentTimeline rebuilt from state.agent_execution (PIPELINE_STAGES; human_approval derived from awaiting_human_approval/human_decision)
- [x] frontend types/workflow.ts WorkflowAgent: provider + durationMs
- [x] WorkflowStatusStepper renders provider chip + formatDuration per step
- [x] BUILDS GREEN: py_compile OK, frontend `npm run build` (tsc -b) OK, backend `mvn compile` EXIT=0
- [ ] Runtime smoke: `docker compose --env-file .env up -d --build` + drive a workflow from UI (Approve/Reject, timeline, provider/duration)
