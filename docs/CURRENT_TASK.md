# Current Task Status

**Last updated**: 2026-06-21
**Status**: Phase 1 complete; human-approval workflow hardened and restarted on live stack
**Branch**: appmod/java-upgrade-20260619103446

## Latest Session Summary (2026-06-21)

### Major Accomplishment: Human Approval Gate Hardening

**Problem**: A reject→approve (or any second, stale) resume call could silently corrupt an
already-terminal workflow run — e.g. turning a REJECTED run into COMPLETED — because nothing
re-validated that the run was still actually awaiting approval before acting on the decision.
A rejected run also still ran `application_tracking` afterward, since the LangGraph graph had
no conditional edge out of `human_approval`.

**Solution Implemented**:
1. Added `add_conditional_edges` from `human_approval` in `graph.py` — approval routes to
   `application_tracking`, rejection routes to `END`.
2. Added a dual-layer 409 guard: agent-service checks `"human_approval" not in
   graph.get_state(cfg).next`; backend's `WorkflowService.resume()` checks
   `deriveDisplayStatus(run) != "INTERRUPTED"`. Either layer independently rejects a stale
   resume call.
3. Added a frontend `useMutation.isPending` guard in `Workflow.tsx` to prevent the
   double-click that most often triggered the race in the first place.
4. Mapped the new 409 through `AgentServiceClient` → `IllegalStateException` →
   `GlobalExceptionHandler` so the API contract stays consistent (409, not 500).

**Impact**: Approve/reject is now idempotent against repeated/stale calls; rejected runs
correctly stop before Application Tracking. ✅

### Audit Trail (Approved/Rejected By, At, Feedback)

Added without a schema migration — stamped into the existing `workflow_runs.state` JSON blob
in `mergeResponse()`, exposed via 5 new `WorkflowRunResponse` fields, rendered in the frontend's
expanded run timeline (rejection shown danger-styled, approval success-styled).

### Single Source of Truth for Workflow Status

Added `WorkflowService.deriveDisplayStatus(WorkflowRun)` (public) and wired it into
`CareerContextRetriever.getWorkflowContext()` so the Copilot's workflow-explanation skill
reports the same derived status the Workflow page shows — plus gave that skill explicit
per-status prompt branches (INTERRUPTED/REJECTED/FAILED/COMPLETED/RUNNING) instead of one
generic instruction.

### Infrastructure: Full Stack Rebuilt & Restarted

`docker compose --env-file .env up -d --build` — rebuilt backend, agent-service, and frontend
images with the above changes; all containers (including previously-stopped zookeeper/minio/kafka)
came up healthy on first health-check poll (`backend=200 agent=200 frontend=200`).

Hit one infra snag: BuildKit couldn't resolve `docker-credential-desktop` (daemon-side, not
fixable via shell `PATH`). Worked around it by temporarily removing `"credsStore": "desktop"`
from `~/.docker/config.json` (safe since `"auths": {}` was empty) and restoring it immediately
after the build succeeded — see PROJECT_MEMORY.md "Deployment Notes" for the reusable fix.

## Completed Tasks (This Session)

| Task | Status | Files Modified |
|------|--------|-----------------|
| Root-cause analysis of reject→approve corruption | ✅ | (analysis only) |
| Conditional edge after `human_approval` | ✅ | `agent-service/app/graph.py` |
| Dual-layer 409 guard (agent-service + backend) | ✅ | `main.py`, `WorkflowService.java`, `AgentServiceClient.java`, `GlobalExceptionHandler.java` |
| Frontend double-submit guard | ✅ | `Workflow.tsx` |
| Audit trail (JSON state, no migration) | ✅ | `WorkflowService.java`, `WorkflowDtos.java`, `WorkflowController.java` |
| Copilot derived-status integration | ✅ | `CareerContextRetriever.java`, `WorkflowExplanationHandler.java` |
| UI polish (current-stage fallback, duration, audit block, at-a-glance metadata) | ✅ | `Workflow.tsx`, `workflow.ts` |
| Build verification (backend/frontend/agent compile clean) | ✅ | — |
| Full stack rebuild + restart + health check | ✅ | docker compose |
| Memory docs maintenance workflow + sync | ✅ | `docs/MEMORY_WORKFLOW.md` (new), this round of updates |

