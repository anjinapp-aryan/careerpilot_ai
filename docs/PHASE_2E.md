# Phase 2E — Enterprise Auto-Apply Execution Engine

> **Status: BUILT, DARK, INERT. NOT deployed, NOT enabled, NO migration applied, NO application ever submitted.**
> This is the highest-risk phase in the product. It is engineered like a banking transaction
> platform: every action is audited, isolated, approvable, retryable, and instantly reversible.

Phase 2E adds the *execution* layer on top of the 2D application-intelligence pipeline — the
machinery that *could* submit a job application on the candidate's behalf, wrapped in a mandatory
safety gate, mandatory human approval, bounded retry, tracking, and analytics. In this deliverable
the machinery is fully wired but **inert**: with stock defaults, completing an entire 2D pipeline
creates **zero** 2E rows, and there is no code path that submits anything.

---

## 1. Architecture

New bounded-context package `ai.careerpilot.execution` (parallel to `resumetailoring`), sub-packaged
by concern: `execution`, `browser`, `ats`, `approval`, `safety`, `retry`, `tracking`, `analytics`,
`event`, `config`, `api`. Domain entities live in `ai.careerpilot.domain`, repositories in
`ai.careerpilot.repo` (existing convention). Every stage mirrors the proven Phase-2D shape:
append-only entity + audit pair, per-stage `Metrics` component, dedicated bounded
`ThreadPoolTaskExecutor`, `@Async @TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)`
worker, dual feature flag, and a no-auth counts-only diagnostics endpoint.

