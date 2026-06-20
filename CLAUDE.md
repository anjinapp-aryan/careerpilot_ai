# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project shape

CareerPilot AI is a three-service monorepo: a **Spring Boot 4 / Java 25 backend** (`backend/`) acts as the control plane, a **Python FastAPI + LangGraph 0.2 agent service** (`agent-service/`) hosts the multi-agent workflow, and a **React 18 + Vite + TS frontend** (`frontend/`) is the UI. They share **one PostgreSQL** database (currently a **Neon serverless** instance, external to docker-compose — see `.env`) with the pgvector extension — backend persists domain data, agent-service persists LangGraph checkpoints into the same DB. Redis, Kafka, and MinIO/S3 are provisioned locally in `docker-compose.yml`.

This is a phase-1 vertical slice. The skeleton runs end-to-end, but several tables and beans are intentionally provisioned-but-unwired (see "Provisioned-but-unused" below) — do not treat their presence as evidence they are integrated.

To **launch and smoke-test the whole stack from scratch**, use the `run-careerpilot-ai` skill ([.claude/skills/run-careerpilot-ai/SKILL.md](.claude/skills/run-careerpilot-ai/SKILL.md)): `docker compose --env-file .env up -d`, then drive it with `node .claude/skills/run-careerpilot-ai/driver.mjs --e2e` — a zero-dependency HTTP harness that probes all three services and runs a real register→login→dashboard→create-job flow against the live backend + Neon.

## Commands

### Run the whole stack (preferred)
```bash
cp .env.example .env
# Set: JWT_SECRET (>=32 chars), GEMINI_API_KEY, and your Neon DATABASE_URL/DATABASE_URL_PY
docker compose --env-file .env up --build
```
Frontend on http://localhost:5173, backend on http://localhost:8080 (Swagger at `/swagger-ui.html`), agent service on http://localhost:8088 (`/docs`), MinIO console on http://localhost:9001.

**Postgres lives outside docker-compose** (Neon serverless). The two `DATABASE_URL` forms in `.env` must use Neon's **direct** endpoint (no `-pooler` in hostname) — Flyway DDL and the LangGraph `PostgresSaver` both rely on prepared statements that break under Neon's transaction-mode pgBouncer pooler. Going back to a local Postgres container is documented as a comment block at the top of `.env.example`.

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
- Backend: **no tests yet**. `spring-boot-starter-test` + `spring-security-test` are on the classpath ([backend/pom.xml](backend/pom.xml)) — run with `mvn test`, single test with `mvn -Dtest=ClassName#method test`.
- Frontend: **no tests yet**; vitest is the natural fit given the Vite toolchain. There is also **no lint step configured** — the only `npm` scripts are `dev`, `build`, and `preview` (no `lint`, no eslint config).

## Architecture — the things you need to read multiple files to see

### The LangGraph workflow is the heart of the product
[agent-service/app/graph.py](agent-service/app/graph.py) defines a single linear `StateGraph` over `CareerState` ([state.py](agent-service/app/state.py)) with these nodes in order: `resume_intelligence → job_discovery → ats_optimization → interview_prep → career_strategy → salary_intelligence → human_approval → application_tracking`. The shared `CareerState` TypedDict is the contract — each agent reads inputs from prior nodes' outputs and writes its own keys. When extending: add the node to `graph.py`, define inputs/outputs in `state.py`, place the agent under `app/agents/`.

The `human_approval` node uses `raise NodeInterrupt(...)` ([agents/human_approval.py](agent-service/app/agents/human_approval.py)) to pause the graph. The FastAPI `/runs` endpoint catches the interrupt and returns `status="interrupted"` to the backend; `/runs/resume` calls `graph.update_state(...)` + `graph.invoke(None, ...)` to continue. There are **no conditional edges** — a rejected approval still runs `application_tracking`. If you fix this, you need an `add_conditional_edges` from `human_approval`.

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

### Backend → agent-service boundary
[WorkflowService.java](backend/src/main/java/ai/careerpilot/service/WorkflowService.java) is the only caller of the agent service. It owns:
- assembling the LangGraph input from a `Resume` row + `Job` rows
- calling `AgentServiceClient` (a `WebClient` wrapper, [agent/AgentServiceClient.java](backend/src/main/java/ai/careerpilot/agent/AgentServiceClient.java))
- persisting/upserting a `WorkflowRun` row keyed by the LangGraph `thread_id` on every transition
- publishing a Kafka event via `WorkflowEventProducer` on every state change
- **converting entity responses to DTOs** via `toResponse(WorkflowRun)` for proper JSON serialization