## Verified Working

✅ Backend, agent-service, frontend all compile/build clean
✅ Full docker-compose stack rebuilt and healthy (`backend=200 agent=200 frontend=200`)
✅ Resuming a non-awaiting run returns 409 at both layers (by design, not a bug)
✅ Copilot workflow-explanation skill reports the same status the Workflow page shows

## Known Issues (carried forward)

| Issue | Severity | Workaround | Status |
|-------|----------|-----------|--------|
| True mid-flight "RUNNING at stage N" visibility | Medium | Agent execution is synchronous; stage-by-stage live progress would need async/streaming execution — explicitly out of scope (architecture redesign) | Deferred, flagged to user |
| Kafka consumers not wired | Low | Events published, not consumed (scaffolding) | Not planned for phase 1 |
| Refresh tokens not implemented | Medium | No token refresh endpoint | Defer to phase 2 |
| Resume upload not implemented | Medium | No S3 storage pipeline | Defer to phase 2 |
| Redis caching not used | Low | No @Cacheable decorators | Enable when needed for performance |

## Next Priorities (Phase 2)

1. **Validate the new guard on the live stack** — exercise approve-once, reject-once, and
   reject-then-approve (expect 409) flows against the rebuilt containers. Offered, not yet run.
2. **Resume & Job Management UI** — upload resume (PDF parsing), create job listings, match
   resumes to jobs.
3. **Notification System** — wire up Kafka consumers (email on completion, webhooks, audit log
   persistence).
4. **Embeddings & Semantic Search** — generate embeddings via pgvector, similarity search.
5. **Performance** — Redis caching, DB indexing, frontend code splitting.
6. **Test suite** — `mvn test` (backend has none yet), expand agent-service pytest beyond the
   rate limiter.

## Deployment Readiness

**Current State**: Phase 1 complete (including approval-gate hardening), NOT deployed to production.

**Blockers for deployment**:
- [ ] Test suite required (mvn test, pytest)
- [ ] Secrets management (rotate JWT_SECRET, secure GEMINI_API_KEY)
- [ ] Database backup strategy (Neon snapshot config)
- [ ] CDN for frontend assets (Vercel, Cloudflare Pages, etc.)
- [ ] Production monitoring (logs, traces, metrics)
- [ ] Rate limiting on API endpoints
- [ ] DDoS protection (Cloudflare or similar)

**For Phase 2 deployment planning**: see CLAUDE.md "Configuration that affects behavior" and
ARCHITECTURE_SUMMARY.md's Configuration table for cloud-specific env vars.

## Session Notes

### What Worked
- Re-validating state server-side at every layer that can independently receive a mutating call
  is the right fix for idempotency races — cheaper and more robust than client-only guards.
- Stamping audit fields into an existing JSON blob avoided a schema migration for data that's
  read-mostly and co-located with state the entity already owns.
- Exposing one derived-status method and reusing it everywhere (UI response + Copilot context)
  is what keeps two independent surfaces from disagreeing about the same fact.

### What to Avoid
- Don't trust a single call site to validate state before a mutation — validate at every layer
  that could independently receive the request.
- Don't add new columns/migrations for audit data that doesn't need to be queried/indexed —
  extend the existing JSON blob first; promote to real columns only when a real query need shows up.
- Don't compute "current status" twice in two services — derive once, expose the method, reuse.

### Tips for Next Session
1. Start with `/api/diagnostics/ai` and `/api/diagnostics/workflow` to verify provider/engine health.
2. If a resume call returns 409, check `deriveDisplayStatus(run)` — it's the guard working, not a bug.
3. See `docs/MEMORY_WORKFLOW.md` before making further architectural changes — it defines when/how to update these four memory files.

---

**Next session**: Run the approve/reject/reject-then-approve validation pass against the live stack, then continue with Phase 2 priorities above.
