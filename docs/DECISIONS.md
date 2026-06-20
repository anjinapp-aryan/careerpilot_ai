# Architectural Decisions & Rationale

## Database: Neon Serverless (External Postgres)

**Decision**: Use Neon serverless Postgres, external to docker-compose.

**Rationale**:
- Cost: scales to zero; no container overhead
- No operational burden: Neon manages replication, backups, patching
- pgvector extension available for future embeddings

**Constraints**:
- Must use **direct endpoint** (no `-pooler` suffix)
  - Flyway DDL uses prepared statements (pgBouncer transaction-mode pooler breaks these)
  - LangGraph PostgresSaver relies on prepared statements
- Keep DATABASE_URL (JDBC) and DATABASE_URL_PY (libpq) in sync

**Alternative Rejected**: Local Postgres container
- Reason: Deployment complexity, no free tier equivalent in production

---

## Frontend: React 18 + Vite + TanStack Query

**Decision**: Use React 18, Vite (build tool), TanStack Query (server state), zustand (auth + UI state).

**Rationale**:
- Vite: fast HMR, no webpack complexity
- TanStack Query: automatic refetch after mutations (explicit invalidateQueries)
- zustand: minimal, localStorage persistence for auth tokens
- TypeScript: type safety without runtime overhead

**Why Not WebSocket/Real-time Updates**:
- Phase 1: Workflow page updates on user action only (explicitly fetch)
- Copilot uses SSE (one-way push) — simpler than WebSocket for this use case

---

## AI Gateway: Multi-Provider Failover Chain

**Decision**: All LLM calls route through AiGatewayService with transparent provider failover (deepseek → qwen → gemini).

**Rationale**:
- Single seam: business logic depends on AiGatewayService, not concrete providers
- Quota resilience: free tier Gemini exhausts quickly; fallback to NVIDIA NIM (higher quotas)
- Cost optimization: cheaper providers first (DeepSeek), expensive fallback (Gemini)
- Health tracking: avoid repeated calls to failed providers (ProviderHealthTracker, 5-min TTL)

**Failover Rules**:
- **Blocking calls** (chat, feedback): fail over before return (transparent to caller)
- **Streaming calls** (copilot): fail over only before first token (mid-stream failure returns error)
- **Quota (429)**: immediate failover, no retries
- **Other errors**: per-provider retry (Resilience4j) then failover

**Why Not Load Balancing**:
- Phase 1: simple sequential chain sufficient
- Cost: sequential tries cheaper providers first
- Quota handling: quota errors don't trigger retries (wastes quota)

---

## LangGraph: Linear Workflow (No Conditional Edges)

**Decision**: Single linear StateGraph with 8 nodes in fixed order (resume_intelligence → ... → application_tracking).

**Rationale**:
- Phase 1: MVP — no branching logic yet
- Human approval pause (NodeInterrupt) gates workflow progress
- All nodes run even if approval rejected (TODO: add conditional edges)

**Why No Branching**:
- Complexity: conditional edges + state merging requires careful design
- Phase 1 scope: linear happy path only
- Future: add add_conditional_edges from human_approval if needed

---

## Multi-Tenancy: Manual userId Checks

**Decision**: No row-level security or Hibernate @Filter. Every method checks userId.equals(entity.getUserId()).

**Rationale**:
- Explicit: isolation failures obvious in code review (not hidden in filters)
- Flexible: different isolation rules per table if needed
- Phase 1: small team, not yet multi-org edge cases

**Constraint**:
- New endpoints must replicate pattern
- No @PreAuthorize yet (anyone authenticated can hit any endpoint)

---

## API Response Format: DTOs for Complex JSON

**Decision**: Controllers return DTOs, not JPA entities. DTOs parse JSON fields to Map<String, Object>.

**Rationale**:
- Avoids Jackson JsonNode serialization errors
- Clear boundary: entities are persistence layer, DTOs are API contract
- Flexible: DTO can omit sensitive fields, transform data

**Pattern**:
```java
// Entity (database)
@JdbcTypeCode(SqlTypes.JSON)
@Column(columnDefinition = "jsonb")
private String state;  // stored as JSON string

// DTO (API response)
public record WorkflowRunResponse(
    Map<String, Object> state,  // parsed to Map, not JsonNode
    ...
)

// Service layer (conversion)
public WorkflowRunResponse toResponse(WorkflowRun entity) {
    Map<String, Object> state = mapper.readValue(entity.getState(), Map.class);
    return new WorkflowRunResponse(..., state, ...);
}
```

---

## Authentication: JWT with Multi-Tenant Context

**Decision**: JWT token carries userId, orgId, email, role. Extracted into AuthenticatedUser on request.

**Rationale**:
- Stateless: no session store needed
- Multi-tenant: orgId available in every request
- Flexible: role extensible for future RBAC

**Constraints**:
- Every service method must extract userId/orgId from AuthenticatedUser
- Manual isolation checks (no automatic row-level security)

---

## Streaming: SSE for Copilot, Polling for Workflow

**Decision**: 
- Copilot: Server-Sent Events (text/event-stream) for real-time token streaming
- Workflow: Non-streaming; frontend polls on user action

**Rationale**:
- SSE simpler than WebSocket for one-way push (backend → frontend)
- Copilot: user expects real-time chat feedback (SSE natural fit)
- Workflow: async long-running jobs; no need for real-time updates (TanStack Query refetch)
- No WebSocket overhead for phase 1

**Constraint**:
- SSE failover only before first token (can't switch providers mid-stream)

---

## Error Handling: Fail Fast or Failover

**Decision**:
- Quota errors (429): immediate failover without retries
- Other errors: per-provider retry (Resilience4j) → failover → error to caller
- Mid-stream failures: return error (can't failover after tokens emitted)

**Rationale**:
- Quota errors: retrying wastes quota, failover cheaper
- Other errors: Resilience4j handles transient failures (rate limits, timeouts)
- Circuit breaker: prevents hammering downed providers
- Streaming: once tokens start flowing, must commit to that provider

---

## Logging Strategy

**Decision**: INFO for workflow state changes + provider selection, ERROR for exceptions with full stack traces.

**Rationale**:
- Observability: track workflow progression without verbose logs
- Debugging: provider selection visible in logs (not hidden in code)
- Error context: full stack traces preserved for post-mortems

---

## Kafka: Produced But Not Consumed (Phase 1)

**Decision**: WorkflowEventProducer publishes state changes; no consumers wired.

**Rationale**:
- Scaffolding: foundation for future event processing (audit, notifications, webhooks)
- Phase 1: simple synchronous responses sufficient
- No cost: events just discarded (topic auto-created)

**Future**: Wire up Kafka consumers for audit logs, webhooks, analytics

---

## Scaffolding Patterns (Not Yet Integrated)

| Item | Reason | When to Wire |
|------|--------|--------------|
| refresh_tokens table | No token refresh endpoint yet | When session management needed |
| audit_logs table | No audit logging | When compliance required |
| pgvector embeddings | No embedding generation | When semantic search needed |
| Redis @Cacheable | No caching strategy yet | When read-heavy workloads appear |
| Kafka consumers | No event-driven features | When audit/webhooks needed |
| S3 resume upload | No upload UI | When document storage needed |

**Principle**: Build scaffolding once, wire up only when feature needed (avoid YAGNI)
