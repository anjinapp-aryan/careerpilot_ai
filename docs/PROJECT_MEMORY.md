# Project Memory

## Critical Fixes (Latest Session - 2026-06-20)

### JsonNode Serialization Bug (FIXED)
- **Symptom**: "Type definition error: [simple type, class com.fasterxml.jackson.databind.JsonNode]" on workflow endpoints
- **Root Cause**: Direct entity serialization attempted to serialize JsonNode fields
- **Fix**: Created WorkflowDtos.java with WorkflowRunResponse (state as Map<String, Object>, not JsonNode)
- **Pattern**: All entity responses must go through DTOs that parse JSON strings to Maps before serialization
- **Files**: WorkflowDtos.java, WorkflowService.toResponse(), WorkflowController (all methods return DTO)

### Provider Callback Threading Bug (FIXED)
- **Symptom**: Provider name always "Unknown" in SSE done event instead of actual provider
- **Root Cause**: ThreadLocal set in one thread, Reactor Flux executes on different thread
- **Fix**: Changed from ThreadLocal to method parameter: streamChat(messages, system, providerCallback)
- **Pattern**: Never use ThreadLocal for Reactor async context — pass via method parameter
- **Result**: Provider names now correctly appear in responses

### Provider Failover Chain (IMPLEMENTED)
- **Current Order**: deepseek (primary) → qwen → gemini (fallback)
- **Docker Config**: Must pass NVIDIA_API_KEY, NVIDIA_BASE_URL, NVIDIA_DEEPSEEK_MODEL, NVIDIA_QWEN_MODEL
- **Before**: Only Gemini configured, quota exhaustion caused complete failure
- **After**: Automatic failover with quota detection (429 errors)
- **Health Tracking**: ProviderHealthTracker caches state 5min to avoid retry storms

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

## Database Decisions

| Decision | Reason |
|----------|--------|
| Neon serverless (external Postgres) | Scale to zero cost, no container overhead. Direct endpoint required (no -pooler). |
| Flyway migrations in backend | Version control + automated schema evolution on startup. |
| LangGraph PostgresSaver | Checkpoint storage survives process restarts. Auto-creates tables (not in Flyway). |
| JSONB for workflow state | Flexible state serialization. Parsed to Map on DTO conversion (not raw JsonNode). |
| Single shared DB, logically partitioned | Agent service reads own checkpoints; backend reads domain data. No cross-concern writes. |

## Operational Patterns

1. **Provider Health Endpoint** → `/api/diagnostics/ai` (no auth)
   - Shows: configured models, API keys loaded, provider order, health (UP/DOWN/NOT_CONFIGURED), call counts
   - Used: monitoring deployments, debugging quota issues

2. **Workflow Diagnostics** → `/api/diagnostics/workflow` (no auth)
   - Shows: workflowEngine/jsonSerialization/agentService status + provider health
   - Used: validate post-deployment startup

3. **Logging** 
   - WorkflowService: "Workflow Created", "Started", "Updated" at INFO
   - AiGatewayService: provider selection + fallback triggers at INFO, full stack traces on ERROR
   - CopilotService: provider callback execution logged

4. **Error Handling**
   - 429 (quota) → immediate failover, no retries
   - Other errors → per-provider retry (Resilience4j) then failover
   - Mid-stream error in streaming → return Flux.error() (can't retry)

## Known Scaffolding (Do NOT Assume Integrated)

- `refresh_tokens` table — no /api/auth/refresh endpoint
- `audit_logs`, `usage_records` tables — no writes
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

## For Next Sessions

1. Verify `/api/diagnostics/workflow` shows all providers UP before running workflows
2. If workflow returns RUNNING/INTERRUPTED, check agent-service logs for progress
3. If copilot returns "Unknown" provider, check that providerCallback is not null in logs
4. If JsonNode errors appear, ensure controller returns DTO (use workflows.toResponse()), not entity
5. Provider failover requires NVIDIA_API_KEY set + docker-compose rebuilt with env vars
