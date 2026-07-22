# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project shape

CareerPilot AI is a three-service monorepo: a **Spring Boot 4 / Java 25 backend** (`backend/`) acts as the control plane, a **Python FastAPI + LangGraph 0.2 agent service** (`agent-service/`) hosts the multi-agent workflow, and a **React 18 + Vite + TS frontend** (`frontend/`) is the UI. They share **one PostgreSQL** database (currently a **Neon serverless** instance, external to docker-compose — see `.env`) with the pgvector extension — backend persists domain data, agent-service persists LangGraph checkpoints into the same DB. Redis, Kafka, and MinIO/S3 run in `docker-compose.yml`.

The backend has grown well past its original vertical slice into ~30 role-based packages (job discovery, resume tailoring, execution/browser automation, workflow tracking, learning, autopilot, company intelligence, career-goal intelligence, etc. — see "Architecture" below for the ones with non-obvious cross-file shape). The great majority of that surface still ships **dark by default** behind independent `*.enabled` feature flags — a package existing, or even having a REST controller, is not evidence it's live in production. Several tables and beans are also intentionally provisioned-but-unwired (see "Provisioned-but-unused" below).

**This is a live production system** at https://careerpilot-ai.duckdns.org/ (backend on an Oracle Cloud VM, frontend on Vercel) — treat backend/infra changes with the caution that implies; see "Deployment" below before touching `docker-compose.yml`, the `Dockerfile`, or anything under `deployment/`.

To **launch and smoke-test the whole stack from scratch**, use the `run-careerpilot-ai` skill ([.claude/skills/run-careerpilot-ai/SKILL.md](.claude/skills/run-careerpilot-ai/SKILL.md)): `docker compose --env-file .env up -d`, then drive it with `node .claude/skills/run-careerpilot-ai/driver.mjs --e2e` — a zero-dependency HTTP harness that probes all three services and runs a real register→login→dashboard→create-job flow against the live backend + Neon.

## Commands

### Run the whole stack (preferred)
`docker-compose.yml` at the repo root is now **hardened for the Oracle Cloud VM production deploy** (`SPRING_PROFILES_ACTIVE=prod`, no host ports published for `redis`/`kafka`/`minio`/`zookeeper` — only the backend can reach them internally). Running it bare on a dev machine works but hides those services from the host and disables Swagger. For local dev, layer the local-dev overlay on top:
```bash
cp .env.example .env
# Set: JWT_SECRET (>=32 chars), GEMINI_API_KEY, and your Neon DATABASE_URL/DATABASE_URL_PY
docker compose --env-file .env -f docker-compose.yml -f docker-compose.local.yml up --build
```
[docker-compose.local.yml](docker-compose.local.yml) restores host access to redis/kafka/minio and other local-dev conveniences that were intentionally stripped from the base file for the VM deploy. **Never apply this overlay on the production VM** — the VM runs `docker-compose.yml` alone.

Frontend on http://localhost:5173, backend on http://localhost:8080 (Swagger at `/swagger-ui.html`), agent service on http://localhost:8088 (`/docs`), MinIO console on http://localhost:9001.

**Postgres lives outside docker-compose** (Neon serverless). The two `DATABASE_URL` forms in `.env` must use Neon's **direct** endpoint (no `-pooler` in hostname) — Flyway DDL and the LangGraph `PostgresSaver` both rely on prepared statements that break under Neon's transaction-mode pgBouncer pooler. Going back to a local Postgres container is documented as a comment block at the top of `.env.example`.

### Production deployment
**[DEPLOYMENT_ORACLE_CLOUD.md](DEPLOYMENT_ORACLE_CLOUD.md) is the authoritative production architecture doc** (single Oracle Cloud VM, `docker-compose.yml` at the repo root, frontend on Vercel separately) — it explains *why* the VM/network/systemd pieces are shaped as they are; the day-to-day *how* (setup, redeploy, rollback, backup, health checks) is the operational runbook at [deployment/README.md](deployment/README.md). [DEPLOYMENT.md](DEPLOYMENT.md) (Render + Vercel + Neon, free-tier demo) and [DEPLOYMENT_CLOUDRUN.md](DEPLOYMENT_CLOUDRUN.md) (Cloud Run, backend-only alternative) are earlier/alternative deploy targets — not what's currently serving production traffic; don't merge their instructions with the Oracle Cloud path.

The backend `Dockerfile`'s `JAVA_OPTS` includes `-XX:MaxMetaspaceSize=320m` — a previous value of `160m` caused a real production crash-loop (Metaspace OOM under sustained uptime) on the Oracle VM's 4GB RAM. Don't lower it without checking VM RAM headroom against the other containers sharing that host.

### Per-service (no Docker)
| Service | Command (from service dir) |
|---|---|
| backend | `mvn spring-boot:run` (needs Postgres+Kafka+Redis+MinIO running) |
| agent-service | `pip install -r requirements.txt && uvicorn app.main:app --reload --port 8088` |
| frontend | `npm install && npm run dev` |

There is **no `mvnw` wrapper** in `backend/` — use the system Maven (`mvn`, 3.9+) on the host; the Docker build calls `mvn` inside the build stage.

### Build / package
- Backend jar: `cd backend && mvn -DskipTests package` (Dockerfile does this in stage 1)
- Frontend bundle: `cd frontend && npm run build`

