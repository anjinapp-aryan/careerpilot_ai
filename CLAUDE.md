# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project shape

CareerPilot AI is a three-service monorepo: a **Spring Boot 3 / Java 21 backend** (`backend/`) acts as the control plane, a **Python FastAPI + LangGraph 0.2 agent service** (`agent-service/`) hosts the multi-agent workflow, and a **React 18 + Vite + TS frontend** (`frontend/`) is the UI. They share **one PostgreSQL** database (currently a **Neon serverless** instance, external to docker-compose — see `.env`) with the pgvector extension — backend persists domain data, agent-service persists LangGraph checkpoints into the same DB. Redis, Kafka, and MinIO/S3 are provisioned locally in `docker-compose.yml`.

This is a phase-1 vertical slice. The skeleton runs end-to-end, but several tables and beans are intentionally provisioned-but-unwired (see "Provisioned-but-unused" below) — do not treat their presence as evidence they are integrated.

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
| backend | `./mvnw spring-boot:run` (needs Postgres+Kafka+Redis+MinIO running) |
| agent-service | `pip install -r requirements.txt && uvicorn app.main:app --reload --port 8088` |
| frontend | `npm install && npm run dev` |

### Build / package
- Backend jar: `cd backend && ./mvnw -DskipTests package` (Dockerfile does this in stage 1)
- Frontend bundle: `cd frontend && npm run build`

### Tests
**No test suites exist yet** in any service. If you add tests, follow the conventions of the framework already on the classpath:
- Backend: `spring-boot-starter-test` + `spring-security-test` are already declared in [backend/pom.xml](backend/pom.xml). Run with `./mvnw test`, single test with `./mvnw -Dtest=ClassName#method test`.
- Agent service: nothing wired; pytest is the natural fit.
- Frontend: nothing wired; vitest is the natural fit given the Vite toolchain.

## Architecture — the things you need to read multiple files to see

### The LangGraph workflow is the heart of the product
[agent-service/app/graph.py](agent-service/app/graph.py) defines a single linear `StateGraph` over `CareerState` ([state.py](agent-service/app/state.py)) with these nodes in order: `resume_intelligence → job_discovery → ats_optimization → interview_prep → career_strategy → salary_intelligence → human_approval → application_tracking`. The shared `CareerState` TypedDict is the contract — each agent reads inputs from prior nodes' outputs and writes its own keys. When extending: add the node to `graph.py`, define inputs/outputs in `state.py`, place the agent under `app/agents/`.

The `human_approval` node uses `raise NodeInterrupt(...)` ([agents/human_approval.py](agent-service/app/agents/human_approval.py)) to pause the graph. The FastAPI `/runs` endpoint catches the interrupt and returns `status="interrupted"` to the backend; `/runs/resume` calls `graph.update_state(...)` + `graph.invoke(None, ...)` to continue. There are **no conditional edges** — a rejected approval still runs `application_tracking`. If you fix this, you need an `add_conditional_edges` from `human_approval`.

State survives restarts via `PostgresSaver` (langgraph-checkpoint-postgres). `saver.setup()` in `_checkpointer()` is idempotent and creates the `checkpoints*` tables in the shared Postgres on first run — these are **not** in Flyway, they're owned by LangGraph.

### The two AIProvider abstractions are deliberate and parallel
Both Java ([backend/src/main/java/ai/careerpilot/ai/AIProvider.java](backend/src/main/java/ai/careerpilot/ai/AIProvider.java) + `GeminiProvider`) and Python ([agent-service/app/ai_provider.py](agent-service/app/ai_provider.py)) implement the same four-method contract (`generateResponse`, `generateStructuredResponse`, `generateJson`, `estimateCost`). Today only the Python side is exercised — the Java `GeminiProvider` is registered as a Spring bean but no service injects it. Keep this seam intact when adding providers; do **not** let agents touch `google.generativeai` directly.

The Python `GeminiProvider` uses `responseSchema` for structured output — every agent passes a JSON Schema and the provider calls `genai.GenerativeModel(...).generate_content(..., generation_config={"response_mime_type": "application/json", "response_schema": SCHEMA})`. Don't switch agents to free-text parsing; the schema is what keeps outputs typed.

### Backend → agent-service boundary
[WorkflowService.java](backend/src/main/java/ai/careerpilot/service/WorkflowService.java) is the only caller of the agent service. It owns:
- assembling the LangGraph input from a `Resume` row + `Job` rows
- calling `AgentServiceClient` (a `WebClient` wrapper, [agent/AgentServiceClient.java](backend/src/main/java/ai/careerpilot/agent/AgentServiceClient.java))
- persisting/upserting a `WorkflowRun` row keyed by the LangGraph `thread_id` on every transition
- publishing a Kafka event via `WorkflowEventProducer` on every state change

