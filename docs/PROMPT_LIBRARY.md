# Prompt Library

Common prompts and templates for working with CareerPilot AI. Use these as starting points for Claude Code requests.

## Architecture & Understanding

**"Explain how [component] works"**
- Include files: ARCHITECTURE_SUMMARY.md, SYSTEM_PATTERNS.md
- For AI routing: "How does provider failover work?" → AiGatewayService
- For workflow: "How do workflows persist state?" → PostgresSaver, WorkflowService
- For streaming: "How does the copilot work?" → CopilotService, SSE, Reactor Flux

**"What are the constraints of [feature]?"**
- Database: Direct Neon endpoint required (no -pooler)
- Streaming: Failover only before first token
- Workflow: Linear nodes only (no branching yet)
- Embedding: pgvector installed but no generation code

## Adding Features

**"Add a new AI provider [Provider] to the gateway"**
1. Create LlmProvider impl (extends AbstractLlmProvider or AbstractOpenAiChatProvider)
2. Add to Spring config (GeminiProvider, NvidiaDeepSeekProvider, NvidiaQwenProvider as templates)
3. Add to AI_PROVIDER_ORDER in .env
4. Update docker-compose.yml to pass API key
5. Test via /api/diagnostics/ai (should show UP if configured)

**"Add a new workflow node"**
1. Create async function in agent-service/app/agents/new_agent.py
2. Define inputs/outputs in agent-service/app/state.py (CareerState TypedDict)
3. Add to graph.py: `graph.add_node("new_name", new_agent_node)`
4. Add to node order
5. Test via /api/workflows/run with fresh thread_id

**"Add an API endpoint to the backend"**
1. Create @RestController method (accept AuthenticatedUser param)
2. In service layer: check userId.equals(entity.getUserId())
3. Return DTO, not entity (use toResponse() pattern)
4. Update Swagger docs (auto-generated from Spring)
5. Test via /api/workflows or /api/copilot as templates

**"Add a database table"**
1. Create JPA entity in domain/
2. Create Repository extending JpaRepository
3. Create next Flyway migration V3__*.sql (never modify V1/V2)
4. If entity has JSON field: @JdbcTypeCode(SqlTypes.JSON) on String column

## Debugging

**"Provider failover is not working"**
- Check: NVIDIA_API_KEY set in .env
- Check: docker-compose rebuilt with env vars (DOCKER_BUILDKIT=0 docker build)
- Check: /api/diagnostics/ai shows all providers UP (not NOT_CONFIGURED)
- Check: logs for "provider failed → switching to" messages

**"Workflow returns RUNNING but no progress"**
- Check: agent-service logs for node execution
- Check: /api/diagnostics/workflow endpoint (should show agentService UP)
- Check: PostgreSQL has checkpoints* tables (created by PostgresSaver on first run)

**"Copilot provider always 'Unknown'"**
- Check: logs for "Callback present: true/false"
- If false: providerCallback not passed to streamChat()
- If true but still Unknown: check providerRef.set() in callback

**"JsonNode serialization error on response"**
- Check: controller returns DTO (e.g., WorkflowRunResponse), not entity
- Check: DTO state field is Map<String, Object>, not JsonNode
- Check: service.toResponse() parses JSON: mapper.readValue(entity.getState(), Map.class)

**"Quota exceeded (429) errors"**
- Check: GEMINI_API_KEY is valid and not exhausted
- Check: AI_PROVIDER_ORDER includes DeepSeek/Qwen
- Check: NVIDIA_API_KEY set if using NVIDIA providers
- After failover chain exhausted: check logs for "All providers exhausted"

## Testing & Validation

**"Verify [service] is working"**
- Backend health: `curl http://localhost:8080/swagger-ui.html`
- Agent service: `curl http://localhost:8088/docs`
- Frontend: `http://localhost:5173`
- Diagnostics: `curl http://localhost:8080/api/diagnostics/ai`

