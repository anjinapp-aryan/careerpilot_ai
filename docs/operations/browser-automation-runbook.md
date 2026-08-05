# Browser Automation — Operational Runbook (Phase 12B)

Operational guide for the Playwright/Chromium guest-apply automation. Covers deployment, staged
rollout, rollback, monitoring, failure behaviour, and incident response.

**One endpoint answers almost every question in this document:**

```bash
curl -s https://careerpilot-ai.duckdns.org/api/diagnostics/browser | jq
```

It requires no authentication (same convention as the other diagnostics endpoints) and returns no
PII — no application content, no user ids, no page text.

---

## 1. What is actually gated

There are **two independent gates**, and both must say yes before any real browser work happens.

| Gate | Property | Controls |
|---|---|---|
| Master | `browser.automation.enabled` (`BROWSER_AUTOMATION_ENABLED`) | Whether a Chromium process may exist at all |
| Rollout | `browser.automation.rollout.percentage` + `.allowed-user-ids` | Whether *this user's* execution may drive it |

Turning the master flag on with the rollout at its default (0%, empty allow-list) exposes **nobody**.
That is Stage 0 and it is deliberate: the risky flag and the traffic-exposing flag are separate so
the infrastructure can be soaked without automating a single application.

Everything else about the automation is unchanged and remains hard limits, not toggles:

- **Guest-apply only.** `GuestApplyEligibility` is a hardcoded allow-list of `{greenhouse, lever}`.
  There is no flag that widens it. `login()` unconditionally throws; no credential is ever stored or
  entered.
- **Human approval is mandatory.** A filled form is screenshotted and parked as a `FORM_SCREENSHOT`
  approval. Only a human decision reaches `finalizeSubmit()`.
- **`SUBMITTED` requires evidence.** `VerificationAdjudicator` admits only `CONFIRMED`/`STRONG`
  confidence. Anything weaker becomes `SUBMIT_UNVERIFIED` — the click happened, delivery could not be
  certified. This is never auto-retried (duplicate applications reach real employers).

> There is deliberately **no flag to disable verification.** A switch that turns off the truthfulness
> gate is not a feature. If verification is failing, the correct response is to stop automating, not
> to stop checking.

---

## 2. Deployment checklist

Run before enabling anything.

- [ ] Backend image built from the Phase 12A Dockerfile (Debian bookworm + distro Chromium, non-root).
- [ ] `docker compose ps` — all seven containers `Up (healthy)`.
- [ ] `GET /api/diagnostics/browser` returns `200`.
- [ ] `.enabled == false` and `.rollout.fullyOff == true` — confirms you are starting from dark.
- [ ] `.installation.browserInstalled == true` — the configured Chromium genuinely exists on disk.
      If `null`, `BROWSER_AUTOMATION_LAUNCH_EXECUTABLE_PATH` is unset, which is **wrong on ARM**.
- [ ] `.runtime.armCompatible == true`. If `false`, read `.runtime.armCompatibilityNote` — Playwright
      publishes no `linux-arm64` Chromium, so the distro binary must be pointed at explicitly.
- [ ] `.capacity.maxLeases == 1`. Do not raise this. See §6.
- [ ] `.session.sandboxDisabled == true` is expected and documented — mitigated by the container
      running as non-root `careerpilot`.
- [ ] `BROWSER_AUTOMATION_MAINTENANCE_ENABLED=true` set alongside the master flag.

---

## 3. Staged rollout

Each stage: change config → restart backend → verify → soak → decide.

| Stage | `PERCENTAGE` | `ALLOWED_USER_IDS` | Soak | Exit criteria |
|---|---|---|---|---|
| 0 | `0` | *(empty)* | **24 h** | Infra stable, no OOM kills, no restarts, `health` is `UP`/`NOT_CONFIGURED` |
| 1 | `0` | your own uuid | ≥ 5 real applications | Every submit either `SUBMITTED` with evidence or an honest `SUBMIT_UNVERIFIED` |
| 2 | `5` | *(keep)* | 24 h | `launchSuccessRate == 100`, `poolAcquireTimeouts == 0`, `browserCrashes == 0` |
| 3 | `25` | *(keep)* | 24 h | Same, plus `poolLeasesExpired == 0` |
| 4 | `50` | *(keep)* | 24 h | Same |
| 5 | `100` | *(keep)* | — | Same |

Set `BROWSER_AUTOMATION_ROLLOUT_STAGE_LABEL` to match, so `.rollout.stage` states which stage is
actually deployed rather than which one you intended.