When you add a new workflow surface (e.g., re-run, branch), funnel it through this service — do not expose the agent service to the frontend.

### JWT auth carries multi-tenant context
[JwtService.java](backend/src/main/java/ai/careerpilot/security/JwtService.java) packs `userId`, `orgId`, `email`, `role` into the access token. [JwtAuthFilter.java](backend/src/main/java/ai/careerpilot/security/JwtAuthFilter.java) extracts them into an `AuthenticatedUser` record placed in the security context, and [CurrentUserResolver.java](backend/src/main/java/ai/careerpilot/security/CurrentUserResolver.java) exposes it as a controller method parameter. Multi-tenant isolation is enforced **manually** in each service via `userId.equals(entity.getUserId())` checks — there is no row-level security and no Hibernate `@Filter`. New endpoints must replicate this pattern.

`@EnableMethodSecurity` is on, but **no controller uses `@PreAuthorize`**. Anyone authenticated can hit any endpoint. Be aware when adding admin-only routes.

### Database is shared but logically partitioned
[V1__init.sql](backend/src/main/resources/db/migration/V1__init.sql) creates 9 tables owned by the backend (Flyway-managed, Hibernate runs in `validate` mode). The LangGraph checkpoint tables are auto-created by `PostgresSaver.setup()` and are **not** in Flyway. If you add new tables for the backend, write a new Flyway migration (`V2__*.sql`); never modify V1. `flyway.baseline-on-migrate: true` is set in `application.yml`, so Flyway will baseline against the existing Neon schema state on first boot if `flyway_schema_history` is missing — useful because V1 may have been applied manually in the Neon SQL editor.

pgvector extension is enabled and `vector(768)` columns exist on `resumes.embedding` and `jobs.embedding`, but **no code path generates embeddings**. If you wire embeddings, the Java `AIProvider` is the right seam, and you'll need an HNSW/IVFFlat index — none exist today.

### Frontend data flow
[lib/api.ts](frontend/src/lib/api.ts) is a single axios instance with a bearer-token request interceptor (reading from the zustand store in [lib/auth.ts](frontend/src/lib/auth.ts)) and a 401-→-logout response interceptor. Auth state persists to localStorage via zustand's `persist` middleware. Server state uses TanStack Query — refetches are explicit (`queryClient.invalidateQueries`) after mutations; there is no SSE/WebSocket, so the Workflow page only updates on user action.

## Configuration that affects behavior

| Env var | Effect |
|---|---|
| `GEMINI_API_KEY` | Required at agent-service startup; `GeminiProvider.__init__` raises if empty |
| `JWT_SECRET` | Required ≥32 chars; `JwtService.init()` refuses to start otherwise |
| `AI_MODEL` | Defaults to `gemini-2.5-pro`; change to `gemini-2.5-flash` for cheaper/faster runs |
| `AI_PROVIDER` | Selects which `AIProvider` impl Spring instantiates (`@ConditionalOnProperty` on `GeminiProvider`) |
| `AGENT_SERVICE_URL` | Backend → agent-service base URL |
| `DATABASE_URL` (JDBC) and `DATABASE_URL_PY` (libpq) | Same DB, two URL forms — keep them in sync |

## Provisioned-but-unused (do not assume these are integrated)

Knowing what is *not* wired prevents wasted debugging:
- `refresh_tokens` table — no `/api/auth/refresh` endpoint exists
- `audit_logs` table — no code writes to it
- `usage_records` table — no code writes to it; cost tracking is not aggregated
- `embedding` vector columns — no embedding generation anywhere
- Java `GeminiProvider` — bean exists, no injection site
- Redis — `spring-boot-starter-data-redis` on the classpath, no `@Cacheable` / `RedisTemplate` usage
- `careerpilot.audit.events` topic — declared in config, neither produced nor consumed
- `security.rate-limit.*` values — read by no limiter (no Bucket4j / RedisRateLimiter)
- `@KafkaListener` — zero consumers exist; producer events go nowhere

When in doubt, grep for the symbol — if it has no callers, it is scaffolding.

## Conventions

- Java package root is `ai.careerpilot`. Sub-packages are role-based: `api` (controllers + DTOs), `service`, `repo`, `domain` (JPA entities), `security`, `kafka`, `storage`, `ai`, `agent`, `config`.
- Python agents live one-file-per-agent under `agent-service/app/agents/`. Each exports a single `<name>_node(state) -> dict` function; the dict is shallow-merged into `CareerState` by LangGraph.
- New backend endpoints accept `AuthenticatedUser` as a method parameter (see any existing controller) — this is how you get `userId`/`orgId`.
- Frontend pages live under `src/pages/`, one file each. Routes are registered in [App.tsx](frontend/src/App.tsx) inside the `<Private>`-guarded `<Layout>`.
