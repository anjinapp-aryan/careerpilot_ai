# P0 Workflow Fix - Action Items

**Status**: Code Fixed ✅ | Ready for Testing 🔄

---

## Immediate Actions (Next 1-2 hours)

- [ ] **Review Changes**
  - Read: [docs/EXECUTIVE_SUMMARY.md](./EXECUTIVE_SUMMARY.md)
  - Read: [docs/P0_WORKFLOW_FIX.md](./P0_WORKFLOW_FIX.md)
  - Command: `git log --stat -1`

- [ ] **Verify Environment**
  ```bash
  # Check all necessary services running
  docker compose ps
  
  # Verify database connection
  psql $DATABASE_URL -c "SELECT 1"
  
  # Check Redis
  redis-cli ping
  ```

- [ ] **Build Backend**
  ```bash
  cd backend && mvn -DskipTests clean compile
  # Expected: BUILD SUCCESS ✅
  ```

---

## Integration Testing (2-4 hours)

Follow: [docs/VALIDATION_TESTING.md](./VALIDATION_TESTING.md)

- [ ] **Test 1: Compilation & Startup**
  - [ ] Backend compiles successfully
  - [ ] Backend starts without errors
  - [ ] /api/diagnostics/ai returns 200 OK

- [ ] **Test 2: User Registration**
  - [ ] POST /api/auth/register works
  - [ ] JWT token returned
  - [ ] No serialization errors

- [ ] **Test 3: Create Resume & Job**
  - [ ] POST /api/resumes returns 200 OK
  - [ ] POST /api/jobs returns 200 OK
  - [ ] Both resources in database

- [ ] **Test 4: THE CRITICAL TEST - Start Workflow**
  ```bash
  curl -X POST http://localhost:8080/api/workflows/run \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{...}'
  ```
  - [ ] Returns 200 OK (NOT 500)
  - [ ] **NO "Type definition error: JsonNode"** ✅✅✅
  - [ ] Response contains WorkflowRunResponse DTO
  - [ ] Status is RUNNING or INTERRUPTED
  - [ ] Logs show no JsonNode errors

- [ ] **Test 5: Get Workflow Status**
  - [ ] GET /api/workflows/{threadId} works
  - [ ] Returns proper WorkflowRunResponse
  - [ ] State contains agent outputs

- [ ] **Test 6: List Workflows**
  - [ ] GET /api/workflows returns array
  - [ ] All items properly serialized
  - [ ] No errors

---

## Code Review Checklist

- [ ] AgentServiceDtos.java
  - [ ] AgentRunResponse record properly defined
  - [ ] Matches agent service response structure
  - [ ] Serializable (no JsonNode)

- [ ] AgentServiceClient.java
  - [ ] startRun() returns AgentRunResponse
  - [ ] resumeRun() returns AgentRunResponse
  - [ ] getRun() returns AgentRunResponse
  - [ ] Error handling present
  - [ ] Logging at appropriate levels
  - [ ] No JsonNode imports

- [ ] WorkflowService.java
  - [ ] All JsonNode imports removed
  - [ ] mergeState() uses Map<String, Object>
  - [ ] intOrNull() handles Number types
  - [ ] toResponse() converts correctly
  - [ ] No JsonNode in return types

- [ ] CLAUDE.md
  - [ ] Architecture section updated
  - [ ] DTO pattern documented
  - [ ] JsonNode issue noted as fixed

---

## Documentation Review

- [ ] [EXECUTIVE_SUMMARY.md](./EXECUTIVE_SUMMARY.md) — Sharable with stakeholders
- [ ] [P0_WORKFLOW_FIX.md](./P0_WORKFLOW_FIX.md) — Technical details
- [ ] [VALIDATION_TESTING.md](./VALIDATION_TESTING.md) — Testing procedures
- [ ] [docs/SYSTEM_PATTERNS.md](./SYSTEM_PATTERNS.md) — DTO best practices
- [ ] [CLAUDE.md](../CLAUDE.md) — Main developer guide

---

## Post-Testing Actions

### If Tests PASS ✅

- [ ] **Create Pull Request**
  ```bash
  git push origin appmod/java-upgrade-20260619103446
  # Create PR to main with test evidence
  ```

- [ ] **Document Test Results**
  - [ ] Screenshot of successful workflow start
  - [ ] Log output showing no JsonNode errors
  - [ ] Response JSON from /api/workflows/run

- [ ] **Schedule Merge**
  - [ ] Get code review approval
  - [ ] Merge to main
  - [ ] Tag version (e.g., v0.2.0-workflow-fix)

### If Tests FAIL ❌

- [ ] **Diagnose Issue**
  - [ ] Check error logs: `docker compose logs backend`
  - [ ] Check agent service: `docker compose logs agent-service`
  - [ ] Verify database: `psql $DATABASE_URL -c "SELECT * FROM workflow_runs"`

- [ ] **Rollback if Needed**
  ```bash
  git revert e0a398ce
  mvn clean compile
  ```

- [ ] **Document Failure**
  - [ ] Error message
  - [ ] Stack trace
  - [ ] Steps to reproduce

---

## Phase 2: Unit & Integration Tests

- [ ] Create test_agent_service_dtos.java
- [ ] Create test_workflow_serialization.java
- [ ] Create test_workflow_e2e.java
- [ ] Add to CI/CD pipeline
- [ ] Run tests on all commits

---

## Phase 3: Monitoring & Metrics

- [ ] Add workflow completion rate metric
- [ ] Add JsonNode error rate metric (should be 0)
- [ ] Add workflow avg execution time metric
- [ ] Set up alerts for workflow failures

---

## Success Criteria

✅ **All** of these must be true to consider this fixed:

1. [ ] Backend compiles without errors
2. [ ] No JsonNode serialization errors in logs
3. [ ] POST /api/workflows/run returns 200 OK
4. [ ] Workflow state properly stored in database
5. [ ] GET /api/workflows/{threadId} returns complete response
6. [ ] All responses are properly formatted JSON
7. [ ] No type definition errors in responses
8. [ ] Agent nodes execute successfully

---

## Key Files Modified

```
✅ CREATED: backend/src/main/java/ai/careerpilot/api/dto/AgentServiceDtos.java
✅ MODIFIED: backend/src/main/java/ai/careerpilot/agent/AgentServiceClient.java
✅ MODIFIED: backend/src/main/java/ai/careerpilot/service/WorkflowService.java
✅ MODIFIED: CLAUDE.md
✅ CREATED: docs/P0_WORKFLOW_FIX.md
✅ CREATED: docs/VALIDATION_TESTING.md
✅ CREATED: docs/EXECUTIVE_SUMMARY.md
✅ CREATED: docs/ACTION_ITEMS.md
```

---

## Time Estimate

| Phase | Time | Status |
|-------|------|--------|
| Code Fix | 30 min | ✅ DONE |
| Documentation | 45 min | ✅ DONE |
| Integration Testing | 2-4 hours | 🔄 NEXT |
| Code Review | 1 hour | ⏳ PENDING |
| Merge to Main | 15 min | ⏳ PENDING |
| **Total** | **~5-6 hours** | **🔄 IN PROGRESS** |

---

## Notes

- **Do NOT deploy to production** until all integration tests pass
- **Rollback is safe** — commit is isolated and backward-compatible
- **No breaking changes** — all APIs remain the same
- **All tests must pass** before merge
- **Evidence required** — screenshot/logs from successful test run

---

**Prepared**: 2026-06-20  
**Target Completion**: 2026-06-20 EOD or 2026-06-21 AM  
**Assigned to**: QA / Engineering Lead  