**Single execution authority (intentional deviation from the spec's worker-per-stage sketch):**
`ApplicationExecutionService` owns the whole `QUEUED→VALIDATING→EXECUTING→terminal` state machine in
one transaction rather than fragmenting it across separate `BrowserAutomationWorker` /
`ATSConnectorWorker` event-listeners. Splitting a submission's state across three async hops would
create partial-state and double-submit hazards — unacceptable for a "banking-grade" flow. The
dedicated `browserAutomationExecutor` / `atsConnectorExecutor` beans still exist and are surfaced in
diagnostics; when real automation is wired in a future phase, the blocking browser/ATS calls dispatch
onto those executors *from within* the single execution authority.

## 2. Event pipeline

```
ApplicationPackageReadyEvent   (EXISTING — 2D.6; SafetyValidationWorker is a 2nd listener)
    → SafetyValidationWorker    → SafetyValidatedEvent      (BLOCK ⇒ chain ends, audited)
    → ApprovalWorker            → approval_queue row PENDING, then STOPS
        ── human POST /api/execution/approve/{id} ⇒ APPROVED ⇒ publishes ──
    → ApprovalGrantedEvent
    → ApplicationExecutionWorker → application_execution SM  (browser/ATS disabled ⇒ ABORTED)
        (→ ApplicationSubmittedEvent — only if something actually submits; never in this build)
    → TrackingWorker            → application_tracking timeline
    → AnalyticsWorker           → execution_analytics rollups
```

**Why it is inert by default (three independent locks):** (1) the pipeline ENTRY
(`SafetyValidationWorker`) is gated by `application.safety.trigger.enabled` = **false**, so it never
auto-fires off 2D output; (2) `ApplicationExecutionWorker` consumes `ApprovalGrantedEvent`, which has
**no automatic publisher** — only the human approve endpoint emits it; (3) even if reached, the
browser provider is a throwing stub and every ATS connector is unconfigured, so execution can only
terminate in `ABORTED`. No single flag flip can cause a submission.

## 3. Entity & migration design

| Migration | Tables | Sub-phase |
|---|---|---|
| `V31__application_execution.sql` | `application_execution`, `application_execution_audit` | 2E.1 |
| `V32__application_approval.sql` | `approval_queue`, `approval_audit` | 2E.4 |
| `V33__application_retry.sql` | `application_retry` | 2E.6 |
| `V34__application_tracking.sql` | `application_tracking` | 2E.7 |
| `V35__execution_analytics.sql` | `execution_analytics` | 2E.8 |

All additive, idempotent (`CREATE TABLE IF NOT EXISTS`), same Neon hand-apply convention as V4–V30.
**None are applied by this work.** `application_tracking` is deliberately separate from the existing
org-scoped `applications` kanban table (linked via `application_id`, never overwriting it).

## 4. Browser abstraction (2E.2)

`BrowserAutomationProvider` interface (`login/navigate/uploadResume/uploadCoverLetter/fillForm/`
`answerQuestions/submit/captureScreenshot/captureConfirmation/logout`) — the seam, deliberately not
Playwright-specific. The only implementation, `PlaywrightAutomationProvider`, is an **inert stub**:
`isConfigured()` always false, every action throws `UnsupportedOperationException`, and **the
`com.microsoft.playwright` dependency is intentionally absent** from `pom.xml`. Future siblings
(`Selenium`, `BrowserUse`, `OpenAIOperator`) are named in javadoc only.

## 5. ATS connector design (2E.3)

`ATSConnector` (`detect/authenticate/extractForm/submit/track`) + `ATSConnectorRegistry` (routes a
job to its ATS by URL host token). Seven stub connectors — Greenhouse, Lever, Workday, LinkedIn,
SmartRecruiters, Ashby, BambooHR — each extending `AbstractStubConnector`: real `detect()` routing
(side-effect free), `isConfigured()` false, every side-effecting method throws. Same "joins the chain
only if configured" convention as `LlmProvider`/`JobProvider`.

## 6. Safety model (2E.5)

Deterministic (no LLM) three-valued gate — `SAFE | REVIEW | BLOCK`, precedence BLOCK > REVIEW > SAFE.
**A disabled gate is a CLOSED gate** (returns BLOCK), and any evaluation error **fails closed** to
BLOCK.
- **BLOCK (hard):** package not `ASSEMBLED`, no tailored resume, no cover letter, duplicate
  application (existing `applications` row in APPLIED/INTERVIEWING/OFFER), excluded role
  (candidate_preferences), blacklisted company.
- **REVIEW (soft):** missing/low ATS analysis (`< ats-min-score`), missing/large gap
  (`> gap-max-score`), off-preference country.

## 7. Retry model (2E.6)

Pure, exhaustively-tested policy — the guarantee that the engine can never hammer an external site:

| Failure class | Action |
|---|---|
| NETWORK | RETRY (bounded) |
| RATE_LIMITED | RETRY_BACKOFF (exponential, bounded) |
| CAPTCHA, LOGIN_FAILED | PAUSE (needs a human; never auto-retried) |
| VALIDATION_FAILED, DUPLICATE, UNKNOWN | STOP (fail closed) |

`max-attempts` = 3 (configurable). Once exhausted, a would-be RETRY becomes STOP. `classify()` maps
raw error text → class by keyword; `decide()` is a pure function (no I/O) so it is unit-tested across
the full matrix.

## 8. Tracking model (2E.7)

Append-only status timeline (`DRAFT|SUBMITTED|VIEWED|UNDER_REVIEW|INTERVIEW|ASSESSMENT|REJECTED|`
`OFFER|HIRED`) in `application_tracking`, opened by a `SUBMITTED` entry off `ApplicationSubmittedEvent`
(never emitted in this build). Never mutates the `applications` kanban row.

## 9. Analytics model (2E.8)

Append-only per-user metric snapshots in `execution_analytics` (submitted count, success/failure
rate, ATS success, interview/offer rate, provider/browser latency, application duration).

## 10. Observability

Five no-auth counts-only diagnostics endpoints (`ExecutionDiagnosticsController`), same computed
health verdict (`NOT_CONFIGURED | UP | DEGRADED | DOWN`) as `PipelineDiagnosticsController`:
`/api/diagnostics/application-execution`, `/browser`, `/ats`, `/tracking`, `/analytics`. With stock
defaults every stage reports `enabled:false … health:"NOT_CONFIGURED"` — proof the engine registers
without activating. Human surface: `ExecutionController` (`GET /pending`, `POST /approve/{id}`,
`POST /reject/{id}`, `GET /executions/{id}`, `GET /tracking?jobId=`) with the PENDING-only
double-guard (409 on re-decide, 403 on cross-tenant).

## 11. Risk assessment

| Risk | Mitigation |
|---|---|
| Accidental real submission | No Playwright dep; connectors unconfigured; `SUBMITTED` unreachable; 3 independent dark locks |
| Auto-apply without approval | `ApprovalGrantedEvent` has no automatic publisher; approval mandatory + double-guarded |
| Fabricated answers | `answerQuestions` documented never-fabricate; safety BLOCKs on missing artifacts before approval |
| Overwriting resume/cover letter | Execution reads by reference only; tracking is a separate table; all 2E tables append-only |
| Endless retry against a site | Pure retry policy, max 3, VALIDATION/DUPLICATE/UNKNOWN ⇒ STOP, CAPTCHA/LOGIN ⇒ PAUSE |
| Duplicate application | Safety `BLOCK` on an existing submitted `applications` row |
| One stage starving another | Five dedicated bounded executors, `AbortPolicy`, no shared pools |
| Synchronous browser work blocking a thread | All execution is async on bounded executors; nothing runs on the request thread |

## 12. Rollback strategy

Every stage is flag-gated; **flag-off is instant and total** (worker no-ops, endpoints inert). No
data rollback is ever needed: all migrations are additive and idempotent, and no 2E code mutates any
pre-2E row. Because migrations are not applied in this work, there is nothing to revert.

## 13. Production rollout strategy (each step gated + reversible)

1. **Ship dark (this deliverable).** Verify all diagnostics read `NOT_CONFIGURED`.
2. Apply migrations V31–V35 to Neon by hand (additive; safe on a live DB).
3. **Safety-only canary:** flip `APPLICATION_SAFETY_TRIGGER_ENABLED=true`. The pipeline now
   *evaluates* safety on 2D completion and parks PENDING approvals — still nothing executes. Watch
   `/api/diagnostics/application-execution` + `safety` metrics.
4. **Approval-queue canary:** operators work the `GET /pending` queue; confirm approve/reject audit
   trail and the 409/403 guards. Execution trigger still off ⇒ approving does nothing downstream.
5. **Execution dry-run:** flip `APPLICATION_EXECUTION_ENABLED` + `_TRIGGER_ENABLED` with browser/ATS
   still off ⇒ every approved execution terminates `ABORTED "no execution backend"`, proving the
   wiring end-to-end with zero external side effects.
6. **Single-connector shadow:** wire ONE real ATS connector behind `ATS_CONNECTOR_ENABLED`, submit to
   a sandbox/test posting only, for a tiny allow-list of internal users.
7. Expand connector-by-connector; enable tracking + analytics; only then consider a future
   `AUTO_APPROVE` — which does not exist in this version.

Any step reverts by flipping its flag off.

---

## Verification performed (build-time only — nothing enabled)

- `mvn -q clean compile` — full compile green.
- `mvn test` — **397 tests, 0 failures** (286 pre-existing + 111 new for Phase 2E).
- New coverage: full retry-policy matrix, every safety check (SAFE/REVIEW/BLOCK), approval
  double-guard (approve/reject/409/403), execution state machine incl. `ABORTED`-when-disabled and
  `SUBMITTED`-is-unreachable, browser-stub-throws, ATS routing + zero-configured invariant, and
  event-chain worker gating + failure isolation.
- **NOT done, by mandate:** no deploy, no migration applied to Neon, no flag flipped, no real browser
  automation, no application submitted.
