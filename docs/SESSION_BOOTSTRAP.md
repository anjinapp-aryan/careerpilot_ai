# Session Bootstrap

Quick reference for Claude Code to onboard into this project. Read this first in any new session.

## What Is CareerPilot AI?

**Enterprise career intelligence platform** with:
- Multi-agent AI workflow (LangGraph, 8 nodes in sequence)
- Real-time copilot chat (SSE streaming + provider failover)
- Multi-tenant resume → job matching
- Transparent AI provider failover (DeepSeek → Qwen → Gemini)

**Tech Stack**:
- Backend: Spring Boot 4 / Java 25
- Agent service: Python FastAPI + LangGraph 0.2
- Frontend: React 18 + Vite + TypeScript
- Database: Neon serverless Postgres (external)
- Infrastructure: Redis, Kafka, MinIO (docker-compose)

## Critical Files to Know

| File | Purpose | Read When |
|------|---------|-----------|
| [CLAUDE.md](../CLAUDE.md) | Developer guide (architecture, conventions, commands) | First reference for any technical question |
| [docs/ARCHITECTURE_SUMMARY.md](ARCHITECTURE_SUMMARY.md) | 3-service overview, tech decisions, deployment notes | Understanding big picture |
| [docs/PROJECT_MEMORY.md](PROJECT_MEMORY.md) | Latest fixes, critical bugs, operational patterns | Before any major change |
| [docs/DECISIONS.md](DECISIONS.md) | Why each design choice, rationale, constraints | Considering architecture changes |
| [docs/SYSTEM_PATTERNS.md](SYSTEM_PATTERNS.md) | Code templates (Java, Python, React, DTO pattern) | Adding feature or fixing bug |
| [docs/PROMPT_LIBRARY.md](PROMPT_LIBRARY.md) | Common requests, debugging guides, checklists | Starting task or debugging |

## Running the Stack (Local Development)

### One-Command Start
```bash
# From project root
# 1. Set up environment
cp .env.example .env
# 2. Edit .env: set JWT_SECRET (≥32 chars), GEMINI_API_KEY, DATABASE_URL/DATABASE_URL_PY (Neon)
# 3. Start everything
docker compose --env-file .env up --build
```

**Services available**:
- Frontend: http://localhost:5173
- Backend Swagger: http://localhost:8080/swagger-ui.html
- Agent service Swagger: http://localhost:8088/docs
- MinIO console: http://localhost:9001
- Diagnostics: http://localhost:8080/api/diagnostics/ai (no auth)

### Per-Service (No Docker)

| Service | Command | Deps |
|---------|---------|------|
| Backend | `cd backend && mvn spring-boot:run` | Postgres (Neon), Kafka, Redis, MinIO |
| Agent service | `cd agent-service && uvicorn app.main:app --reload --port 8088` | Postgres (Neon), GEMINI_API_KEY |
| Frontend | `cd frontend && npm install && npm run dev` | Vite dev server only |

## Critical Configuration

### Environment Variables (`.env`)
```
JWT_SECRET=<≥32 random chars>
GEMINI_API_KEY=<free API key from aistudio.google.com/apikey>
DATABASE_URL=jdbc:postgresql://<host>/<db>?sslmode=require&channelBinding=require
DATABASE_URL_PY=postgresql://<user>:<pass>@<host>/<db>?sslmode=require&channel_binding=require

# Provider failover
PRIMARY_PROVIDER=deepseek
AI_PROVIDER_ORDER=deepseek,qwen,gemini
NVIDIA_API_KEY=<if using DeepSeek/Qwen fallback>
NVIDIA_BASE_URL=https://integrate.api.nvidia.com/v1
NVIDIA_DEEPSEEK_MODEL=deepseek-v4-flash
NVIDIA_QWEN_MODEL=qwen/qwen2.5-72b-instruct-nf
```

