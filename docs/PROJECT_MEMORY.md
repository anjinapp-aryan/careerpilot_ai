# Project Memory

## Critical Fixes (Latest Session - 2026-06-21)

### Reject→Approve State Corruption (FIXED)
- **Symptom**: Resuming a run that had already been rejected (or already approved) re-ran the
  agent-service resume call and silently overwrote the terminal state — a REJECTED run could be
  turned into COMPLETED by a second, stale "approve" click.
- **Root Cause**: Neither the agent-service `/runs/resume` endpoint nor the backend
  `WorkflowService.resume()` checked whether the run was actually still parked at the
  `human_approval` interrupt before acting on a decision.
- **Fix**: Dual-layer 409 guard. Agent-service checks `"human_approval" not in
  graph.get_state(cfg).next` and raises `HTTPException(409, ...)` before calling
  `graph.update_state(...)`. Backend checks `deriveDisplayStatus(run) != "INTERRUPTED"` and
  raises `IllegalStateException` (mapped to 409 via `GlobalExceptionHandler`) before even
  calling the agent. Frontend also added a `useMutation.isPending` guard to stop the
  double-submit that most often triggered this race.
- **Pattern**: Any "resume/continue" endpoint over a paused state machine must re-validate the
  current state server-side immediately before mutating it — never trust that the client only
  calls it once. Validate at both the orchestrator (agent-service) and the persistence-owning
  layer (backend), since either could be called directly.
- **Files**: `agent-service/app/main.py` (`resume_run`), `WorkflowService.resume()`,
  `AgentServiceClient.resumeRun()` (maps 409→`IllegalStateException`), `GlobalExceptionHandler`,
  `frontend/src/pages/Workflow.tsx`.

### Audit Trail Without Schema Migration (IMPLEMENTED)
- **Need**: Track who approved/rejected a run, when, and with what feedback.
- **Decision**: Stamp `approved_by`/`approved_at`/`rejected_by`/`rejected_at`/`human_feedback`
  directly into the existing `state` JSONB blob in `WorkflowService.mergeResponse()`, rather
  than adding columns + a Flyway migration.
- **Pattern**: For audit-style fields that are read-mostly and already co-located with a JSON
  blob the entity owns, prefer extending that blob over a schema migration — but only when the
  field is genuinely just display/audit data, not something you'll need to query/index on. If
  you ever need to filter/sort workflow runs by approver, this should become real columns.
- **Files**: `WorkflowService.mergeResponse()`, `WorkflowDtos.WorkflowRunResponse` (5 new
  fields), `WorkflowController.resume()` (passes `user.email()` as actor).

### Copilot Can No Longer Contradict the Workflow UI (FIXED)
- **Symptom**: The Copilot's workflow-explanation skill could describe a run's status
  differently than the Workflow page, because it read the raw persisted `status` column while
  the UI displayed a status derived from the agent timeline.
- **Fix**: `CareerContextRetriever.getWorkflowContext()` now calls the same
  `WorkflowService.deriveDisplayStatus(run)` the UI uses, and
  `WorkflowExplanationHandler.systemPrompt()` was given explicit per-status branches
  (INTERRUPTED/REJECTED/FAILED/COMPLETED/RUNNING) instead of one generic instruction.
- **Pattern**: Whenever a status/derived value is computed for one surface, expose it as a
  public method and reuse it everywhere else that value is reported — don't let two surfaces
  each compute their own version of "current status."

## Design Principles

1. **Single Seam for External Dependencies**
   - AiGatewayService = single LLM entry point (no direct provider access from business logic)
   - WorkflowService = single agent-service caller (no frontend→agent-service calls)
   - AiGatewayService = single place to add providers (add impl + update order list)

2. **Multi-Tenant Isolation Manual**
   - No row-level security or Hibernate @Filter
   - Every service method must check: userId.equals(entity.getUserId())
   - JWT carries userId, orgId — extractable via AuthenticatedUser param

3. **Streaming > Blocking for Long Operations**
   - Copilot = SSE (SseEmitter) with token streaming + done event
   - Workflow = non-blocking; frontend polls on user action
   - No WebSocket — explicit refetch after mutation via TanStack Query

4. **DTO Pattern for Complex JSON Fields**
   - Entities store JSON as String (mapper.writeValueAsString)
   - DTOs parse on read (mapper.readValue(entity.state, Map.class))
   - Controllers return DTOs, not entities
   - Prevents Jackson type errors on response serialization

5. **Failover Only Before First Token**
   - Blocking: can fail over transparent to caller
   - Streaming: once first token emitted, must return error (client already receiving)
   - Quota detection (429): triggers immediate failover before trying retries

6. **Never Use ThreadLocal Across Reactor Async Boundaries**
   - Reactor executes callbacks on different threads than where they were scheduled — ThreadLocal
     set in one thread is invisible by the time `doOnComplete()`/`doOnNext()` runs.
   - Pass context as an explicit method parameter instead (e.g. `streamChat(messages, system,
     providerCallback)` takes a `Consumer<String>` so the provider name reaches the caller).