**Stage 0 → 1 is the largest real jump** — it is the first time a browser touches a live employer
form. Stages 2→5 only change how many users can reach an already-proven path.

Advancing never drops anyone: bucketing is monotonic, so the cohort from the previous stage is
always a subset of the next (asserted in `BrowserRolloutGateTest`).

---

## 4. Rollback checklist

Pick the smallest rollback that addresses the problem.

**Level 1 — stop new automation** (seconds; in-flight approved forms still finalize):

```bash
# on the VM, in the compose env file
BROWSER_AUTOMATION_ROLLOUT_PERCENTAGE=0
BROWSER_AUTOMATION_ROLLOUT_ALLOWED_USER_IDS=
docker compose --env-file .env up -d --force-recreate backend
```

**Level 2 — hard stop, including in-flight finalizes:**

```bash
BROWSER_AUTOMATION_ENABLED=false
docker compose --env-file .env up -d --force-recreate backend
```

**Level 3 — full image rollback:** `deployment/README.md`.

Verify any level with:

```bash
curl -s .../api/diagnostics/browser | jq '{enabled, rollout, health}'
```

Level 1 leaves already-approved forms able to complete on purpose. A human already looked at that
screenshot and approved it; stranding it in `AWAITING_APPROVAL` helps nobody and risks the user
re-applying manually to a job that may already have been submitted. Use Level 2 when you need the
browser to stop mid-flight.

---

## 5. Monitoring checklist

Poll `GET /api/diagnostics/browser`.

**Alert immediately:**

| Signal | Meaning |
|---|---|
| `health == "DOWN"` | Automation cannot work — missing browser, ARM misconfig, or launches failing |
| `installation.browserInstalled == false` | Configured Chromium path does not exist |
| `runtime.armCompatible == false` | ARM host with no distro browser configured — launch will fail |
| `lifecycle.launchFailures > 0` | Chromium failed to start; read `lifecycle.lastLaunchError` |
| `lifecycle.browserCrashes > 0` | Browser wedged and was restarted |

**Investigate (degraded, not broken):**

| Signal | Meaning |
|---|---|
| `poolAcquireTimeouts > 0` | Demand exceeds the 1-lease budget — a capacity decision, not a bug |
| `poolLeasesExpired > 0` | **A code path is failing to release a lease.** The sweep recovered it; the leak is still a defect |
| `browserScreenshotFailures > 0` | Evidence capture failing — the approval gate may be reviewing blank images |
| `browserLastScreenshotAt` stale while submissions continue | Same, quieter |
| `browserSubmitUnverified` climbing | Submits happening but not certifiable — investigate before it becomes routine |
| `session.zombie == true` | Contexts stuck or leaking |

**Sanity checks:** `session.openPages` should equal `capacity.activeLeases` (one page per lease by
construction). `runtime.memory` is **JVM heap only** — Chromium is a child process and is not
counted; use `docker stats` for total container memory.

---

## 6. Capacity

`browser.automation.pool.max-leases` defaults to **1**. Leave it there.

The binding constraint on the production VM is **1 vCPU**, not RAM (5.5 GiB total, ~2.5 GiB
available). Chromium rendering and JS execution are CPU-bound and share that single core with the
JVM, Kafka, ZooKeeper and uvicorn. A second concurrent browser does not double throughput — it
roughly halves the speed of both and pushes the box toward swap, which on one core is effectively an
outage.

If `poolAcquireTimeouts` is consistently non-zero, the answer is a bigger VM or accepting the queue,
**not** a higher lease count.

---

## 7. Failure behaviour

Every one of these fails safe. **None can produce a false `SUBMITTED`.**

| Failure | Behaviour |
|---|---|
| Chromium missing | `browser()` throws; counted as a launch failure with `lastLaunchError`; execution → `FAILED` → retry policy. `health` → `DOWN` |
| Launch failure | Partial Playwright state is torn down immediately (no orphan driver process); same as above |
| Lease unavailable | Typed `BrowserCapacityUnavailableException` after the acquire timeout — a fast, retryable failure, never an unbounded queue |
| Lease timeout (TTL) | Reclaimed, context destroyed, permit returned; `poolLeasesExpired` increments |
| Browser crash / wedge | Zombie detection → restart, but **only when no lease is outstanding** (never destroys healthy in-flight sessions) |
| Context crash | Lease teardown returns the permit and notifies the session manager, so the open-context counter cannot drift |
| Page timeout | Page-level default timeout → action fails → bounded retry → `FAILED` → retry policy |
| Screenshot failure | Counted; never blocks the outcome it annotates. If the *approval* screenshot fails, the approval is not enqueued and the attempt aborts — automation cannot proceed without the human gate |
| Confirmation failure | No confirmation signal → adjudicator cannot reach `CONFIRMED`/`STRONG` → `SUBMIT_UNVERIFIED` |
| Verification failure/exception | **Fails closed** to `SUBMIT_UNVERIFIED`, never `SUBMITTED` |
| CAPTCHA / login wall | Detected before any field is touched → `ABORTED` → human review |
| Automation timeout | Bounded by lease TTL and page timeouts; the sweep reclaims anything left behind |

