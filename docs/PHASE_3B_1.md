# Phase 3B.1 — Career-Relevant Domestic & International Job Feed Engine

Converts the Domestic and International tabs from location-only feeds into career-aware feeds
(role + skill + experience + domain relevance ≥ 70), while Browse stays the unfiltered
"everything" tab. Ships **fully dark and additive** — no migration, no flag flipped, no deploy,
no push.

## Problem

Domestic/International filtered only on country, so a Senior Java Developer's feed included
Media Manager, Hospitality Coach, BIM Engineer, Customer Service roles — low trust, manual
triage burden.

## What was built

New, self-contained package `ai.careerpilot.discovery.relevance` (+ `.api` sub-package):

| Class | Responsibility |
|---|---|
| `RoleFamily` | Enum: `JAVA_BACKEND, DATA_ENGINEERING, DEVOPS, FRONTEND, EXCLUDED, OTHER` |
| `RoleFamilyResolver` | Title → `RoleFamily`, keyword-driven, `@Value`-configurable lists |
| `RoleFamilyService` | Candidate-vs-job role eligibility + 0-100 similarity |
| `ExperienceEligibilityService` | Junior-role filter for senior candidates, configurable tolerance |
| `SkillOverlapService` | `matched / required` skill-family overlap, reuses `JobTaxonomy` normalization |
| `DomainPreferenceService` | Excluded-domain check (Media/Hospitality/Construction/Creative/BIM + existing `JobTaxonomy` families) + preferred-domain fit |
| `JobEligibilityEngine` | Composes all four checks → `Verdict` (`ELIGIBLE`/`REJECTED_ROLE`/`REJECTED_EXPERIENCE`/`REJECTED_SKILLS`/`REJECTED_DOMAIN`) |
| `CareerRelevanceScore` | Weighted composite (40/30/20/10) + match-strength band |
| `CareerRelevanceExplanation` | Step 10 explainability wire shape |
| `CareerRelevanceEvaluator` | Single entry point: resolves candidate context, scores, explains |
| `JobRelevanceController` | `GET /api/jobs/{id}/relevance` (new, additive endpoint) |

**One existing file was edited** (additively): `JobService.discovered()` gained a private
`applyCareerRelevance(...)` branch, called after the existing role-exclusion step, that is a
complete no-op unless both `career.relevance.enabled` and the scope-specific filter flag are on.
`JobService.browsePool()` was not touched — Browse is untouched by construction (it never calls
the new method).

## Architecture review

- **Additive-only.** Every new class lives in a new package. The single edited file
  (`JobService.java`) gained one new constructor parameter and two new `@Value` flags, with the
  existing legacy/scope-strict branches unchanged line-for-line above the new call site.
- **Reuses, doesn't duplicate.** `SkillOverlapService` and `DomainPreferenceService` call directly
  into the existing `JobTaxonomy` (skill-family normalization, industry-family classification) —
  the same deterministic taxonomy the recommendation engine already relies on, so this feed filter
  can never disagree with Recommended about what a skill or industry family *is*. No new taxonomy
  was invented for skills/industries; only the four named tech role-families and the
  domain-exclusion keyword list are new, purpose-built for this feed.
- **No new persisted fields.** "Preferred domains" reuses the existing, previously-unconsumed
  `CandidateProfile.domains`/`industries` JSON columns (populated by resume AI extraction since
  Phase 1) — read directly via `CandidateProfileRepository`, no schema change.
- **Contracts left untouched.** `JobScoring.ScoreBreakdown` (7-field, 40/25/20/10/3/1/1 weights)
  is unmodified — `CareerRelevanceScore` is an intentionally distinct 40/30/20/10 metric for feed
  eligibility, not a replacement. `RecommendationController`/`JobRecommendationService`/
  `JobMatchingService` (the actual recommendation engine) are untouched. Phase 2E and Phase 3A are
  untouched (no files under `execution/` or `workflow/` were read for write access, let alone
  edited).
- **Bean-safety verified.** Every new class name was checked against the entire backend source
  tree before and after writing (`find . -name "*.java" | sort | uniq -d`) — zero collisions
  introduced, learning directly from the Phase 3A `AnalyticsWorker`/`WorkflowController` incident
  earlier in this engagement.

## Migration review

**None required or written.** Every new signal this phase needs already exists in the schema:
`Job.requiredExperience`, `Job.skills`, `Job.title`/`description`, and
`CandidateProfile.domainsJson`/`industriesJson`. No `Vnn__*.sql` file was added, so there is
nothing pending against Neon from this phase.

## Regression analysis

- **48 new tests**, all green (`RoleFamilyResolverTest`, `RoleFamilyServiceTest`,
  `ExperienceEligibilityServiceTest`, `SkillOverlapServiceTest`, `DomainPreferenceServiceTest`,
  `JobEligibilityEngineTest`, `CareerRelevanceScoreTest`, `CareerRelevanceEvaluatorTest`,
  `JobServiceCareerRelevanceTest`).
- Re-ran the full `ai.careerpilot.jobdiscovery.**` package (pre-existing recommendation-engine
  suite) alongside the new package: **0 failures, 0 errors.**
- `JobServiceCareerRelevanceTest` explicitly asserts the flag-off legacy paths are byte-identical
  (all 3 stub jobs returned, unsorted-by-relevance) and that `browsePool` never invokes
  `CareerRelevanceEvaluator` at all — Browse's "no filters" guarantee is a test assertion, not just
  a code-review claim.
- No existing test constructs `JobService` directly (grepped before editing), so the constructor
  signature change (one new required bean + two new flags) breaks no existing test file.

## Performance analysis

- **No new I/O per job.** Candidate context (`RelevanceCandidateContext`) is resolved **once per
  request** (one `CandidateSignalResolver.resolve` + one `CandidateProfileRepository.findByUserId`
  call); scoring each job in the already-fetched page is pure in-memory computation over fields
  already loaded on the `Job` entity.
- **Known tradeoff, documented, not engineered away:** filtering happens on the already-paginated
  DB page (same pattern the existing `applyRoleExclusion` already uses), so a page can return
  fewer than `size` results once a scope flag is on. See `PHASE_3B_1_FILTER_RULES.md` for the
  precise mechanics and why a pool-then-paginate rewrite was deliberately out of scope for an
  additive, no-deploy phase.
- Scoring cost per job is O(1) set operations over small (<10 element) skill-family sets — no
  measurable latency impact expected even at `size=100`.

## Rollout strategy

All four flags default `false` — see `PHASE_3B_1_ROLLBACK.md` for the full canary sequence
(master flag → domestic → international → explainability, each independently reversible). Not
part of this delivery: enabling any flag, applying a migration, or deploying — per the explicit
hard constraints of this phase.

## Explicitly not done (per hard constraints)

No Phase 2E/3A file modified, no vector DB/Kafka/ElasticSearch/ML/embeddings introduced, no flag
enabled, no migration applied, no docker rebuild, no deploy, no push.