7. **Re-validate State Server-Side Before Any "Resume/Continue" Mutation**
   - A paused state machine (e.g. LangGraph's `human_approval` interrupt) must be re-checked for
     "is it still actually paused here?" immediately before acting on a client decision —
     at every layer that can independently receive the call (agent-service AND backend), not just
     the topmost one.
   - Derived/display status (`deriveDisplayStatus`) is the single source of truth for "is this run
     awaiting approval" — don't re-derive it ad hoc at each call site.

## Database Decisions

| Decision | Reason |
|----------|--------|
| Neon serverless (external Postgres) | Scale to zero cost, no container overhead. Direct endpoint required (no -pooler). |
| Flyway migrations in backend | Version control + automated schema evolution on startup. |
| LangGraph PostgresSaver | Checkpoint storage survives process restarts. Auto-creates tables (not in Flyway). |
| JSONB for workflow state | Flexible state serialization. Parsed to Map on DTO conversion (not raw JsonNode). Now also carries audit-trail fields (approved/rejected by/at, feedback) — no separate audit columns. |
| Single shared DB, logically partitioned | Agent service reads own checkpoints; backend reads domain data. No cross-concern writes. |

## Operational Patterns

1. **Provider Health Endpoint** → `/api/diagnostics/ai` (no auth)
   - Shows: configured models, API keys loaded, provider order, health (UP/DOWN/NOT_CONFIGURED), call counts
   - Used: monitoring deployments, debugging quota issues

2. **Workflow Diagnostics** → `/api/diagnostics/workflow` (no auth)
   - Shows: workflowEngine/jsonSerialization/agentService status + provider health
   - Used: validate post-deployment startup

3. **Logging**
   - WorkflowService: "Workflow Created", "Started", "Updated" at INFO; resume-rejected-not-awaiting at INFO when the 409 guard fires
   - AiGatewayService: provider selection + fallback triggers at INFO, full stack traces on ERROR
   - CopilotService: provider callback execution logged

4. **Error Handling**
   - 429 (quota) → immediate failover, no retries
   - Other errors → per-provider retry (Resilience4j) then failover
   - Mid-stream error in streaming → return Flux.error() (can't retry)
   - Invalid state transition (resume on non-awaiting run) → `IllegalStateException`/409, not a generic 500

5. **Copilot Skill Routing**
   - `CopilotSkillRouter` maps each request to one of 10 `CopilotSkill` handlers, by explicit
     `action` or keyword-inferred from the message, falling back to `GeneralAssistantHandler`.
   - Each handler assembles its own RAG context via `CareerContextRetriever` — adding a skill
     means a new enum value + handler class + router wiring, no changes elsewhere.

## Known Scaffolding (Do NOT Assume Integrated)

- `refresh_tokens` table — no /api/auth/refresh endpoint
- `audit_logs`, `usage_records` tables — no writes (note: workflow approval audit trail now exists, but lives in `workflow_runs.state` JSON, not these tables)
- Redis — `@Cacheable` never used
- Kafka consumers — events go nowhere (@KafkaListener empty)
- pgvector — embeddings not generated (extension installed, no indexes)
- Resume upload & S3 storage — MinIO configured, no integration

## Deployment Notes (Phase 1)

- **Not yet deployed** to production
- **Docker compose** is primary dev/test environment
- **Neon direct endpoint** required (Flyway + LangGraph prepared statements break on pgBouncer -pooler)
- **NVIDIA_API_KEY** must be set in Docker env if using DeepSeek/Qwen
- **S3_*  vars** map to MinIO locally, Cloudflare R2 in production (see .env comments)
- **No Kafka consumers wired** — events published, never consumed
- **Docker credential helper gotcha**: if `docker compose up --build` fails with
  `error getting credentials - err: exec: "docker-credential-desktop": executable file not
  found in %PATH%`, it's BuildKit's daemon process failing to resolve the helper — exporting
  the helper's directory onto your shell's PATH does *not* fix it (the daemon doesn't inherit
  it). Workaround: temporarily remove `"credsStore": "desktop"` from `~/.docker/config.json`
  (safe only if `"auths": {}` is empty — i.e. only public-registry pulls are needed), rebuild,
  then restore the original file immediately after.

## For Next Sessions

1. Verify `/api/diagnostics/workflow` shows all providers UP before running workflows
2. If workflow returns RUNNING/INTERRUPTED, check agent-service logs for progress
3. If copilot returns "Unknown" provider, check that providerCallback is not null in logs
4. If JsonNode errors appear, ensure controller returns DTO (use workflows.toResponse()), not entity
5. Provider failover requires NVIDIA_API_KEY set + docker-compose rebuilt with env vars
6. If a reject/approve resume call returns 409, that's the new guard working as intended — check `deriveDisplayStatus(run)` to see what state it's actually in, don't treat 409 as a bug
