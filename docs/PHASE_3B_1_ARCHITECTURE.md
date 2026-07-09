# Phase 3B.1 — Architecture

## Package layout

```
ai.careerpilot.discovery.relevance/
  RoleFamily.java                    enum
  RoleFamilyResolver.java            title → RoleFamily (keyword taxonomy)
  RoleFamilyService.java             candidate-vs-job role eligibility + similarity
  ExperienceEligibilityService.java  junior-role filter
  SkillOverlapService.java           skill-family overlap %
  DomainPreferenceService.java       excluded/preferred domain fit
  RelevanceCandidateContext.java     candidate-signal carrier (record)
  JobEligibilityEngine.java          composes the four checks → Verdict
  CareerRelevanceScore.java          weighted composite + match-strength band
  CareerRelevanceExplanation.java    Step 10 wire DTO (record)
  CareerRelevanceEvaluator.java      entry point (context resolution + scoring + explain)
  api/
    JobRelevanceController.java     GET /api/jobs/{id}/relevance
```

## Data flow

```
JobService.discovered(scope=domestic|international)
        │
        ├─ existing query (scopeStrict or legacy path) — UNCHANGED
        ├─ existing applyRoleExclusion(...)             — UNCHANGED
        └─ applyCareerRelevance(...)                    — NEW, additive
                │
                ├─ no-op unless career.relevance.enabled
                │  AND career.<scope>.filter.enabled
                │
                ├─ CareerRelevanceEvaluator.resolveContext(userId)   [once per request]
                │       ├─ CandidateSignalResolver.resolve(userId)   → skills, targetRole, years
                │       └─ CandidateProfileRepository.findByUserId   → domains, industries
                │
                └─ per job in the already-fetched page:
                        CareerRelevanceEvaluator.score(job, ctx)
                              └─ JobEligibilityEngine.evaluate(job, ctx)
                                      ├─ RoleFamilyService.evaluate(...)
                                      │      └─ RoleFamilyResolver.resolve(...)
                                      ├─ ExperienceEligibilityService.isEligible(...)
                                      ├─ SkillOverlapService.overlapPercent(...)
                                      │      └─ JobTaxonomy.skillFamilies(...)   [reused]
                                      └─ DomainPreferenceService.fitsPreferredDomains(...)
                                             └─ JobTaxonomy.classifyFamily/isExcludedFamily [reused]
                              └─ CareerRelevanceScore.from(eligibility)
                → filter (score >= 70) + sort (score desc, postedDate desc)
                → rebuild Page<Job> (PageImpl, same pattern as applyRoleExclusion)
```

```
GET /api/jobs/{id}/relevance                          — NEW controller, additive
        │
        ├─ 404 if job not found / cross-tenant (same guard as JobMatchExplanationService)
        └─ CareerRelevanceEvaluator.explain(userId, job)
                ├─ no-op (empty) unless career.explainability.enabled
                └─ same JobEligibilityEngine.evaluate(...) + CareerRelevanceScore.from(...)
                   + reasons(...) → CareerRelevanceExplanation
```

## Why a new, narrower `RoleFamily` taxonomy instead of extending `JobTaxonomy`

`JobTaxonomy.ROLE_FAMILY` already has 11 families (ARCHITECT/LEAD/BACKEND/FRONTEND/FULLSTACK/
DATA/DEVOPS/MOBILE/ML/QA/SECURITY) tuned for recommendation-scoring *nuance* — it rewards partial
overlap. Phase 3B.1 needs a coarser, feed-*eligibility* decision (four buckets + excluded + other),
and reusing `JobTaxonomy`'s enum directly would mean changing its `EXCLUDED_FAMILIES` set or role
map to add the spec's new categories (Media, Hospitality, Construction, Creative, BIM) — which
would alter recommendation-scoring behavior for every existing caller (`JobScoring`,
`JobMatchingService`, `PreferenceGate`), violating "do not modify recommendation engine
contracts." A separate, small taxonomy purpose-built for this feed avoids that coupling entirely
while still delegating the *skill*-family and *industry*-family logic (which had no such
conflict) to the existing, battle-tested `JobTaxonomy`.

## Why `JobService.discovered()` was edited, not left alone

The Hard Requirements forbid touching Phase 2E, Phase 3A, and the recommendation engine — but
Domestic/International/Browse are **job discovery**, the explicit target of this phase, and
`JobService` already has precedent for exactly this shape of additive branch (`scopeStrictEnabled`
→ `applyRoleExclusion`, added in an earlier phase). `applyCareerRelevance` follows that same
established pattern: a private method, called at the end of the existing public method, that
returns its input unchanged whenever its flags are off.

## Controller placement

`JobRelevanceController` is a **new** file mapped at the same `/api/jobs` base path as the existing
`JobController` — multiple `@RestController` classes sharing one base path is an established
pattern in this codebase (`WorkflowController` and `WorkflowTraceController` both map
`/api/workflow`). This avoids any edit to the existing, heavily-tested `JobController.java`.
