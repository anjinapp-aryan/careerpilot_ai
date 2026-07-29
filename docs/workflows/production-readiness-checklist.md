# Workflow Production Readiness Checklist

Complete before flipping `<workflow>.workflow.enabled` to `true` in production (or before a canary
rollout). Mirrors the dark-launch → verify → canary discipline every prior phase in this codebase
has followed.

## Pre-flight

- [ ] `mvn test` full suite green (record the exact count, e.g. "2266/2266")
- [ ] `pytest` full suite green (record the exact count)
- [ ] `docs/development/workflow-review-checklist.md` fully passed
- [ ] Migration applied cleanly on a fresh schema (or against the real Neon instance, staging first)
- [ ] Confirmed the new `workflow_definition` row is visible via `WorkflowRegistryService.listActive()`
      / the existing registry read path

## Dark-launch verification (flags OFF)

- [ ] `<workflow>.workflow.enabled=false` (default) — confirm `POST /api/<workflow>/{missionId}/run`
      returns the expected `IllegalStateException`-derived error, not a 500 or silent no-op
- [ ] Confirm zero behavior change to any existing endpoint (`/runs`, `/skill-gap/runs`, any other
      workflow's endpoint) — diff the relevant integration/smoke test output before vs. after

## Canary verification (flags ON, one environment)

- [ ] `runtime.enabled=true` and `<workflow>.workflow.enabled=true` together (both required)
- [ ] `POST /api/<workflow>/{missionId}/run` against a real mission returns a `SUCCEEDED` result
      with a plausible `result` payload
- [ ] `GET /api/<workflow>/{missionId}/latest` and `.../history` return the expected shape
- [ ] Correlation ID appears consistently across Java logs and Python logs for the same run
      (grep both log streams for the same `correlationId`/`correlation_id`)
- [ ] `GET /workflows` (dispatcher listing) includes the new `workflow_id`
- [ ] Deliberately trigger a failure path (e.g. temporarily break the AI Gateway config) and
      confirm the result is `FAILED`/`status="error"` with a populated error message — never a 500

## AI cost/quota check

- [ ] Counted the number of AI Gateway calls per run (should match the workflow-definition
      checklist's agent table — no unplanned extra calls)
- [ ] Confirmed the provider failover chain (`AI_PROVIDER_ORDER`) is unaffected — this workflow
      uses the existing `get_workflow_ai_gateway()` singleton, not a new provider chain

## Resource check (Oracle Cloud Free Tier, 2GB RAM)

- [ ] Confirmed the workflow's graph does not introduce a new `PostgresSaver`/checkpoint table
      unless it genuinely needs a human-approval pause (most workflows should be stateless, per
      the Standard)
- [ ] No new long-lived in-memory structure without a bound (mirrors `InMemoryExecutionHistory`'s
      50-entry cap, `InMemoryWorkflowMetrics`'s counter-only shape)

## Rollout

- [ ] Flag flipped in production via the normal env-var/config deployment path (no code deploy
      needed for the flip itself, per this platform's dark-by-default convention)
- [ ] `GET /api/diagnostics/workflow` (or the workflow's own diagnostics, if any) checked
      post-flip
- [ ] Rollback plan: flipping the flag back to `false` is the entire rollback — confirmed no
      persisted state from this workflow affects any other workflow's behavior if disabled again

## Documentation

- [ ] `CLAUDE.md`'s new phase section includes the live-verification note (or explicitly states
      verification was test-only, per this codebase's existing "not curl-verified" honesty
      convention where applicable)
