# CareerPilot AI

Agentic AI Career Operating System. Multi-agent platform built on LangGraph (Python) with a Spring Boot (Java 25) control plane, React/TypeScript frontend, Postgres+pgvector, Redis, and S3-compatible storage.

## What's in this vertical slice

This is **phase 1 of the build**: a runnable end-to-end skeleton with the architectural shape complete and 3 agents (Resume Intelligence, Job Discovery, ATS Optimization) doing real Gemini work, plus 5 more agents wired into the LangGraph state machine (Interview Prep, Career Strategy, Salary Intelligence, Human Approval HITL, Application Tracking) — every node calls Gemini through the AIProvider abstraction.

### What works end-to-end
- Multi-tenant signup (organization + user + free subscription)
- JWT login, RBAC, JWT-secured REST API
- Resume upload to S3/MinIO + Tika text extraction + persistence
- Job CRUD + search
- Application CRUD with status pipeline
- LangGraph 8-agent workflow with Postgres checkpointing and human-in-the-loop interrupt
- Dashboard aggregating Career Health / Resume / ATS / Match / Interview / Offer scores

### Phases still to build (next turns)
- Phase 10: AWS deployment (Terraform/CDK, RDS, ElastiCache, MSK, ECR, ECS Fargate)
- Phase 11: Kubernetes manifests + Helm chart + HPA
- Phase 12: Production hardening — rate limiting, OWASP, audit log enrichment, billing integration (Stripe), admin console UI

---

## Architecture

```
                       ┌──────────────────────┐
   Browser  ──────►    │  React + Vite (5173) │
                       └──────────┬───────────┘
                                  │ JWT REST
                                  ▼
                       ┌──────────────────────┐
                       │ Spring Boot (8080)   │
                       │ Auth · Resume · Jobs │
                       │ Apps · Dashboard     │
                       │ Workflow controller  │
                       └──┬─────────┬─────────┘
                          │         │
                ┌─────────┘         └────────────┐
                ▼                                ▼
         ┌──────────────────┐            ┌──────────────────────┐
         │  Neon Postgres   │◄─checkpoint─┤ Agent service (8088) │
         │ (cloud, pgvector)│             │ FastAPI + LangGraph  │
         └──────────────────┘             │  8 agents · Gemini   │
                                          └──────────────────────┘
        Redis · MinIO/S3 (all local in docker-compose)
```

LangGraph runs in its own Python service. The Java backend calls it over HTTP; LangGraph persists workflow state with `PostgresSaver` in the shared Postgres so runs can be paused, resumed, and audited.

### AIProvider abstraction
Both the Java backend (`ai.careerpilot.ai.AIProvider` → `GeminiProvider`) and the Python agent service (`app.ai_provider.AIProvider` → `GeminiProvider`) implement the same contract: `generate_response`, `generate_structured_response`, `generate_json`, `estimate_cost`. Agents never touch the Gemini SDK directly. Adding `OpenAIProvider` or `ClaudeProvider` later is a single-file change behind an `ai.provider` config flag.

---

## Run locally with Docker

Requirements: Docker Desktop, a free Neon Postgres database, a free Gemini API key. Java/Node/Python are not needed on the host.

### One-time setup
1. **Postgres** — sign up at https://neon.tech (free tier), create a project, copy the connection string from *Connection Details*. In the Neon SQL editor, enable pgvector:
   ```sql
   CREATE EXTENSION IF NOT EXISTS vector;
   ```
2. **Gemini API key** — get one free at https://aistudio.google.com/apikey.
3. **`.env`**:
   ```bash
   cp .env.example .env
   ```
   Then edit `.env` and set:
   - `JWT_SECRET` (≥32 chars, e.g. `openssl rand -hex 48`)
   - `GEMINI_API_KEY`
   - `POSTGRES_USER`, `POSTGRES_PASSWORD`, `DATABASE_URL`, `DATABASE_URL_PY` — derived from your Neon string. Use the **direct** endpoint (drop `-pooler` from the hostname); Flyway DDL and the LangGraph checkpointer break under Neon's transaction pooler.

### Launch
```bash
docker compose --env-file .env up --build
```

This brings up: `redis`, `minio`, `agent-service`, `backend`, `frontend`. Postgres is **not** in the compose stack — the backend connects directly to Neon. On first boot Flyway baselines against whatever schema state Neon is in; if you applied [V1__init.sql](backend/src/main/resources/db/migration/V1__init.sql) manually first, Flyway sees the tables and creates a baseline row at v1 instead of re-running the DDL.

Once everything is healthy:
- Frontend: http://localhost:5173
- Backend API + Swagger: http://localhost:8080/swagger-ui.html
- Agent service: http://localhost:8088/docs
- MinIO console: http://localhost:9001 (minioadmin / minioadmin)

To go back to a local Postgres container instead of Neon, the recipe is in the comment block at the top of [.env.example](.env.example).

### Smoke test
1. Open http://localhost:5173, register an account.
2. Upload a resume on the Resumes tab.
3. Add 1–2 jobs on the Jobs tab. Copy their IDs.
4. On the AI Workflow tab, paste the resume ID and the job IDs, click "Start workflow".
5. The run will pause at the Human Approval node — approve or reject it. The remaining nodes execute, and Dashboard updates with all six scores.