### Database Connection (Neon)
- ❌ Do NOT use `-pooler` endpoint (pgBouncer breaks Flyway + LangGraph prepared statements)
- ✅ Use **direct endpoint**: `<host>.neon.tech` (no `-pooler` suffix)
- Keep JDBC (DATABASE_URL) and libpq (DATABASE_URL_PY) URLs in sync

## Health Checks

**After starting stack, verify**:

1. Backend health → GET http://localhost:8080/api/diagnostics/ai
   - Should show: all API keys loaded, configured models, provider health (UP/DOWN/NOT_CONFIGURED)

2. Workflow diagnostics → GET http://localhost:8080/api/diagnostics/workflow
   - Should show: workflowEngine UP, jsonSerialization UP, agentService UP, providers UP

3. Database → Agent service logs should show: "Successfully created checkpoints tables"

4. Frontend → http://localhost:5173 should load (register → login flow)

**If anything is DOWN**:
- Check `/api/diagnostics/ai` for provider status + error messages
- Check service logs for stack traces
- Verify .env variables are set correctly
- Verify Docker containers are running: `docker compose ps`

## Latest Critical Fixes (2026-06-20)

### ✅ JsonNode Serialization (FIXED)
**Issue**: "Type definition error: [simple type, class com.fasterxml.jackson.databind.JsonNode]"
**Fix**: Use WorkflowDtos.WorkflowRunResponse (state as Map<String, Object>, not JsonNode)
**Pattern**: Controllers return DTO, not entity

### ✅ Provider Callback Threading (FIXED)
**Issue**: Provider always "Unknown" in SSE done event
**Fix**: Pass Consumer<String> providerCallback as method param (not ThreadLocal)
**Check**: Logs show "Callback present: true" and actual provider names

### ✅ Provider Failover Chain (IMPLEMENTED)
**Status**: DeepSeek → Qwen → Gemini working
**Verify**: /api/diagnostics/ai shows deepseek, qwen, gemini all UP
**Quota handling**: HTTP 429 triggers immediate failover without retries

## Common First Steps in New Session

1. **Understanding the codebase**:
   - Read ARCHITECTURE_SUMMARY.md (5 min)
   - Read CLAUDE.md "Architecture" section (10 min)
   - Skim DECISIONS.md for design rationale (10 min)

2. **Running locally**:
   - `docker compose --env-file .env up --build`
   - Check /api/diagnostics/ai (verify providers UP)
   - Test register → login → dashboard flow

3. **Making a change**:
   - Identify which service: backend (Java), agent-service (Python), or frontend (React)
   - Use SYSTEM_PATTERNS.md as template
   - Check PROMPT_LIBRARY.md for similar examples
   - Before PR: run checklist from PROMPT_LIBRARY.md

4. **Debugging**:
   - Check service logs (docker compose logs -f backend, etc.)
   - Check /api/diagnostics/ai and /api/diagnostics/workflow
   - Use grep queries from PROMPT_LIBRARY.md to find similar code
   - Check PROJECT_MEMORY.md for known issues + solutions

## Single Seams (One Entry Point for Each Concern)

| Concern | Entry Point | File |
|---------|------------|------|
| All LLM calls | AiGatewayService | backend/src/main/java/ai/careerpilot/ai/AiGatewayService.java |
| Agent service calls | WorkflowService | backend/src/main/java/ai/careerpilot/service/WorkflowService.java |
| AI configuration | AiGatewayProperties | backend/src/main/java/ai/careerpilot/config/AiGatewayProperties.java |
| Provider health | ProviderHealthTracker | backend/src/main/java/ai/careerpilot/ai/ProviderHealthTracker.java |

**Key principle**: Depend on these, never on concrete providers or external services directly.

## Key Constraints to Remember