`SUBMIT_UNVERIFIED` is classified `CONFIRMATION_MISSING` → `PAUSE` by `RetryPolicyService`. It is
**never auto-retried** — retrying a submit that may have succeeded is how duplicate applications
reach an employer.

---

## 8. Incident response

### `health == "DOWN"`

1. `curl .../api/diagnostics/browser | jq '{installation, runtime, lifecycle}'`
2. `installation.browserInstalled == false` → the path is wrong or the image lacks Chromium.
   Verify: `docker compose exec backend /usr/bin/chromium --version`
3. `runtime.armCompatible == false` → set `BROWSER_AUTOMATION_LAUNCH_EXECUTABLE_PATH=/usr/bin/chromium`.
4. `lifecycle.lastLaunchError` mentions `/dev/shm` → confirm `shm_size: 256m` on the backend service.
5. Otherwise → **Level 1 rollback**, then diagnose without the clock running.

### Pool saturated / acquire timeouts

1. `jq '.capacity'` — is `activeLeases` stuck at 1 with a large `ageMs`?
2. If it never drains, a lease is leaked. Confirm `BROWSER_AUTOMATION_MAINTENANCE_ENABLED=true`;
   the sweep reclaims it within `lease-ttl-seconds` and logs `BROWSER_MAINTENANCE reclaimed …`.
3. If `poolLeasesExpired` keeps climbing, a caller is not releasing — that is a code defect. Roll
   back to Level 1 and investigate.

### Memory pressure / OOM kill

1. `docker stats --no-stream` — the backend container limit covers **JVM + Chromium together**.
2. Confirm `-Xmx768m` is in effect: `jq '.runtime.memory.jvmMaxMb'` ≈ 768.
3. Do not raise `max-leases`. Reduce `BROWSER_AUTOMATION_LAUNCH_MAX_OLD_SPACE_MB` first.

### Submissions succeed but nothing is verified

`browserRealSubmissions` climbing while `SUBMIT_UNVERIFIED` dominates means the confirmation
detection no longer matches that ATS's post-submit page — an ATS UI change, not an infrastructure
fault. The system is behaving correctly by refusing to claim success. Roll back to Level 1 and fix
`ConfirmationPageAnalyzer`.

---

## 9. Troubleshooting reference

| Symptom | Likely cause | Check |
|---|---|---|
| `chromiumVersion` is `null` | Browser never launched (normal when idle) | `.session.launched` |
| `browserInstalled` is `null` | No explicit executable path — relying on Playwright's bundled browser | Set the path explicitly on ARM |
| `armCompatible` false | ARM host, no distro browser configured | `.runtime.armCompatibilityNote` |
| `Executable doesn't exist` | Image lacks Chromium, or wrong path | `docker compose exec backend ls -l /usr/bin/chromium` |
| Renderer crashes | `/dev/shm` too small | `shm_size: 256m` in `docker-compose.yml` |
| `Failed to launch: No usable sandbox` | Sandbox enabled without container privileges | `BROWSER_AUTOMATION_LAUNCH_NO_SANDBOX=true` |
| Executions abort with "rollout stage" | Working as designed — user outside the cohort | `.rollout` |
| Nothing automates despite the flag on | Rollout still at 0% | `.rollout.fullyOff` |

---

## 10. Known limitations

- **`WAITING_MANUAL_SUBMISSION` is a dead end.** Sessions parked there have no automated exit. Known,
  tracked, deliberately out of scope for this phase.
- **`runtime.memory` excludes Chromium.** It is a child process; only container-level metrics see it.
- **Chromium version requires a launched browser.** Deliberate: this endpoint is unauthenticated and
  will not spawn a subprocess to answer a diagnostic question.
- **Playwright ↔ distro-Chromium end-to-end is not yet proven in production.** Phase 12A verified
  Chromium launches and renders under the production flags, as the production user, on the production
  architecture. It did not verify Playwright's protocol handshake against a distro build rather than
  a Playwright-pinned one. **This is what Stage 1 exists to find out.**