**DTO pattern for API responses**: Controller methods must return `WorkflowRunResponse` (defined in [WorkflowDtos.java](backend/src/main/java/ai/careerpilot/api/dto/WorkflowDtos.java)), not the raw `WorkflowRun` entity. The DTO uses `Map<String, Object>` for state instead of `JsonNode`, which avoids Jackson type definition errors. The service layer parses the entity's JSON state string into a Map before constructing the DTO. This pattern should be replicated for any entity with complex JSON fields.

When you add a new workflow surface (e.g., re-run, branch), funnel it through this service — do not expose the agent service to the frontend.

### JWT auth carries multi-tenant context
[JwtService.java](backend/src/main/java/ai/careerpilot/security/JwtService.java) packs `userId`, `orgId`, `email`, `role` into the access token. [JwtAuthFilter.java](backend/src/main/java/ai/careerpilot/security/JwtAuthFilter.java) extracts them into an `AuthenticatedUser` record placed in the security context, and [CurrentUserResolver.java](backend/src/main/java/ai/careerpilot/security/CurrentUserResolver.java) exposes it as a controller method parameter. Multi-tenant isolation is enforced **manually** in each service via `userId.equals(entity.getUserId())` checks — there is no row-level security and no Hibernate `@Filter`. New endpoints must replicate this pattern.

`@EnableMethodSecurity` is on, but **no controller uses `@PreAuthorize`**. Anyone authenticated can hit any endpoint. Be aware when adding admin-only routes.

### Database is shared but logically partitioned
[V1__init.sql](backend/src/main/resources/db/migration/V1__init.sql) creates 9 tables owned by the backend (Flyway-managed, Hibernate runs in `validate` mode), and [V2__copilot.sql](backend/src/main/resources/db/migration/V2__copilot.sql) adds the copilot conversation/message tables. The LangGraph checkpoint tables are auto-created by `PostgresSaver.setup()` and are **not** in Flyway. If you add new tables for the backend, write the next migration (`V3__*.sql`); never modify an applied migration. `flyway.baseline-on-migrate: true` is set in `application.yml`, so Flyway will baseline against the existing Neon schema state on first boot if `flyway_schema_history` is missing — useful because V1 may have been applied manually in the Neon SQL editor.

pgvector extension is enabled and `vector(768)` columns exist on `resumes.embedding` and `jobs.embedding`, but **no code path generates embeddings**. If you wire embeddings, the `AiGatewayService` is the right seam, and you'll need an HNSW/IVFFlat index — none exist today.

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

When in doubt, grep for the symbol — if it has no callers, it is scaffolding.

## Diagnostics and Monitoring

[DiagnosticsController.java](backend/src/main/java/ai/careerpilot/api/DiagnosticsController.java) exposes two public endpoints (no auth required) for troubleshooting:

- **`GET /api/diagnostics/ai`** — Gateway diagnostics: API keys loaded, configured models, base URLs, provider health (UP/DOWN/NOT_CONFIGURED), provider order, call stats (total calls, fallbacks, failures per provider), default temperature.
- **`GET /api/diagnostics/workflow`** — Workflow engine diagnostics: workflowEngine/jsonSerialization/agentService status, plus provider chain health. Used to validate that all three services and the provider chain are operational after deployment.

These endpoints are **not guarded by auth** (anyone can call them) to enable uptime monitoring without needing credentials.

## Conventions

- Java package root is `ai.careerpilot`. Sub-packages are role-based: `api` (controllers + DTOs), `service`, `repo`, `domain` (JPA entities), `security`, `kafka`, `storage`, `ai`, `agent`, `config`.
- Python agents live one-file-per-agent under `agent-service/app/agents/`. Each exports a single `<name>_node(state) -> dict` function; the dict is shallow-merged into `CareerState` by LangGraph.
- New backend endpoints accept `AuthenticatedUser` as a method parameter (see any existing controller) — this is how you get `userId`/`orgId`.
- Frontend pages live under `src/pages/`, one file each. Routes are registered in [App.tsx](frontend/src/App.tsx) inside the `<Private>`-guarded `<Layout>`.