### Tests
The only real suite today is in **agent-service**: `agent-service/tests/test_rate_limiter.py` (pytest, ~50 cases covering the `GeminiRateLimiter`). Run it from `agent-service/`:
```bash
pip install -r requirements-dev.txt   # pytest + pytest-asyncio, on top of requirements.txt
pytest                                 # single test: pytest tests/test_rate_limiter.py::test_name
```
- Backend: a real, large JUnit 5 + Mockito + AssertJ suite (1600+ tests and growing — most new services get a matching `*Test.java` alongside them) using `spring-boot-starter-test` + `spring-security-test` ([backend/pom.xml](backend/pom.xml)). Run with `mvn test`, single test with `mvn -Dtest=ClassName#method test`. Prefer `mvn clean test-compile` over a bare incremental compile when checking for cascading breakage across files you didn't directly touch — Maven's incremental compiler can miss it.
- Frontend: vitest is wired (`npm test` runs `vitest run`), with at least one real spec ([lib/authError.test.ts](frontend/src/lib/authError.test.ts)) — coverage is still thin, add specs alongside the code you touch. There is still **no lint step configured** — no `lint` script, no eslint config.

## Architecture — the things you need to read multiple files to see

### The LangGraph workflow is the heart of the product
[agent-service/app/graph.py](agent-service/app/graph.py) defines a single linear `StateGraph` over `CareerState` ([state.py](agent-service/app/state.py)) with these nodes in order: `resume_intelligence → job_discovery → ats_optimization → interview_prep → career_strategy → salary_intelligence → human_approval → application_tracking`. The shared `CareerState` TypedDict is the contract — each agent reads inputs from prior nodes' outputs and writes its own keys. When extending: add the node to `graph.py`, define inputs/outputs in `state.py`, place the agent under `app/agents/`.

The `human_approval` node uses `raise NodeInterrupt(...)` ([agents/human_approval.py](agent-service/app/agents/human_approval.py)) to pause the graph. The FastAPI `/runs` endpoint catches the interrupt and returns `status="interrupted"` to the backend; `/runs/resume` calls `graph.update_state(...)` + `graph.invoke(None, ...)` to continue. A conditional edge `_route_after_approval` routes `human_approval → application_tracking` on approval or `→ END` on rejection.

**Resuming a run is only valid while it's parked at `human_approval`.** [main.py](agent-service/app/main.py) checks `"human_approval" not in graph.get_state(cfg).next` and returns HTTP 409 if the run isn't currently awaiting approval; [WorkflowService.resume()](backend/src/main/java/ai/careerpilot/service/WorkflowService.java) mirrors this with its own guard (`deriveDisplayStatus(run) != "INTERRUPTED"` → `IllegalStateException` → 409) before even calling the agent. This double guard exists because resuming an already-terminal run (e.g. approving a run that was already rejected) silently corrupts its terminal state — don't remove either check independently.

State survives restarts via `PostgresSaver` (langgraph-checkpoint-postgres). `saver.setup()` in `_checkpointer()` is idempotent and creates the `checkpoints*` tables in the shared Postgres on first run — these are **not** in Flyway, they're owned by LangGraph.

### Two AI seams, no longer symmetric — read both before touching either
Each service has its own provider abstraction, and they have diverged. Do **not** assume they mirror each other.

**Java side — a multi-provider AI Gateway with health tracking and transparent failover.** [AiGatewayService.java](backend/src/main/java/ai/careerpilot/ai/AiGatewayService.java) is the single entry point for all AI in the backend; business services depend on it, never on a concrete provider. It routes each call through a configured provider order (default `deepseek,qwen,gemini`) with **automatic transparent failover**: 
- **Blocking calls** (`chat()`, `generateFeedback()`, etc.) fail over before returning if the primary provider fails.
- **Streaming calls** (`streamChat()`) fail over only *before the first token* — once tokens emit, failover is impossible (client already receiving from that provider).
- **Quota detection**: HTTP 429 errors trigger immediate failover without exhausting retries ([QuotaExceededException.java](backend/src/main/java/ai/careerpilot/ai/QuotaExceededException.java)).
- **Health tracking**: [ProviderHealthTracker.java](backend/src/main/java/ai/careerpilot/ai/ProviderHealthTracker.java) caches provider health (HEALTHY/DEGRADED/QUOTA_EXCEEDED/UNKNOWN) with 5-minute TTL to avoid repeated calls to failed providers.
- **Per-provider resilience**: Resilience4j **retry + circuit breaker** + usage metrics ([AiMetrics.java](backend/src/main/java/ai/careerpilot/ai/AiMetrics.java)).

