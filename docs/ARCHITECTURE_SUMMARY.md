# Architecture Summary

**CareerPilot AI** is a three-service monorepo implementing an enterprise career intelligence platform with multi-agent AI workflows and real-time collaborative features.

## Service Architecture

### Backend (Spring Boot 4 / Java 25)
- **Role**: Control plane, API gateway, business logic, multi-tenant context
- **Key Modules**: 
  - `api/` — REST controllers (auth, workflow, copilot, diagnostics)
  - `service/` — Business logic (WorkflowService, CopilotService, AgentOrchestrator)
  - `ai/` — LLM routing via AiGatewayService (multi-provider failover chain)
  - `domain/` — JPA entities (User, Resume, Job, WorkflowRun, Conversation)
  - `security/` — JWT auth, multi-tenant context extraction
  - `kafka/` — Event producers (workflow state changes)
- **Port**: 8080 (Swagger at `/swagger-ui.html`)

### Agent Service (Python FastAPI + LangGraph 0.2)
- **Role**: Multi-agent workflow orchestration
- **Topology**: Linear StateGraph with 8 nodes: resume_intelligence → job_discovery → ats_optimization → interview_prep → career_strategy → salary_intelligence → human_approval → application_tracking
- **State Management**: PostgresSaver checkpoints (survives restarts)
- **Human Loop**: NodeInterrupt in human_approval pauses; /runs/resume resumes
- **Port**: 8088 (`/docs` for Swagger)

### Frontend (React 18 + Vite + TypeScript)
- **Role**: User interface, real-time chat, workflow orchestration UI
- **Architecture**: TanStack Query (server state), zustand (auth + UI state)
- **SSE Integration**: copilotStream.ts consumes backend's text/event-stream responses
- **Port**: 5173 (dev), 80 (Docker)

## Data & Infrastructure

| Component | Tech | Notes |
|-----------|------|-------|
| **Database** | Neon serverless Postgres | External (not in docker-compose). Direct endpoint required (no -pooler). V1+V2 migrations in Flyway. LangGraph checkpoints auto-created by PostgresSaver. |
| **Message Queue** | Kafka (Confluent) | Local compose only. Not deployed to production. WorkflowEventProducer publishes state changes. No consumers wired (scaffolding). |
| **Cache** | Redis 7 | docker-compose only. No @Cacheable / RedisTemplate usage yet (scaffolding). |
| **Storage** | MinIO (S3-compatible) | Local docker-compose. Cloudflare R2 in production. No resume upload pipeline yet. |

## AI Routing & Failover

**AiGatewayService** (backend/src/main/java/ai/careerpilot/ai/):
- **Entry point** for all LLM calls — no direct provider access
- **Chain**: deepseek → qwen → gemini (configurable via `AI_PROVIDER_ORDER`)
- **Failover triggers**: 
  - Blocking calls fail over before return if primary fails
  - Streaming calls fail over only before first token (no mid-stream switchover)
  - HTTP 429 (quota) triggers immediate failover
- **Health tracking**: ProviderHealthTracker caches health state 5min (avoids retry storms)
- **Resilience**: Resilience4j circuit breaker + retry per provider

**Providers**:
- GeminiProvider — Google Gemini (free tier eligible: gemini-2.5-flash)
- NvidiaDeepSeekProvider — DeepSeek v4 via NVIDIA NIM (OpenAI-compatible API)
- NvidiaQwenProvider — Qwen via NVIDIA NIM (OpenAI-compatible API)

## Authentication & Multi-Tenancy

- **JWT** carries: userId, orgId, email, role (JwtService)
- **Isolation**: Manual checks `userId.equals(entity.getUserId())` — no row-level security
- **Auth filter**: Extracts token → AuthenticatedUser in security context
- **Controllers**: Accept AuthenticatedUser param to access userId/orgId
- **No @PreAuthorize** yet — anyone authenticated can hit any endpoint

## Key Workflows

1. **Register & Auth** → Backend creates User + OAuth optional → JWT issued
2. **Copilot Chat** → Frontend SSE stream → Backend → AiGatewayService → Provider (with failover)
3. **Workflow Run** → Frontend calls /api/workflows/run → Backend assembles input → Calls agent-service → Persists WorkflowRun → Publishes Kafka event
4. **Workflow Resume** → Frontend calls /api/workflows/{threadId}/resume → Backend calls agent-service → Updates state

## Configuration

| Variable | Purpose |
|----------|---------|
| JWT_SECRET | ≥32 chars. Refuse start if missing. |
| GEMINI_API_KEY | Required for agent-service startup. |
| DATABASE_URL | JDBC for backend. Direct endpoint (no -pooler). |
| DATABASE_URL_PY | libpq for agent-service & LangGraph. Keep in sync. |
| AI_PROVIDER_ORDER | Failover chain (default: deepseek,qwen,gemini). |
| NVIDIA_API_KEY | Required if DeepSeek/Qwen in chain. Empty = skip NVIDIA providers. |
| PRIMARY_PROVIDER | Display name for primary (e.g., deepseek). Used in health endpoints. |

## Phase-1 Scope

✅ End-to-end skeleton (register → login → dashboard → create job → workflow)
✅ Multi-agent LangGraph orchestration with human approval
✅ Streaming copilot with provider failover
✅ JWT + multi-tenant isolation
✅ Provider health tracking & transparent failover

❌ Scaffolding (do not assume integrated):
- refresh_tokens table (no endpoint)
- audit_logs, usage_records tables (no writes)
- pgvector embeddings (no generation)
- Redis caching (@Cacheable)
- Kafka consumers (events go nowhere)
- Resume upload & S3 storage