1. **Database**: Direct Neon endpoint required (no -pooler)
2. **Streaming failover**: Failover only before first token (can't switch mid-stream)
3. **Multi-tenancy**: Every service method must check `userId.equals(entity.getUserId())`
4. **JSON fields**: Store as String, parse to Map on DTO conversion (not JsonNode)
5. **Reactor async**: No ThreadLocal (values don't survive thread boundaries); pass via method params
6. **Workflow nodes**: Linear only (no branching yet)
7. **No refresh tokens**: No token refresh endpoint yet
8. **Kafka consumers**: Not wired (events produced but not consumed)

## File Organization

```
careerpilot_ai/
├── docs/                          # ← You are here
│   ├── ARCHITECTURE_SUMMARY.md
│   ├── PROJECT_MEMORY.md
│   ├── DECISIONS.md
│   ├── SYSTEM_PATTERNS.md
│   ├── PROMPT_LIBRARY.md
│   └── SESSION_BOOTSTRAP.md       # ← Start here
├── CLAUDE.md                      # Developer guide (read first)
├── backend/                       # Spring Boot 4 / Java 25
│   ├── src/main/java/ai/careerpilot/
│   │   ├── api/                  # Controllers + DTOs
│   │   ├── service/              # Business logic
│   │   ├── domain/               # JPA entities
│   │   ├── ai/                   # LLM routing (AiGatewayService)
│   │   ├── security/             # Auth, JWT
│   │   ├── kafka/                # Event producers
│   │   └── config/               # Spring configuration
│   └── src/main/resources/db/migration/  # Flyway SQL
├── agent-service/                # Python FastAPI + LangGraph
│   ├── app/
│   │   ├── graph.py             # LangGraph workflow definition
│   │   ├── state.py             # CareerState TypedDict
│   │   ├── agents/              # One file per agent node
│   │   ├── ai_provider.py       # Rate-limited Gemini provider
│   │   ├── rate_limiter.py      # Token/RPM bucket enforcement
│   │   └── main.py              # FastAPI app
│   └── tests/
├── frontend/                      # React 18 + Vite + TypeScript
│   ├── src/
│   │   ├── pages/               # Page components
│   │   ├── components/          # Reusable UI
│   │   ├── lib/
│   │   │   ├── api.ts          # Axios instance
│   │   │   ├── auth.ts         # zustand auth store
│   │   │   └── copilotStream.ts # SSE consumer
│   │   └── App.tsx             # Routes
│   ├── vite.config.ts
│   ├── tsconfig.json
│   └── package.json
├── docker-compose.yml            # Local stack (Kafka, Redis, MinIO, services)
├── .env.example                  # Environment template
└── .env                          # Local secrets (git-ignored)
```

## Quick Reference: What to Do Next

**I want to...**
- Understand the architecture → Read ARCHITECTURE_SUMMARY.md (5 min)
- Add a new feature → Read SYSTEM_PATTERNS.md + PROMPT_LIBRARY.md
- Fix a bug → Read PROJECT_MEMORY.md + PROMPT_LIBRARY.md debugging section
- Review a design decision → Read DECISIONS.md
- Add a new AI provider → Read SYSTEM_PATTERNS.md "Provider pattern" + PROMPT_LIBRARY.md
- Debug provider failover → Read PROJECT_MEMORY.md "Critical Fixes" + PROMPT_LIBRARY.md "Provider failover"
- Add a new endpoint → Use SYSTEM_PATTERNS.md "Controller pattern" as template
- Start a workflow → POST /api/workflows/run with resumeId, jobIds, targetRole
- Test Copilot → POST /api/copilot/stream with prompt (SSE response)

## Support & Resources

| Resource | Purpose |
|----------|---------|
| CLAUDE.md | Canonical developer guide (architecture, commands, conventions) |
| docs/*.md | Supplementary memory system (this folder) |
| .claude/skills/run-careerpilot-ai/ | Skill for end-to-end stack testing |
| /api/diagnostics/ai | Provider health + gateway metrics |
| /api/diagnostics/workflow | Workflow engine status |
| Docker compose logs | Service debugging (`docker compose logs -f backend`) |

---

**Last updated**: 2026-06-20
**Status**: Phase 1 (vertical slice complete, multi-provider failover working, JsonNode fixes deployed)