Every provider implements [LlmProvider.java](backend/src/main/java/ai/careerpilot/ai/LlmProvider.java) (shared logic in `AbstractLlmProvider`); impls live under `ai/provider/` — `GeminiProvider`, `NvidiaDeepSeekProvider`, `NvidiaQwenProvider` (the two NVIDIA ones extend `AbstractOpenAiChatProvider` since NVIDIA's API is OpenAI-compatible). A provider only joins the chain when `isConfigured()` is true, so DeepSeek/Qwen are skipped unless `NVIDIA_API_KEY` is set. 

**Streaming provider callback**: When calling `streamChat(messages, system, providerCallback)`, pass a `Consumer<String>` to learn which provider actually served the response (useful for frontend attribution). The callback fires in the `doOnComplete()` handler, *after* the stream succeeds. This replaced ThreadLocal tracking, which didn't survive Reactor's async thread boundaries.

**Adding a provider** = one new `LlmProvider` impl + listing its key in `ai.gateway.order` — no business-logic changes.

**Python side — a single rate-limited Gemini provider.** [agent-service/app/ai_provider.py](agent-service/app/ai_provider.py) keeps the four-method contract (`generate_response`, `generate_structured_response`, `generate_json`, `estimate_cost`), but agents must obtain it via `get_ai_provider()`, which returns a `RateLimitedAIProvider` decorator wrapping `GeminiProvider`. The decorator delegates to a process-wide singleton `GeminiRateLimiter` ([rate_limiter.py](agent-service/app/rate_limiter.py)) that enforces RPM + TPM token buckets, minimum request spacing, and full-jitter retry on 429/503 — all 8 agents share one limiter. Counters are exposed at `GET /metrics`. **Never instantiate `GeminiProvider` directly in an agent** (it's intentionally retry-free) and never touch `google.generativeai` directly — go through `get_ai_provider()`.

The Python `GeminiProvider` uses `responseSchema` for structured output — every agent passes a JSON Schema and the provider calls `genai.GenerativeModel(...).generate_content(..., generation_config={"response_mime_type": "application/json", "response_schema": SCHEMA})`. Don't switch agents to free-text parsing; the schema is what keeps outputs typed.

### The Copilot is a separate, streaming AI surface (not the LangGraph workflow)
Distinct from the backend→agent-service workflow path: the backend hosts its own conversational copilot under `/api/copilot` ([CopilotController.java](backend/src/main/java/ai/careerpilot/api/CopilotController.java)), which **streams tokens over SSE** (`POST /api/copilot/stream`, `text/event-stream` via `SseEmitter`) plus `GET /conversations` and `GET /conversations/{id}/messages`. [CopilotService.java](backend/src/main/java/ai/careerpilot/service/CopilotService.java) calls `AiGatewayService.streamChat(messages, system, providerCallback)` to capture which provider served the response; the callback executes after streaming completes and stores the provider name in an `AtomicReference` that the SSE done event sends to the frontend. [AgentOrchestrator.java](backend/src/main/java/ai/careerpilot/service/AgentOrchestrator.java) builds the per-page/per-action system prompt + context block, and [ConversationMemory.java](backend/src/main/java/ai/careerpilot/service/ConversationMemory.java) persists turns via `CopilotConversationRepository`/`CopilotMessageRepository` (tables from `V2__copilot.sql`). The frontend consumes this through `lib/copilotStream.ts` + `components/copilot/CopilotPanel.tsx`. `GET /api/diagnostics/ai` and `GET /api/diagnostics/workflow` ([DiagnosticsController.java](backend/src/main/java/ai/careerpilot/api/DiagnosticsController.java)) expose gateway provider health and call/fallback metrics plus workflow engine status.

**Copilot prompts are skill-routed, not one monolithic prompt.** [CopilotSkillRouter.java](backend/src/main/java/ai/careerpilot/service/copilot/CopilotSkillRouter.java) maps each request to one of 10 `CopilotSkill` enum values — by explicit `action` if the frontend sent one, else by keyword-matching the free-text `message` (`inferSkillFromMessage`), else falling back to `GeneralAssistantHandler`. Each skill is a `CopilotSkillHandler` impl under `service/copilot/skill/` (e.g. `WorkflowExplanationHandler`, `AtsAnalysisHandler`, `ResumeAnalysisHandler`) extending `AbstractSkillHandler`, with three jobs: `assembleContext()` pulls RAG data via [CareerContextRetriever.java](backend/src/main/java/ai/careerpilot/service/CareerContextRetriever.java) into a `SkillContext`, `systemPrompt()` returns the skill-specific instructions, `contextBlock()` renders that data as the prompt's context section. Adding a new skill = new `CopilotSkill` enum value + new handler class + wiring it into `CopilotSkillRouter`'s constructor map.

### Backend → agent-service boundary
[WorkflowService.java](backend/src/main/java/ai/careerpilot/service/WorkflowService.java) is the only caller of the agent service. It owns:
- assembling the LangGraph input from a `Resume` row + `Job` rows
- calling `AgentServiceClient` (a `WebClient` wrapper, [agent/AgentServiceClient.java](backend/src/main/java/ai/careerpilot/agent/AgentServiceClient.java))
- persisting/upserting a `WorkflowRun` row keyed by the LangGraph `thread_id` on every transition
- publishing a Kafka event via `WorkflowEventProducer` on every state change
- **converting entity responses to DTOs** via `toResponse(WorkflowRun)` for proper JSON serialization

**DTO pattern for API responses**: Controller methods must return `WorkflowRunResponse` (defined in [WorkflowDtos.java](backend/src/main/java/ai/careerpilot/api/dto/WorkflowDtos.java)), not the raw `WorkflowRun` entity. The DTO uses `Map<String, Object>` for state instead of `JsonNode`, which avoids Jackson type definition errors. The service layer parses the entity's JSON state string into a Map before constructing the DTO. This pattern should be replicated for any entity with complex JSON fields.

**`deriveDisplayStatus(WorkflowRun)` is the single source of truth for lifecycle status** — it derives the per-stage agent timeline from the run's JSON `state` blob and computes the displayed status (`RUNNING`/`INTERRUPTED`/`REJECTED`/`COMPLETED`/`FAILED`) from that timeline rather than trusting the raw persisted `status` column, which can lag. Both `WorkflowController` responses and `CareerContextRetriever.getWorkflowContext()` (used by the Copilot) call this same method, so the UI and the AI assistant can never disagree about a run's state. Approval/rejection audit fields (`approved_by`/`approved_at`/`rejected_by`/`rejected_at`/`human_feedback`) are stamped into that same `state` JSON blob in `mergeResponse()` — there's no separate audit table or migration for them.

When you add a new workflow surface (e.g., re-run, branch), funnel it through this service — do not expose the agent service to the frontend.

### JWT auth carries multi-tenant context
[JwtService.java](backend/src/main/java/ai/careerpilot/security/JwtService.java) packs `userId`, `orgId`, `email`, `role` into the access token. [JwtAuthFilter.java](backend/src/main/java/ai/careerpilot/security/JwtAuthFilter.java) extracts them into an `AuthenticatedUser` record placed in the security context, and [CurrentUserResolver.java](backend/src/main/java/ai/careerpilot/security/CurrentUserResolver.java) exposes it as a controller method parameter. Multi-tenant isolation is enforced **manually** in each service via `userId.equals(entity.getUserId())` checks — there is no row-level security and no Hibernate `@Filter`. New endpoints must replicate this pattern.

`@EnableMethodSecurity` is on, but **no controller uses `@PreAuthorize`**. Anyone authenticated can hit any endpoint. Be aware when adding admin-only routes.

### Database is shared but logically partitioned
[V1__init.sql](backend/src/main/resources/db/migration/V1__init.sql) creates 9 tables owned by the backend (Flyway-managed, Hibernate runs in `validate` mode), and [V2__copilot.sql](backend/src/main/resources/db/migration/V2__copilot.sql) adds the copilot conversation/message tables. The LangGraph checkpoint tables are auto-created by `PostgresSaver.setup()` and are **not** in Flyway. If you add new tables for the backend, write the next migration (`V3__*.sql`); never modify an applied migration. `flyway.baseline-on-migrate: true` is set in `application.yml`, so Flyway will baseline against the existing Neon schema state on first boot if `flyway_schema_history` is missing — useful because V1 may have been applied manually in the Neon SQL editor.

pgvector extension is enabled and `vector(768)` columns exist on `resumes.embedding` and `jobs.embedding`, but **no code path generates embeddings**. If you wire embeddings, the `AiGatewayService` is the right seam, and you'll need an HNSW/IVFFlat index — none exist today.

### Job discovery is a global pool, layered on top of org-scoped jobs
[jobdiscovery/](backend/src/main/java/ai/careerpilot/jobdiscovery/) ingests real listings from external sources and keeps them separate from the existing org-scoped Browse/Add-job/Apply flow on the same `jobs` table — discovered rows have `org_id IS NULL` and `source`/`external_id` set; manually-added jobs have `external_id IS NULL` and are untouched by any of this. `V4__job_discovery.sql` adds the discovery columns plus `job_recommendations` and `job_fetch_audit` (applied by hand against Neon — see the migration's own header comment, same baseline-on-migrate caveat as V1).

- **Providers** (`jobdiscovery/provider/`) implement `JobProvider` (`name()`, `isConfigured()`, `fetch() -> List<RawJob>`) — same "joins the chain only if configured" convention as `LlmProvider`. `AdzunaProvider`/`JoobleProvider` are keyed (skipped without API keys); `RemoteOkProvider`/`ArbeitnowProvider` are keyless and always on. `AbstractWebJobProvider` holds the shared `WebClient` fetch/parse boilerplate.
- **`JobAggregationService.discoverAll()`** is the orchestrator: for each configured provider, fetch → `JobNormalizer.toJob()`/`.merge()` → upsert into `jobs` deduped on `(source, external_id)`, recording one `JobFetchAudit` row per provider. A provider failure is isolated (FAILED audit row, run continues) — it never throws to the caller. `JobDiscoveryScheduler` runs this daily at 06:00 (`jobs.discovery.cron`), gated by `jobs.discovery.enabled`; `JobController`'s `POST /api/jobs/discovery/run` triggers it manually and `GET /api/jobs/discovery/audit` reads the audit trail.
- **`JobMatchingService.refreshForUser()`** is a separate, rule-based (no LLM) matcher: scores the discovered pool via `JobScoring` and upserts the top 50 into `job_recommendations` so the Recommended tab is a cheap indexed read. It is **source-agnostic** — the candidate signals (skills/target role/locations/experience/preferences/excluded roles) come from **[CandidateSignalResolver.java](backend/src/main/java/ai/careerpilot/jobdiscovery/CandidateSignalResolver.java)**, which is the Phase-1 single-source-of-truth switch: when `jobs.matching.profile-source-enabled=true` and a `candidate_profiles` row exists, every signal is read from the **canonical `CandidateProfile` only**; otherwise it falls back to the legacy `WorkflowRun` state + live `candidate_preferences` path (pre-Phase-1 behavior, unchanged). Flip the flag on only **after** profiles are backfilled (`POST /api/admin/candidate-profile/backfill?dryRun=false`, admin-role + flag gated); rollback is instant (flag off). The matcher also applies a **user-defined excluded-roles hard filter** (`JobMatchingService.isRoleExcluded`, whole-word title match + non-tech family match via `JobTaxonomy`) — empty exclusions are a no-op, so it is safe always-on. Excluded roles are edited in `candidate_preferences` (`excluded_roles`, added by `V9`) and snapshotted into the profile.
- `JobController`'s `GET /api/jobs/discovered?scope=domestic|international&country=...` reads the global pool directly; `GET /api/jobs/recommended` reads the precomputed `job_recommendations`. The frontend's [Jobs.tsx](frontend/src/pages/Jobs.tsx) Domestic/International tabs call the former, separately from the existing org-scoped job list.

### Resume tailoring is a 7-stage async event pipeline, not one service
[resumetailoring/](backend/src/main/java/ai/careerpilot/resumetailoring/) implements Phase 2D: Resume Tailoring → ATS Optimization → Gap Analysis → ATS Explainability → Cover Letter → Application Package → Auto-Apply Preparation. Each stage is its own vertical slice (own sub-package, own job table, own bounded `ThreadPoolTaskExecutor`, own REST controller) chained by Spring `@TransactionalEventListener` — there is no shared orchestrator class. The chain: `RecommendationApprovedEvent → ResumeTailoringWorker → ResumeTailoredEvent → AtsOptimizationWorker → AtsOptimizedEvent → GapAnalysisWorker → GapAnalysisCompletedEvent → AtsExplainabilityWorker → AtsExplainabilityCompletedEvent → CoverLetterWorker → CoverLetterCompletedEvent → ApplicationPackageWorker → ApplicationPackageReadyEvent → AutoApplyPreparationWorker` (final stage, publishes nothing further). Events live in `resumetailoring/event/`.

**Every worker follows the identical shape** — do not deviate when adding a stage:
```java
@Async(PipelineExecutorsConfig.<STAGE>_EXECUTOR)
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
public void on<PriorEvent>(<PriorEvent> event) {
    if (!triggerEnabled || !service.isEnabled()) return;
    try {
        executor.execute(() -> service.doWork(...));
    } catch (Exception e) {
        log.warn("... worker failed ...: {}", e.toString());
    }
}
```
Two independent flags per stage (`<stage>.enabled` gates the engine itself; `<stage>.trigger....enabled` gates whether it auto-fires off the prior stage's event) — both default `false`. `PipelineExecutorsConfig` centralizes the bounded executors (one per stage, deliberately not shared, so one stage's saturated queue can't starve another's).

**Do not wrap a worker's listener body in `@Transactional(propagation = REQUIRES_NEW)`.** This was a real, previously-shipped bug (`ResumeTailoringWorker`/`AtsOptimizationWorker`): the annotation opened a second transaction around the job-row insert, and the bounded-executor thread it then handed off to could read that row back *before* the wrapping transaction committed — a silent race that stranded jobs in `QUEUED` forever with zero error logs. The `@TransactionalEventListener(phase=AFTER_COMMIT)` already guarantees the triggering transaction has committed; no worker needs its own `@Transactional`.

Each stage's job table (`resume_tailoring_jobs`, `ats_optimization_jobs`, `gap_analysis_jobs`, `ats_explainability_jobs`, `cover_letter_jobs`, `application_package_jobs`, `auto_apply_package_jobs` — migrations `V24`–`V30`) tracks `QUEUED`/`RUNNING`/`SUCCEEDED`/`FAILED` and is the only way to trace an async run; there is no synchronous fallback API for any stage. Poll a stage's status via its own `GET /api/diagnostics/<stage>` and `GET /api/diagnostics/<stage>/queue` endpoints ([DiagnosticsController.java](backend/src/main/java/ai/careerpilot/api/DiagnosticsController.java) for resume-tailoring/ATS-optimization, [PipelineDiagnosticsController.java](backend/src/main/java/ai/careerpilot/api/PipelineDiagnosticsController.java) for gap-analysis/ATS-explainability/cover-letter/application-package/auto-apply-package).

### Phase 2E — Application execution (`execution/`) plus real browser automation
[execution/](backend/src/main/java/ai/careerpilot/execution/) reuses the same `@Async` + `@TransactionalEventListener(AFTER_COMMIT)` worker pattern as resume tailoring, chained: safety validation (`execution/safety/SafetyEngine`) → mandatory human approval (`execution/approval/ApprovalService`, `POST /api/execution/approve`) → execution (`execution/execution/ApplicationExecutionService`) → retry policy (`execution/retry/RetryPolicyService`, deterministic failure-class → RETRY/PAUSE/STOP) → tracking → analytics, each its own sub-package/executor via `execution/config/ExecutionExecutorsConfig.java`.

Real browser automation lives here too: `execution/browser/GuestApplyAutomationService.java` drives Microsoft Playwright's Java API via `PlaywrightAutomationProvider`. "Guest" means unauthenticated/no-login apply forms only — eligibility is a **hardcoded allowlist** in `execution/ats/GuestApplyEligibility.java` limited to `{greenhouse, lever}` (the only ATSes with public no-login forms); every other connector under `execution/ats/connector/` is a stub. `login()` unconditionally throws — no credentials are ever stored or entered. The flow is two-phase: fill only verified real `User` fields, screenshot the unsubmitted form, queue a `FORM_SCREENSHOT` approval — only after a human approves does `finalizeSubmit()` re-navigate and actually click submit. Gated by `BROWSER_AUTOMATION_ENABLED` (default `false`); the safety/approval stage flags default to fail-closed "on" even though the execution stage itself is dark.

### Phase 3A — Workflow tracking is a second, deliberately separate tracking system
[workflow/](backend/src/main/java/ai/careerpilot/workflow/) is explicitly documented in its own code as event-driven, not a linear pipeline. `workflow/correlation/WorkflowCorrelationService` keeps one row per running Phase 3A instance, upserted on every stage transition; `WorkflowDeadLetterService` is where every worker's catch block writes instead of propagating, for audit/replay. `workflow/tracking/ApplicationLifecycleService` + `ApplicationStatusMachine` is the canonical per-(user,job) status machine — **deliberately separate from Phase 2E's execution-scoped tracking**: `application.tracking.*` (Phase 2E) and `workflow.tracking.*` (Phase 3A) are two different config namespaces tracking two different things, don't conflate them. `workflow/timeline/TimelineService` is a human-readable, confidence-scored event stream distinct from the validated status history, and `workflow/analytics/` is its own metric set, separate from Phase 2E's `execution_analytics`.

Two narrower additions in the same package family: `workflow/email/EmailIntelligenceService` is a deterministic keyword classifier that is **fully inert** — no mailbox integration exists, nothing fetches email, rows only arrive via a manual-ingest path. `workflow/interview/InterviewService` tracks interview rounds (Recruiter/Technical/System Design/Manager/HR/Final/Offer) with append-only feedback. All gated by their own `workflow.*` / `email.intelligence.*` / `interview.tracking.*` flags, default `false`.

### Phase 6 — Learning-from-outcomes exists but is deliberately disconnected from live scoring
`learning/LearningEventBridge` (same async-listener pattern) captures 2D/2E/3A domain events — application submitted/rejected/accepted, interview detected, offer received, recommendation approved — into `LearningEvent` rows. Downstream, `learning/pattern/SuccessPatternEngine` + `FailurePatternEngine` compute pattern rows, and `learning/recommendation/RecommendationWeightManager` upserts weights that `learning/recommendation/AdaptiveRecommendationEngine.getBoost(userId, dimension, key)` exposes as a read-only facade.

**Important**: that engine's own Javadoc states it is not wired into `JobScoring`/`JobMatchingService` — built end-to-end, but live job matching does not consult it. Treat it like the "Provisioned-but-unused" list below, not as an active feature. Also in this phase: `learning/resume/ResumeLearningService` (best resume version by interview/offer rate), and `learning/career/CareerLearningEngine` + `CareerStrategyEngine` — the latter is what actually populates `domain/CareerStrategy`, combining a probability engine and a trajectory analyzer into one row per user. Gated by `learning.adaptive-recommendation.enabled` / `learning.adaptive-career.enabled`, both default `false`.

### Phase 7 — Autopilot, submission orchestration, and the Application Command Center are three different patterns
- [autopilot/](backend/src/main/java/ai/careerpilot/autopilot/) (`ApplicationDecisionEngine`, `AutoApplyEngine`, an `ApplicationProvider` SPI for Ashby/Greenhouse/Lever/LinkedIn/SmartRecruiters/Taleo/Workday/CompanyPortal) is scheduler/orchestrator style (`AutopilotScheduler`, `CareerOrchestrator`), not an event chain. Gated by `application.decision.enabled` / `application.auto.enabled`.
- [submission/](backend/src/main/java/ai/careerpilot/submission/) (`ApplicationSubmissionSessionService`, `SubmissionStateMachine`) is a single orchestrator that **links by id** — never copies — into existing dormant rows from earlier stages (execution, packageintel, review, companyintel, story), spanning validation → field-mapping → question-detection → strategy → optional-approval → execution → verification → tracking → learning, publishing its own session-progress events rather than running N independent stage workers. Gated by `application.submission.*`, default `false`.
- [applications/](backend/src/main/java/ai/careerpilot/applications/) — the Application Command Center (`ApplicationCardService`, `ApplicationHealthService`, `ApplicationNextActionService`, `ApplicationRecommendationService`) — has **no flag, no worker, no event**. It's a plain synchronous, always-on read/aggregation `@Service` layer over existing `applications` data (adds `favorite`/`priority`/`archived` columns). This is the one subsystem in this whole section that is live by default, not dark-shipped.

### Company intelligence, offer intelligence, and career roadmap persistence
- `companyintel/CompanyKnowledgeService` builds a per-user company "knowledge graph" (versioned 0–100 scores, timeline, relationship edges) purely from artifacts the platform already produced — never fabricated. Gated `company.knowledge.enabled`.
- `jobdiscovery/enterprise/CompanyConnectorService` is an admin-manageable DB layer over the existing CSV-configured enterprise connectors (Workday/Taleo/SuccessFactors): CSV env vars stay the deploy-time source of truth, the DB bootstraps from CSV once and then becomes authoritative for enable/disable/health.
- `jobdiscovery/discovery/CompanyDiscoveryService` probes keyless public ATS endpoints (Greenhouse/Ashby/SmartRecruiters/Workday) for new company slugs, inserting `PENDING_APPROVAL` connector rows for admin review. Gated `company.discovery.enabled`.
- `offer/OfferAnalysisService` parses the `salary_insights` key the LangGraph `salary_intelligence` agent already writes into `WorkflowRun` state and upserts an `Offer` row (percentiles, negotiation strategy, leverage points). Gated `offer.intelligence.enabled`.
- `workflow/career/CareerRoadmapPersistenceService` parses `career_roadmap`/`skill_gaps` from the `career_strategy` agent's output into the `career_strategy` table. Gated `career.roadmap.persistence.enabled`.

Both offer analysis and roadmap persistence are real implementations, not stubs, but ship dark; `WorkflowService` calls them as try/catch-isolated additive side effects on workflow transitions — same non-throwing convention as everywhere else.

### Package validation, AI review, decision memory, and retention — four more additive layers, don't conflate them
- `packageintel/ApplicationPackageIntelligenceService` (Phase 7.11) does NOT re-assemble or re-score anything: it delegates package assembly to the existing Phase 2D.6 `ApplicationPackageService`, then binds already-computed signals from other engines (autopilot decision, company research, learning boost, resume match) onto the package, runs `ApplicationPackageValidator`, and records an immutable validation history row. Gated `application.package.validation.enabled`; fail-safe by construction — a failure records a BLOCKED validation, never a fabricated READY.
- `review/ApplicationReviewPipeline` (Phase 7.12) is the next gate on top of that: it REVIEWS the Phase 7.11 package (never mutates it) by running each enabled reviewer (`review/reviewer/` — resume/ATS/company-fit/learning/consistency/quality) and persists an `ApplicationReview` head + immutable history row. Gated `application.review.enabled`; same BLOCKED-not-fabricated fail-safety.
- `memory/CareerMemoryService` (Phase 7.15.1, "Career Decision Memory") is deliberately **not** a rebuild of `learning_event` (Phase 6, pattern-computation) or `recommendation_feedback` (Phase 2C, raw reaction log) — it normalizes both of those plus five Phase 3A workflow events (via `CareerMemoryEventBridge`) into one durable, ranked, append-only "why the candidate decided X" record the Copilot can read instead of re-asking. Gated `career.memory.enabled`. Copilot-conversation extraction is deliberately NOT wired (would need LLM-based parsing with no existing infra, and a bad extraction silently poisoning future recommendations is a correctness risk left for its own review).
- `retention/RetentionService` is a pure delete-by-age maintenance job (no LLM, no events) for the append-only ledgers that grow unbounded — workflow dead-letter/correlation tables plus the recommendation/execution/resume-tailoring audit trails. Each target has its own configurable retention window and its own `REQUIRES_NEW` transaction, so one target failing never aborts the others. Gated `retention.enabled`, default `false` (no-op with stock flags).

### Career Goal Intelligence and the Executive Decision Engine both upsert into the SAME `CareerStrategy` row
`learning/career/goal/` (Phase 7.19) adds four independently-flagged, deterministic services — `SkillGapIntelligenceService`, `PromotionReadinessService`, `CareerGoalPlannerService`, `CareerRoadmapGeneratorService` — each of which computes nothing that isn't already sitting in `job_recommendations`/`CandidateProfile`/`Interview` rows; ungrounded dimensions (of the 12 the original spec wanted, only 5 have any real data source) return `NOT_COMPUTED` with a reason rather than a guessed value. `CareerGoalEngine` is the co-writer that upserts whichever of the four are enabled onto the **existing single-row-per-user** `domain/CareerStrategy` entity (the same row Phase 6.6's `CareerStrategyEngine` and Gap C's `CareerRoadmapPersistenceService` already write into) — never a parallel "strategy" table.

`learning/career/executive/ExecutiveDecisionEngine` (Phase 7.19.5) sits one layer up and **computes nothing at all** — it only reads the four services above plus `JobRecommendation`/`InterviewRepository`/`CareerStrategy.careerSuccessProbability` and turns them into a small evidence-backed decision list (Apply Now / Wait Before Applying / Study Next / Prepare Interview / Switch Goal). Every decision type without a real evidence chain in this platform (negotiate offer, request referral, geography focus, etc.) is explicitly listed as omitted with a reason, never fabricated. `DailyCareerSummaryGenerator` (see Daily Brief below) attaches this engine's output onto the *same* `daily_career_summary` row rather than a rival brief pipeline. Gated `career.skill-gap.enabled` / `career.promotion-readiness.enabled` / `career.goal-planner.enabled` / `career.roadmap-generator.enabled` / `executive.decision.enabled`, all default `false`, all inline `@Value` (no `application.yml` entries).

### Daily Brief already exists, dark by default — don't re-build it
`dailydiscovery/DailyJobDiscoveryScheduler` runs a separate 02:00 cron from the existing 06:00 `jobs.discovery.*` scheduler, driving `DailyJobDiscoveryCoordinator` → `DailyJobDiscoveryService` (computes a per-user snapshot: recommended/must-apply/high-priority/human-review counts, top companies/skills) → `DailyCareerSummaryGenerator` (rewrites the deterministic snapshot into a 4–6 sentence summary via `AiGatewayService`, falling back to the raw template on any AI failure — every number is computed, never invented). REST surface: `dailydiscovery/api/DailyDiscoveryController` (`GET /api/daily-discovery/summary`, `GET /api/daily-discovery/analytics`, `POST /api/daily-discovery/run` manual trigger). Gated by `career.discovery.scheduler.enabled` / `career.discovery.summary.enabled`, both `false`.

`story/StarStoryEngine` (gated `story.engine.enabled`) is the same dark-by-default shape for STAR-method interview story extraction/generation/quality-evaluation.

### Frontend data flow
[lib/api.ts](frontend/src/lib/api.ts) is a single axios instance with a bearer-token request interceptor (reading from the zustand store in [lib/auth.ts](frontend/src/lib/auth.ts)) and a 401-→-logout response interceptor. Auth state persists to localStorage via zustand's `persist` middleware. Server state uses TanStack Query — refetches are explicit (`queryClient.invalidateQueries`) after mutations; there is no SSE/WebSocket, so the Workflow page only updates on user action.

## Configuration that affects behavior

| Env var | Effect |
|---|---|
| `GEMINI_API_KEY` | Required at agent-service startup; `GeminiProvider.__init__` raises if empty |
| `JWT_SECRET` | Required ≥32 chars; `JwtService.init()` refuses to start otherwise |
| `AI_MODEL` | Defaults to `gemini-2.5-pro`; change to `gemini-2.5-flash` for cheaper/faster runs |
| `AI_PROVIDER_ORDER` | Comma-separated list of provider names for failover chain; default `deepseek,qwen,gemini` |
| `PRIMARY_PROVIDER` | Display name for primary provider (e.g., `deepseek`) — used in health endpoints and logs |
| `NVIDIA_API_KEY` | NVIDIA NIM API key; required if DeepSeek/Qwen in chain. Set to empty string to skip NVIDIA providers |
| `NVIDIA_BASE_URL` | NVIDIA NIM base URL; typically `https://integrate.api.nvidia.com/v1` |
| `NVIDIA_DEEPSEEK_MODEL` | DeepSeek model name; e.g., `nvidia/deepseek-r1` |
| `NVIDIA_QWEN_MODEL` | Qwen model name; e.g., `nvidia/qwen2.5-72b-instruct` |
| `AGENT_SERVICE_URL` | Backend → agent-service base URL |
| `DATABASE_URL` (JDBC) and `DATABASE_URL_PY` (libpq) | Same DB, two URL forms — keep them in sync |
| `CANDIDATE_PROFILE_ENABLED` | Gates Candidate Profile generation, the `/api/candidate-profile*` endpoints, and the admin backfill. Default `false` (ships dark) |
| `JOBS_MATCHING_PROFILE_SOURCE_ENABLED` | When `true`, `JobMatchingService` reads candidate signals from the canonical `CandidateProfile` (single source of truth) instead of the legacy `WorkflowRun`+`candidate_preferences` path. Default `false`. Flip on **after** backfill |
| `CANDIDATE_PROFILE_BACKFILL_THROTTLE_MS` | Pause between LLM extractions during the one-time enablement backfill. Default `500` |
| `RESUME_TAILORING_ENABLED` / `RESUME_TAILORING_TRIGGER_ON_APPROVE_ENABLED` | Phase 2D.1: enables the tailoring engine / auto-fires it when a recommendation is approved. Default `false` |
| `ATS_OPTIMIZATION_ENABLED` / `ATS_OPTIMIZATION_TRIGGER_ON_TAILORING_ENABLED` | Phase 2D.2. Default `false` |
| `GAP_ANALYSIS_ENABLED` / `GAP_ANALYSIS_TRIGGER_ENABLED` | Phase 2D.3. Default `false` |
| `ATS_EXPLAINABILITY_ENABLED` / `ATS_EXPLAINABILITY_TRIGGER_ENABLED` | Phase 2D.4. Default `false` |
| `COVER_LETTER_ENABLED` / `COVER_LETTER_TRIGGER_ENABLED` | Phase 2D.5. Default `false` |
| `APPLICATION_PACKAGE_ENABLED` / `APPLICATION_PACKAGE_TRIGGER_ENABLED` | Phase 2D.6. Default `false` |
| `AUTO_APPLY_PACKAGE_ENABLED` / `AUTO_APPLY_TRIGGER_ENABLED` | Phase 2D.7, final stage. Default `false` |

Every Phase 2D stage above ships dark (all flags `false`) and is meant to be canaried one at a time — flip `<stage>_ENABLED` first, verify via its diagnostics endpoint, then flip `<stage>_TRIGGER...ENABLED` to chain it onto the prior stage's event.

The Phase 2E–7 flags below follow the same dark-ship convention, but most gate on an inline `@Value("${...:false}")` default with **no corresponding `application.yml` entry** — unlike the fully-wired Phase 2D flags above, enabling them means adding the property yourself, not just setting an env var Spring already binds.

| Property | Effect |
|---|---|
| `BROWSER_AUTOMATION_ENABLED` | Enables real Playwright-driven guest-apply automation (Greenhouse/Lever only); requires human approval of a form screenshot before actual submit |
| `career.discovery.scheduler.enabled` / `career.discovery.summary.enabled` | Daily Brief: 02:00 discovery snapshot + AI-rewritten morning summary |
| `learning.adaptive-recommendation.enabled` | Enables the outcome-learning engine — note `getBoost()` still isn't consulted by live matching even when on |
| `learning.adaptive-career.enabled` | Populates `CareerStrategy` via `CareerStrategyEngine` |
| `offer.intelligence.enabled` / `career.roadmap.persistence.enabled` | Persist salary-intelligence and career-roadmap agent output instead of discarding it |
| `company.knowledge.enabled` / `company.discovery.enabled` | Company knowledge graph + keyless-endpoint company discovery (admin-approval queue) |

## Provisioned-but-unused (do not assume these are integrated)

Knowing what is *not* wired prevents wasted debugging:
- `refresh_tokens` table — no `/api/auth/refresh` endpoint exists
- `audit_logs` table — no code writes to it
- `usage_records` table — no code writes to it; cost tracking is not aggregated
- `embedding` vector columns — no embedding generation anywhere (pgvector extension is installed, but no HNSW/IVFFlat indexes)
- Redis — `spring-boot-starter-data-redis` on the classpath, no `@Cacheable` / `RedisTemplate` usage
- `careerpilot.audit.events` topic — declared in config, neither produced nor consumed
- `security.rate-limit.*` values — read by no limiter (no Bucket4j / RedisRateLimiter)
- `@KafkaListener` — zero consumers exist; producer events go nowhere
- `refresh_tokens` table — exists but no refresh endpoint wired
- `AdaptiveRecommendationEngine.getBoost()` (`learning/recommendation/`) — computed and persisted end-to-end, but not called by `JobScoring`/`JobMatchingService`; live job matching does not use outcome-based learning today despite the pipeline existing

When in doubt, grep for the symbol — if it has no callers, it is scaffolding.

## Diagnostics and Monitoring

[DiagnosticsController.java](backend/src/main/java/ai/careerpilot/api/DiagnosticsController.java) exposes two public endpoints (no auth required) for troubleshooting:

- **`GET /api/diagnostics/ai`** — Gateway diagnostics: API keys loaded, configured models, base URLs, provider health (UP/DOWN/NOT_CONFIGURED), provider order, call stats (total calls, fallbacks, failures per provider), default temperature.
- **`GET /api/diagnostics/workflow`** — Workflow engine diagnostics: workflowEngine/jsonSerialization/agentService status, plus provider chain health. Used to validate that all three services and the provider chain are operational after deployment.

These endpoints are **not guarded by auth** (anyone can call them) to enable uptime monitoring without needing credentials.

Most later phases ship their own narrower diagnostics controller rather than extending `DiagnosticsController` — e.g. `ExecutionDiagnosticsController` (execution/recovery/operations), `PipelineDiagnosticsController` (gap-analysis/ATS-explainability/cover-letter/application-package/auto-apply-package). Before adding a new admin/ops surface, check whether it belongs on one of these existing controllers instead of a new one — `frontend/src/pages/OperationsCenter.tsx` (admin-only route) already aggregates execution/retry/recovery data this way and is the pattern to extend, not duplicate, for any future ops dashboard work.

## Conventions

- Java package root is `ai.careerpilot`. Sub-packages are role-based: `api` (controllers + DTOs), `service`, `repo`, `domain` (JPA entities), `security`, `kafka`, `storage`, `ai`, `agent`, `config`.
- Python agents live one-file-per-agent under `agent-service/app/agents/`. Each exports a single `<name>_node(state) -> dict` function; the dict is shallow-merged into `CareerState` by LangGraph.
- New backend endpoints accept `AuthenticatedUser` as a method parameter (see any existing controller) — this is how you get `userId`/`orgId`.
- Frontend pages live under `src/pages/`, one file each. Routes are registered in [App.tsx](frontend/src/App.tsx) inside the `<Private>`-guarded `<Layout>`.