### Run pieces individually (no Docker)

Backend:
```bash
cd backend
./mvnw spring-boot:run
```

Agent service:
```bash
cd agent-service
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8088
```

Frontend:
```bash
cd frontend
npm install
npm run dev
```

---

## Project layout

```
careerpilot_ai/
├── backend/                 Spring Boot 4 · Java 25
│   ├── src/main/java/ai/careerpilot/
│   │   ├── CareerPilotApplication.java
│   │   ├── ai/              AIProvider + GeminiProvider (JVM)
│   │   ├── agent/           HTTP client to Python agent service
│   │   ├── api/             REST controllers + DTOs + exception handler
│   │   ├── config/          Web MVC config
│   │   ├── domain/          JPA entities (User, Org, Subscription, …)
│   │   ├── repo/            Spring Data JPA repositories
│   │   ├── security/        JWT filter + service + config
│   │   ├── service/         AuthService, ResumeService, WorkflowService, …
│   │   └── storage/         S3 client + service
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/V1__init.sql  (Flyway, pgvector enabled)
│   ├── Dockerfile
│   └── pom.xml
│
├── agent-service/           Python · FastAPI · LangGraph 0.2
│   ├── app/
│   │   ├── main.py          /runs · /runs/resume · /runs/{id}
│   │   ├── graph.py         StateGraph + PostgresSaver
│   │   ├── state.py         Typed shared state
│   │   ├── ai_provider.py   AIProvider abstraction + GeminiProvider
│   │   ├── config.py
│   │   └── agents/
│   │       ├── resume_intelligence.py
│   │       ├── job_discovery.py
│   │       ├── ats_optimization.py
│   │       ├── interview_prep.py
│   │       ├── career_strategy.py
│   │       ├── salary_intelligence.py
│   │       ├── human_approval.py        (NodeInterrupt HITL)
│   │       └── application_tracking.py
│   ├── requirements.txt
│   └── Dockerfile
│
├── frontend/                React 18 · Vite · TS · Tailwind · TanStack Query
│   ├── src/
│   │   ├── App.tsx          router + private routes
│   │   ├── main.tsx
│   │   ├── components/      Layout · ScoreCard
│   │   ├── pages/           Login · Register · Dashboard · Resumes · Jobs · Applications · Workflow
│   │   └── lib/             api (axios) · auth (zustand)
│   ├── package.json
│   ├── vite.config.ts
│   ├── tailwind.config.js
│   ├── nginx.conf
│   └── Dockerfile
│
├── docker-compose.yml       postgres+pgvector · redis · minio · backend · agent-service · frontend
├── .env.example
└── README.md
```

---

## REST API (current surface)

| Method | Path                                | Auth | Purpose                                  |
|-------:|-------------------------------------|:----:|------------------------------------------|
| POST   | `/api/auth/register`                |  —   | Create org + owner user; returns JWT     |
| POST   | `/api/auth/login`                   |  —   | Exchange credentials for JWT             |
| GET    | `/api/dashboard`                    |  ✓   | Aggregated career-health snapshot        |
| POST   | `/api/resumes` (multipart)          |  ✓   | Upload + parse resume                    |
| GET    | `/api/resumes`                      |  ✓   | List user's resumes                      |
| GET    | `/api/jobs?q=`                      |  ✓   | Paged job search                         |
| POST   | `/api/jobs`                         |  ✓   | Create job                               |
| GET    | `/api/jobs/{id}`                    |  ✓   | Fetch job                                |
| POST   | `/api/applications`                 |  ✓   | Create application                       |
| GET    | `/api/applications`                 |  ✓   | List applications                        |
| PATCH  | `/api/applications/{id}`            |  ✓   | Update status / notes                    |
| POST   | `/api/workflows/run`                |  ✓   | Kick off LangGraph multi-agent workflow  |
| POST   | `/api/workflows/{threadId}/resume`  |  ✓   | Provide human approval/rejection         |
| GET    | `/api/workflows/{threadId}`         |  ✓   | Inspect a single run                     |
| GET    | `/api/workflows`                    |  ✓   | List recent runs for the user            |

OpenAPI docs are auto-generated at `/swagger-ui.html`.

---

## Security

- BCrypt(12) password hashing, JWT (HS256) with configurable TTL, stateless sessions.
- Method-level `@PreAuthorize` available (RBAC already enabled).
- Multi-tenant isolation: every protected query filters by `userId`/`orgId` from the JWT.
- CORS configured for the dev frontend.
- Audit log table provisioned; wire `AuditService` writes in phase 12.
- `JWT_SECRET` and `GEMINI_API_KEY` are required env vars — the app refuses to start without them.

## Observability

- Spring Actuator endpoints: `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`
- Workflow runs persist their full LangGraph state (`workflow_runs.state JSONB`) and the LangGraph PostgresSaver stores per-checkpoint history.

---

## Costs

`AIProvider.estimateCost(in, out)` returns USD using Gemini 2.5 Pro list pricing. The agent service tallies cost into `state.cost_usd` and the backend records per-feature usage in `usage_records`, ready for the metered billing surface in phase 12.

## License
Proprietary — internal scaffold.
