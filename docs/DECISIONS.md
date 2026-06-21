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

**Superseded by**: "Human Approval Gate: Conditional Routing After Decision" (below, 2026-06-21).
A rejected run running `application_tracking` anyway was a real defect, not just a Phase-1
simplification — the original rationale (avoid branching complexity) no longer outweighs
correctness. The rest of the graph is still a single linear chain; only the one edge changed.

---

## Human Approval Gate: Conditional Routing After Decision

**Decision** (2026-06-21): Added `add_conditional_edges` from `human_approval`, routing to
`application_tracking` on approval or to `END` on rejection (`_route_after_approval`).

**Rationale**:
- A rejected run was previously still running `application_tracking` — silently continuing
  past a gate the user explicitly stopped at. This was a defect, not a deferred feature.
- The fix is scoped to exactly one edge — the rest of the graph remains linear, preserving the
  "Phase 1: simple sequential chain" intent everywhere else.

**Why Not Broader Branching**:
- Still Phase 1 scope: this is the one place a binary human decision needs to fork the graph.
  No other node has a similar decision point yet.

---

## Workflow Resume: Dual-Layer State Guard Before Mutating

**Decision** (2026-06-21): Both the agent-service (`/runs/resume`) and the backend
(`WorkflowService.resume()`) independently re-check that a run is still parked at
`human_approval` before acting on an approve/reject decision, each returning HTTP 409 if not.

**Rationale**:
- A second, stale resume call (e.g. double-click, or approve-after-reject) was silently
  corrupting a run's terminal state — the only way to prevent it is to validate current state
  immediately before the mutation, at every layer that can independently receive the call.
- Checking only in the backend isn't sufficient (the agent-service endpoint could be called
  directly); checking only in the agent-service isn't sufficient either (the backend persists
  its own `WorkflowRun.status`, which must also stay consistent).
- Reuses `deriveDisplayStatus(WorkflowRun)` as the single source of truth for "is this run
  awaiting approval," rather than introducing a second status computation.

**Why Not Database-Level Locking**:
- Phase 1 scope: the race is between a user's repeated clicks, not concurrent writers across
  processes. A derived-status guard plus a frontend `isPending` lock is the smallest fix that
  closes the actual observed defect; row-level locking would be solving a concurrency problem
  this system doesn't yet have.

---

## Audit Trail: JSON State Field, Not a Schema Migration

**Decision** (2026-06-21): Approval/rejection audit fields (`approved_by`, `approved_at`,
`rejected_by`, `rejected_at`, `human_feedback`) are stamped into the existing `workflow_runs.state`
JSONB blob, not added as new columns via a Flyway migration.

**Rationale**:
- These fields are read-mostly, always read alongside the rest of a run's state, and don't need
  to be queried/indexed independently — extending the existing blob is strictly smaller than a
  schema migration for the same outcome.
- Keeps the change scoped to `WorkflowService` + the DTO; no new Flyway version, no Hibernate
  entity changes.

**Alternative Rejected**: New `approved_by`/`rejected_by`/etc. columns on `workflow_runs` (a
Flyway `V3__*.sql` migration). Rejected because nothing in this phase needs to filter/sort runs
by approver — if that need appears later, promote these from JSON keys to real columns then.

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
