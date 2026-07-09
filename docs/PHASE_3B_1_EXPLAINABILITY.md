# Phase 3B.1 — Explainability

## Endpoint

```
GET /api/jobs/{id}/relevance
```

New, additive controller (`JobRelevanceController`, mapped alongside the existing `JobController`
at `/api/jobs`). Gated by `career.explainability.enabled` (default `false`):

- **Flag off** → `404 Not Found` (the underlying `CareerRelevanceEvaluator.explain(...)` returns
  `Optional.empty()`).
- **Job not found, or a cross-tenant org job** → `404 Not Found` (same multi-tenant guard as
  `JobMatchExplanationService.explain` — global discovered jobs with `org_id IS NULL` are visible
  to any authenticated user; org-scoped jobs only to their own org).
- **Enabled + authorized** → `200 OK` with the payload below.

## Response shape

```json
{
  "relevanceScore": 92,
  "matchStrength": "Excellent Match",
  "roleMatch": true,
  "skillOverlap": 87,
  "experienceFit": true,
  "domainFit": true,
  "reasons": [
    "Matches Java Architect profile",
    "87% skill overlap",
    "Experience aligned",
    "Preferred industry"
  ]
}
```

`matchStrength` is additive beyond the spec's literal example — it's the same band label already
computed by `CareerRelevanceScore` (see `PHASE_3B_1_FILTER_RULES.md`), included here so the
frontend doesn't have to re-derive it from `relevanceScore` in a second place.

## Reason strings

Built deterministically (no LLM) from the same `JobEligibilityEngine.Result` that produced the
score — always exactly 4 reasons, one per factor, in this fixed order:

1. Role — `"Matches {targetRole} profile"` when matched, else `"Role does not match your target profile"`.
2. Skills — always `"{skillOverlap}% skill overlap"`.
3. Experience — `"Experience aligned"` or `"Experience below target level"`.
4. Domain — `"Preferred industry"` or `"Excluded industry/domain"`.

## STEP 11 — additional fields, no UI redesign

Per the spec, no frontend change ships in this phase. The fields it names
(`relevanceScore`, `matchStrength`, `matchReasons`) are exactly what
`CareerRelevanceExplanation` already exposes (`reasons` ≡ `matchReasons`), ready for a future,
separate UI phase to wire into the existing Domestic/International/Recommended cards without any
backend change.

## Deterministic and side-effect-free

`explain()` and `score()` are pure computations over already-resolved candidate signals and the
job entity's already-loaded columns — no LLM call, no new DB write, no caching layer. Calling the
endpoint repeatedly for the same (user, job) always returns the same result until either the
candidate's profile or the job's data changes.