**"Test a workflow end-to-end"**
1. Create user via register → /api/auth/register (returns JWT)
2. Create resume via POST /api/resumes (with JWT header)
3. Create job via POST /api/jobs (with JWT header)
4. Start workflow: POST /api/workflows/run with resumeId, jobIds, targetRole
5. Poll workflow status: GET /api/workflows/{threadId}
6. Check agent-service logs for node progression
7. If INTERRUPTED: POST /api/workflows/{threadId}/resume with decision

**"Test provider failover"**
1. Start stack with all providers configured (NVIDIA_API_KEY set)
2. Run /api/diagnostics/ai → verify all UP
3. Disable primary (comment out AI_PROVIDER_ORDER, rebuild)
4. Call copilot → should use fallback
5. Check logs for "Failed → Switching to" message

## Common Modifications

**"Change primary provider"**
- Edit .env: AI_PROVIDER_ORDER=qwen,deepseek,gemini (reorder)
- Edit .env: PRIMARY_PROVIDER=qwen (display name)
- Rebuild: docker compose --env-file .env up --build

**"Adjust AI model"**
- Backend (Copilot): Change AI_MODEL in .env (default gemini-2.5-flash)
- Agent service: Change AI_MODEL in .env
- NVIDIA providers: Change NVIDIA_DEEPSEEK_MODEL, NVIDIA_QWEN_MODEL in .env

**"Customize multi-tenancy isolation"**
- Current: every service method checks userId.equals(entity.getUserId())
- Alternative: use Hibernate @Filter or Spring Data @Query annotations
- Risk: easy to miss isolation check → data leak (prefer explicit checks)

**"Add Kafka consumer for events"**
- Create @Component with @KafkaListener("careerpilot.workflow.events")
- Consume Map<String, Object> from WorkflowEventProducer
- No consumers exist yet (scaffolding)

**"Enable Redis caching"**
- Add @Cacheable("cache-name") to service methods
- Configure redis.template in config
- Currently unused (scaffolding)

## Git & CI/CD

**"Push a feature to main"**
- Ensure tests pass (mvn test, pytest for agent-service)
- Ensure /api/diagnostics/ai shows all providers UP
- Ensure no JsonNode serialization errors (check response DTO)
- Ensure no ThreadLocal usage with Reactor (use method params instead)
- Ensure manual userId checks in new service methods
- Create PR with summary + test evidence

**"Deploy to production"**
- See DEPLOYMENT.md (not yet written; planned for phase 2)
- Phase 1: local docker-compose only
- Neon connection must use direct endpoint (not -pooler)
- S3_ENDPOINT changes to Cloudflare R2 (see .env comments)
- Redis, Kafka, MinIO replaced with managed services (or removed)

## Common Search Queries

| Query | Purpose |
|-------|---------|
| grep -r "userId.equals" backend/ | Find all multi-tenancy checks |
| grep -r "ThreadLocal" backend/src/ | Verify no ThreadLocal usage (use method params) |
| grep -r "JsonNode" backend/src/main/java/ai/careerpilot/api/ | Ensure no JsonNode in responses (use DTO Map) |
| grep -r "AiGatewayService" backend/src/main/java/ai/careerpilot/service/ | Find all LLM call sites (should all go through gateway) |
| grep -r "new GeminiProvider" backend/ | Ensure no direct provider instantiation |
| grep -r "POST.*auth\|GET.*auth" backend/src/main/java/ai/careerpilot/api/ | Find auth endpoints |
| grep -r "streamChat" backend/src/ | Find all streaming calls (verify providerCallback passed) |

## Review Checklist (Before PR)

- [ ] No direct provider instantiation (use AiGatewayService)
- [ ] No ThreadLocal for Reactor async (use method params)
- [ ] Controllers return DTO, not entity
- [ ] All new service methods check userId.equals(entity.getUserId())
- [ ] Workflow state stored as String, parsed on read
- [ ] Tests pass (mvn test, pytest)
- [ ] /api/diagnostics/ai shows all providers UP
- [ ] No compilation errors
- [ ] No JsonNode in API responses (use Map<String, Object>)
